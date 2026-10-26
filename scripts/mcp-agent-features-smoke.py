#!/usr/bin/env python3
"""Opt-in live regression checks against an authenticated MCP HTTP server.

Read-only by default. --allow-send posts one UUID-tagged test message to the
explicit chat, replays its receipt, and checks malformed HTML is rejected.
Use only a chat where you are authorized to send. Keys and message bodies are
never printed. Install requirements-mcp-client.txt first.
"""
import argparse
import asyncio
import json
from pathlib import Path
import re
import sys
from uuid import uuid4

class SmokeFailure(Exception):
    pass


def require(condition, message):
    if not condition:
        raise SmokeFailure(message)


def telegram_id(value):
    try:
        parsed = int(value)
    except ValueError as error:
        raise argparse.ArgumentTypeError("Expected a signed Telegram integer ID") from error
    if parsed == 0 or not -(2**63) <= parsed < 2**63:
        raise argparse.ArgumentTypeError("Expected a nonzero signed 64-bit Telegram ID")
    return parsed


async def check(client, args):
    import jsonschema

    await client.initialize()
    tools, seen, cursor = {}, set(), None
    while True:
        page = await client.list_tools(cursor=cursor)
        for tool in page.tools:
            require(tool.name not in tools, "Duplicate discovered tool")
            tools[tool.name] = tool
        cursor = page.nextCursor
        if not cursor:
            break
        require(cursor not in seen and len(seen) < 100, "Discovery cursor stalled")
        seen.add(cursor)

    async def call(name, arguments, expected_error=None):
        require(name in tools, "Required tool unavailable: " + name)
        result = await client.call_tool(name, {"account": args.account, **arguments})
        jsonschema.validate(result.structuredContent, tools[name].outputSchema)
        data = result.structuredContent["data"]
        require(result.structuredContent["meta"]["untrustedTelegramContent"] is True, "Untrusted marker missing")
        if expected_error:
            require(result.isError is True and data.get("error", {}).get("code") == expected_error,
                    name + " returned unexpected error classification")
        else:
            code = data.get("error", {}).get("code", "UNKNOWN") if isinstance(data, dict) else "UNKNOWN"
            code = code if re.fullmatch(r"[A-Z_]{1,80}", str(code)) else "UNKNOWN"
            require(result.isError is False, name + " failed with " + code)
        return data

    chat = {"chat_id": args.chat_id}
    if args.expected_user_id is not None:
        identity = await call("get_me", {})
        require(identity.get("userId") == args.expected_user_id, "Authenticated Telegram user differs from expected account")
    history = await call("get_history", {**chat, "limit": 5})
    require("contentType" in json.dumps(tools["get_history"].outputSchema), "History schema lacks new metadata")

    def metadata(rows):
        require(all(row.get("chatId") == args.chat_id and isinstance(row.get("isOutgoing"), bool)
                    and isinstance(row.get("contentType"), str) for row in rows), "Message metadata missing or wrong chat")

    metadata(history)
    baseline = await call("wait_for_changes", {**chat, "timeout_seconds": 0})
    require(not baseline["gap"], "Coverage gap: reconcile chat history before retrying")
    polled = await call("wait_for_changes", {**chat, "cursor": baseline["nextCursor"], "timeout_seconds": 1})
    require(not polled["gap"], "Coverage gap during polling: reconcile chat history")
    report = {"status": "PASS", "tools": len(tools), "history_metadata": "PASS",
              "wait_poll": "PASS", "wait_timed_out": polled["timedOut"], "send_enabled": args.allow_send}
    if args.allow_send:
        token = "mcp-agent-features-smoke:" + uuid4().hex
        message_text = "Telegram MCP live test: message events and idempotent delivery. " + token
        outgoing = {**chat, "text": message_text, "parse_mode": "plain", "idempotency_key": token}
        pending = asyncio.create_task(call("wait_for_changes", {**chat, "cursor": polled["nextCursor"], "timeout_seconds": 5}))
        try:
            sent = await call("send_message", outgoing)
            require(sent.get("status") == "SENT" and sent.get("message_id", 0) > 0, "Send outcome is unconfirmed; inspect receipt before retrying")
            message_id = sent["message_id"]
            for attempt in range(6):
                changes = await pending
                require(not changes["gap"], "Coverage gap while awaiting delivery event: reconcile chat history")
                if any(item["kind"] == "new" and item["chatId"] == args.chat_id and item["messageId"] == message_id
                       for item in changes["entries"]):
                    break
                require(attempt < 5, "Final sent message ID absent from observed events")
                pending = asyncio.create_task(call("wait_for_changes", {**chat, "cursor": changes["nextCursor"], "timeout_seconds": 5}))
            replay = await call("send_message", outgoing)
            require(replay.get("replayed") is True and replay.get("message_id") == message_id, "Keyed replay did not reuse final message ID")
            rows = await call("get_messages", {**chat, "message_id": message_id, "context_size": 5})
            metadata(rows)
            matches = [row for row in rows if row.get("text") == message_text]
            require(len(matches) == 1 and matches[0]["messageId"] == message_id, "Sent test message missing or duplicated")
            await call("send_message", {**chat, "text": "<b>" + token, "parse_mode": "html"}, "INVALID_INPUT")
            report.update(delivery_event="PASS", keyed_replay="PASS", duplicate_check="PASS", formatting_rejection="PASS", sent_message_id=message_id)
        finally:
            if not pending.done():
                pending.cancel()
                await asyncio.gather(pending, return_exceptions=True)
    if args.voice_message_id is not None:
        transcript = await call("transcribe_voice_note", {**chat, "message_id": args.voice_message_id})
        require(transcript["status"] in {"COMPLETED", "PENDING"}, "Unknown transcription status")
        report["transcription"] = {"status": transcript["status"], "characters": len(transcript.get("text") or transcript.get("partialText") or "")}
    return report


async def run(args):
    from mcp import ClientSession
    from mcp.client.streamable_http import streamablehttp_client

    key = Path(args.api_key_file).read_text(encoding="utf-8").strip()
    require(bool(key), "API key file is empty")
    async with streamablehttp_client(args.url, headers={"Authorization": "Bearer " + key}) as (read, write, _):
        async with ClientSession(read, write) as client:
            return await check(client, args)


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--url", required=True)
    parser.add_argument("--api-key-file", required=True)
    parser.add_argument("--account", required=True)
    parser.add_argument("--chat-id", required=True, type=telegram_id)
    parser.add_argument("--allow-send", action="store_true", help="Explicitly authorize one test send and a malformed-HTML rejection check")
    parser.add_argument("--expected-user-id", type=telegram_id, help="Refuse tests if get_me returns a different account")
    parser.add_argument("--voice-message-id", type=telegram_id, help="Opt in to quota-consuming transcription of this existing message")
    args = parser.parse_args()
    if args.voice_message_id is not None and args.voice_message_id < 0:
        parser.error("--voice-message-id must be positive")
    try:
        print(json.dumps(asyncio.run(run(args)), ensure_ascii=True))
    except Exception as error:
        print(json.dumps({"status": "FAIL", "error_type": type(error).__name__,
                          "check": str(error) if isinstance(error, SmokeFailure) else "Transport or schema check failed; no payload printed"}))
        sys.exit(1)
