"""Offline regressions for the manual conformance and canary harnesses."""
import base64
import contextlib
from http.client import HTTPConnection
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import importlib.util
import io
import json
import os
from pathlib import Path
import subprocess
import tarfile
import tempfile
import threading
import unittest
from unittest.mock import patch


def load_script(name):
    spec = importlib.util.spec_from_file_location(name.replace("-", "_"),
                                                Path(__file__).resolve().parents[1] / f"{name}.py")
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


class ConformanceTests(unittest.TestCase):
    def test_failed_start_stops_only_the_isolated_daemon(self):
        module = load_script("mcp-conformance")
        with tempfile.TemporaryDirectory() as folder:
            root = Path(folder)
            jar = root / "test.jar"
            jar.touch()
            with patch.dict(os.environ, {"TELEGRAM_MCP_DATA_DIR": "operator-state",
                                         "TDLIB_API_HASH": "operator-secret", "MCP_READ_ONLY": "false"}, clear=True), \
                    patch.object(module.tempfile, "mkdtemp", return_value=folder), \
                    patch.object(module.shutil, "which", return_value="npx"), \
                    patch.object(module, "start_daemon", side_effect=RuntimeError("startup failed")) as start, \
                    patch.object(module.subprocess, "run", return_value=subprocess.CompletedProcess([], 0)) as run, \
                    patch("sys.argv", ["mcp-conformance", "--jar", str(jar)]):
                with self.assertRaisesRegex(RuntimeError, "startup failed"):
                    module.main()
            startup_env = start.call_args.args[3]
            stop_env = run.call_args.kwargs["env"]
            self.assertEqual(startup_env, stop_env)
            self.assertEqual(str(root.resolve()), stop_env["TELEGRAM_MCP_DATA_DIR"])
            self.assertEqual("true", stop_env["MCP_READ_ONLY"])
            self.assertEqual("", stop_env.get("TDLIB_API_HASH"))
            self.assertEqual(["java", "-jar", str(jar), "daemon", "stop"], run.call_args.args[0])

    def test_suite_failure_is_preserved_and_private_state_is_cleaned(self):
        module = load_script("mcp-conformance")

        def start(jar, root, port, env):
            (root / "daemon").mkdir()
            (root / "daemon/api-key").write_text("synthetic-key")

        with tempfile.TemporaryDirectory() as folder:
            root = Path(folder) / "state"
            root.mkdir()
            jar = Path(folder) / "test.jar"
            jar.touch()
            report = Path(folder) / "reports"
            with patch.object(module.tempfile, "mkdtemp", return_value=str(root)), \
                    patch.object(module.shutil, "which", return_value="npx"), \
                    patch.object(module, "start_daemon", side_effect=start), \
                    patch.object(module, "ThreadingHTTPServer"), \
                    patch.object(module.subprocess, "run", side_effect=[subprocess.CompletedProcess([], 42),
                                                                        subprocess.CompletedProcess([], 0),
                                                                        subprocess.CompletedProcess([], 0)]) as run, \
                    patch("sys.argv", ["mcp-conformance", "--jar", str(jar), "--output-dir", str(report),
                                       "--scenario", "server-initialize", "--scenario", "tools-list"]):
                with self.assertRaises(SystemExit) as failure:
                    module.main()
            self.assertEqual(42, failure.exception.code)
            self.assertFalse(root.exists())
            suite, second, stop = run.call_args_list
            self.assertEqual(suite.kwargs["env"], stop.kwargs["env"])
            self.assertEqual(["--output-dir", str(report.resolve() / "server-initialize")], suite.args[0][-2:])
            self.assertEqual(["--output-dir", str(report.resolve() / "tools-list")], second.args[0][-2:])
            self.assertEqual([{"scenario": "server-initialize", "exit_code": 42},
                              {"scenario": "tools-list", "exit_code": 0}],
                             json.loads((report / "executions.json").read_text()))

    def test_proxy_forwards_small_sse_event_before_stream_closes(self):
        module = load_script("mcp-conformance")
        release = threading.Event()
        event = b"data: first\n\n"
        observed = {}

        class Upstream(BaseHTTPRequestHandler):
            def do_GET(self):
                observed["authorization"] = self.headers.get("Authorization")
                self.send_response(200)
                self.send_header("Content-Type", "text/event-stream")
                self.end_headers()
                self.wfile.write(event)
                self.wfile.flush()
                release.wait(5)

            def log_message(self, *args):
                pass

        upstream = ThreadingHTTPServer(("127.0.0.1", 0), Upstream)
        module.Proxy.target_port = upstream.server_port
        module.Proxy.api_key = "synthetic-key"
        proxy = ThreadingHTTPServer(("127.0.0.1", 0), module.Proxy)
        for server in (upstream, proxy):
            threading.Thread(target=server.serve_forever, daemon=True).start()
        connection = HTTPConnection("127.0.0.1", proxy.server_port, timeout=2)
        try:
            connection.request("GET", "/mcp")
            response = connection.getresponse()
            self.assertEqual(200, response.status)
            self.assertEqual(event, response.read(len(event)))
            self.assertEqual("Bearer synthetic-key", observed["authorization"])
        finally:
            release.set()
            connection.close()
            for server in (proxy, upstream):
                server.shutdown()
                server.server_close()


class CanaryArchiveTests(unittest.TestCase):
    def archive(self, member):
        buffer = io.BytesIO()
        with tarfile.open(fileobj=buffer, mode="w:gz") as archive:
            archive.addfile(member, io.BytesIO(b"test".ljust(member.size, b"x")) if member.isfile() else None)
        return base64.b64encode(buffer.getvalue()).decode("ascii")

    def test_regular_session_file_is_extracted_privately(self):
        module = load_script("telegram-canary")
        member = tarfile.TarInfo("td.binlog")
        member.size = 4
        member.mode = 0o777
        with tempfile.TemporaryDirectory() as folder, \
                patch.dict(os.environ, {"TELEGRAM_CANARY_SESSION_B64": self.archive(member)}):
            target = Path(folder) / "session"
            module.extract_session(target)
            self.assertEqual(b"test", (target / "td.binlog").read_bytes())
            if os.name != "nt":
                self.assertEqual(0o600, (target / "td.binlog").stat().st_mode & 0o777)

    def test_paths_links_devices_and_oversized_expansion_are_rejected(self):
        module = load_script("telegram-canary")
        for name, kind in (("../escape", tarfile.REGTYPE), ("link", tarfile.SYMTYPE),
                           ("device", tarfile.CHRTYPE)):
            member = tarfile.TarInfo(name)
            member.type = kind
            member.linkname = "elsewhere"
            with self.subTest(name=name), tempfile.TemporaryDirectory() as folder, \
                    patch.dict(os.environ, {"TELEGRAM_CANARY_SESSION_B64": self.archive(member)}):
                with self.assertRaises(SystemExit):
                    module.extract_session(Path(folder) / "session")
        member = tarfile.TarInfo("large")
        member.size = 4096
        encoded = self.archive(member)
        with tempfile.TemporaryDirectory() as folder, \
                patch.dict(os.environ, {"TELEGRAM_CANARY_SESSION_B64": encoded}), \
                patch.object(module, "MAX_SESSION_BYTES", len(base64.b64decode(encoded)) + 1):
            with self.assertRaisesRegex(SystemExit, "extraction budget"):
                module.extract_session(Path(folder) / "session")

    def test_absent_credentials_exit_with_documented_code(self):
        module = load_script("telegram-canary")
        with patch.dict(os.environ, {}, clear=True), \
                patch("sys.argv", ["telegram-canary", "--jar", "unused.jar"]), \
                contextlib.redirect_stderr(io.StringIO()):
            with self.assertRaises(SystemExit) as result:
                module.main()
        self.assertEqual(2, result.exception.code)
