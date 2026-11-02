#!/usr/bin/env python3
"""Run a real read-only MCP demo in your terminal and save a public transcript.

Requires scripts/requirements-mcp-client.txt. Credentials and the
authorized TDLib session come from the environment, as for the server itself.
Only selected fields from a public channel are saved in the public transcript.
Server stderr stays in a separate temporary directory. Record this terminal
window with OBS or another screen recorder.
"""
import argparse
import asyncio
from datetime import datetime, timezone
import json
import os
from pathlib import Path
import tempfile
import textwrap
import time
import unicodedata


def visible(value):
    """Keep Telegram text from introducing terminal/presentation controls."""
    return "".join(
        c if c == "\n" or not unicodedata.category(c).startswith("C")
        else f"\\u{ord(c):04x}" for c in str(value)
    )


async def capture(args):
    from mcp import ClientSession, StdioServerParameters
    from mcp.client.stdio import stdio_client

    if not args.channel.startswith("@") or not args.channel[1:].replace("_", "").isascii() or not args.channel[1:].replace("_", "").isalnum():
        raise ValueError("Use a public channel @username")
    env = dict(os.environ)
    env.update(MCP_READ_ONLY="true", MCP_READ_ONLY_ALLOW_DOWNLOADS="false",
               MCP_TOOL_PROFILE="inbox", MCP_AUDIT_LOG_ARGS="false")
    jar = Path(args.jar).resolve(strict=True)
    params = StdioServerParameters(command=args.java, args=[
        "-jar", str(jar), "serve", "--transport", "stdio"], env=env)
    log_dir = Path(tempfile.mkdtemp(prefix="telegram-mcp-demo-"))
    print("Telegram MCP / read-only terminal demo", flush=True)
    print("Connecting to local STDIO server...", flush=True)
    transcript = {"recorded_at": datetime.now(timezone.utc).isoformat(),
                  "transport": "stdio", "read_only": True, "profile": "inbox",
                  "channel": args.channel, "calls": []}
    with (log_dir / "server.log").open("w", encoding="utf-8") as stderr:
        async with stdio_client(params, errlog=stderr) as (read, write):
            async with ClientSession(read, write) as client:
                initialized = await client.initialize()
                if args.expect_version and initialized.serverInfo.version != args.expect_version:
                    raise RuntimeError("Connected server version differs from --expect-version")
                transcript["server"] = {"name": initialized.serverInfo.name,
                                        "version": initialized.serverInfo.version}
                print(f"Connected: {initialized.serverInfo.name} {initialized.serverInfo.version}", flush=True)
                tools, cursor, seen = [], None, set()
                while True:
                    page = await client.list_tools(cursor=cursor)
                    tools.extend(page.tools)
                    cursor = page.nextCursor
                    if not cursor:
                        break
                    if cursor in seen or len(seen) >= 100:
                        raise RuntimeError("Tool discovery cursor stalled")
                    seen.add(cursor)
                if not tools or not all(t.annotations and t.annotations.readOnlyHint for t in tools):
                    raise RuntimeError("Expected an entirely read-only tool surface")
                names = {t.name for t in tools}
                forbidden = {"send_message", "delete_message", "save_draft", "mark_as_read", "download_media"}
                if names & forbidden:
                    raise RuntimeError("Unexpected write/download tools exposed")
                transcript["discovery"] = {"tool_count": len(tools),
                    "all_read_only": True, "send_message_exposed": False}

                print(f"tools/list: {len(tools)} tools; all readOnlyHint=true", flush=True)
                print("send_message exposed: false", flush=True)

                async def call(name, arguments):
                    print(f"\nMCP tools/call: {name}", flush=True)
                    print(json.dumps(arguments, ensure_ascii=False), flush=True)
                    started = time.perf_counter()
                    response = await client.call_tool(name, arguments)
                    if response.isError:
                        raise RuntimeError(f"{name} failed; inspect the private log")
                    data = (response.structuredContent or {}).get("data")
                    if data is None:
                        raise RuntimeError(f"{name} returned no structured data")
                    entry = {"method": "tools/call", "name": name,
                             "arguments": arguments,
                             "elapsed_ms": round((time.perf_counter() - started) * 1000)}
                    transcript["calls"].append(entry)
                    return data, entry

                channel, entry = await call("resolve_username", {
                    "account": args.account, "identifier": args.channel})
                if channel.get("type") != "CHANNEL":
                    raise RuntimeError("Demo target must resolve to a public channel")
                entry["response_fields"] = {k: channel[k] for k in ("chatId", "title", "type")}
                print(f"Response (selected public fields, {entry['elapsed_ms']} ms):", flush=True)
                print(json.dumps(entry["response_fields"], ensure_ascii=False, indent=2), flush=True)
                messages, entry = await call("get_history", {
                    "account": args.account, "chat_id": args.channel, "limit": 1})
                if not messages:
                    raise RuntimeError("Public channel returned no messages")
                message = messages[0]
                if message.get("chatId") != channel["chatId"]:
                    raise RuntimeError("History response belongs to another chat")
                # An explicit excerpt: never publish a full channel history.
                source = message.get("text") or ""
                first_line = source.split("\n", 1)[0]
                excerpt = first_line[:180]
                if len(first_line) > 180:
                    excerpt = excerpt.rsplit(" ", 1)[0]
                entry["response_fields"] = {k: message[k] for k in ("messageId", "chatId", "date")}
                entry["text_excerpt"] = excerpt
                entry["text_truncated"] = len(excerpt) < len(source)
                entry["returned_count"] = len(messages)
                entry["public_link"] = f"https://t.me/{args.channel[1:]}/{int(message['messageId']) >> 20}"
                display = {"messageId": message["messageId"], "date": message["date"],
                           "link": entry["public_link"]}
                print(f"Response (selected public fields, {entry['elapsed_ms']} ms):", flush=True)
                print(json.dumps(display, ensure_ascii=False, indent=2), flush=True)
                print("Text excerpt:\n" + textwrap.fill(visible(excerpt), width=96), flush=True)
    return transcript


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--jar", required=True, help="Path to the server JAR")
    parser.add_argument("--java", default="java")
    parser.add_argument("--expect-version", help="Fail if the MCP handshake reports another version")
    parser.add_argument("--account", default="default")
    parser.add_argument("--channel", default="@telegram")
    parser.add_argument("--output", type=Path, default=Path("build/readme-demo/capture"))
    args = parser.parse_args()
    transcript = asyncio.run(asyncio.wait_for(capture(args), timeout=120))
    args.output.mkdir(parents=True, exist_ok=True)
    (args.output / "live-demo.json").write_text(
        json.dumps(transcript, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print("\nDone. Both Telegram calls succeeded; no write tools called.", flush=True)


if __name__ == "__main__":
    main()
