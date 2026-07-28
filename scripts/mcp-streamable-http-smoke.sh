#!/usr/bin/env bash
set -euo pipefail

usage() {
  cat >&2 <<'USAGE'
Usage:
  mcp-streamable-http-smoke.sh [--endpoint URL] [--api-key KEY]
    [--require TOOL[,TOOL...]] [--forbid TOOL[,TOOL...]]

Runs initialize and tools/list without calling Telegram. --require and
--forbid may be repeated. The API key defaults to MCP_API_KEY.
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
  # Bash 3.2 treats an empty array expansion as unbound under `set -u`.
  for item in ${parsed[@]+"${parsed[@]}"}; do
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

endpoint="http://127.0.0.1:8080/mcp"
api_key="${MCP_API_KEY:-}"
declare -a required_tools=()
declare -a forbidden_tools=()

while (($# > 0)); do
  case "$1" in
    --endpoint)
      [[ $# -ge 2 ]] || { usage; exit 2; }
      endpoint="$2"
      shift 2
      ;;
    --api-key)
      [[ $# -ge 2 ]] || { usage; exit 2; }
      api_key="$2"
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

require_command curl
require_command python3

protocol_version="2025-06-18"
tmp_dir="$(mktemp -d "${TMPDIR:-/tmp}/telegram-mcp-http-smoke.XXXXXX")"
cleanup() {
  rm -rf -- "$tmp_dir"
}
trap cleanup EXIT

declare -a common_headers=(
  -H "Accept: application/json, text/event-stream"
  -H "Content-Type: application/json"
  -H "MCP-Protocol-Version: $protocol_version"
)
if [[ -n "$api_key" ]]; then
  common_headers+=(-H "Authorization: Bearer $api_key")
fi

http_post() {
  local body="$1"
  local headers_file="$2"
  local output_file="$3"
  local session_id="${4:-}"
  local allow_empty_disconnect="${5:-false}"
  local -a session_header=()
  local status
  local curl_exit

  if [[ -n "$session_id" ]]; then
    session_header=(-H "Mcp-Session-Id: $session_id")
  fi

  set +e
  status="$(
    curl --silent --show-error --max-time 15 \
      --request POST \
      --dump-header "$headers_file" \
      --output "$output_file" \
      --write-out '%{http_code}' \
      ${common_headers[@]+"${common_headers[@]}"} \
      ${session_header[@]+"${session_header[@]}"} \
      --data "$body" \
      "$endpoint"
  )"
  curl_exit=$?
  set -e

  if ((curl_exit != 0)); then
    if [[ "$allow_empty_disconnect" == "true" && "$curl_exit" -eq 52 ]]; then
      printf '000\n'
      return 0
    fi
    printf 'HTTP request failed with curl exit code %s\n' "$curl_exit" >&2
    return 1
  fi
  if [[ ! "$status" =~ ^2[0-9][0-9]$ ]]; then
    printf 'HTTP request returned status %s: %s\n' \
      "$status" "$(tr '\n' ' ' <"$output_file")" >&2
    return 1
  fi
  printf '%s\n' "$status"
}

initialize_body='{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-06-18","capabilities":{},"clientInfo":{"name":"telegram-mcp-streamable-smoke","version":"1.0.0"}}}'
http_post "$initialize_body" "$tmp_dir/initialize.headers" "$tmp_dir/initialize.body" >/dev/null

session_id="$(
  awk -F': *' '
    tolower($1) == "mcp-session-id" {
      sub(/\r$/, "", $2)
      print $2
      exit
    }
  ' "$tmp_dir/initialize.headers"
)"
if [[ -z "$session_id" ]]; then
  printf 'Server did not return Mcp-Session-Id during initialize\n' >&2
  exit 1
fi

notification_body='{"jsonrpc":"2.0","method":"notifications/initialized","params":{}}'
http_post \
  "$notification_body" \
  "$tmp_dir/notification.headers" \
  "$tmp_dir/notification.body" \
  "$session_id" \
  true >/dev/null

tools_body='{"jsonrpc":"2.0","id":2,"method":"tools/list","params":{}}'
http_post "$tools_body" "$tmp_dir/tools.headers" "$tmp_dir/tools.body" "$session_id" >/dev/null

initialize_python_path="$tmp_dir/initialize.body"
tools_python_path="$tmp_dir/tools.body"
if command -v cygpath >/dev/null 2>&1; then
  initialize_python_path="$(cygpath -w "$initialize_python_path")"
  tools_python_path="$(cygpath -w "$tools_python_path")"
fi

python3 - \
  "$endpoint" \
  "$initialize_python_path" \
  "$tools_python_path" \
  ${required_tools[@]+"${required_tools[@]}"} \
  --forbidden \
  ${forbidden_tools[@]+"${forbidden_tools[@]}"} <<'PY'
import json
import pathlib
import sys

endpoint = sys.argv[1]
initialize_path = pathlib.Path(sys.argv[2])
tools_path = pathlib.Path(sys.argv[3])
arguments = sys.argv[4:]
separator = arguments.index("--forbidden")
required = arguments[:separator]
forbidden = arguments[separator + 1:]


def load_mcp_payload(path: pathlib.Path):
    content = path.read_text(encoding="utf-8").strip()
    data_lines = [
        line[5:].lstrip()
        for line in content.splitlines()
        if line.startswith("data:")
    ]
    payload = "\n".join(data_lines) if data_lines else content
    return json.loads(payload)


initialize = load_mcp_payload(initialize_path)
protocol_version = initialize.get("result", {}).get("protocolVersion")
if protocol_version != "2025-06-18":
    raise SystemExit(
        f"Server negotiated {protocol_version!r} instead of '2025-06-18'"
    )

tools_payload = load_mcp_payload(tools_path)
if tools_payload.get("error") is not None:
    raise SystemExit(
        "tools/list failed: "
        + json.dumps(tools_payload["error"], separators=(",", ":"))
    )
tools = tools_payload.get("result", {}).get("tools", [])
if not tools:
    raise SystemExit("Server returned no MCP tools")

invalid = [
    tool.get("name", "<unnamed>")
    for tool in tools
    if tool.get("inputSchema", {}).get("type") != "object"
    or tool.get("inputSchema", {}).get("properties") is None
    or tool.get("outputSchema", {}).get("type") != "object"
    or tool.get("outputSchema", {}).get("properties", {}).get("data") is None
    or tool.get("outputSchema", {}).get("properties", {}).get("meta") is None
]
if invalid:
    raise SystemExit(
        "Tools without portable input/output schemas: " + ", ".join(invalid)
    )

annotation_names = (
    "readOnlyHint",
    "destructiveHint",
    "idempotentHint",
    "openWorldHint",
)
unannotated = [
    tool.get("name", "<unnamed>")
    for tool in tools
    if any(tool.get("annotations", {}).get(name) is None for name in annotation_names)
]
if unannotated:
    raise SystemExit(
        "Tools without complete MCP behavior annotations: "
        + ", ".join(unannotated)
    )

names = [tool.get("name") for tool in tools]
missing = [name for name in required if name not in names]
if missing:
    raise SystemExit(
        "Expected tool(s) missing from MCP surface: " + ", ".join(missing)
    )
exposed = [name for name in forbidden if name in names]
if exposed:
    raise SystemExit(
        "Unexpected tool(s) exposed by MCP surface: " + ", ".join(exposed)
    )

print(json.dumps({
    "endpoint": endpoint,
    "protocolVersion": protocol_version,
    "server": initialize.get("result", {}).get("serverInfo", {}).get("name"),
    "serverVersion": initialize.get("result", {}).get("serverInfo", {}).get("version"),
    "toolCount": len(tools),
    "annotatedToolCount": len(tools),
    "readOnlySurface": "send_message" not in names,
    "requiredTools": required,
    "forbiddenTools": forbidden,
    "status": "PASS",
}, separators=(",", ":")))
PY
