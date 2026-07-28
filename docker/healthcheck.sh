#!/bin/sh
# Container health probe that matches the transport the server was started with.
#
# Only the Streamable HTTP transport has something to probe. A STDIO server owns
# no listener — its liveness is the process itself, which Docker already tracks —
# so probing the actuator there reports every healthy stdio container as
# unhealthy for its whole lifetime.
set -eu

transport=$(
    printf '%s' "${MCP_TRANSPORT:-streamable-http}" |
        awk '{ gsub(/^[[:space:]]+|[[:space:]]+$/, ""); print tolower($0) }'
)
port=${SERVER_PORT:-8080}

# CLI options override the environment in TelegramMcpCli, so the probe must use
# the same precedence. Read the NUL-delimited argv rather than searching a flat
# string: that avoids matching option-looking text inside an unrelated argument.
if [ -r /proc/1/cmdline ]; then
    cli_settings=$(
        tr '\0' '\n' < /proc/1/cmdline |
            awk '
                pending == "transport" {
                    value = $0
                    gsub(/^[[:space:]]+|[[:space:]]+$/, "", value)
                    transport = tolower(value)
                    pending = ""
                    next
                }
                pending == "port" {
                    port = $0
                    pending = ""
                    next
                }
                $0 == "--transport" {
                    pending = "transport"
                    next
                }
                index($0, "--transport=") == 1 {
                    value = substr($0, length("--transport=") + 1)
                    gsub(/^[[:space:]]+|[[:space:]]+$/, "", value)
                    transport = tolower(value)
                    next
                }
                $0 == "--server.port" {
                    pending = "port"
                    next
                }
                index($0, "--server.port=") == 1 {
                    port = substr($0, length("--server.port=") + 1)
                }
                END {
                    print transport
                    print port
                }
            '
    )
    cli_transport=$(printf '%s\n' "$cli_settings" | sed -n '1p')
    cli_port=$(printf '%s\n' "$cli_settings" | sed -n '2p')
    if [ -n "$cli_transport" ]; then
        transport=$cli_transport
    fi
    case "$cli_port" in
        '' | *[!0-9]*) ;;
        *) port=$cli_port ;;
    esac
fi

case "$transport" in
    stdio)
        exit 0
        ;;
esac

exec curl -fsS "http://localhost:${port}/actuator/health"
