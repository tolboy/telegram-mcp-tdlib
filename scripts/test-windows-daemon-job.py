#!/usr/bin/env python3
"""Offline regression: a Windows daemon survives its launcher's Job Object.

Uses only the standard library and temporary application data, never Telegram.
Checks both an ordinary host job that allows breakaway and one that forbids it.
"""
import argparse
import ctypes
from ctypes import wintypes
import json
import os
from pathlib import Path
import socket
import subprocess
import sys
import tempfile


class BasicLimits(ctypes.Structure):
    _fields_ = [("process_time", ctypes.c_longlong), ("job_time", ctypes.c_longlong),
                ("flags", wintypes.DWORD), ("minimum_working_set", ctypes.c_size_t),
                ("maximum_working_set", ctypes.c_size_t), ("active_processes", wintypes.DWORD),
                ("affinity", ctypes.c_size_t), ("priority", wintypes.DWORD), ("scheduling", wintypes.DWORD)]


class IoCounters(ctypes.Structure):
    _fields_ = [(name, ctypes.c_ulonglong) for name in ("read_ops", "write_ops", "other_ops", "read_bytes", "write_bytes", "other_bytes")]


class ExtendedLimits(ctypes.Structure):
    _fields_ = [("basic", BasicLimits), ("io", IoCounters),
                ("process_memory", ctypes.c_size_t), ("job_memory", ctypes.c_size_t),
                ("peak_process_memory", ctypes.c_size_t), ("peak_job_memory", ctypes.c_size_t)]


def winapi():
    kernel = ctypes.WinDLL("kernel32", use_last_error=True)
    for name, args, result in (
        ("CreateJobObjectW", [ctypes.c_void_p, wintypes.LPCWSTR], wintypes.HANDLE),
        ("SetInformationJobObject", [wintypes.HANDLE, ctypes.c_int, ctypes.c_void_p, wintypes.DWORD], wintypes.BOOL),
        ("AssignProcessToJobObject", [wintypes.HANDLE, wintypes.HANDLE], wintypes.BOOL),
        ("OpenProcess", [wintypes.DWORD, wintypes.BOOL, wintypes.DWORD], wintypes.HANDLE),
        ("CloseHandle", [wintypes.HANDLE], wintypes.BOOL),
    ):
        method = getattr(kernel, name)
        method.argtypes, method.restype = args, result
    return kernel


def checked(result):
    if not result:
        raise ctypes.WinError(ctypes.get_last_error())
    return result


def command(args):
    if args.executable:
        return [str(Path(args.executable).resolve(strict=True))]
    return ["java", "-jar", str(Path(args.jar).resolve(strict=True))]


def worker(args):
    if sys.stdin.readline().strip() != "go":
        raise RuntimeError("Worker must wait for its Job Object assignment")
    result = subprocess.run(command(args) + ["daemon", "start", "--port", str(args.port)],
                            capture_output=True, text=True, encoding="utf-8", timeout=70)
    print(json.dumps({"returncode": result.returncode, "stdout": result.stdout, "stderr": result.stderr}))


def scenario(args, allow_breakaway):
    root = Path(tempfile.mkdtemp(prefix="telegram-mcp-job-smoke-"))
    env = {k: v for k, v in os.environ.items() if not k.upper().startswith(
        ("TDLIB_", "TELEGRAM_", "MCP_", "SPRING_", "SERVER_", "AUTH_"))}
    env.update(TELEGRAM_MCP_DATA_DIR=str(root), TDLIB_API_ID="0")
    with socket.socket() as reservation:
        reservation.bind(("127.0.0.1", 0))
        port = reservation.getsockname()[1]
    kernel = winapi()
    job = checked(kernel.CreateJobObjectW(None, None))
    limits = ExtendedLimits()
    limits.basic.flags = 0x2000 | (0x800 if allow_breakaway else 0)  # KILL_ON_JOB_CLOSE, BREAKAWAY_OK
    process = None
    base = command(args)
    try:
        checked(kernel.SetInformationJobObject(job, 9, ctypes.byref(limits), ctypes.sizeof(limits)))
        worker_args = [sys.executable, str(Path(__file__).resolve()), "--worker", "--port", str(port)]
        worker_args += ["--executable", base[0]] if args.executable else ["--jar", base[-1]]
        process = subprocess.Popen(worker_args, stdin=subprocess.PIPE, stdout=subprocess.PIPE,
                                   stderr=subprocess.PIPE, text=True, encoding="utf-8", env=env,
                                   creationflags=subprocess.CREATE_NO_WINDOW)
        handle = checked(kernel.OpenProcess(0x0100 | 0x0001, False, process.pid))  # SET_QUOTA | TERMINATE
        try:
            checked(kernel.AssignProcessToJobObject(job, handle))
        finally:
            kernel.CloseHandle(handle)
        output, errors = process.communicate("go\n", timeout=85)
        assert process.returncode == 0, "Worker failed; inspect retained evidence"
        result = json.loads(output)
        (root / "launcher-result.json").write_text(json.dumps(result, indent=2), encoding="utf-8")
        (root / "worker-stderr.log").write_text(errors, encoding="utf-8")
        checked(kernel.CloseHandle(job))
        job = None
        if allow_breakaway:
            assert result["returncode"] == 0 and result["stdout"].startswith("READY"), result
            status = subprocess.run(base + ["daemon", "status"], env=env, capture_output=True,
                                    text=True, timeout=15)
            assert status.returncode == 0 and status.stdout.startswith("READY"), "Daemon died when host Job Object closed"
        else:
            assert result["returncode"] != 0, "Restricted job unexpectedly launched a daemon"
            assert "breakaway" in (result["stdout"] + result["stderr"]).lower(), "Missing actionable breakaway error"
            assert not (root / "daemon/state.json").exists(), "An attached fallback daemon was started"
        print(json.dumps({"breakaway_allowed": allow_breakaway, "result": "PASS",
                          "launch_outcome": "survived_host_close" if result["returncode"] == 0 else "rejected_breakaway",
                          "evidence": str(root)}))
    finally:
        if job:
            kernel.CloseHandle(job)
        if process and process.poll() is None:
            process.kill()
            process.communicate(timeout=10)
        stopped = subprocess.run(base + ["daemon", "stop"], env=env, capture_output=True, text=True, timeout=25)
        if stopped.returncode:
            raise RuntimeError(f"Isolated daemon cleanup failed; inspect {root}")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    target = parser.add_mutually_exclusive_group(required=True)
    target.add_argument("--jar")
    target.add_argument("--executable")
    parser.add_argument("--worker", action="store_true", help=argparse.SUPPRESS)
    parser.add_argument("--port", type=int, help=argparse.SUPPRESS)
    args = parser.parse_args()
    if sys.platform != "win32":
        parser.error("This regression requires Windows")
    if args.worker:
        worker(args)
    else:
        scenario(args, True)
        scenario(args, False)
