#!/usr/bin/env bash
# Verifies a published container image, not a local build of the same source.
#
# 1.11.0 passed every local gate and still shipped a broken termination path.
# The defect only appeared when a container was stopped while it was still
# starting, on a session directory with no existing login — a state no local
# check exercised. This runs the lifecycle contract against the artifact users
# actually pull, from that state.
#
# Signals go through `docker stop`, which sends SIGTERM to the container's PID 1.
# Killing the `docker run` client instead proves nothing: the CLI is only a
# client of the daemon, and its death leaves the container running. That
# distinction is the original cause of the orphaned-container bug.
#
# Requires: docker, python3.
set -euo pipefail

usage() {
  cat >&2 <<'USAGE'
Usage:
  verify-published-image.sh --image IMAGE[:TAG] [--expect-version X.Y.Z]

Pulls the image and checks the STDIO lifecycle contract:
  * answers initialize
  * exits 0 when stdin closes
  * exits 0 on SIGTERM once running
  * exits under a bounded deadline on SIGTERM during startup
USAGE
}

image=""
expected_version=""
while [[ $# -gt 0 ]]; do
  case "$1" in
    --image)
      [[ $# -ge 2 ]] || { usage; exit 2; }
      image="$2"; shift 2 ;;
    --expect-version)
      [[ $# -ge 2 ]] || { usage; exit 2; }
      expected_version="$2"; shift 2 ;;
    --help|-h)
      usage; exit 0 ;;
    *)
      printf 'Unknown argument: %s\n' "$1" >&2
      usage; exit 2 ;;
  esac
done

[[ -n "$image" ]] || { usage; exit 2; }

for tool in docker python3; do
  command -v "$tool" >/dev/null 2>&1 || {
    printf 'Required command is not available: %s\n' "$tool" >&2
    exit 2
  }
done

# `docker stop` waits this long for a clean exit before sending SIGKILL. Keeping
# it at the daemon default is the point: the server must fit inside what a
# stock supervisor already allows.
STOP_TIMEOUT=30
READY_TIMEOUT=180

label="telegram-mcp-verify=$$"
containers=()

cleanup() {
  for cid in "${containers[@]:-}"; do
    [[ -n "$cid" ]] && docker rm -f "$cid" >/dev/null 2>&1 || true
  done
}
trap cleanup EXIT

run_detached() {
  local session_dir="$1"
  docker run -d -i --label "$label" \
    -v "${session_dir}:/data/tdlib-data" \
    -e MCP_READ_ONLY=true -e MCP_TOOL_PROFILE=reader \
    -e TDLIB_API_ID=0 -e TDLIB_API_HASH= \
    "$image" serve --transport stdio
}

# Each check starts from the state a first run sees: an empty session directory
# with no login. A warm, authenticated session hides the slow startup that makes
# the signal window reachable at all.
new_session_dir() {
  mktemp -d
}

fail() {
  printf 'FAIL: %s\n' "$1" >&2
  [[ -n "${2:-}" ]] && printf '%s\n' "$2" | tail -40 >&2
  exit 1
}

printf 'Pulling %s\n' "$image" >&2
# The image is ~1 GB, and a truncated layer download would otherwise fail a
# release for a reason that has nothing to do with the release.
pulled=false
for attempt in 1 2 3; do
  if docker pull --quiet "$image" >/dev/null; then
    pulled=true
    break
  fi
  printf 'Pull attempt %d failed; retrying\n' "$attempt" >&2
  sleep $((attempt * 10))
done
[[ "$pulled" == true ]] || fail "could not pull ${image} after 3 attempts"

if [[ -n "$expected_version" ]]; then
  actual=$(docker inspect "$image" --format '{{index .Config.Labels "org.opencontainers.image.version"}}')
  [[ "$actual" == "$expected_version" ]] || \
    fail "image reports version ${actual}, expected ${expected_version}"
fi

# ── 1. Answers initialize and exits when stdin closes ────────────────────────
stdin_dir=$(new_session_dir)
export VERIFY_IMAGE="$image" VERIFY_SESSION_DIR="$stdin_dir" VERIFY_READY_TIMEOUT="$READY_TIMEOUT"
stdin_result=$(python3 - <<'PY'
import json, os, subprocess, sys, threading

image = os.environ["VERIFY_IMAGE"]
session_dir = os.environ["VERIFY_SESSION_DIR"]
ready_timeout = int(os.environ["VERIFY_READY_TIMEOUT"])

process = subprocess.Popen(
    ["docker", "run", "--rm", "-i",
     "-v", f"{session_dir}:/data/tdlib-data",
     "-e", "MCP_READ_ONLY=true", "-e", "MCP_TOOL_PROFILE=reader",
     "-e", "TDLIB_API_ID=0", "-e", "TDLIB_API_HASH=",
     image, "serve", "--transport", "stdio"],
    stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.DEVNULL,
    text=True, encoding="utf-8", errors="replace", bufsize=1,
)
request = {
    "jsonrpc": "2.0", "id": 1, "method": "initialize",
    "params": {"protocolVersion": "2025-06-18", "capabilities": {},
               "clientInfo": {"name": "verify-published-image", "version": "1.0.0"}},
}
process.stdin.write(json.dumps(request, separators=(",", ":")) + "\n")
process.stdin.flush()

answer = []
reader = threading.Thread(target=lambda: answer.append(process.stdout.readline()), daemon=True)
reader.start()
reader.join(timeout=ready_timeout)
if not answer or not answer[0].strip():
    process.kill()
    print(f"no initialize response within {ready_timeout}s", file=sys.stderr)
    sys.exit(1)
if not json.loads(answer[0]).get("result"):
    process.kill()
    print("initialize did not return a result", file=sys.stderr)
    sys.exit(1)

process.stdin.close()
try:
    code = process.wait(timeout=60)
except subprocess.TimeoutExpired:
    process.kill()
    print("no exit within 60s of stdin close", file=sys.stderr)
    sys.exit(1)
if code != 0:
    print(f"stdin close exited {code}, expected 0", file=sys.stderr)
    sys.exit(1)
print(code)
PY
) || fail "stdio lifecycle check failed"
rm -rf "$stdin_dir"

# ── 2. SIGTERM once running ──────────────────────────────────────────────────
running_dir=$(new_session_dir)
cid=$(run_detached "$running_dir")
containers+=("$cid")
deadline=$((SECONDS + READY_TIMEOUT))
until docker logs "$cid" 2>&1 | grep -q "Started TelegramMcpApplicationKt"; do
  [[ $SECONDS -lt $deadline ]] || fail "server did not finish starting within ${READY_TIMEOUT}s" "$(docker logs "$cid" 2>&1)"
  [[ "$(docker inspect -f '{{.State.Status}}' "$cid")" == "running" ]] || \
    fail "container exited during startup with $(docker inspect -f '{{.State.ExitCode}}' "$cid")" "$(docker logs "$cid" 2>&1)"
  sleep 3
done

start=$SECONDS
docker stop -t "$STOP_TIMEOUT" "$cid" >/dev/null
running_elapsed=$((SECONDS - start))
running_code=$(docker inspect -f '{{.State.ExitCode}}' "$cid")
[[ "$running_code" == "0" ]] || \
  fail "SIGTERM while running exited ${running_code} after ${running_elapsed}s (137 means the daemon had to SIGKILL)" "$(docker logs "$cid" 2>&1)"
docker rm -f "$cid" >/dev/null 2>&1 || true
rm -rf "$running_dir"

# ── 3. SIGTERM during startup ────────────────────────────────────────────────
# The window 1.11.0 shipped broken: the JVM refuses a shutdown hook once it is
# already terminating, so without a direct request the close runs unbounded.
startup_dir=$(new_session_dir)
cid=$(run_detached "$startup_dir")
containers+=("$cid")
sleep 2
[[ "$(docker inspect -f '{{.State.Status}}' "$cid")" == "running" ]] || \
  fail "container exited before the startup signal" "$(docker logs "$cid" 2>&1)"

start=$SECONDS
docker stop -t "$STOP_TIMEOUT" "$cid" >/dev/null
startup_elapsed=$((SECONDS - start))
startup_code=$(docker inspect -f '{{.State.ExitCode}}' "$cid")
startup_logs=$(docker logs "$cid" 2>&1)
if grep -q "Shutdown in progress" <<<"$startup_logs"; then
  fail "the JVM refused the shutdown hook and left the close unbounded" "$startup_logs"
fi
[[ "$startup_code" != "137" ]] || \
  fail "SIGTERM during startup was SIGKILLed after ${startup_elapsed}s" "$startup_logs"
docker rm -f "$cid" >/dev/null 2>&1 || true
rm -rf "$startup_dir"

python3 -c "
import json, sys
print(json.dumps({
    'image': sys.argv[1],
    'initialize': True,
    'stdinCloseExitCode': int(sys.argv[2]),
    'runningSignalExitCode': int(sys.argv[3]),
    'runningSignalSeconds': int(sys.argv[4]),
    'startupSignalExitCode': int(sys.argv[5]),
    'startupSignalSeconds': int(sys.argv[6]),
}, separators=(',', ':')))
" "$image" "$stdin_result" "$running_code" "$running_elapsed" "$startup_code" "$startup_elapsed"

printf 'Published image verified: %s\n' "$image" >&2
