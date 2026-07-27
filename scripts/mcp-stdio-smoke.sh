#!/usr/bin/env bash
set -euo pipefail

usage() {
  cat >&2 <<'USAGE'
Usage:
  mcp-stdio-smoke.sh --jar PATH [--require TOOL[,TOOL...]] [--forbid TOOL[,TOOL...]]
  mcp-stdio-smoke.sh --executable PATH [--require TOOL[,TOOL...]] [--forbid TOOL[,TOOL...]]

Runs initialize and tools/list without calling Telegram. --require and
--forbid may be repeated. Requires Python 3; --jar also requires Java.
USAGE
}

require_command() {
  if ! command -v "$1" >/dev/null 2>&1; then
    printf 'Required command is not available: %s\n' "$1" >&2
    exit 2
  fi
}

append_csv() {
  local destination_name="$1"
  local value="$2"
  local item
  local -a parsed=()
  IFS=',' read -r -a parsed <<<"$value"
  for item in "${parsed[@]}"; do
    item="${item#"${item%%[![:space:]]*}"}"
    item="${item%"${item##*[![:space:]]}"}"
    if [[ -n "$item" ]]; then
      case "$destination_name" in
        required_tools) required_tools+=("$item") ;;
        forbidden_tools) forbidden_tools+=("$item") ;;
        *) printf 'Internal list name is invalid: %s\n' "$destination_name" >&2; exit 2 ;;
      esac
    fi
  done
}

jar=""
executable=""
declare -a required_tools=()
declare -a forbidden_tools=()

while (($# > 0)); do
  case "$1" in
    --jar)
      [[ $# -ge 2 ]] || { usage; exit 2; }
      jar="$2"
      shift 2
      ;;
    --executable)
      [[ $# -ge 2 ]] || { usage; exit 2; }
      executable="$2"
      shift 2
      ;;
    --require)
      [[ $# -ge 2 ]] || { usage; exit 2; }
      append_csv required_tools "$2"
      shift 2
      ;;
    --forbid)
      [[ $# -ge 2 ]] || { usage; exit 2; }
      append_csv forbidden_tools "$2"
      shift 2
      ;;
    -h|--help)
      usage
      exit 0
      ;;
    *)
      printf 'Unknown argument: %s\n' "$1" >&2
      usage
      exit 2
      ;;
  esac
done

if [[ -n "$jar" && -n "$executable" ]] || [[ -z "$jar" && -z "$executable" ]]; then
  printf 'Specify exactly one of --jar or --executable.\n' >&2
  usage
  exit 2
fi

require_command python3
if [[ -n "$jar" ]]; then
  require_command java
fi

resolve_path() {
  local path="$1"
  local directory
  local basename
  directory="$(dirname -- "$path")"
  basename="$(basename -- "$path")"
  [[ -d "$directory" && -e "$path" ]] || {
    printf 'Path does not exist: %s\n' "$path" >&2
    exit 2
  }
  printf '%s/%s\n' "$(cd -- "$directory" && pwd -P)" "$basename"
}

mode=""
target=""
if [[ -n "$jar" ]]; then
  mode="jar"
  target="$(resolve_path "$jar")"
else
  mode="executable"
  target="$(resolve_path "$executable")"
fi
if command -v cygpath >/dev/null 2>&1; then
  target="$(cygpath -w "$target")"
fi

python3 - \
  "$mode" \
  "$target" \
  "${required_tools[@]}" \
  --forbidden \
  "${forbidden_tools[@]}" <<'PY'
import json
import os
import queue
import subprocess
import sys
import threading
import time

mode = sys.argv[1]
target = sys.argv[2]
arguments = sys.argv[3:]
separator = arguments.index("--forbidden")
required = arguments[:separator]
forbidden = arguments[separator + 1:]

command = (
    ["java", "-jar", target]
    if mode == "jar"
    else [target]
)
command.extend(["serve", "--transport", "stdio"])

environment = os.environ.copy()
for key in list(environment):
    if key.startswith("TELEGRAM_ACCOUNTS_") or key.startswith("TDLIB_"):
        environment.pop(key)
environment.update({
    "MCP_READ_ONLY": "true",
    "MCP_TOOL_PROFILE": "reader",
    "TDLIB_API_ID": "0",
    "TDLIB_API_HASH": "",
})

process = subprocess.Popen(
    command,
    stdin=subprocess.PIPE,
    stdout=subprocess.PIPE,
    stderr=subprocess.PIPE,
    text=True,
    encoding="utf-8",
    errors="replace",
    bufsize=1,
    env=environment,
)
assert process.stdin is not None
assert process.stdout is not None
assert process.stderr is not None

stderr_lines = []
stdout_queue = queue.Queue()


def drain_stderr():
    stderr_lines.extend(process.stderr)


def drain_stdout():
    for line in process.stdout:
        stdout_queue.put(line)
    stdout_queue.put(None)


stderr_thread = threading.Thread(target=drain_stderr, daemon=True)
stderr_thread.start()
stdout_thread = threading.Thread(target=drain_stdout, daemon=True)
stdout_thread.start()


def stderr_text():
    return "".join(stderr_lines).replace("\n", " ").strip()


def send(message):
    process.stdin.write(json.dumps(message, separators=(",", ":")) + "\n")
    process.stdin.flush()


def read_response(expected_id, timeout_seconds=30):
    deadline = time.monotonic() + timeout_seconds
    while time.monotonic() < deadline:
        if process.poll() is not None:
            raise RuntimeError(
                f"STDIO server exited with code {process.returncode}: {stderr_text()}"
            )
        try:
            line = stdout_queue.get(
                timeout=min(0.25, max(0.0, deadline - time.monotonic()))
            )
        except queue.Empty:
            continue
        if line is None:
            raise RuntimeError(
                f"STDIO server closed stdout: {stderr_text()}"
            )
        try:
            message = json.loads(line)
        except json.JSONDecodeError as exc:
            raise RuntimeError(
                f"Non-JSON data was written to STDOUT: {exc}"
            ) from exc
        if message.get("id") == expected_id:
            return message
    raise TimeoutError(
        f"Timed out waiting for JSON-RPC response id={expected_id}"
    )


try:
    initialize_request = {
        "jsonrpc": "2.0",
        "id": 1,
        "method": "initialize",
        "params": {
            "protocolVersion": "2025-06-18",
            "capabilities": {},
            "clientInfo": {
                "name": "telegram-mcp-stdio-smoke",
                "version": "1.0.0",
            },
        },
    }
    send(initialize_request)
    initialize = read_response(1)
    if initialize.get("error") is not None:
        raise RuntimeError(
            "Initialize failed: "
            + json.dumps(initialize["error"], separators=(",", ":"))
        )

    send({
        "jsonrpc": "2.0",
        "method": "notifications/initialized",
        "params": {},
    })
    send({
        "jsonrpc": "2.0",
        "id": 2,
        "method": "tools/list",
        "params": {},
    })
    tools_response = read_response(2)
    if tools_response.get("error") is not None:
        raise RuntimeError(
            "tools/list failed: "
            + json.dumps(tools_response["error"], separators=(",", ":"))
        )

    tools = tools_response.get("result", {}).get("tools", [])
    names = [tool.get("name") for tool in tools]
    for name in required:
        if name not in names:
            raise RuntimeError(f"Required stdio tool is missing: {name}")
    for name in forbidden:
        if name in names:
            raise RuntimeError(f"Forbidden stdio tool is exposed: {name}")

    invalid = [
        tool.get("name", "<unnamed>")
        for tool in tools
        if tool.get("inputSchema", {}).get("type") != "object"
        or tool.get("outputSchema") is None
        or tool.get("annotations", {}).get("readOnlyHint") is None
    ]
    if invalid:
        raise RuntimeError(
            "Invalid stdio tool contracts: " + ", ".join(invalid)
        )

    process.stdin.close()
    try:
        exit_code = process.wait(timeout=10)
    except subprocess.TimeoutExpired as exc:
        process.kill()
        process.wait(timeout=5)
        raise RuntimeError(
            "STDIO server did not exit within 10000ms after stdin closed. "
            f"STDERR: {stderr_text()}"
        ) from exc
    stderr_thread.join(timeout=1)
    if exit_code != 0:
        raise RuntimeError(
            f"STDIO server exited with code {exit_code} after stdin closed. "
            f"STDERR: {stderr_text()}"
        )

    # A container or service manager stops the server with a signal, never by
    # closing stdin. That path closes the same TDLib session, so it needs the
    # same bounded exit: without one the supervisor waits out its grace period
    # and escalates to SIGKILL, killing TDLib mid-write.
    signal_process = subprocess.Popen(
        command,
        stdin=subprocess.PIPE,
        stdout=subprocess.PIPE,
        stderr=subprocess.DEVNULL,
        text=True,
        encoding="utf-8",
        errors="replace",
        bufsize=1,
        env=environment,
    )
    try:
        # Complete a handshake rather than sleeping: the signal has to arrive at
        # a server that finished starting, and a fixed wait would race a slow
        # cold start into a false failure.
        signal_process.stdin.write(
            json.dumps(initialize_request, separators=(",", ":")) + "\n"
        )
        signal_process.stdin.flush()
        handshake = queue.Queue()
        threading.Thread(
            target=lambda: handshake.put(signal_process.stdout.readline()),
            daemon=True,
        ).start()
        try:
            if not json.loads(handshake.get(timeout=60)).get("result"):
                raise RuntimeError("STDIO server did not answer initialize before the signal")
        except queue.Empty as exc:
            raise RuntimeError(
                "STDIO server did not answer initialize within 60000ms before the signal"
            ) from exc

        signal_process.terminate()
        try:
            signal_exit = signal_process.wait(timeout=15)
        except subprocess.TimeoutExpired as exc:
            signal_process.kill()
            signal_process.wait(timeout=5)
            raise RuntimeError(
                "STDIO server did not exit within 15000ms of a termination signal. "
                "A supervisor would escalate to SIGKILL."
            ) from exc
    finally:
        if signal_process.poll() is None:
            signal_process.kill()
            signal_process.wait(timeout=5)

    # The same signal, but delivered before startup finishes. That window is
    # wide -- startup loads TDLib's native libraries -- and it is not covered by
    # the phase above, which waits for `initialize` precisely so the server is
    # ready. 1.11.0 shipped a signal path that worked once running and threw
    # `IllegalStateException: Shutdown in progress` out of main when the signal
    # landed first, leaving the close unbounded until the supervisor gave up.
    startup_process = subprocess.Popen(
        command,
        stdin=subprocess.PIPE,
        stdout=subprocess.DEVNULL,
        stderr=subprocess.PIPE,
        text=True,
        encoding="utf-8",
        errors="replace",
        env=environment,
    )
    startup_stderr = []
    startup_drain = threading.Thread(
        target=lambda: startup_stderr.extend(startup_process.stderr),
        daemon=True,
    )
    startup_drain.start()
    try:
        # Long enough for the JVM to install its signal handler, far short of a
        # finished Spring context.
        time.sleep(1.5)
        if startup_process.poll() is not None:
            raise RuntimeError(
                f"STDIO server exited before the startup signal: {startup_process.returncode}"
            )
        startup_process.terminate()
        try:
            startup_signal_exit = startup_process.wait(timeout=30)
        except subprocess.TimeoutExpired as exc:
            startup_process.kill()
            startup_process.wait(timeout=5)
            raise RuntimeError(
                "STDIO server did not exit within 30000ms of a termination signal "
                "delivered during startup. A supervisor would escalate to SIGKILL."
            ) from exc
        startup_drain.join(timeout=2)
        startup_stderr_text = "".join(startup_stderr)
        if "Shutdown in progress" in startup_stderr_text:
            raise RuntimeError(
                "STDIO server failed to register its shutdown path during startup: "
                "the JVM refused the hook and the close was left unbounded."
            )
    finally:
        if startup_process.poll() is None:
            startup_process.kill()
            startup_process.wait(timeout=5)

    print(json.dumps({
        "transport": "stdio",
        "protocolVersion": initialize.get("result", {}).get("protocolVersion"),
        "toolCount": len(tools),
        "outputSchemas": True,
        "stdoutJsonOnly": True,
        "lifecycleExit": True,
        "exitCode": exit_code,
        "signalExit": True,
        "signalExitCode": signal_exit,
        "startupSignalExit": True,
        "startupSignalExitCode": startup_signal_exit,
    }, separators=(",", ":")))
finally:
    if process.stdin and not process.stdin.closed:
        try:
            process.stdin.close()
        except BrokenPipeError:
            pass
    if process.poll() is None:
        process.kill()
        process.wait(timeout=5)
PY
