"""Build the portable zip that the Scoop manifest points at.

    python tools/package.py            # expects an existing release build
    python tools/package.py --build    # runs cargo build --release first

Produces dist/Aerowave-<version>-win-x64.zip laid out as

    Aerowave/
      aerowave.exe
      WebView2Loader.dll
      README.md
      LICENSE
      data/README.txt

`data` is what makes the copy portable: the app writes its settings and the
webview's cache there instead of into the user profile, and Scoop persists it
across updates. The zip's single top-level folder matches the manifest's
`extract_dir`.

Paste the sha256 this prints into the GitHub release notes and into the Scoop
manifest's `hash` field - they are what a manual downloader checks against, and
the zip's hash changes on every repack even when nothing in it does.
"""

import argparse
import hashlib
import json
import pathlib
import shutil
import subprocess
import sys
import zipfile

ROOT = pathlib.Path(__file__).resolve().parent.parent
TAURI = ROOT / "src-tauri"
RELEASE = TAURI / "target" / "release"
DIST = ROOT / "dist"
APP_DIR_NAME = "Aerowave"

DATA_README = """\
Aerowave keeps its settings in this folder.

While this folder exists next to aerowave.exe, the app is portable: settings
live in aerowave.json here and the webview's cache lives in webview\\, so
nothing is written to your user profile. Delete this folder and the app falls
back to %APPDATA%\\com.aerowave.radio instead.

Installed with Scoop, this folder is persisted across updates.

One exception: turning on "Start with Windows" writes a registry entry under
HKCU\\Software\\Microsoft\\Windows\\CurrentVersion\\Run pointing at the exe. That
is the only thing the app puts outside its own folder, it is off by default,
and turning it back off removes it.
"""


def version() -> str:
    conf = json.loads((TAURI / "tauri.conf.json").read_text(encoding="utf-8"))
    return conf["version"]


def find_webview2_loader() -> pathlib.Path:
    """The x64 loader DLL, which the exe imports and cannot start without."""
    beside = RELEASE / "WebView2Loader.dll"
    if beside.is_file():
        return beside
    # Fall back to the copy the webview2-com-sys build script unpacked. There
    # are arm64 and x86 builds in there too, so be explicit about which.
    matches = sorted((RELEASE / "build").glob("webview2-com-sys-*/out/x64/WebView2Loader.dll"))
    if not matches:
        sys.exit("WebView2Loader.dll not found - has the release build run?")
    return matches[-1]


def sha256(path: pathlib.Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for chunk in iter(lambda: handle.read(1 << 20), b""):
            digest.update(chunk)
    return digest.hexdigest()


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--build", action="store_true", help="run cargo build --release first")
    args = parser.parse_args()

    if args.build:
        subprocess.run(["cargo", "build", "--release"], cwd=TAURI, check=True)

    exe = RELEASE / "aerowave.exe"
    if not exe.is_file():
        sys.exit(f"{exe} is missing - run with --build, or build it yourself first")

    ver = version()
    staging = DIST / APP_DIR_NAME
    if staging.exists():
        shutil.rmtree(staging)
    (staging / "data").mkdir(parents=True)

    shutil.copy2(exe, staging / "aerowave.exe")
    loader = find_webview2_loader()
    shutil.copy2(loader, staging / "WebView2Loader.dll")
    shutil.copy2(ROOT / "README.md", staging / "README.md")
    shutil.copy2(ROOT / "LICENSE", staging / "LICENSE")
    (staging / "data" / "README.txt").write_text(DATA_README, encoding="utf-8")

    zip_path = DIST / f"{APP_DIR_NAME}-{ver}-win-x64.zip"
    zip_path.unlink(missing_ok=True)
    with zipfile.ZipFile(zip_path, "w", zipfile.ZIP_DEFLATED, compresslevel=9) as zf:
        for path in sorted(staging.rglob("*")):
            if path.is_file():
                zf.write(path, pathlib.Path(APP_DIR_NAME) / path.relative_to(staging))

    print(f"loader   : {loader}")
    print(f"contents :")
    for path in sorted(staging.rglob("*")):
        if path.is_file():
            print(f"  {path.relative_to(staging).as_posix():24} {path.stat().st_size:>10,} bytes")
    print(f"zip      : {zip_path}")
    print(f"size     : {zip_path.stat().st_size:,} bytes")
    print(f"sha256   : {sha256(zip_path)}")
    print(f"version  : {ver}")


if __name__ == "__main__":
    main()
