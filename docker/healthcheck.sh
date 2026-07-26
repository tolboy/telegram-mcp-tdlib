#!/bin/sh
# Container health probe that matches the transport the server was started with.
#
# Only the Streamable HTTP transport has something to probe. A STDIO server owns
# no listener — its liveness is the process itself, which Docker already tracks —
# so probing the actuator there reports every healthy stdio container as
# unhealthy for its whole lifetime.
set -eu

if [ "${MCP_TRANSPORT:-}" = "stdio" ]; then
    exit 0
fi

# The transport can also arrive as a `serve` argument, which never reaches the
# environment. PID 1 is the JVM, so its command line is the authoritative answer.
if [ -r /proc/1/cmdline ]; then
    cmdline=$(tr '\0' ' ' < /proc/1/cmdline)
    case " ${cmdline} " in
        *" --transport stdio "* | *" --transport=stdio "*)
            exit 0
            ;;
    esac
fi

exec curl -fsS "http://localhost:${SERVER_PORT:-8080}/actuator/health"
