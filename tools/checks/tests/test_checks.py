"""Regression checks for standalone repository validation."""

import contextlib
import io
import pathlib
import subprocess
import sys
import time
import unittest
from unittest.mock import patch

CHECKS = pathlib.Path(__file__).resolve().parents[1]
ROOT = CHECKS.parents[1]
sys.path.insert(0, str(CHECKS))

import check_seam
import check_version
import core_tests
import guard_vendor
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
            self.assertEqual(run.call_args.args[0], ["cargo", "test", "-p", "aerowave-core"])

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


if __name__ == "__main__":
    unittest.main()
