"""Signing and packaged-APK guards using synthetic, in-memory fixtures only."""

import hashlib
import importlib.util
import json
import pathlib
import stat
import subprocess
import types
import unittest
from unittest.mock import patch

HELPER = pathlib.Path(__file__).resolve().parents[2] / "android-build-linux.py"
spec = importlib.util.spec_from_file_location("android_build_linux", HELPER)
build = importlib.util.module_from_spec(spec)
spec.loader.exec_module(build)
CERTIFICATE = b"synthetic public certificate"
FINGERPRINT = hashlib.sha256(CERTIFICATE).hexdigest().upper()
MANIFEST = {"schemaVersion": 2, "applicationId": build.APP_ID, "alias": build.ALIAS,
            "keyStoreFile": "aerowave-release.p12", "credentialBackend": "secret-service",
            "secretServiceAttributes": {"application": build.APP_ID, "purpose": "android-release-signing",
                                        "keyId": "00000000-0000-4000-8000-000000000001"},
            "certificateSha256": FINGERPRINT, "createdUtc": "2026-10-03T12:00:00Z"}
APK_MANIFEST = '<manifest xmlns:android="http://schemas.android.com/apk/res/android" package="com.aerowave.radio" android:versionName="0.16.2" android:versionCode="16002"><application android:debuggable="false"/></manifest>'
METHOD = ".method public final getPluginManager()Lapp/tauri/plugin/PluginManager;\n"
TOOLS = tuple(pathlib.Path(name) for name in ("apksigner", "zipalign", "apkanalyzer"))


class SigningTests(unittest.TestCase):
    def test_private_owner_modes_and_no_links(self):
        for directory in (False, True):
            mode = (stat.S_IFDIR | 0o700) if directory else (stat.S_IFREG | 0o600)
            for actual, owner, succeeds in ((mode, 42, True), (mode | 0o040, 42, False),
                                            (mode, 43, False), (stat.S_IFLNK | 0o700, 42, False)):
                with self.subTest(directory=directory, mode=actual, owner=owner), \
                        patch.object(build.os, "getuid", return_value=42, create=True), \
                        patch.object(pathlib.Path, "lstat", return_value=types.SimpleNamespace(st_mode=actual, st_uid=owner)):
                    if succeeds:
                        build.private_path(pathlib.Path("synthetic-private"), directory=directory)
                    else:
                        with self.assertRaises(ValueError):
                            build.private_path(pathlib.Path("synthetic-private"), directory=directory)

    def test_manifest_identity_and_credential_attributes_are_required(self):
        cases = [MANIFEST]
        for field, value in (("schemaVersion", 1), ("applicationId", "other.app"),
                             ("alias", "debug"), ("keyStoreFile", "../key.p12"),
                             ("credentialBackend", "dpapi"), ("certificateSha256", "bad"),
                             ("secretServiceAttributes", {"application": build.APP_ID}),
                             ("createdUtc", "2026-10-03T12:00:00+01:00")):
            cases.append({**MANIFEST, field: value})
        for index, value in enumerate(cases):
            with self.subTest(index=index), patch.object(build, "private_path") as private, \
                    patch.object(pathlib.Path, "read_text", return_value=json.dumps(value)):
                if not index:
                    self.assertEqual(build.read_signing_manifest(pathlib.Path("synthetic-private")), MANIFEST)
                    self.assertEqual(private.call_count, 3)
                else:
                    with self.assertRaises(ValueError):
                        build.read_signing_manifest(pathlib.Path("synthetic-private"))

    def test_lookup_keeps_password_in_memory_and_preserves_its_spaces(self):
        secret = " synthetic password "
        result = subprocess.CompletedProcess([], 0, (secret + "\n").encode(), b"")
        with patch.object(build.subprocess, "run", return_value=result) as run:
            self.assertEqual(build.signing_password(MANIFEST), secret)
        command = run.call_args.args[0]
        self.assertEqual(command[:2], ["secret-tool", "lookup"])
        self.assertNotIn(secret, command)
        self.assertIn(MANIFEST["secretServiceAttributes"]["keyId"], command)
        with patch.object(build.subprocess, "run", return_value=subprocess.CompletedProcess([], 1, b"", b"failure")):
            with self.assertRaises(ValueError):
                build.signing_password(MANIFEST)

    def test_certificate_verification_uses_password_environment_and_refuses_mismatch(self):
        result = subprocess.CompletedProcess([], 0, CERTIFICATE, b"")
        for fingerprint, succeeds in ((FINGERPRINT, True), ("0" * 64, False)):
            observed = {}
            def run(command, **kwargs):
                observed.update(command=command, password=kwargs["env"]["AEROWAVE_ANDROID_STORE_PASSWORD"])
                return result
            with self.subTest(succeeds=succeeds), patch.object(build.subprocess, "run", side_effect=run):
                if succeeds:
                    build.verify_keystore("keytool", "synthetic.p12", "synthetic-secret", fingerprint)
                else:
                    with self.assertRaises(ValueError):
                        build.verify_keystore("keytool", "synthetic.p12", "synthetic-secret", fingerprint)
            self.assertNotIn("synthetic-secret", observed["command"])
            self.assertIn("-storepass:env", observed["command"])
            self.assertEqual(observed["password"], "synthetic-secret")


class ApkTests(unittest.TestCase):
    def validate(self, *, names=None, manifest=APK_MANIFEST, method=METHOD, certificate=FINGERPRINT):
        def output(*command):
            if command[0] == TOOLS[0]:
                return f"Signer #1 certificate SHA-256 digest: {certificate.lower()}\n"
            if command[1:3] == ("manifest", "print"):
                return manifest
            if command[1:3] == ("dex", "code"):
                return method
            return ""
        with patch.object(build.zipfile, "ZipFile") as archive, patch.object(build, "tool_output", side_effect=output) as tools:
            archive.return_value.__enter__.return_value.namelist.return_value = names or ["lib/arm64-v8a/libaerowave_lib.so", "classes.dex"]
            archive.return_value.__enter__.return_value.testzip.return_value = None
            build.validate_apk(pathlib.Path("synthetic.apk"), "0.16.2", FINGERPRINT, TOOLS)
            return tools.call_args_list

    def test_actual_apk_checks_identity_signature_16kb_alignment_and_jni(self):
        calls = self.validate()
        self.assertIn("-P", calls[1].args)
        self.assertIn("16", calls[1].args)
        self.assertEqual(calls[2].args[1:3], ("manifest", "print"))
        self.assertEqual(calls[3].args[1:3], ("dex", "code"))

    def test_wrong_abi_missing_library_and_duplicate_members_fail(self):
        for names in (["lib/x86_64/libaerowave_lib.so"], ["lib/arm64-v8a/other.so"],
                      ["lib/arm64-v8a/libaerowave_lib.so", "lib/x86/libother.so"],
                      ["lib/arm64-v8a/libaerowave_lib.so"] * 2):
            with self.subTest(names=names), self.assertRaises(ValueError):
                self.validate(names=names)

    def test_apk_certificate_identity_version_and_debugging_must_match(self):
        with self.assertRaises(ValueError):
            self.validate(certificate="0" * 64)
        for old, new in (("com.aerowave.radio", "other.app"), ("0.16.2", "0.16.1"),
                         ("16002", "16001"), ('debuggable="false"', 'debuggable="true"')):
            with self.subTest(field=old), self.assertRaises(ValueError):
                self.validate(manifest=APK_MANIFEST.replace(old, new))

    def test_r8_cannot_remove_or_change_jni_method_access(self):
        for method in ("", METHOD.replace("public final", "private final"),
                       METHOD.replace("public final", "public static"),
                       METHOD.replace("public final", "public abstract")):
            with self.subTest(method=method), self.assertRaises(ValueError):
                self.validate(method=method)

    def test_semantic_version_matches_gradle_bounds(self):
        self.assertEqual(build.version_code("0.16.2"), 16002)
        for invalid in ("0.16.2-beta", "00.16.2", "0.1000.0", "0.0.1000", "0.0.0", "2100.0.1"):
            with self.subTest(version=invalid), self.assertRaises(ValueError):
                build.version_code(invalid)

    def test_changed_head_fails_and_either_dirty_status_is_recorded(self):
        for before, after in ((False, False), (True, False), (False, True), (True, True)):
            with self.subTest(before=before, after=after), patch.object(build, "tool_output", side_effect=["commit\n", " M source" if after else ""]):
                self.assertEqual(build.confirm_source("root", "commit", before), before or after)
        with patch.object(build, "tool_output", return_value="another-commit\n"), self.assertRaises(ValueError):
            build.confirm_source("root", "commit", False)


if __name__ == "__main__":
    unittest.main()
