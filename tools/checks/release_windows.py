"""Build-package validation and one-time publication for the 0.16.0 prerelease.

Publication is only allowed inside this repository's main-push Actions run.
Existing files, tags, releases and assets are never removed or replaced.
"""

import argparse
import ctypes
import hashlib
import json
import os
import pathlib
import struct
import subprocess
import sys
import zipfile

ROOT = pathlib.Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "tools"))
import package as portable_package

VERSION = "0.16.0"
TAG = "v" + VERSION
REPOSITORY = "jkkma/aerowave"
ASSET = f"Aerowave-{VERSION}-win-x64.zip"
RECEIPT = ROOT / "dist" / f"Aerowave-{VERSION}-validation.json"
MEMBERS = {
    "Aerowave/aerowave.exe", "Aerowave/WebView2Loader.dll",
    "Aerowave/README.md", "Aerowave/LICENSE", "Aerowave/data/README.txt",
}


def sha256(data):
    return hashlib.sha256(data).hexdigest()


def assert_fresh(paths):
    occupied = [str(path) for path in paths if path.exists() or path.is_symlink()]
    if occupied:
        raise ValueError("Refusing to replace retained output: " + ", ".join(occupied))


def validate_pe(data, *, dll):
    if len(data) < 64 or data[:2] != b"MZ":
        raise ValueError("Expected a Windows PE binary")
    offset = struct.unpack_from("<I", data, 60)[0]
    if offset + 24 > len(data) or data[offset:offset + 4] != b"PE\0\0":
        raise ValueError("Missing PE header")
    machine = struct.unpack_from("<H", data, offset + 4)[0]
    characteristics = struct.unpack_from("<H", data, offset + 22)[0]
    if machine != 0x8664 or bool(characteristics & 0x2000) != dll:
        raise ValueError("Expected the Windows x64 " + ("DLL" if dll else "executable"))


def fixed_versions(data):
    values = struct.unpack("<13I", data)
    if values[0] != 0xFEEF04BD:
        raise ValueError("Invalid Windows version resource")
    return [tuple((values[i] >> 16, values[i] & 0xFFFF,
                   values[i + 1] >> 16, values[i + 1] & 0xFFFF)) for i in (2, 4)]


def binary_versions(path):
    from ctypes import wintypes
    api = ctypes.WinDLL("version", use_last_error=True)
    api.GetFileVersionInfoSizeW.argtypes = [wintypes.LPCWSTR, ctypes.POINTER(wintypes.DWORD)]
    api.GetFileVersionInfoSizeW.restype = wintypes.DWORD
    api.GetFileVersionInfoW.argtypes = [wintypes.LPCWSTR, wintypes.DWORD, wintypes.DWORD, ctypes.c_void_p]
    api.GetFileVersionInfoW.restype = wintypes.BOOL
    api.VerQueryValueW.argtypes = [ctypes.c_void_p, wintypes.LPCWSTR,
                                 ctypes.POINTER(ctypes.c_void_p), ctypes.POINTER(wintypes.UINT)]
    api.VerQueryValueW.restype = wintypes.BOOL
    unused = wintypes.DWORD()
    size = api.GetFileVersionInfoSizeW(str(path), ctypes.byref(unused))
    if not size:
        raise ValueError("The executable has no readable Windows version resource")
    data = ctypes.create_string_buffer(size)
    if not api.GetFileVersionInfoW(str(path), 0, size, data):
        raise ctypes.WinError(ctypes.get_last_error())
    pointer, length = ctypes.c_void_p(), wintypes.UINT()
    if not api.VerQueryValueW(data, "\\", ctypes.byref(pointer), ctypes.byref(length)) or length.value < 52:
        raise ValueError("Missing fixed file/product version")
    return fixed_versions(ctypes.string_at(pointer, 52))


def validate_archive(archive, expected):
    with zipfile.ZipFile(archive) as package:
        names = package.namelist()
        if len(names) != len(set(names)) or set(names) != MEMBERS:
            raise ValueError("ZIP must contain exactly the executable, loader, README, licence and portable data README")
        damaged = package.testzip()
        if damaged:
            raise ValueError(f"ZIP CRC check failed: {damaged}")
        contents = {name: package.read(name) for name in names}
    if any(not content for content in contents.values()):
        raise ValueError("ZIP contains an empty required file")
    validate_pe(contents["Aerowave/aerowave.exe"], dll=False)
    validate_pe(contents["Aerowave/WebView2Loader.dll"], dll=True)
    for name, original in expected.items():
        if contents[name] != original:
            raise ValueError(f"Packaged file differs from the validated build/source: {name}")
    return {name: sha256(contents[name]) for name in sorted(contents)}


def run(*command):
    return subprocess.run(command, cwd=ROOT, check=True, capture_output=True,
                          text=True, encoding="utf-8", stdin=subprocess.DEVNULL).stdout


def github(*arguments, payload=None):
    command = ["gh", "api", *arguments]
    if payload is not None:
        command += ["--input", "-"]
    result = subprocess.run(command, cwd=ROOT, check=True, capture_output=True,
                            text=True, encoding="utf-8",
                            input=json.dumps(payload) if payload is not None else None)
    return json.loads(result.stdout)


def release_context():
    if (os.environ.get("GITHUB_REPOSITORY") != REPOSITORY
            or os.environ.get("GITHUB_EVENT_NAME") != "push"
            or os.environ.get("GITHUB_REF") != "refs/heads/main"):
        raise ValueError("Publication is restricted to jkkma/aerowave main-push Actions runs")
    revision = run("git", "rev-parse", "HEAD").strip()
    if revision != os.environ.get("GITHUB_SHA"):
        raise ValueError("Checkout does not match the exact checked Actions commit")
    run("git", "diff", "--exit-code", "HEAD", "--")
    if portable_package.version() != VERSION:
        raise ValueError("This publication path authorizes only version " + VERSION)
    return revision


def require_unused_tag_and_release():
    refs = github(f"repos/{REPOSITORY}/git/matching-refs/tags/{TAG}")
    if any(ref["ref"] == "refs/tags/" + TAG for ref in refs):
        raise ValueError(f"Tag {TAG} already exists; refusing to move or reuse it")
    pages = github(f"repos/{REPOSITORY}/releases", "--paginate", "--slurp")
    if any(release["tag_name"] == TAG for page in pages for release in page):
        raise ValueError(f"Release {TAG} already exists; refusing to replace it or its assets")


def package_release():
    revision = release_context()
    if os.name != "nt":
        raise ValueError("Package verification requires the Windows build runner")
    exe = portable_package.RELEASE / "aerowave.exe"
    want = tuple(int(part) for part in VERSION.split(".")) + (0,)
    if binary_versions(exe) != [want, want]:
        raise ValueError("The executable's file/product version does not match " + VERSION)
    expected = {
        "Aerowave/aerowave.exe": exe.read_bytes(),
        "Aerowave/WebView2Loader.dll": portable_package.find_webview2_loader().read_bytes(),
        "Aerowave/README.md": (ROOT / "README.md").read_bytes(),
        "Aerowave/LICENSE": (ROOT / "LICENSE").read_bytes(),
        # package.py writes text with the platform's normal newline translation.
        "Aerowave/data/README.txt": portable_package.DATA_README.replace("\n", os.linesep).encode("utf-8"),
    }
    archive = ROOT / "dist" / ASSET
    assert_fresh([ROOT / "dist" / "Aerowave", archive, RECEIPT])
    # package.py's removal branches are unreachable after these absence checks
    # in this fresh, single-owner hosted-runner workspace.
    subprocess.run([sys.executable, "-B", "tools/package.py"], cwd=ROOT, check=True)
    members = validate_archive(archive, expected)
    receipt = {"version": VERSION, "commit": revision, "asset": ASSET,
               "size": archive.stat().st_size, "sha256": portable_package.sha256(archive),
               "members": members}
    with RECEIPT.open("x", encoding="utf-8") as handle:
        json.dump(receipt, handle, indent=2)
        handle.write("\n")
    print(json.dumps(receipt, indent=2))


def validate_release_record(release, receipt, *, draft):
    if release["tag_name"] != TAG or release["draft"] is not draft or release["prerelease"] is not True:
        raise ValueError("GitHub release flags/tag do not match the authorized prerelease")
    assets = release["assets"]
    if len(assets) != 1 or assets[0]["name"] != ASSET or assets[0]["size"] != receipt["size"]:
        raise ValueError("GitHub release must contain exactly the verified Windows ZIP")
    asset = assets[0]
    if asset["state"] != "uploaded":
        raise ValueError("GitHub asset upload has not completed")
    if asset.get("digest") and asset["digest"] != "sha256:" + receipt["sha256"]:
        raise ValueError("GitHub asset digest does not match the packaged ZIP")


def publish_release():
    revision = release_context()
    receipt = json.loads(RECEIPT.read_text(encoding="utf-8"))
    archive = ROOT / "dist" / ASSET
    if (receipt["commit"] != revision or receipt["version"] != VERSION or receipt["asset"] != ASSET
            or receipt["size"] != archive.stat().st_size
            or receipt["sha256"] != portable_package.sha256(archive)):
        raise ValueError("Package validation receipt does not match this commit and ZIP")
    notes = (ROOT / "docs" / "releases" / f"{VERSION}.md").read_text(encoding="utf-8").strip()
    if not notes:
        raise ValueError("Release notes are empty")
    notes += (f"\n\n## Built artifact\n\n- Commit: `{revision}`\n"
              f"- Asset: `{ASSET}` ({receipt['size']:,} bytes)\n"
              f"- SHA-256: `{receipt['sha256']}`\n")
    require_unused_tag_and_release()
    # Creating a ref is atomic. A concurrent publication fails here without
    # moving a tag or overwriting an existing release.
    github(f"repos/{REPOSITORY}/git/refs", "--method", "POST",
           payload={"ref": "refs/tags/" + TAG, "sha": revision})
    release = github(f"repos/{REPOSITORY}/releases", "--method", "POST", payload={
        "tag_name": TAG, "target_commitish": revision, "name": f"Aerowave {VERSION} (prerelease)",
        "body": notes, "draft": True, "prerelease": True, "make_latest": "false",
    })
    print(f"Created draft release {release['id']}; failures retain it and its tag for review.", flush=True)
    run("gh", "release", "upload", TAG, str(archive), "--repo", REPOSITORY)
    endpoint = f"repos/{REPOSITORY}/releases/{release['id']}"
    validate_release_record(github(endpoint), receipt, draft=True)
    verify_dir = ROOT / "dist" / f"verified-upload-{VERSION}"
    assert_fresh([verify_dir])
    verify_dir.mkdir()
    run("gh", "release", "download", TAG, "--repo", REPOSITORY,
        "--pattern", ASSET, "--dir", str(verify_dir))
    if portable_package.sha256(verify_dir / ASSET) != receipt["sha256"]:
        raise ValueError("Downloaded GitHub draft asset checksum differs; draft remains unpublished")
    ref = github(f"repos/{REPOSITORY}/git/ref/tags/{TAG}")
    if ref["object"]["type"] != "commit" or ref["object"]["sha"] != revision:
        raise ValueError("Release tag no longer points at the checked commit")
    # Publish only after a complete ZIP has survived both local checks and an
    # authenticated upload/download checksum round trip. Never publish empty.
    github(endpoint, "--method", "PATCH", payload={"draft": False, "prerelease": True, "make_latest": "false"})
    published = github(endpoint)
    validate_release_record(published, receipt, draft=False)
    print("Published verified prerelease: " + published["html_url"])
    print("SHA-256: " + receipt["sha256"])
    summary = os.environ.get("GITHUB_STEP_SUMMARY")
    if summary:
        with open(summary, "a", encoding="utf-8") as handle:
            handle.write(f"## Windows prerelease published\n\n{published['html_url']}\n\n{notes}\n")


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("action", choices=["preflight", "package", "publish"])
    args = parser.parse_args(argv)
    try:
        if args.action == "preflight":
            release_context()
            require_unused_tag_and_release()
        elif args.action == "package":
            package_release()
        else:
            publish_release()
    except (OSError, ValueError, KeyError, subprocess.SubprocessError, zipfile.BadZipFile) as error:
        print(f"Release stopped without deleting or replacing artifacts: {error}", file=sys.stderr)
        if isinstance(error, subprocess.CalledProcessError) and error.stderr:
            print(error.stderr.strip(), file=sys.stderr)
        return 2
    return 0


if __name__ == "__main__":
    sys.exit(main())
