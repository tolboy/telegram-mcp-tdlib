#!/usr/bin/env python3
"""Live get_me check. Exits 2 when canary credentials are absent.

The script never prints the API hash, the session archive, or the get_me
payload. A phone number can be inside that payload, so only the numeric id
is compared and the comparison result is printed.

Pack TELEGRAM_CANARY_SESSION_B64 as a gzip tar of the TDLib database
directory contents (the files that normally sit in TDLIB_DATA_DIR), not a
parent folder and not a link.
"""
import argparse
import asyncio
import base64
import binascii
import io
import os
from pathlib import Path
import sys
import tarfile
import tempfile

REQUIRED = (
    "TELEGRAM_CANARY_API_ID",
    "TELEGRAM_CANARY_API_HASH",
    "TELEGRAM_CANARY_USER_ID",
    "TELEGRAM_CANARY_SESSION_B64",
)


def missing():
    return [name for name in REQUIRED if not os.environ.get(name)]


def extract_session(destination: Path) -> None:
    raw = os.environ["TELEGRAM_CANARY_SESSION_B64"].encode("ascii")
    try:
        payload = base64.b64decode(raw, validate=True)
    except binascii.Error as error:
        raise SystemExit(f"TELEGRAM_CANARY_SESSION_B64 is not valid base64: {error}") from error
    if len(payload) > 200_000_000:
        raise SystemExit("Canary session archive is larger than 200 MB")
    with tarfile.open(fileobj=io.BytesIO(payload), mode="r:gz") as archive:
        destination.mkdir(parents=True, exist_ok=True)
        root = destination.resolve()
        for member in archive.getmembers():
            target = (destination / member.name).resolve()
            if target != root and root not in target.parents:
                raise SystemExit("Canary session archive contains a path outside the destination")
            if member.issym() or member.islnk():
                raise SystemExit("Canary session archive contains a link")
        archive.extractall(destination)


async def check(jar: Path, data_dir: Path, hash_file: Path) -> None:
    from mcp import ClientSession, StdioServerParameters
    from mcp.client.stdio import stdio_client

    env = {key: value for key, value in os.environ.items()
           if not key.startswith(("TELEGRAM_", "TDLIB_", "MCP_", "SPRING_", "SERVER_"))}
    env.update(
        TELEGRAM_MCP_DATA_DIR=str(data_dir),
        TDLIB_DATA_DIR=str(data_dir / "tdlib"),
        TDLIB_API_ID=os.environ["TELEGRAM_CANARY_API_ID"],
        TDLIB_API_HASH_FILE=str(hash_file),
        MCP_TOOL_PROFILE="reader",
        MCP_READ_ONLY="true",
        MCP_READ_ONLY_ALLOW_DOWNLOADS="false",
    )
    params = StdioServerParameters(
        command="java",
        args=["-jar", str(jar), "serve", "--transport", "stdio"],
        env=env,
        cwd=str(data_dir),
    )
    expected = os.environ["TELEGRAM_CANARY_USER_ID"].strip()
    async with stdio_client(params) as (read, write):
        async with ClientSession(read, write) as client:
            await client.initialize()
            result = await client.call_tool("get_me", {})
            if result.isError:
                raise SystemExit("Canary get_me failed")
            data = result.structuredContent.get("data") or {}
            actual = str(data.get("userId", ""))
            if actual != expected:
                raise SystemExit("Canary user id did not match TELEGRAM_CANARY_USER_ID")
    print("canary get_me matched the configured user id")


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--jar", required=True)
    args = parser.parse_args()
    absent = missing()
    if absent:
        raise SystemExit("Canary credentials are not set: " + ", ".join(absent))
    jar = Path(args.jar).resolve()
    if not jar.is_file():
        raise SystemExit(f"Jar was not found: {jar}")
    with tempfile.TemporaryDirectory(prefix="telegram-mcp-canary-") as temporary:
        root = Path(temporary)
        session = root / "tdlib"
        extract_session(session)
        hash_file = root / "api-hash"
        hash_file.write_text(os.environ["TELEGRAM_CANARY_API_HASH"], encoding="utf-8")
        try:
            asyncio.run(check(jar, root, hash_file))
        finally:
            hash_file.write_text("", encoding="utf-8")


if __name__ == "__main__":
    try:
        main()
    except KeyboardInterrupt:
        sys.exit(130)
