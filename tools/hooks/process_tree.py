"""Run a check with a deadline that also owns its descendants."""

import os
import signal
import subprocess
import sys


class WindowsJob:
    def __init__(self):
        import ctypes
        from ctypes import wintypes

        class Limits(ctypes.Structure):
            _fields_ = [("process_time", ctypes.c_int64), ("job_time", ctypes.c_int64),
                        ("flags", wintypes.DWORD), ("min_working", ctypes.c_size_t),
                        ("max_working", ctypes.c_size_t), ("process_limit", wintypes.DWORD),
                        ("affinity", ctypes.c_size_t), ("priority", wintypes.DWORD),
                        ("scheduling", wintypes.DWORD)]

        class IoCounters(ctypes.Structure):
            _fields_ = [(name, ctypes.c_uint64) for name in
                        ("read_ops", "write_ops", "other_ops", "read_bytes", "write_bytes", "other_bytes")]

        class ExtendedLimits(ctypes.Structure):
            _fields_ = [("basic", Limits), ("io", IoCounters),
                        ("process_memory", ctypes.c_size_t), ("job_memory", ctypes.c_size_t),
                        ("peak_process", ctypes.c_size_t), ("peak_job", ctypes.c_size_t)]

        self.api = ctypes.WinDLL("kernel32", use_last_error=True)
        for name, args, result in [
            ("CreateJobObjectW", [ctypes.c_void_p, wintypes.LPCWSTR], wintypes.HANDLE),
            ("SetInformationJobObject", [wintypes.HANDLE, ctypes.c_int, ctypes.c_void_p, wintypes.DWORD], wintypes.BOOL),
            ("AssignProcessToJobObject", [wintypes.HANDLE, wintypes.HANDLE], wintypes.BOOL),
            ("OpenProcess", [wintypes.DWORD, wintypes.BOOL, wintypes.DWORD], wintypes.HANDLE),
            ("TerminateJobObject", [wintypes.HANDLE, wintypes.UINT], wintypes.BOOL),
            ("CloseHandle", [wintypes.HANDLE], wintypes.BOOL),
        ]:
            fn = getattr(self.api, name)
            fn.argtypes, fn.restype = args, result
        self.handle = self.api.CreateJobObjectW(None, None)
        if not self.handle:
            raise ctypes.WinError(ctypes.get_last_error())
        limits = ExtendedLimits()
        limits.basic.flags = 0x2000  # JOB_OBJECT_LIMIT_KILL_ON_JOB_CLOSE
        if not self.api.SetInformationJobObject(self.handle, 9, ctypes.byref(limits), ctypes.sizeof(limits)):
            error = ctypes.WinError(ctypes.get_last_error())
            self.close()
            raise error

    def assign(self, pid):
        import ctypes
        process = self.api.OpenProcess(0x0100 | 0x0001, False, pid)
        if not process:
            raise ctypes.WinError(ctypes.get_last_error())
        try:
            if not self.api.AssignProcessToJobObject(self.handle, process):
                raise ctypes.WinError(ctypes.get_last_error())
        finally:
            self.api.CloseHandle(process)

    def terminate(self):
        import ctypes
        if not self.api.TerminateJobObject(self.handle, 1):
            raise ctypes.WinError(ctypes.get_last_error())

    def close(self):
        if self.handle:
            self.api.CloseHandle(self.handle)
            self.handle = None


def run_process(command, *, cwd=None, env=None, timeout):
    job = WindowsJob() if os.name == "nt" else None
    process = None
    try:
        # The wrapper cannot spawn Cargo until it belongs to the job. Assigning
        # an already-running Cargo would leave a race for an unowned test child.
        launch = [sys.executable, "-B", os.path.abspath(__file__), "--child", *command] if job else command
        process = subprocess.Popen(
            launch, cwd=cwd, env=env,
            stdin=subprocess.PIPE if job else subprocess.DEVNULL,
            stdout=subprocess.PIPE, stderr=subprocess.PIPE,
            text=True, encoding="utf-8", errors="replace",
            start_new_session=not bool(job),
            creationflags=subprocess.CREATE_NO_WINDOW if job else 0,
        )
        if job:
            job.assign(process.pid)
        try:
            stdout, stderr = process.communicate("1" if job else None, timeout=timeout)
        except subprocess.TimeoutExpired:
            if job:
                job.terminate()
            else:
                os.killpg(process.pid, signal.SIGKILL)
            stdout, stderr = process.communicate(timeout=5)
            raise subprocess.TimeoutExpired(command, timeout, stdout, stderr) from None
        return subprocess.CompletedProcess(command, process.returncode, stdout, stderr)
    finally:
        if job:
            job.close()
        if process is not None and process.poll() is None:
            process.kill()
            process.wait(timeout=5)


if __name__ == "__main__":
    if len(sys.argv) < 3 or sys.argv[1] != "--child" or sys.stdin.read(1) != "1":
        sys.exit(2)
    sys.exit(subprocess.call(sys.argv[2:], stdin=subprocess.DEVNULL))
