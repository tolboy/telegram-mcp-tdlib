#!/usr/bin/env python3
"""Forward MCP tools from a running HTTP daemon to a local STDIO client.

Requires requirements-mcp-client.txt. Never starts Telegram or changes its login.
Only the tools capability is proxied; sampling, prompts and resources are not.
"""
import argparse
import asyncio
from datetime import timedelta
import os
import sys
from pathlib import Path
from urllib.parse import urlsplit

import httpx
from mcp import ClientSession, types
from mcp.client.streamable_http import streamablehttp_client
from mcp.server.lowlevel import Server
from mcp.server.stdio import stdio_server


def endpoint(value):
    parsed = urlsplit(value)
    if (parsed.scheme not in {"http", "https"} or not parsed.hostname
            or parsed.username is not None or parsed.password is not None
            or parsed.query or parsed.fragment):
        raise argparse.ArgumentTypeError("Use an HTTP(S) MCP URL without credentials, query or fragment")
    if parsed.scheme == "http" and parsed.hostname not in {"localhost", "127.0.0.1", "::1"}:
        raise argparse.ArgumentTypeError("Plain HTTP is allowed only on loopback; use HTTPS remotely")
    return value


def abort_transport(reason):
    print(f"MCP bridge: {reason}. Check daemon status and start it before reconnecting. "
          "Interrupted tool delivery may be unknown; no automatic retry was made.", file=sys.stderr, flush=True)
    # A failed SDK transport can leave its stdin worker blocked. This process
    # is disposable and owns no Telegram session; notify the host through EOF.
    os._exit(1)


class BridgeHttpClient(httpx.AsyncClient):
    async def send(self, request, *args, **kwargs):
        try:
            response = await super().send(request, *args, **kwargs)
        except httpx.HTTPError:
            if request.method == "POST":
                abort_transport("HTTP daemon connection failed")
            raise
        if request.method == "POST" and response.status_code >= 400:
            abort_transport(f"HTTP daemon returned status {response.status_code}")
        return response


def http_client(headers=None, timeout=None, auth=None):
    # Do not redirect authenticated traffic or route loopback through ambient proxies.
    return BridgeHttpClient(headers=headers, timeout=timeout, auth=auth,
                             follow_redirects=False, trust_env=False)


async def bridge(url, key, request_timeout=60):
    async with streamablehttp_client(
        url, headers={"Authorization": "Bearer " + key},
        timeout=30, sse_read_timeout=300, httpx_client_factory=http_client,
    ) as (up_read, up_write, _):
        async with ClientSession(up_read, up_write, read_timeout_seconds=timedelta(seconds=request_timeout)) as upstream:
            await upstream.initialize()
            tools, seen, cursor = [], set(), None
            for _ in range(100):
                page = await upstream.list_tools(cursor=cursor)
                tools.extend(page.tools)
                cursor = page.nextCursor
                if not cursor:
                    break
                if cursor in seen:
                    raise RuntimeError("Tool discovery cursor did not advance")
                seen.add(cursor)
            else:
                raise RuntimeError("Tool discovery exceeded 100 pages")
            if len({t.name for t in tools}) != len(tools):
                raise RuntimeError("Upstream advertised duplicate tool names")
            server = Server("telegram-mcp-http-tools-bridge", version="1.0.0")

            @server.list_tools()
            async def list_tools():
                return tools

            @server.call_tool(validate_input=False)
            async def call_tool(name, arguments):
                # Forward exactly once; preserve upstream errors and content metadata.
                # The daemon remains the authority for schema, policy and account checks.
                try:
                    # The SDK's read timeout starts after its request stream send.
                    # Bound the whole operation too, including a stalled send after
                    # the HTTP writer has failed.
                    return await asyncio.wait_for(upstream.call_tool(name, arguments), timeout=request_timeout)
                except Exception:
                    return types.CallToolResult(isError=True, content=[types.TextContent(
                        type="text", text="HTTP daemon request failed; delivery is unknown. No automatic retry was made.",
                    )])

            async with stdio_server() as (read, write):
                await server.run(read, write, server.create_initialization_options())


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--url", type=endpoint, required=True)
    parser.add_argument("--api-key-file", type=Path, required=True)
    parser.add_argument("--timeout-seconds", type=int, default=60, choices=range(1, 601), metavar="1..600")
    args = parser.parse_args()
    try:
        key = args.api_key_file.expanduser().resolve(strict=True).read_text(encoding="utf-8").strip()
        if not key or any(c.isspace() for c in key):
            raise ValueError("Invalid key file")
        asyncio.run(bridge(args.url, key, args.timeout_seconds))
    except KeyboardInterrupt:
        os._exit(130)
    except Exception:
        # Neither remote errors nor local exceptions may expose credentials on stderr.
        print("MCP bridge failed. Check the endpoint, private key file and daemon readiness.", file=sys.stderr, flush=True)
        # On fatal upstream loss the SDK's stdin reader can remain blocked in a
        # worker thread. Exit this disposable proxy so the host observes EOF;
        # it owns no Telegram session and never stops the upstream daemon.
        os._exit(1)
    return 0


if __name__ == "__main__":
    sys.exit(main())
