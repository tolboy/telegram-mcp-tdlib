#!/usr/bin/env python3
"""Independent Python SDK success/error checks on an isolated, unauthenticated runtime.

No Telegram credentials are used. Evidence is retained in a new temp directory.
Install requirements-mcp-client.txt first. This is not a native AI-host test.
"""
import argparse
import asyncio
import json
import os
from pathlib import Path
import socket
import subprocess
import sys
import tempfile

import jsonschema
from mcp import ClientSession, StdioServerParameters
from mcp.client.stdio import stdio_client
from mcp.client.streamable_http import streamablehttp_client


def require(condition, message):
    if not condition:
        raise RuntimeError(message)


async def check(read, write, version):
    async with ClientSession(read, write) as client:
        await client.initialize()
        tools, seen, cursor = [], set(), None
        while True:
            page = await client.list_tools(cursor=cursor)
            tools.extend(page.tools)
            cursor = page.nextCursor
            if not cursor:
                break
            require(cursor not in seen and len(seen) < 100, "Discovery cursor stalled")
            seen.add(cursor)
        names = {t.name for t in tools}
        require(not names.intersection({"send_message", "save_draft", "clear_draft", "mark_as_read",
                                       "delete_message", "send_reaction", "download_media"}), "Write/download tool exposed")
        require(all(t.annotations and t.annotations.readOnlyHint for t in tools), "Missing read-only hint")
        success = await client.call_tool("_manifest", {})
        require(success.isError is False, "Manifest failed")
        data = success.structuredContent["data"]
        require({t["name"] for t in data["tools"]} == names, "Manifest and tools/list differ")
        require(data["toolCount"] == len(tools), "Wrong manifest tool count")
        if version:
            require(data["serverVersion"] == version, "Unexpected server build version")
        require(json.loads(success.content[0].text) == data, "Text and structured success disagree")
        error = await client.call_tool("get_me", {"account": "default"})
        require(error.isError is True, "Unauthenticated account read did not fail")
        error_data = error.structuredContent["data"]["error"]
        require(error_data["code"] in {"AUTH_REQUIRED", "TELEGRAM_UNAVAILABLE"}, "Unexpected offline error code")
        require(bool(error.content[0].text.strip()), "Error text missing")
        for name, result in [("_manifest", success), ("get_me", error)]:
            tool = next(t for t in tools if t.name == name)
            jsonschema.validate(result.structuredContent, tool.outputSchema)
            require(result.structuredContent["meta"]["untrustedTelegramContent"] is True, "Untrusted marker lost")
            require(result.meta["io.github.tolboy/untrusted-content"] is True, "Result metadata lost")
            for block in result.content:
                annotations = block.model_dump(mode="json", exclude_none=True).get("annotations", {})
                require("priority" not in annotations, "Optional priority regression")
                require(annotations.get("audience") == ["user"], "Audience lost")
        return {"tools": len(tools), "server_version": data["serverVersion"],
                "success": "PASS", "structured_error": error_data["code"],
                "output_schemas": "PASS", "annotations": "PASS"}


async def run(args):
    root = Path(tempfile.mkdtemp(prefix="telegram-mcp-result-smoke-")).resolve()
    env = {k: v for k, v in os.environ.items()
           if not k.startswith(("TELEGRAM_", "TDLIB_", "MCP_", "SPRING_", "SERVER_", "AUTH_"))}
    env.update(TELEGRAM_MCP_DATA_DIR=str(root), TDLIB_DATA_DIR=str(root / "tdlib"),
               TDLIB_API_ID="0", TDLIB_API_HASH="", MCP_TOOL_PROFILE="inbox",
               MCP_READ_ONLY="true", MCP_READ_ONLY_ALLOW_DOWNLOADS="false")
    command = [str(Path(args.executable).resolve())] if args.executable else ["java", "-jar", str(Path(args.jar).resolve())]
    report = {"method": "independent_python_sdk", "telegram_credentials_used": False,
              "evidence_directory": str(root), "cases": {}}
    if args.transport in {"all", "stdio"}:
        with (root / "stdio.stderr.log").open("w", encoding="utf-8") as stderr:
            params = StdioServerParameters(command=command[0], args=command[1:]+["serve", "--transport", "stdio"], env=env, cwd=str(root))
            async with stdio_client(params, errlog=stderr) as (read, write):
                report["cases"]["stdio"] = await check(read, write, args.expect_version)
    if args.transport in {"all", "http", "bridge"}:
        with socket.socket() as available:
            available.bind(("127.0.0.1", 0))
            port = available.getsockname()[1]

        def daemon(*options):
            result = subprocess.run(command + ["daemon", *options], env=env, cwd=root,
                                    stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True,
                                    encoding="utf-8", timeout=65)
            if result.returncode != 0:
                (root / "daemon-command.stderr.log").write_text(result.stderr, encoding="utf-8")
            require(result.returncode == 0, f"Isolated daemon command failed; inspect {root}")
            return result.stdout.strip()

        try:
            require(daemon("start", "--port", str(port)).startswith("READY"), "Isolated daemon not ready")
            url = f"http://127.0.0.1:{port}/mcp"
            key_file = root / "daemon" / "api-key"
            key = key_file.read_text().strip()
            if args.transport in {"all", "http"}:
                async with streamablehttp_client(url, headers={"Authorization": "Bearer " + key}) as (read, write, _):
                    report["cases"]["http"] = await check(read, write, args.expect_version)
            if args.transport in {"all", "bridge"}:
                with (root / "bridge.stderr.log").open("w", encoding="utf-8") as stderr:
                    params = StdioServerParameters(command=sys.executable, args=[str(Path(__file__).with_name("mcp-http-stdio-bridge.py")), "--url", url, "--api-key-file", str(key_file)], cwd=str(root))
                    async with stdio_client(params, errlog=stderr) as (read, write):
                        report["cases"]["bridge"] = await check(read, write, args.expect_version)
            require(daemon("status").startswith("READY"), "Closing clients stopped the shared daemon")
            daemon("stop")
            require(daemon("start", "--port", str(port)).startswith("READY"), "Isolated daemon restart failed")
            report["daemon_restart"] = "PASS (no authorized Telegram session)"
        finally:
            daemon("stop")
    (root / "report.json").write_text(json.dumps(report, indent=2)+"\n", encoding="utf-8")
    print(json.dumps(report, indent=2))


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    source = parser.add_mutually_exclusive_group(required=True)
    source.add_argument("--jar")
    source.add_argument("--executable")
    parser.add_argument("--transport", choices=["all", "stdio", "http", "bridge"], default="all")
    parser.add_argument("--expect-version")
    asyncio.run(run(parser.parse_args()))
