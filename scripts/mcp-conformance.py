#!/usr/bin/env python3
"""Run MCP conformance against a local daemon.

@modelcontextprotocol/conformance 0.1.16 cannot send an Authorization header.
This process listens on another loopback port, adds the daemon's generated
API key, and does not print that key. It is a manual check, not a pull-request
gate. By default it runs generic lifecycle, catalog and DNS-rebinding checks.
The upstream full suite requires fixture tools such as test_simple_text and
test resources/prompts that this production server intentionally does not ship.
Use repeated --scenario options to select other applicable upstream checks.
"""
import argparse
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import json
import os
from pathlib import Path
import shutil
import socket
import subprocess
import tempfile
import threading

DEFAULT_SCENARIOS = (
    "server-initialize", "ping", "tools-list", "prompts-list", "dns-rebinding-protection",
)


def free_port() -> int:
    with socket.socket() as probe:
        probe.bind(("127.0.0.1", 0))
        return probe.getsockname()[1]


def isolated_environment(root: Path) -> dict[str, str]:
    env = {key: value for key, value in os.environ.items()
           if not key.startswith(("TELEGRAM_", "TDLIB_", "MCP_", "SPRING_", "SERVER_", "AUTH_"))}
    env.update(
        TELEGRAM_MCP_DATA_DIR=str(root),
        TDLIB_DATA_DIR=str(root / "tdlib"),
        TDLIB_API_ID="0",
        TDLIB_API_HASH="",
        MCP_TOOL_PROFILE="reader",
        MCP_READ_ONLY="true",
    )
    return env


def start_daemon(jar: Path, root: Path, port: int, env: dict[str, str]) -> None:
    result = subprocess.run(
        ["java", "-jar", str(jar), "daemon", "start", "--port", str(port)],
        env=env,
        cwd=root,
        text=True,
        encoding="utf-8",
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
        timeout=70,
        check=False,
    )
    if result.returncode != 0 or not result.stdout.strip().startswith("READY"):
        log = root / "daemon" / "daemon.log"
        detail = log.read_text(encoding="utf-8", errors="replace")[-4000:] if log.exists() else result.stderr[-4000:]
        raise SystemExit("Isolated daemon did not become ready.\n" + detail)


class Proxy(BaseHTTPRequestHandler):
    target_port = 0
    api_key = ""

    def do_GET(self): self.forward()
    def do_POST(self): self.forward()
    def do_DELETE(self): self.forward()

    def forward(self):
        import http.client
        length = int(self.headers.get("Content-Length", "0") or "0")
        body = self.rfile.read(length) if length else None
        headers = {name: value for name, value in self.headers.items() if name.lower() != "host"}
        headers["Authorization"] = "Bearer " + self.api_key
        connection = http.client.HTTPConnection("127.0.0.1", self.target_port, timeout=120)
        try:
            connection.request(self.command, self.path, body=body, headers=headers)
            upstream = connection.getresponse()
            self.send_response(upstream.status)
            for name, value in upstream.getheaders():
                if name.lower() in {"transfer-encoding", "connection", "keep-alive"}:
                    continue
                self.send_header(name, value)
            self.end_headers()
            while True:
                # read() waits for the whole buffer on long-lived SSE streams.
                # read1() forwards each available event without waiting for EOF.
                chunk = upstream.read1(8192)
                if not chunk:
                    break
                self.wfile.write(chunk)
                self.wfile.flush()
        except (BrokenPipeError, ConnectionResetError):
            # Clients can abandon SSE streams; daemon shutdown also resets
            # outstanding GETs. Closing the response preserves transport failure
            # at the client without a spurious proxy traceback.
            pass
        finally:
            connection.close()

    def log_message(self, fmt, *args):
        return


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--jar", required=True)
    parser.add_argument("--spec-version", default="2025-11-25")
    parser.add_argument("--scenario", action="append", help="Applicable upstream scenario (repeatable)")
    parser.add_argument("--output-dir", type=Path,
                        default=Path(__file__).resolve().parent.parent / "build/reports/mcp-conformance")
    args = parser.parse_args()
    jar = Path(args.jar).resolve()
    if not jar.is_file():
        raise SystemExit(f"Jar was not found: {jar}")
    npx = shutil.which("npx")
    if npx is None:
        raise SystemExit("npx is required to run @modelcontextprotocol/conformance")
    root = Path(tempfile.mkdtemp(prefix="telegram-mcp-conformance-")).resolve()
    env = isolated_environment(root)
    daemon_port = free_port()
    proxy_port = free_port()
    server = None
    try:
        start_daemon(jar, root, daemon_port, env)
        key = (root / "daemon" / "api-key").read_text(encoding="utf-8").strip()
        if not key:
            raise SystemExit("Daemon did not write an API key")
        Proxy.target_port = daemon_port
        Proxy.api_key = key
        server = ThreadingHTTPServer(("127.0.0.1", proxy_port), Proxy)
        thread = threading.Thread(target=server.serve_forever, daemon=True)
        thread.start()
        failure = 0
        executions = []
        for scenario in args.scenario or DEFAULT_SCENARIOS:
            command = [
                npx, "--yes", "@modelcontextprotocol/conformance@0.1.16", "server",
                "--url", f"http://127.0.0.1:{proxy_port}/mcp", "--scenario", scenario,
                "--spec-version", args.spec_version,
                "--output-dir", str(args.output_dir.resolve() / scenario),
            ]
            completed = subprocess.run(command, cwd=root, env=env, check=False, timeout=120)
            executions.append({"scenario": scenario, "exit_code": completed.returncode})
            args.output_dir.mkdir(parents=True, exist_ok=True)
            (args.output_dir / "executions.json").write_text(json.dumps(executions, indent=2) + "\n", encoding="utf-8")
            if completed.returncode != 0 and failure == 0:
                failure = completed.returncode
        if failure:
            raise SystemExit(failure)
    finally:
        if server is not None:
            server.shutdown()
            server.server_close()
        # A cwd alone does not isolate daemon state: PlatformPaths uses the
        # environment or the user's global data directory. Always stop with the
        # exact environment used for startup, including after a startup failure.
        stopped = subprocess.run(
            ["java", "-jar", str(jar), "daemon", "stop"],
            env=env,
            cwd=root,
            stdout=subprocess.DEVNULL,
            stderr=subprocess.DEVNULL,
            check=False,
            timeout=30,
        )
        if stopped.returncode != 0:
            raise SystemExit(f"Isolated daemon did not stop; state retained at {root}")
        shutil.rmtree(root)


if __name__ == "__main__":
    main()
