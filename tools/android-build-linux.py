"""Build and retain an ARM64 Android release using an existing Secret Service key."""

import datetime
import hashlib
import json
import os
import pathlib
import re
import shutil
import stat
import subprocess
import sys
import uuid
import xml.etree.ElementTree as ET
import zipfile

ROOT = pathlib.Path(__file__).resolve().parents[1]
APP_ID = "com.aerowave.radio"
ALIAS = "aerowave-release"
SIGNING_VARIABLES = (
    "AEROWAVE_ANDROID_KEYSTORE_FILE", "AEROWAVE_ANDROID_STORE_PASSWORD",
    "AEROWAVE_ANDROID_KEY_ALIAS", "AEROWAVE_ANDROID_KEY_PASSWORD",
)


def private_path(path, *, directory=False):
    info = path.lstat()
    mode = 0o700 if directory else 0o600
    kind = stat.S_ISDIR if directory else stat.S_ISREG
    if not kind(info.st_mode) or info.st_uid != os.getuid() or stat.S_IMODE(info.st_mode) != mode:
        raise ValueError(f"Signing {'directory' if directory else 'file'} must be owned by this user with mode {mode:04o}: {path}")


def read_signing_manifest(signing_root):
    private_path(signing_root, directory=True)
    manifest_path = signing_root / "signing-manifest.json"
    private_path(manifest_path)
    manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
    required = {"schemaVersion": 2, "applicationId": APP_ID, "alias": ALIAS,
                "keyStoreFile": "aerowave-release.p12", "credentialBackend": "secret-service"}
    if any(manifest.get(key) != value for key, value in required.items()):
        raise ValueError("Existing signing manifest has an unsupported identity or credential backend")
    attributes = manifest.get("secretServiceAttributes", {})
    if (set(attributes) != {"application", "purpose", "keyId"}
            or attributes.get("application") != APP_ID
            or attributes.get("purpose") != "android-release-signing"
            or str(uuid.UUID(attributes.get("keyId", ""))) != attributes.get("keyId")):
        raise ValueError("Existing signing manifest has invalid Secret Service attributes")
    if not re.fullmatch(r"[0-9A-F]{64}", manifest.get("certificateSha256", "")):
        raise ValueError("Existing signing manifest has an invalid certificate fingerprint")
    created = datetime.datetime.fromisoformat(manifest.get("createdUtc", "").replace("Z", "+00:00"))
    if created.utcoffset() != datetime.timedelta(0):
        raise ValueError("Existing signing manifest must record a UTC creation time")
    private_path(signing_root / manifest["keyStoreFile"])
    return manifest


def signing_password(manifest):
    attributes = manifest["secretServiceAttributes"]
    command = ["secret-tool", "lookup"]
    for key in ("application", "purpose", "keyId"):
        command.extend((key, attributes[key]))
    result = subprocess.run(command, capture_output=True, stdin=subprocess.DEVNULL, check=False)
    if result.returncode:
        raise ValueError("Signing password lookup failed; unlock or restore the original Secret Service credential")
    password = result.stdout.removesuffix(b"\n").decode("utf-8")
    if not password or "\0" in password:
        raise ValueError("Secret Service did not return a usable signing password")
    return password


def verify_keystore(keytool, keystore, password, expected):
    environment = dict(os.environ, AEROWAVE_ANDROID_STORE_PASSWORD=password)
    try:
        result = subprocess.run([
            str(keytool), "-exportcert", "-storetype", "PKCS12", "-keystore", str(keystore),
            "-alias", ALIAS, "-storepass:env", "AEROWAVE_ANDROID_STORE_PASSWORD",
        ], env=environment, capture_output=True, stdin=subprocess.DEVNULL, check=False)
    finally:
        environment.pop("AEROWAVE_ANDROID_STORE_PASSWORD", None)
    if result.returncode or not result.stdout:
        raise ValueError("Existing keystore certificate could not be verified; no signing material was changed")
    if hashlib.sha256(result.stdout).hexdigest().upper() != expected:
        raise ValueError("Existing keystore certificate does not match the signing manifest")


def version_code(version):
    if not re.fullmatch(r"(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)", version):
        raise ValueError("Android releases require a stable major.minor.patch version")
    major, minor, patch = map(int, version.split("."))
    code = major * 1_000_000 + minor * 1_000 + patch
    if minor > 999 or patch > 999 or not 1 <= code <= 2_100_000_000:
        raise ValueError("Android semantic version cannot be represented by the supported version code")
    return code


def sdk_tools(sdk):
    def newest(root, suffix):
        candidates = [path for path in root.iterdir() if path.is_dir()
                      and re.fullmatch(r"\d+(?:\.\d+)*", path.name)]
        for path in sorted(candidates, key=lambda item: tuple(map(int, item.name.split("."))), reverse=True):
            if (path / suffix).is_file():
                return path / suffix
        raise ValueError(f"Missing Android SDK tool {suffix} under {root}")

    signer = newest(sdk / "build-tools", pathlib.Path("apksigner"))
    align = signer.with_name("zipalign")
    analyzer = sdk / "cmdline-tools" / "latest" / "bin" / "apkanalyzer"
    if not analyzer.is_file():
        analyzer = newest(sdk / "cmdline-tools", pathlib.Path("bin/apkanalyzer"))
    if not align.is_file():
        raise ValueError("Missing zipalign beside the selected apksigner")
    return signer, align, analyzer


def output_apk(root, version):
    output = root / "src-tauri/gen/android/app/build/outputs/apk"
    metadata_files = [path for path in output.rglob("output-metadata.json") if path.parent.name == "release"]
    if len(metadata_files) != 1:
        raise ValueError("Expected exactly one release APK metadata file")
    path = metadata_files[0]
    metadata = json.loads(path.read_text(encoding="utf-8"))
    if metadata.get("applicationId") != APP_ID or len(metadata.get("elements", [])) != 1:
        raise ValueError("Release metadata has an unexpected application identity or artifact count")
    element = metadata["elements"][0]
    if element.get("versionName") != version or element.get("versionCode") != version_code(version):
        raise ValueError("Release metadata version does not match the source version")
    filename = element.get("outputFile", "")
    if not filename or pathlib.Path(filename).name != filename:
        raise ValueError("Release metadata does not name a local APK file")
    apk = path.parent / filename
    if not apk.is_file() or apk.is_symlink():
        raise ValueError("The release APK recorded by Gradle is missing or linked")
    return apk


def tool_output(*command):
    result = subprocess.run(list(map(str, command)), capture_output=True, text=True,
                            stdin=subprocess.DEVNULL, check=False)
    if result.returncode:
        raise ValueError(f"APK validation failed: {pathlib.Path(command[0]).name} exited {result.returncode}")
    return result.stdout


def confirm_source(root, commit, was_dirty):
    if tool_output("git", "-C", root, "rev-parse", "HEAD").strip() != commit:
        raise ValueError("Source HEAD changed during the build; the APK was retained without a validation receipt")
    now_dirty = bool(tool_output("git", "-C", root, "status", "--porcelain", "--untracked-files=normal"))
    return was_dirty or now_dirty


def validate_apk(apk, version, certificate, tools):
    signer, align, analyzer = tools
    with zipfile.ZipFile(apk) as archive:
        names = archive.namelist()
        if len(names) != len(set(names)) or archive.testzip():
            raise ValueError("Release APK contains duplicate or damaged ZIP members")
        libraries = [name for name in names if re.fullmatch(r"lib/[^/]+/[^/]+\.so", name)]
        if {name.split("/")[1] for name in libraries} != {"arm64-v8a"} or "lib/arm64-v8a/libaerowave_lib.so" not in libraries:
            raise ValueError("Release APK must contain Aerowave's native library for only arm64-v8a")
    signature = tool_output(signer, "verify", "--verbose", "--print-certs", apk)
    fingerprints = re.findall(r"(?im)^(?:Signer #\d+ certificate|V\d+(?:\.\d+)? Signer: certificate) SHA-256 digest:\s*([0-9a-f]{64})\s*$", signature)
    if not fingerprints or {value.upper() for value in fingerprints} != {certificate}:
        raise ValueError("Release APK certificate does not match the existing release key")
    tool_output(align, "-c", "-P", "16", "-v", "4", apk)
    manifest = ET.fromstring(tool_output(analyzer, "manifest", "print", apk))
    android = "{http://schemas.android.com/apk/res/android}"
    application = manifest.find("application")
    if (manifest.get("package") != APP_ID or manifest.get(android + "versionName") != version
            or int(manifest.get(android + "versionCode", "0"), 0) != version_code(version)
            or application is None or application.get(android + "debuggable", "false") != "false"):
        raise ValueError("Packaged Android manifest must match the release identity/version and disable debugging")
    method = tool_output(analyzer, "dex", "code", "--class", "com.aerowave.radio.TauriActivity",
                         "--method", "getPluginManager()Lapp/tauri/plugin/PluginManager;", apk)
    if not re.search(r"(?m)^\.method public(?: final)? getPluginManager\(\)Lapp/tauri/plugin/PluginManager;\s*$", method):
        raise ValueError("Packaged Tauri getPluginManager must remain public, concrete and non-static after R8")


def main():
    if sys.platform != "linux":
        raise ValueError("This release helper requires Linux and its Secret Service")
    signing_root = pathlib.Path(os.environ.get("AEROWAVE_ANDROID_SIGNING_ROOT", pathlib.Path.home() / ".local/share/aerowave/signing"))
    if not signing_root.is_absolute() or signing_root.resolve().is_relative_to(ROOT):
        raise ValueError("The signing root must be an absolute private directory outside the repository")
    manifest = read_signing_manifest(signing_root)
    version = json.loads((ROOT / "package.json").read_text(encoding="utf-8"))["version"]
    code = version_code(version)
    for name in ("JAVA_HOME", "ANDROID_HOME", "NDK_HOME"):
        if not os.environ.get(name):
            raise ValueError(f"Set {name} before building; see docs/android-release.md")
    java = pathlib.Path(os.environ["JAVA_HOME"])
    sdk = pathlib.Path(os.environ["ANDROID_HOME"])
    for path in (java / "bin/java", java / "bin/keytool", sdk / "platforms/android-36/android.jar",
                 pathlib.Path(os.environ["NDK_HOME"]) / "source.properties"):
        if not path.is_file():
            raise ValueError(f"Missing Android build prerequisite: {path}")
    tools = sdk_tools(sdk)
    subprocess.run([sys.executable, "-B", "tools/checks/check_version.py", "--strict"], cwd=ROOT, check=True)
    source_commit = tool_output("git", "-C", ROOT, "rev-parse", "HEAD").strip()
    source_dirty = bool(tool_output("git", "-C", ROOT, "status", "--porcelain", "--untracked-files=normal"))
    password = signing_password(manifest)
    environment = None
    try:
        keystore = signing_root / manifest["keyStoreFile"]
        verify_keystore(java / "bin/keytool", keystore, password, manifest["certificateSha256"])
        timestamp = datetime.datetime.now(datetime.timezone.utc).strftime("%Y%m%dT%H%M%S%fZ")
        retained = ROOT / "dist/android-release" / f"{timestamp}-v{version}-aarch64"
        retained.mkdir(parents=True, exist_ok=False)
        print(f"Retaining Android release output: {retained}", flush=True)
        environment = dict(os.environ)
        environment.update(dict(zip(SIGNING_VARIABLES, (str(keystore), password, ALIAS, password))))
        # Do not leave decrypted credentials in a reusable Gradle daemon.
        environment["GRADLE_OPTS"] = (environment.get("GRADLE_OPTS", "") + " -Dorg.gradle.daemon=false").strip()
        subprocess.run(["npm", "run", "tauri", "--", "android", "build", "--apk", "--target", "aarch64", "--ci"],
                       cwd=ROOT, env=environment, check=True)
        apk = retained / f"Aerowave-{version}-android-aarch64.apk"
        with output_apk(ROOT, version).open("rb") as source, apk.open("xb") as destination:
            shutil.copyfileobj(source, destination)
        validate_apk(apk, version, manifest["certificateSha256"], tools)
        source_dirty = confirm_source(ROOT, source_commit, source_dirty)
        apk_hash = hashlib.sha256(apk.read_bytes()).hexdigest().upper()
        receipt = {"schemaVersion": 1, "applicationId": APP_ID, "versionName": version, "versionCode": code,
                   "target": "aarch64", "androidAbi": "arm64-v8a", "apkFile": apk.name,
                   "apkSha256": apk_hash, "certificateSha256": manifest["certificateSha256"],
                   "verifiedBy": ["apksigner verify --verbose --print-certs", "zipalign -c -P 16 -v 4",
                                  "apkanalyzer manifest print", "apkanalyzer dex code getPluginManager"],
                   "sourceCommit": source_commit, "sourceDirty": source_dirty,
                   "createdUtc": datetime.datetime.now(datetime.timezone.utc).isoformat()}
        with (retained / "manifest.json").open("x", encoding="utf-8") as handle:
            json.dump(receipt, handle, indent=2)
            handle.write("\n")
        print(f"Retained signed APK: {apk}\nAPK SHA-256: {apk_hash}\nCertificate SHA-256: {manifest['certificateSha256']}")
    finally:
        if environment is not None:
            for name in SIGNING_VARIABLES:
                environment.pop(name, None)
        password = None


if __name__ == "__main__":
    try:
        main()
    except (OSError, ValueError, KeyError, TypeError, ET.ParseError, zipfile.BadZipFile, subprocess.SubprocessError) as error:
        print(f"Android release stopped without replacing signing material or retained artifacts: {error}", file=sys.stderr)
        sys.exit(2)
