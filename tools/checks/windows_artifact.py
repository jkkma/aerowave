"""Package a fresh Windows build, then test the extracted native application."""

import json
import pathlib
import subprocess
import sys
import zipfile

import release_windows as checks


def main():
    root = checks.ROOT
    version = checks.portable_package.version()
    archive = root / "dist" / f"Aerowave-{version}-win-x64.zip"
    receipt = root / "dist" / "windows-validation.json"
    smoke_log = root / "dist" / "windows-smoke.log"
    extracted = root / "dist" / "windows-smoke"
    checks.assert_fresh([root / "dist" / "Aerowave", archive, receipt, extracted, smoke_log])
    subprocess.run([sys.executable, "-B", "tools/checks/check_version.py", "--strict"], cwd=root, check=True)
    exe = root / "src-tauri" / "target" / "release" / "aerowave.exe"
    versions = checks.binary_versions(exe)
    if versions != [tuple(map(int, version.split("."))) + (0,)] * 2:
        raise ValueError("Executable version does not match the release")
    expected = {
        "Aerowave/aerowave.exe": exe.read_bytes(),
        "Aerowave/WebView2Loader.dll": checks.portable_package.find_webview2_loader().read_bytes(),
        "Aerowave/README.md": (root / "README.md").read_bytes(),
        "Aerowave/LICENSE": (root / "LICENSE").read_bytes(),
    }
    subprocess.run([sys.executable, "tools/package.py"], cwd=root, check=True)
    members = checks.validate_archive(archive, expected)
    with zipfile.ZipFile(archive) as package:
        package.extractall(extracted)
    smoke = subprocess.run(
        ["node", "tools/checks/windows_smoke.cjs", str(extracted / "Aerowave" / "aerowave.exe"), version],
        cwd=root, capture_output=True, text=True, timeout=120,
    )
    smoke_log.write_text(smoke.stdout + smoke.stderr, encoding="utf-8")
    if smoke.returncode:
        print(smoke_log.read_text(encoding="utf-8"), file=sys.stderr)
        smoke.check_returncode()
    evidence = json.loads(smoke.stdout)
    receipt.write_text(json.dumps({
        "version": version,
        "commit": checks.run("git", "rev-parse", "HEAD").strip(),
        "sha256": checks.sha256(archive.read_bytes()),
        "members": members,
        "smoke": evidence,
    }, indent=2) + "\n", encoding="utf-8")
    print(receipt.read_text(encoding="utf-8"))


if __name__ == "__main__":
    main()
