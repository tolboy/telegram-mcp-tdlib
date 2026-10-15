#!/usr/bin/env python3
"""Validate public config examples and build a credential-free Agensi ZIP."""

import argparse
import hashlib
import json
from pathlib import Path
import tomllib
from zipfile import ZIP_DEFLATED, ZipFile, ZipInfo


REPO = Path(__file__).resolve().parent.parent
SKILL = REPO / "skills" / "telegram-inbox-assistant"
# Explicit inclusion prevents local session files, credentials and test captures
# from entering a marketplace submission when new files appear in the folder.
FILES = (
    "LICENSE",
    "SETUP.md",
    "SKILL.md",
    "templates/claude-code.json",
    "templates/claude-desktop-bridge.json",
    "templates/codex.toml",
    "templates/cursor.json",
    "templates/vscode.json",
)
EXAMPLE_ENV = {
    "TDLIB_API_ID": "123456",
    "TDLIB_API_HASH_FILE": "/absolute/path/to/telegram-api-hash.txt",
    "MCP_TOOL_PROFILE": "inbox",
    "MCP_READ_ONLY": "true",
    "MCP_READ_ONLY_ALLOW_DOWNLOADS": "false",
}


def validate_templates():
    bridge = json.loads((SKILL / "templates/claude-desktop-bridge.json").read_text(encoding="utf-8"))["mcpServers"]["telegram"]
    if bridge != {
        "command": "/absolute/path/to/bridge-venv/bin/python",
        "args": ["/absolute/path/to/telegram-mcp/support/mcp-http-stdio-bridge.py", "--url", "http://127.0.0.1:18080/mcp",
                 "--api-key-file", "/absolute/path/to/private/claude-mcp-key.txt"],
    }:
        raise ValueError("Bridge template must use public paths and a key file")
    entries = []
    for filename in ("claude-code.json", "cursor.json", "vscode.json"):
        config = json.loads((SKILL / "templates" / filename).read_text(encoding="utf-8"))
        root = "servers" if filename == "vscode.json" else "mcpServers"
        entry = config[root]["telegram"]
        if filename != "cursor.json" and entry.get("type") != "stdio":
            raise ValueError(f"{filename}: explicit STDIO type is missing")
        entries.append(entry)
    config = tomllib.loads((SKILL / "templates/codex.toml").read_text(encoding="utf-8"))
    entries.append(config["mcp_servers"]["telegram"])
    for entry in entries:
        if entry["command"] != "telegram-mcp" or entry["args"] != ["serve", "--transport", "stdio"]:
            raise ValueError("Unexpected launch command in public template")
        if entry["env"] != EXAMPLE_ENV:
            raise ValueError("Templates must use the read-only policy and public credential placeholders")


def package(output):
    validate_templates()
    source = SKILL / "SKILL.md"
    if not source.read_text(encoding="utf-8").startswith("---\nname: telegram-inbox-assistant\n"):
        raise ValueError("SKILL.md must contain the expected root frontmatter")
    if (SKILL / "LICENSE").read_bytes().replace(b"\r\n", b"\n") != (REPO / "LICENSE").read_bytes().replace(b"\r\n", b"\n"):
        raise ValueError("Skill license differs from the repository license")
    payloads = {}
    for name in FILES:
        path = SKILL / name
        if path.is_symlink() or not path.resolve().is_relative_to(SKILL.resolve()):
            raise ValueError(f"Package source escapes the skill folder: {name}")
        # All included resources are UTF-8 text. Git may check them out as
        # CRLF on Windows; normalize text for identical extracted contents.
        # DEFLATE bytes can still vary between zlib implementations; the release
        # archive and its sidecar define the canonical downloadable artifact.
        payloads[name] = path.read_bytes().replace(b"\r\n", b"\n")
    output = output.resolve()
    if output.is_relative_to(SKILL.resolve()):
        raise ValueError("Write the ZIP outside the installable skill directory")
    output.parent.mkdir(parents=True, exist_ok=True)
    with ZipFile(output, "w", compression=ZIP_DEFLATED) as archive:
        for name, data in payloads.items():
            info = ZipInfo(name, date_time=(2026, 10, 15, 0, 0, 0))
            info.create_system = 3  # Stable Unix metadata on Windows and CI alike.
            info.compress_type = ZIP_DEFLATED
            info.external_attr = 0o100644 << 16
            archive.writestr(info, data)
    with ZipFile(output) as archive:
        if tuple(archive.namelist()) != FILES or archive.testzip() is not None:
            raise ValueError("ZIP verification failed")
        for name, data in payloads.items():
            if archive.read(name) != data:
                raise ValueError(f"ZIP content mismatch: {name}")
    digest = hashlib.sha256(output.read_bytes()).hexdigest()
    output.with_suffix(".zip.sha256").write_text(f"{digest}  {output.name}\n", encoding="ascii")
    print(json.dumps({"zip": str(output), "files": len(FILES), "sha256": digest}))


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", type=Path, default=REPO / "build/skills/telegram-inbox-assistant.zip")
    package(parser.parse_args().output)
