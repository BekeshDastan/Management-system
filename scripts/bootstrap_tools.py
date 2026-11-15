"""Fetch official, checksum-verified build tools into an ignored project-local folder."""
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path
import hashlib
import json
import urllib.request
import zipfile

ROOT = Path(__file__).resolve().parents[1]
TOOLS = ROOT / ".local-tools"
DOWNLOADS = TOOLS / "downloads"


def fetch(url):
    request = urllib.request.Request(url, headers={"User-Agent": "Diet-build-bootstrap"})
    with urllib.request.urlopen(request, timeout=120) as response:
        return response.read()


def archive(url, checksum, algorithm):
    DOWNLOADS.mkdir(parents=True, exist_ok=True)
    target = DOWNLOADS / url.rsplit("/", 1)[-1]
    if not target.exists() or hashlib.new(algorithm, target.read_bytes()).hexdigest() != checksum:
        part = target.with_suffix(target.suffix + ".part")
        with urllib.request.urlopen(urllib.request.Request(url, headers={"User-Agent": "Diet-build-bootstrap"}), timeout=120) as source, part.open("wb") as output:
            while chunk := source.read(1024 * 1024):
                output.write(chunk)
        actual = hashlib.new(algorithm, part.read_bytes()).hexdigest()
        if actual != checksum:
            raise ValueError(f"Checksum mismatch for {target.name}: {actual}")
        part.replace(target)
    print(f"Verified {target.name}", flush=True)
    return target


def extract(path, destination):
    destination.mkdir(parents=True, exist_ok=True)
    root = destination.resolve()
    with zipfile.ZipFile(path) as source:
        for item in source.infolist():
            if not (root / item.filename).resolve().is_relative_to(root):
                raise ValueError("Archive path escapes tool directory")
        source.extractall(root)


def install_jdk():
    name = "OpenJDK21U-jdk_x64_windows_hotspot_21.0.13_9.zip"
    base = "https://github.com/adoptium/temurin21-binaries/releases/download/jdk-21.0.13%2B9/"
    checksum = fetch(base + name + ".sha256.txt").decode().split()[0]
    path = archive(base + name, checksum, "sha256")
    if not (TOOLS / "jdk" / "jdk-21.0.13+9" / "bin" / "java.exe").exists():
        extract(path, TOOLS / "jdk")
    return {"tool": "Temurin21.0.13+9", "url": base + name, "algorithm": "sha256", "checksum": checksum}


def install_gradle():
    url = "https://services.gradle.org/distributions/gradle-9.5.0-bin.zip"
    checksum = fetch(url + ".sha256").decode().strip()
    path = archive(url, checksum, "sha256")
    if not (TOOLS / "gradle" / "gradle-9.5.0" / "bin" / "gradle.bat").exists():
        extract(path, TOOLS / "gradle")
    return {"tool": "Gradle9.5.0", "url": url, "algorithm": "sha256", "checksum": checksum}


def install_android_cli():
    url = "https://dl.google.com/android/repository/commandlinetools-win-16111833_latest.zip"
    checksum = "57d04f2d75eb8e8fffc5000a987e5de4b5a63e9d"
    path = archive(url, checksum, "sha1")
    destination = TOOLS / "android-sdk" / "cmdline-tools" / "latest"
    if not (destination / "bin" / "sdkmanager.bat").exists():
        temp = TOOLS / "android-cli-extracted"
        extract(path, temp)
        destination.parent.mkdir(parents=True, exist_ok=True)
        (temp / "cmdline-tools").replace(destination)
    return {"tool": "AndroidCLI16111833", "url": url, "algorithm": "sha1", "checksum": checksum}


if __name__ == "__main__":
    with ThreadPoolExecutor(max_workers=3) as pool:
        results = list(pool.map(lambda fn: fn(), [install_jdk, install_gradle, install_android_cli]))
    (TOOLS / "tool-provenance.json").write_text(json.dumps(results, indent=2) + "\n", encoding="utf-8")
    print("Build tools ready under .local-tools; no global Java settings changed.", flush=True)
