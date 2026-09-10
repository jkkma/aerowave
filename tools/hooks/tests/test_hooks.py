"""Regression checks for Codex event handling and standalone validation."""

import contextlib
import io
import json
import pathlib
import subprocess
import sys
import unittest
from unittest.mock import patch

HOOKS = pathlib.Path(__file__).resolve().parents[1]
ROOT = HOOKS.parents[1]
sys.path.insert(0, str(HOOKS))

import check_seam
import check_version
import core_tests
import guard_vendor
from hook_input import edited_paths, read_event


def event(patch_text, cwd=ROOT):
    return {"tool_name": "apply_patch", "cwd": str(cwd),
            "tool_input": {"command": patch_text}}


def invoke_main(module, args, payload=None):
    output, errors = io.StringIO(), io.StringIO()
    stdin = io.StringIO(json.dumps(payload)) if payload is not None else None
    with patch.object(sys, "stdin", stdin), contextlib.redirect_stdout(output), \
            contextlib.redirect_stderr(errors):
        code = module.main(args)
    return code, output.getvalue(), errors.getvalue()


class PatchPathsTests(unittest.TestCase):
    def test_every_operation_and_rename_destination(self):
        payload = event("""*** Begin Patch
*** Add File: src/new.js
+new
*** Update File: src/old.js
*** Move to: src/vendor/moved.js
@@
-old
+new
*** Delete File: src-tauri/core/src/removed.rs
*** End Patch
""")
        self.assertEqual(edited_paths(payload), {
            ROOT / "src/new.js", ROOT / "src/old.js", ROOT / "src/vendor/moved.js",
            ROOT / "src-tauri/core/src/removed.rs",
        })

    def test_subdirectory_and_absolute_paths(self):
        self.assertEqual(edited_paths(event(
            "*** Update File: ../src/app.js\r\n", ROOT / "docs")), {ROOT / "src/app.js"})
        self.assertEqual(edited_paths(event(
            "*** Update File: " + str(ROOT / "src/app.js"))), {ROOT / "src/app.js"})

    def test_patch_contents_do_not_create_false_paths(self):
        payload = event("*** Update File: src/app.js\n+*** Delete File: src/vendor/lib.js\n")
        self.assertEqual(edited_paths(payload), {ROOT / "src/app.js"})

    def test_shell_text_is_not_treated_as_a_patch(self):
        payload = event("*** Update File: src/vendor/lib.js")
        payload["tool_name"] = "Bash"
        self.assertEqual(edited_paths(payload), set())

    def test_non_object_input_is_rejected(self):
        with patch.object(sys, "stdin", io.StringIO("[]")):
            with self.assertRaises(ValueError):
                read_event()

    def test_malformed_known_patch_fails_closed(self):
        for tool_input in (None, {}, {"command": []}, {"command": "not a patch"}):
            payload = {"tool_name": "apply_patch", "tool_input": tool_input}
            with self.subTest(tool_input=tool_input):
                self.assertEqual(invoke_main(guard_vendor, ["--hook"], payload)[0], 2)


class VendorTests(unittest.TestCase):
    def test_blocks_vendor_anywhere_in_multifile_patch(self):
        payload = event("*** Update File: src/app.js\n*** Update File: src/vendor/three.js\n")
        code, output, errors = invoke_main(guard_vendor, ["--hook"], payload)
        self.assertEqual(code, 0)
        self.assertEqual(json.loads(output)["hookSpecificOutput"]["permissionDecision"], "deny")
        self.assertFalse(errors)

    def test_move_into_or_out_of_vendor_is_protected(self):
        for source, destination in [("src/vendor/a.js", "src/a.js"),
                                    ("src/a.js", "src/vendor/a.js")]:
            with self.subTest(source=source):
                paths = edited_paths(event(f"*** Update File: {source}\n*** Move to: {destination}"))
                self.assertEqual(len(guard_vendor.protected_paths(paths)), 1)

    def test_neighbor_directory_is_allowed(self):
        code, output, errors = invoke_main(guard_vendor, [str(ROOT / "src/vendor-extra/a.js")])
        self.assertEqual((code, output, errors), (0, "", ""))

    def test_cli_reports_vendor_violation_without_stdin(self):
        code, _, errors = invoke_main(guard_vendor, [str(ROOT / "src/vendor/three.js")])
        self.assertEqual(code, 2)
        self.assertIn("must not be hand-edited", errors)


class CoreTests(unittest.TestCase):
    def test_core_second_in_patch_runs_tests_once(self):
        payload = event("*** Update File: src/app.js\n*** Update File: src-tauri/core/src/lib.rs")
        result = subprocess.CompletedProcess([], 0, "tests passed", "")
        with patch.object(core_tests, "find_cargo", return_value=("cargo", None)), \
                patch.object(core_tests.subprocess, "run", return_value=result) as run:
            self.assertEqual(invoke_main(core_tests, ["--hook"], payload)[0], 0)
            run.assert_called_once()
            self.assertEqual(run.call_args.args[0], ["cargo", "test", "-p", "aerowave-core"])

    def test_manifest_and_moved_away_file_trigger_tests(self):
        for name in ("Cargo.toml", "src/removed.rs"):
            self.assertTrue(core_tests.touches_core({ROOT / "src-tauri/core" / name}))
        self.assertFalse(core_tests.touches_core({ROOT / "src-tauri/src/lib.rs"}))

    def test_unrelated_edit_does_not_look_for_cargo(self):
        with patch.object(core_tests, "find_cargo") as find:
            self.assertEqual(invoke_main(core_tests, ["--hook"], event("*** Update File: src/app.js"))[0], 0)
            find.assert_not_called()

    def test_missing_cargo_fails_direct_check(self):
        with patch.object(core_tests, "find_cargo", return_value=(None, None)):
            code, _, errors = invoke_main(core_tests, [])
            self.assertEqual(code, 2)
            self.assertIn("cargo not found", errors)

    def test_failed_tests_are_reported(self):
        result = subprocess.CompletedProcess([], 1, "", "assertion failed")
        with patch.object(core_tests, "find_cargo", return_value=("cargo", None)), \
                patch.object(core_tests.subprocess, "run", return_value=result):
            code, _, errors = invoke_main(core_tests, [])
            self.assertEqual(code, 2)
            self.assertIn("assertion failed", errors)


class StopTests(unittest.TestCase):
    def test_continuation_returns_valid_json_without_rechecking(self):
        for module in (check_version, check_seam):
            with self.subTest(module=module.__name__):
                code, output, errors = invoke_main(module, ["--hook"], {"stop_hook_active": True})
                self.assertEqual((code, json.loads(output), errors), (0, {}, ""))

    def test_strict_version_check_rejects_stale_and_missing_lock_entries(self):
        for lock in ({"aerowave": "1", "aerowave-core": "0"}, {"aerowave": "1"}):
            with patch.object(check_version, "json_version", return_value="1"), \
                    patch.object(check_version, "cargo_package_version", return_value="1"), \
                    patch.object(check_version, "cargo_lock_versions", return_value=lock):
                self.assertEqual(invoke_main(check_version, ["--strict"])[0], 2)
                code, output, _ = invoke_main(check_version, ["--hook"], {})
                self.assertEqual(code, 0)
                self.assertIn("Cargo.lock", json.loads(output)["systemMessage"])

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
            code, output, _ = invoke_main(check_seam, ["--hook"], {})
            self.assertEqual(code, 0)
            self.assertIn("alarm-fire", json.loads(output)["systemMessage"])


class ConfigIntegrationTests(unittest.TestCase):
    @unittest.skipUnless(sys.platform == "win32", "Exercises Windows hook commands")
    def test_configured_commands_work_from_subdirectory(self):
        config = json.loads((ROOT / ".codex/hooks.json").read_text(encoding="utf-8"))
        for name, groups in config["hooks"].items():
            for group in groups:
                for hook in group["hooks"]:
                    payload = event("*** Update File: ../src/app.js", ROOT / "docs")
                    payload["hook_event_name"] = name
                    with self.subTest(command=hook["commandWindows"]):
                        result = subprocess.run(hook["commandWindows"], shell=True,
                                                cwd=ROOT / "docs", input=json.dumps(payload),
                                                text=True, capture_output=True, timeout=30)
                        self.assertEqual(result.returncode, 0, result.stderr)
                        if name == "Stop":
                            self.assertIsInstance(json.loads(result.stdout), dict)


if __name__ == "__main__":
    unittest.main()
