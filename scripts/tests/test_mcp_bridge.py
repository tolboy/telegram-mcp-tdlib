"""Deterministic HTTP corpus: no Telegram account, credentials or model required."""
import asyncio
from datetime import timedelta
import json
from pathlib import Path
import subprocess
import sys
import tempfile
import threading
import unittest
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

from mcp import ClientSession, StdioServerParameters
from mcp.client.stdio import stdio_client

BRIDGE = Path(__file__).resolve().parents[1] / "mcp-http-stdio-bridge.py"
KEY = "synthetic-local-test-key"
DATA = {"text": "Привет 日本語 👋", "id": 9007199254740993, "empty": [],
        "nullable": None, "truncated": True, "nextMessageId": "9007199254740992"}
CORPUS = {
    "dual": {"content": [{"type": "text", "text": json.dumps(DATA, ensure_ascii=False),
                           "annotations": {"audience": ["user"]}, "_meta": {"untrusted": True}}],
             "structuredContent": {"data": DATA, "meta": {"page": {"complete": None}}},
             "_meta": {"untrusted": True}, "isError": False},
    "text_only": {"content": [{"type": "text", "text": "Plain text\nsecond line"}], "isError": False},
    "empty": {"content": [], "isError": False},
    "error": {"content": [{"type": "text", "text": "Identifier must not be blank"}],
              "structuredContent": {"data": {"error": {"code": "INVALID_ARGUMENT", "retryable": False}}},
              "isError": True},
}


class Fixture(BaseHTTPRequestHandler):
    def log_message(self, *args):
        pass

    def reply(self, status, value=None):
        body = json.dumps(value).encode() if value is not None else b""
        self.send_response(status)
        self.send_header("Content-Type", "application/json")
        self.send_header("Mcp-Session-Id", "synthetic-session")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def do_GET(self):
        self.reply(405)

    def do_DELETE(self):
        self.reply(200)

    def do_POST(self):
        if self.headers.get("Authorization") != "Bearer " + KEY:
            self.reply(401)
            return
        request = json.loads(self.rfile.read(int(self.headers["Content-Length"])))
        method = request["method"]
        if "id" not in request:
            self.reply(202)
            return
        if method == "initialize":
            result = {"protocolVersion": "2025-06-18", "capabilities": {"tools": {}},
                      "serverInfo": {"name": "synthetic-corpus", "version": "1"}}
        elif method == "tools/list":
            page_two = request.get("params", {}).get("cursor") == "second"
            result = {"tools": [{"name": "other" if page_two else "fixture",
                                  "description": "Synthetic fixture", "inputSchema": {"type": "object"},
                                  "annotations": {"readOnlyHint": True}}]}
            if not page_two:
                result["nextCursor"] = "second"
        elif method == "tools/call":
            arguments = request["params"]["arguments"]
            self.server.calls.append(arguments)
            if arguments.get("variant") == "transport_error":
                self.reply(503)
                return
            result = CORPUS[arguments["variant"]]
        else:
            self.reply(400)
            return
        self.reply(200, {"jsonrpc": "2.0", "id": request["id"], "result": result})


class BridgeTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix="telegram-mcp-bridge-test-")
        self.root = Path(self.temp.name).resolve()
        assert self.root.is_relative_to(Path(tempfile.gettempdir()).resolve())
        self.key = self.root / "key"
        self.key.write_text(KEY)
        self.server = ThreadingHTTPServer(("127.0.0.1", 0), Fixture)
        self.server.calls = []
        self.thread = threading.Thread(target=self.server.serve_forever, daemon=True)
        self.thread.start()
        self.url = f"http://127.0.0.1:{self.server.server_port}/mcp"

    def tearDown(self):
        self.server.shutdown()
        self.server.server_close()
        self.thread.join(3)
        self.temp.cleanup()

    def test_preserves_corpus_schemas_pagination_and_errors(self):
        async def scenario():
            params = StdioServerParameters(command=sys.executable, args=[str(BRIDGE), "--url", self.url, "--api-key-file", str(self.key), "--timeout-seconds", "2"])
            with (self.root / "stderr").open("w") as log:
                async with stdio_client(params, errlog=log) as (read, write):
                    async with ClientSession(read, write, read_timeout_seconds=timedelta(seconds=10)) as client:
                        initialized = await client.initialize()
                        self.assertIsNone(initialized.capabilities.prompts)
                        self.assertIsNone(initialized.capabilities.resources)
                        tools = await client.list_tools()
                        self.assertEqual(["fixture", "other"], [t.name for t in tools.tools])
                        for variant, expected in CORPUS.items():
                            result = await client.call_tool("fixture", {"variant": variant, "chat_id": ""})
                            self.assertEqual(expected, result.model_dump(mode="json", by_alias=True, exclude_none=True))
            self.assertTrue(self.thread.is_alive(), "Closing STDIO must not shut down upstream")
            self.assertEqual(["dual", "text_only", "empty", "error"],
                             [c["variant"] for c in self.server.calls])
        asyncio.run(scenario())

    def test_http_transport_failure_closes_or_errors_without_retry(self):
        async def scenario():
            process = await asyncio.create_subprocess_exec(
                sys.executable, str(BRIDGE), "--url", self.url, "--api-key-file", str(self.key),
                "--timeout-seconds", "2", stdin=asyncio.subprocess.PIPE,
                stdout=asyncio.subprocess.PIPE, stderr=asyncio.subprocess.PIPE,
            )
            async def send(value):
                process.stdin.write((json.dumps(value)+"\n").encode())
                await process.stdin.drain()
            try:
                await send({"jsonrpc": "2.0", "id": 1, "method": "initialize", "params": {
                    "protocolVersion": "2025-06-18", "capabilities": {},
                    "clientInfo": {"name": "failure-fixture", "version": "1"}}})
                initialized = json.loads(await asyncio.wait_for(process.stdout.readline(), 10))
                self.assertIn("result", initialized)
                await send({"jsonrpc": "2.0", "method": "notifications/initialized"})
                await send({"jsonrpc": "2.0", "id": 2, "method": "tools/call", "params": {
                    "name": "fixture", "arguments": {"variant": "transport_error"}}})
                response = await asyncio.wait_for(process.stdout.readline(), 8)
                if response:
                    self.assertTrue(json.loads(response)["result"]["isError"])
                    process.stdin.close()
                else:
                    self.assertNotEqual(0, await asyncio.wait_for(process.wait(), 5))
                self.assertEqual([{"variant": "transport_error"}], self.server.calls)
            finally:
                if process.returncode is None:
                    process.stdin.close()
                    try:
                        await asyncio.wait_for(process.wait(), 5)
                    except TimeoutError:
                        process.kill()
                        await process.wait()
        asyncio.run(scenario())

    def test_bad_key_fails_without_leaking_key(self):
        self.key.write_text("synthetic-wrong-secret")
        result = subprocess.run([sys.executable, str(BRIDGE), "--url", self.url,
                                 "--api-key-file", str(self.key)], input="", capture_output=True, text=True, timeout=20)
        self.assertNotEqual(0, result.returncode)
        self.assertEqual("", result.stdout)
        self.assertNotIn("synthetic-wrong-secret", result.stderr)
        self.assertIn("MCP bridge", result.stderr)

    def test_rejects_remote_cleartext_and_embedded_credentials(self):
        for url in ["http://example.com/mcp", "https://secret@example.com/mcp", "https://example.com/mcp?token=secret"]:
            result = subprocess.run([sys.executable, str(BRIDGE), "--url", url,
                                     "--api-key-file", str(self.key)], capture_output=True, text=True, timeout=20)
            self.assertEqual(2, result.returncode)
            self.assertEqual("", result.stdout)


if __name__ == "__main__":
    unittest.main()
