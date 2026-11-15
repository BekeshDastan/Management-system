"""Verify Diet APK packaging, SDK/privacy declarations and 16KiB native alignment."""
import argparse
import hashlib
import json
from pathlib import Path
import re
import struct
import subprocess
import zipfile

DOMAINS = {"root", "file", "database", "sharedpref", "external", "device_root", "device_file", "device_database", "device_sharedpref"}


def verify(apk, sdk, release=False, require_signature=False):
    if not __debug__:
        raise RuntimeError("Run APK checks without Python -O")
    root = Path(__file__).resolve().parents[1]
    suffix = ".exe" if __import__("os").name == "nt" else ""
    aapt = sdk / "build-tools/36.0.0" / ("aapt2" + suffix)

    def dump(*args):
        result = subprocess.run([str(aapt), "dump", *map(str, args)], capture_output=True, text=True, encoding="utf-8", errors="replace")
        if result.returncode:
            raise RuntimeError(result.stderr)
        return result.stdout

    badging = dump("badging", apk)
    manifest = dump("xmltree", "--file", "AndroidManifest.xml", apk)
    permissions = dump("permissions", apk)
    assert "minSdkVersion:'29'" in badging and "targetSdkVersion:'37'" in badging, "Wrong SDK boundary"
    assert re.search(r"allowBackup[^\n]*=false\b", manifest), "allowBackup must be false"
    assert "fullBackupContent" in manifest and "dataExtractionRules" in manifest, "Both backup formats are required"
    assert "backupAgent" not in manifest, "Custom backup agent is forbidden"
    assert not re.search(r"directBootAware[^\n]*=true\b", manifest), "A component is enabled before unlock"
    if release:
        assert not re.search(r"debuggable[^\n]*=true\b", manifest), "Release is debuggable"
    forbidden = ("INTERNET", "READ_MEDIA_IMAGES", "READ_MEDIA_VIDEO", "READ_EXTERNAL_STORAGE", "WRITE_EXTERNAL_STORAGE", "CAMERA", "SCHEDULE_EXACT_ALARM", "USE_EXACT_ALARM")
    for name in forbidden:
        assert "android.permission." + name not in permissions, "Unneeded permission: " + name
    native, abi_sets = [], {}
    rules = {}
    with zipfile.ZipFile(apk) as archive, apk.open("rb") as stream:
        for item in archive.infolist():
            low = item.filename.lower()
            assert not low.endswith((".db", ".sqlite", ".jks", ".keystore", ".p12")), "Private data/key in APK"
            if item.filename.startswith("res/") and item.filename.endswith(".xml"):
                raw = archive.read(item)
                for tag in ("full-backup-content", "data-extraction-rules"):
                    if tag.encode() in raw or tag.encode("utf-16le") in raw:
                        tree = dump("xmltree", "--file", item.filename, apk)
                        if "E: " + tag in tree:
                            rules[tag] = tree
            if not low.endswith(".so"):
                continue
            elf = archive.read(item)
            assert elf[:4] == b"\x7fELF" and elf[5] == 1
            is64 = elf[4] == 2
            phoff = struct.unpack_from("<Q" if is64 else "<I", elf, 32 if is64 else 28)[0]
            entry_size, count = struct.unpack_from("<HH", elf, 54 if is64 else 42)
            aligns = [struct.unpack_from("<Q" if is64 else "<I", elf, phoff + i * entry_size + (48 if is64 else 28))[0]
                      for i in range(count) if struct.unpack_from("<I", elf, phoff + i * entry_size)[0] == 1]
            assert aligns and min(aligns) >= 16384, "ELF LOAD alignment: " + item.filename
            stream.seek(item.header_offset + 26)
            name_len, extra_len = struct.unpack("<HH", stream.read(4))
            offset = item.header_offset + 30 + name_len + extra_len
            assert item.compress_type == 0 and offset % 16384 == 0, "ZIP native alignment: " + item.filename
            abi, name = item.filename.split("/")[1:]
            abi_sets.setdefault(abi, set()).add(name)
            native.append({"file": item.filename, "elfLoadAlignment": min(aligns), "zipOffsetMod16KiB": offset % 16384})
        starter = json.loads(archive.read("assets/starter_catalog.json"))
        assert len(starter["items"]) == 60 and len({i["id"] for i in starter["items"]}) == 60

    def check_rules(text):
        assert "E: include" not in text and "E: cross-platform-transfer" not in text
        domains = re.findall(r'A: domain="([^"]+)"', text)
        paths = re.findall(r'A: path="([^"]+)"', text)
        assert len(domains) == 9 and set(domains) == DOMAINS and paths == ["."] * 9, "Incomplete exclude domains"

    assert set(rules) == {"full-backup-content", "data-extraction-rules"}
    check_rules(rules["full-backup-content"])
    sections = re.split(r"(?m)^\s*E: (cloud-backup|device-transfer)\b", rules["data-extraction-rules"])
    assert set(sections[1::2]) == {"cloud-backup", "device-transfer"}
    for content in sections[2::2]:
        check_rules(content)
    assert "arm64-v8a" in abi_sets and len({tuple(sorted(s)) for s in abi_sets.values()}) == 1, "Incomplete ABI library set"
    signature = {"checked": False}
    if require_signature:
        java = root / ".local-tools/jdk/jdk-21.0.13+9/bin" / ("java" + suffix)
        checked = subprocess.run([str(java), "-jar", str(sdk / "build-tools/36.0.0/lib/apksigner.jar"),
                                  "verify", "--verbose", "--print-certs", "--min-sdk-version", "29", str(apk)],
                                 capture_output=True, text=True, encoding="utf-8", errors="replace")
        assert checked.returncode == 0, "APK signature invalid or absent: " + checked.stderr
        certificate = re.search(r"Signer #1 certificate SHA-256 digest: ([0-9a-fA-F]+)", checked.stdout)
        assert certificate, "Certificate digest missing"
        signature = {"checked": True, "verified": True, "certificateSha256": certificate.group(1).lower()}
    with apk.open("rb") as source:
        digest = hashlib.file_digest(source, "sha256").hexdigest()
    return {"apk": apk.name, "sha256": digest, "signature": signature, "bytes": apk.stat().st_size,
            "minSdk": 29, "targetSdk": 37, "allowBackup": False, "backupRuleDomainsPerTransport": 9,
            "forbiddenPermissionsAbsent": list(forbidden), "starterRows": 60, "abis": sorted(abi_sets),
            "native": native, "status": "PASS", "scope": "Static packaging only; no cloud/D2D/OEM or physical-device claim"}


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("apk", type=Path)
    parser.add_argument("--sdk", type=Path, default=Path(__file__).resolve().parents[1] / ".local-tools/android-sdk")
    parser.add_argument("--release", action="store_true")
    parser.add_argument("--require-signature", action="store_true", help="Also verify APK cryptographic signatures via apksigner")
    parser.add_argument("--report", type=Path)
    args = parser.parse_args()
    result = verify(args.apk.resolve(), args.sdk.resolve(), args.release, args.require_signature)
    if args.report:
        args.report.parent.mkdir(parents=True, exist_ok=True)
        args.report.write_text(json.dumps(result, indent=2) + "\n", encoding="utf-8")
    print(json.dumps({k: v for k, v in result.items() if k != "native"}, ensure_ascii=False))
