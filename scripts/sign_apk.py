"""Sign a Diet release locally; private material stays in ignored .local-tools/signing."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import secrets
import subprocess
import sys

ROOT = Path(__file__).resolve().parents[1]
TOOLS = ROOT / ".local-tools"
KEY_ROOT = TOOLS / "signing"
KEY = KEY_ROOT / "diet-release.jks"
PASSWORD = KEY_ROOT / "diet-release.password"

def run(args):
    completed = subprocess.run([str(a) for a in args], capture_output=True, text=True,
                               encoding="utf-8", errors="replace")
    if completed.returncode:
        raise RuntimeError(completed.stderr.strip() or completed.stdout.strip())
    return completed.stdout + completed.stderr

def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("input", type=Path)
    parser.add_argument("output", type=Path)
    parser.add_argument("--init-key", action="store_true", help="Create the permanent private local key once")
    args = parser.parse_args()
    jdk = TOOLS / "jdk/jdk-21.0.13+9/bin"
    suffix = ".exe" if os.name == "nt" else ""
    java = jdk / ("java" + suffix)
    keytool = jdk / ("keytool" + suffix)
    sdk = TOOLS / "android-sdk/build-tools/36.0.0"
    source, target = args.input.resolve(), args.output.resolve()
    if source == target:
        raise ValueError("Signed output must be separate from the unsigned input")
    if not source.is_file():
        raise FileNotFoundError(source)
    if not target.is_relative_to(ROOT):
        raise ValueError("Output must remain in the Diet workspace")
    for private_file in (KEY, PASSWORD):
        ignored = subprocess.run(["git", "check-ignore", "--quiet", str(private_file)],
                                 cwd=ROOT, capture_output=True)
        if ignored.returncode != 0:
            raise ValueError("Private signing paths must be ignored and untracked before signing")
    if not KEY.exists():
        if not args.init_key:
            raise ValueError("No permanent local key. Use --init-key for the first release only.")
        KEY_ROOT.mkdir(parents=True, exist_ok=True)
        if PASSWORD.exists():
            raise ValueError("Password exists without a key; preserve both and resolve before recreating")
        # The actual password is never included in command arguments, logs, Git or the APK.
        with PASSWORD.open("x", encoding="ascii", newline="\n") as stream:
            stream.write(secrets.token_urlsafe(48) + "\n")
        run([keytool, "-genkeypair", "-noprompt", "-keystore", KEY, "-storetype", "JKS",
             "-storepass:file", PASSWORD, "-keypass:file", PASSWORD,
             "-alias", "diet-release", "-keyalg", "RSA", "-keysize", "3072",
             "-sigalg", "SHA256withRSA", "-validity", "10950",
             "-dname", "CN=Diet Local Release, OU=Diet, O=Diet"])
    if not PASSWORD.is_file():
        raise ValueError("Permanent key password is missing; do not replace the existing certificate")
    target.parent.mkdir(parents=True, exist_ok=True)
    # AGP already aligns native libs. A check before signing prevents breaking a signature later.
    run([sdk / ("zipalign" + suffix), "-c", "-P", "16", "4", source])
    signer = [java, "-jar", sdk / "lib/apksigner.jar"]
    run([*signer, "sign", "--ks", KEY, "--ks-key-alias", "diet-release",
         "--ks-pass", "file:" + str(PASSWORD),
         "--min-sdk-version", "29", "--v1-signing-enabled", "false",
         "--v2-signing-enabled", "true", "--v3-signing-enabled", "true",
         "--v4-signing-enabled", "false", "--out", target, source])
    result = run([*signer, "verify", "--verbose", "--print-certs", "--min-sdk-version", "29", target])
    cert = re.search(r"Signer #1 certificate SHA-256 digest: ([0-9a-fA-F]+)", result)
    if not cert:
        raise RuntimeError("Signing succeeded but the public certificate digest could not be verified")
    with target.open("rb") as stream:
        digest = hashlib.file_digest(stream, "sha256").hexdigest()
    report = {"apk": target.name, "bytes": target.stat().st_size, "sha256": digest,
              "certificateSha256": cert.group(1).lower(), "verified": True,
              "minSdk": 29, "privateKeyTracked": False}
    target.with_suffix(".signing.json").write_text(json.dumps(report, indent=2) + "\n", encoding="utf-8")
    print(json.dumps(report))

if __name__ == "__main__":
    try:
        main()
    except Exception as error:
        sys.exit(str(error))
