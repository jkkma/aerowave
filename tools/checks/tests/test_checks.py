"""Regression checks for standalone repository validation."""

import contextlib
import io
import pathlib
import struct
import subprocess
import sys
import time
import unittest
import warnings
import zipfile
from unittest.mock import Mock, patch

CHECKS = pathlib.Path(__file__).resolve().parents[1]
ROOT = CHECKS.parents[1]
sys.path.insert(0, str(CHECKS))

import check_seam
import check_version
import check_repository
import core_tests
import guard_vendor
import release_windows
from paths import resolve_paths
from process_tree import run_process


def invoke_main(module, args):
    output, errors = io.StringIO(), io.StringIO()
    with patch.object(sys, "stdin", None), contextlib.redirect_stdout(output), \
            contextlib.redirect_stderr(errors):
        code = module.main(args)
    return code, output.getvalue(), errors.getvalue()


class PathTests(unittest.TestCase):
    def test_subdirectory_and_absolute_paths(self):
        with patch.object(pathlib.Path, "cwd", return_value=ROOT / "docs"):
            self.assertEqual(resolve_paths(["../src/app.js"]), {ROOT / "src/app.js"})
            self.assertEqual(resolve_paths([str(ROOT / "src/app.js")]), {ROOT / "src/app.js"})


class VendorTests(unittest.TestCase):
    def test_blocks_vendor_anywhere_in_file_arguments(self):
        code, output, errors = invoke_main(guard_vendor, [
            str(ROOT / "src/app.js"), str(ROOT / "src/vendor/three.js"),
        ])
        self.assertEqual(code, 2)
        self.assertFalse(output)
        self.assertIn("must not be hand-edited", errors)

    def test_move_into_or_out_of_vendor_is_protected(self):
        for source, destination in [("src/vendor/a.js", "src/a.js"),
                                    ("src/a.js", "src/vendor/a.js")]:
            with self.subTest(source=source):
                paths = resolve_paths([str(ROOT / source), str(ROOT / destination)])
                self.assertEqual(len(guard_vendor.protected_paths(paths)), 1)

    def test_neighbor_directory_is_allowed(self):
        code, output, errors = invoke_main(guard_vendor, [str(ROOT / "src/vendor-extra/a.js")])
        self.assertEqual((code, output, errors), (0, "", ""))

    def test_cli_reports_vendor_violation_without_stdin(self):
        code, _, errors = invoke_main(guard_vendor, [str(ROOT / "src/vendor/three.js")])
        self.assertEqual(code, 2)
        self.assertIn("must not be hand-edited", errors)


class CoreTests(unittest.TestCase):
    def test_core_second_in_file_arguments_runs_tests_once(self):
        result = subprocess.CompletedProcess([], 0, "tests passed", "")
        with patch.object(core_tests, "find_cargo", return_value=("cargo", None)), \
                patch.object(core_tests, "run_process", return_value=result) as run:
            code, output, errors = invoke_main(core_tests, [
                str(ROOT / "src/app.js"), str(ROOT / "src-tauri/core/src/lib.rs"),
            ])
            self.assertEqual((code, output, errors), (0, "tests passed\n", ""))
            run.assert_called_once()
            self.assertEqual(run.call_args.args[0], ["cargo", "test", "--locked", "-p", "aerowave-core"])

    def test_manifest_and_moved_away_file_trigger_tests(self):
        for name in ("Cargo.toml", "src/removed.rs"):
            self.assertTrue(core_tests.touches_core({ROOT / "src-tauri/core" / name}))
        self.assertFalse(core_tests.touches_core({ROOT / "src-tauri/src/lib.rs"}))

    def test_unrelated_edit_does_not_look_for_cargo(self):
        with patch.object(core_tests, "find_cargo") as find:
            self.assertEqual(invoke_main(core_tests, [str(ROOT / "src/app.js")])[0], 0)
            find.assert_not_called()

    def test_missing_cargo_fails_direct_check(self):
        with patch.object(core_tests, "find_cargo", return_value=(None, None)):
            code, _, errors = invoke_main(core_tests, [])
            self.assertEqual(code, 2)
            self.assertIn("cargo not found", errors)

    def test_failed_tests_are_reported(self):
        result = subprocess.CompletedProcess([], 1, "", "assertion failed")
        with patch.object(core_tests, "find_cargo", return_value=("cargo", None)), \
                patch.object(core_tests, "run_process", return_value=result):
            code, _, errors = invoke_main(core_tests, [])
            self.assertEqual(code, 2)
            self.assertIn("assertion failed", errors)


class ProcessTreeTests(unittest.TestCase):
    def test_success_and_unicode_output_are_preserved(self):
        result = run_process([sys.executable, "-X", "utf8", "-c", "print('caf\\u00e9')"], timeout=5)
        self.assertEqual(result.returncode, 0)
        self.assertEqual(result.stdout.strip(), "caf\u00e9")

    def test_timeout_terminates_the_child_holding_output_open(self):
        program = (
            "import subprocess,sys,time; "
            "child=subprocess.Popen([sys.executable,'-c','import time; time.sleep(60)']); "
            "print(child.pid,flush=True); time.sleep(60)"
        )
        started = time.monotonic()
        with self.assertRaises(subprocess.TimeoutExpired) as caught:
            run_process([sys.executable, "-c", program], timeout=2)
        self.assertLess(time.monotonic() - started, 8)
        pid = int(caught.exception.output.strip())
        if sys.platform == "win32":
            import ctypes
            from ctypes import wintypes
            api = ctypes.WinDLL("kernel32", use_last_error=True)
            api.OpenProcess.argtypes = [wintypes.DWORD, wintypes.BOOL, wintypes.DWORD]
            api.OpenProcess.restype = wintypes.HANDLE
            api.GetExitCodeProcess.argtypes = [wintypes.HANDLE, ctypes.POINTER(wintypes.DWORD)]
            api.CloseHandle.argtypes = [wintypes.HANDLE]
            handle = api.OpenProcess(0x1000, False, pid)
            if handle:
                try:
                    code = wintypes.DWORD()
                    self.assertTrue(api.GetExitCodeProcess(handle, ctypes.byref(code)))
                    self.assertNotEqual(code.value, 259, "test child is still running")
                finally:
                    api.CloseHandle(handle)


class ReleaseChecksTests(unittest.TestCase):
    def test_strict_version_check_rejects_stale_and_missing_lock_entries(self):
        for lock in ({"aerowave": "1", "aerowave-core": "0"}, {"aerowave": "1"}):
            with patch.object(check_version, "json_version", return_value="1"), \
                    patch.object(check_version, "cargo_package_version", return_value="1"), \
                    patch.object(check_version, "cargo_lock_versions", return_value=lock):
                self.assertEqual(invoke_main(check_version, ["--strict"])[0], 2)
                code, output, errors = invoke_main(check_version, [])
                self.assertEqual(code, 0)
                self.assertFalse(output)
                self.assertIn("Cargo.lock", errors)

    def test_source_version_mismatch_blocks(self):
        with patch.object(check_version, "json_version", return_value="1"), \
                patch.object(check_version, "cargo_package_version", return_value="0"):
            self.assertEqual(invoke_main(check_version, [])[0], 2)

    def test_unregistered_command_blocks(self):
        with patch.object(check_seam, "registered", return_value={"get_state"}), \
                patch.object(check_seam, "invoked", return_value={"missing"}):
            code, _, errors = invoke_main(check_seam, [])
            self.assertEqual(code, 2)
            self.assertIn("missing", errors)

    def test_unemitted_event_warns(self):
        with patch.object(check_seam, "registered", return_value=set()), \
                patch.object(check_seam, "invoked", return_value=set()), \
                patch.object(check_seam, "listened", return_value={"alarm-fire"}), \
                patch.object(check_seam, "emitted", return_value=set()):
            code, output, errors = invoke_main(check_seam, [])
            self.assertEqual(code, 0)
            self.assertFalse(output)
            self.assertIn("alarm-fire", errors)


class CommandIntegrationTests(unittest.TestCase):
    def test_manual_commands_work_from_subdirectory(self):
        commands = [
            ("check_version.py", ["--strict"]),
            ("check_seam.py", []),
            ("guard_vendor.py", ["../src/app.js"]),
            ("core_tests.py", ["../src/app.js"]),
        ]
        for script, args in commands:
            with self.subTest(script=script):
                result = subprocess.run([sys.executable, "-B", str(CHECKS / script), *args],
                                        cwd=ROOT / "docs", stdin=subprocess.DEVNULL,
                                        text=True, capture_output=True, timeout=30)
                self.assertEqual(result.returncode, 0, result.stderr)


class RepositoryChecksTests(unittest.TestCase):
    def test_collects_committed_staged_unstaged_and_untracked_paths(self):
        outputs = [b"a" * 40 + b"\n", b"src/app.js\0src/vendor/old.js\0",
                   b"src/vendor/new name.js\0src/moved.js\0", b"docs/new\nfile.md\0"]
        with patch.object(check_repository, "git_output", side_effect=outputs) as git:
            paths = check_repository.changed_paths("origin/main")
        self.assertEqual(paths, {ROOT / name for name in [
            "src/app.js", "src/vendor/old.js", "src/vendor/new name.js",
            "src/moved.js", "docs/new\nfile.md",
        ]})
        self.assertEqual(git.call_args_list[0].args,
                         ("rev-parse", "--verify", "--end-of-options", "origin/main^{commit}"))
        for call in git.call_args_list[1:3]:
            self.assertIn("--no-renames", call.args)
            self.assertIn("-z", call.args)
        self.assertIn("--cached", git.call_args_list[1].args)
        self.assertNotIn("--cached", git.call_args_list[2].args)

    def test_invalid_comparison_fails_closed(self):
        with patch.object(check_repository, "changed_paths",
                          side_effect=subprocess.CalledProcessError(128, ["git"])):
            errors = io.StringIO()
            with contextlib.redirect_stderr(errors):
                self.assertEqual(check_repository.check_vendor_changes("missing"), 2)
            self.assertIn("Cannot inspect", errors.getvalue())

    def test_vendor_guard_uses_changed_paths_not_a_fixed_allowlisted_file(self):
        for name in ("src/vendor/new.js", "src/vendor/removed.js"):
            with self.subTest(name=name), patch.object(
                    check_repository, "changed_paths", return_value={ROOT / name}):
                with contextlib.redirect_stderr(io.StringIO()):
                    self.assertEqual(check_repository.check_vendor_changes("HEAD"), 2)

    def test_clean_or_non_vendor_changes_pass(self):
        for paths in (set(), {ROOT / "src/app.js"}, {ROOT / "src/vendor-extra/new.js"}):
            with self.subTest(paths=paths), patch.object(
                    check_repository, "changed_paths", return_value=paths):
                with contextlib.redirect_stdout(io.StringIO()):
                    self.assertEqual(check_repository.check_vendor_changes("HEAD"), 0)

    def test_aggregate_continues_after_failure_and_reports_it(self):
        with patch.object(check_repository.subprocess, "run", side_effect=[
            subprocess.CompletedProcess([], 2), subprocess.CompletedProcess([], 0),
            subprocess.CompletedProcess([], 0),
        ]) as run, patch.object(check_repository, "check_vendor_changes", return_value=0):
            code, _, errors = invoke_main(check_repository, [])
        self.assertEqual(code, 2)
        self.assertEqual(run.call_count, 3)
        self.assertIn("Strict versions", errors)

    def test_aggregate_carries_explicit_base_to_vendor_guard(self):
        with patch.object(check_repository.subprocess, "run",
                          return_value=subprocess.CompletedProcess([], 0)), \
                patch.object(check_repository, "check_vendor_changes", return_value=0) as guard:
            self.assertEqual(invoke_main(check_repository, ["--base", "origin/main"])[0], 0)
        guard.assert_called_once_with("origin/main")


class WindowsReleaseTests(unittest.TestCase):
    @staticmethod
    def pe(*, dll=False, machine=0x8664):
        data = bytearray(128)
        data[:2] = b"MZ"
        struct.pack_into("<I", data, 60, 64)
        data[64:68] = b"PE\0\0"
        struct.pack_into("<H", data, 68, machine)
        struct.pack_into("<H", data, 86, 0x2000 if dll else 0x0002)
        return bytes(data)

    def contents(self):
        return {
            "Aerowave/aerowave.exe": self.pe(),
            "Aerowave/WebView2Loader.dll": self.pe(dll=True),
            "Aerowave/README.md": b"README", "Aerowave/LICENSE": b"MIT licence",
            "Aerowave/data/README.txt": b"Portable settings directory",
        }

    @staticmethod
    def archive(contents):
        output = io.BytesIO()
        with warnings.catch_warnings(), zipfile.ZipFile(output, "w") as archive:
            warnings.simplefilter("ignore", UserWarning)
            for name, value in contents:
                archive.writestr(name, value)
        output.seek(0)
        return output

    def test_portable_zip_validates_all_required_bytes_and_hashes(self):
        contents = self.contents()
        hashes = release_windows.validate_archive(self.archive(contents.items()), contents)
        self.assertEqual(set(hashes), release_windows.MEMBERS)
        self.assertEqual(hashes["Aerowave/LICENSE"], release_windows.sha256(b"MIT licence"))

    def test_missing_empty_duplicate_and_unexpected_members_are_rejected(self):
        contents = self.contents()
        variants = [list(contents.items())[1:], [*contents.items(), ("../outside", b"x")],
                    [*contents.items(), ("Aerowave/LICENSE", b"again")],
                    [(name, b"" if name.endswith("README.txt") else data) for name, data in contents.items()]]
        for members in variants:
            with self.subTest(members=[name for name, _ in members]), self.assertRaises(ValueError):
                release_windows.validate_archive(self.archive(members), contents)

    def test_packaged_file_must_match_the_current_build(self):
        contents = self.contents()
        changed = {**contents, "Aerowave/LICENSE": b"different licence"}
        with self.assertRaisesRegex(ValueError, "differs"):
            release_windows.validate_archive(self.archive(changed.items()), contents)

    def test_non_pe_wrong_architecture_and_wrong_binary_role_are_rejected(self):
        for data, dll in [(b"not a binary", False), (self.pe(machine=0x14C), False),
                          (self.pe(dll=True), False), (self.pe(), True)]:
            with self.subTest(dll=dll), self.assertRaises(ValueError):
                release_windows.validate_pe(data, dll=dll)

    def test_fixed_file_and_product_versions_are_read_independently(self):
        fields = [0xFEEF04BD, 0x10000, 16, 0, 17, 0, 0, 0, 0, 0, 0, 0, 0]
        self.assertEqual(release_windows.fixed_versions(struct.pack("<13I", *fields)),
                         [(0, 16, 0, 0), (0, 17, 0, 0)])
        with self.assertRaisesRegex(ValueError, "version resource"):
            release_windows.fixed_versions(bytes(52))

    def test_packaging_refuses_existing_files_and_dangling_symlinks(self):
        for exists, symlink in [(True, False), (False, True)]:
            path = Mock()
            path.exists.return_value = exists
            path.is_symlink.return_value = symlink
            with self.assertRaisesRegex(ValueError, "Refusing to replace"):
                release_windows.assert_fresh([path])

    def test_publication_requires_this_repository_main_push_context(self):
        with patch.dict("os.environ", {}, clear=True), patch.object(release_windows, "run") as run:
            with self.assertRaisesRegex(ValueError, "restricted"):
                release_windows.release_context()
            run.assert_not_called()

    def test_existing_tag_blocks_before_any_release_creation(self):
        with patch.object(release_windows, "github", return_value=[
                {"ref": "refs/tags/" + release_windows.TAG}]) as github:
            with self.assertRaisesRegex(ValueError, "Tag .* already exists"):
                release_windows.require_unused_tag_and_release()
            github.assert_called_once()

    def test_existing_release_in_later_page_blocks_publication(self):
        with patch.object(release_windows, "github", side_effect=[[], [[], [{"tag_name": release_windows.TAG}]]]):
            with self.assertRaisesRegex(ValueError, "Release .* already exists"):
                release_windows.require_unused_tag_and_release()

    def test_similar_tag_names_do_not_conflict(self):
        with patch.object(release_windows, "github", side_effect=[
                [{"ref": "refs/tags/" + release_windows.TAG + "-older"}], [[]]]):
            release_windows.require_unused_tag_and_release()

    def test_release_requires_complete_exact_asset_and_prerelease_flags(self):
        receipt = {"size": 123, "sha256": "a" * 64}
        asset = {"name": release_windows.ASSET, "size": 123, "state": "uploaded",
                 "digest": "sha256:" + "a" * 64}
        release = {"tag_name": release_windows.TAG, "draft": True, "prerelease": True, "assets": [asset]}
        release_windows.validate_release_record(release, receipt, draft=True)
        for change in [{"assets": []}, {"assets": [asset, asset]}, {"draft": False},
                       {"prerelease": False}, {"tag_name": "v0.16.1"},
                       {"assets": [{**asset, "state": "starter"}]},
                       {"assets": [{**asset, "digest": "sha256:" + "b" * 64}]}]:
            with self.subTest(change=change), self.assertRaises(ValueError):
                release_windows.validate_release_record({**release, **change}, receipt, draft=True)


if __name__ == "__main__":
    unittest.main()
