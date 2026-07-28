package dev.telegrammcp.server.cli

/**
 * Emits a ready-to-paste MCP client entry.
 *
 * Connecting this server is the step where most first runs go wrong, and the
 * mistakes are not interesting ones: the container key differs per client, one
 * client wants TOML, another needs a `type` the rest omit, and a tag written
 * once keeps starting whatever it first said. Generating the block removes that
 * class of error rather than documenting it again.
 *
 * Only clients whose shape was checked against a real config file are listed.
 * A wrong generated block is worse than none, and most remaining clients read
 * the same `mcpServers` object that [Client.CURSOR] emits.
 */
internal object ClientConfigPrinter {

    /** Clients whose entry differs in more than cosmetics. */
    enum class Client(val id: String, val format: Format) {
        /** Claude Desktop's `claude_desktop_config.json`. */
        CLAUDE("claude", Format.JSON),

        /**
         * Claude Code's `~/.claude.json`, or `.mcp.json` in a project. Same
         * object as Claude Desktop plus an explicit transport `type`.
         */
        CLAUDE_CODE("claude-code", Format.JSON),

        CURSOR("cursor", Format.JSON),

        /** VS Code names the same structure `servers` and ignores `mcpServers`. */
        VSCODE("vscode", Format.JSON),

        /** Codex reads TOML tables, not JSON. */
        CODEX("codex", Format.TOML),
        ;

        /** The object that holds server entries, for the JSON clients. */
        fun rootKey(): String = if (this == VSCODE) "servers" else "mcpServers"

        /** Claude Code and VS Code require an explicit local STDIO type. */
        fun declaresStdioTransportType(): Boolean = this == CLAUDE_CODE || this == VSCODE

        /** VS Code also requires an explicit type for remote HTTP servers. */
        fun declaresHttpTransportType(): Boolean = this == CLAUDE_CODE || this == VSCODE

        companion object {
            fun parse(value: String): Client = entries.firstOrNull { it.id == value.lowercase() }
                ?: error("Unknown client '$value'; use one of ${entries.joinToString("|") { it.id }}")
        }
    }

    enum class Format { JSON, TOML }

    data class Options(
        val client: Client = Client.CLAUDE,
        val profile: String = "reader",
        val readOnly: Boolean = true,
        val apiId: String = "123456",
        val apiHashFile: String = "/absolute/path/to/telegram-api-hash",
        val docker: String? = null,
        /** Connect to a shared HTTP daemon instead of starting a local process. */
        val httpUrl: String? = null,
    )

    fun render(options: Options): String = when {
        options.httpUrl != null -> renderHttp(options)
        options.client.format == Format.TOML -> renderToml(options)
        else -> renderJson(options)
    }

    private fun renderJson(options: Options): String {
        val entry = options.docker?.let { image ->
            listOf(
                jsonField("command", "docker"),
                """      "args": [${dockerArgs(options, image).joinToString(", ", transform = ::jsonString)}]""",
            )
        } ?: listOf(
            jsonField("command", "telegram-mcp"),
            """      "args": ["serve", "--transport", "stdio"]""",
            """      "env": {
${environmentEntries(options).joinToString(",\n") { (key, value) ->
                "        ${jsonString(key)}: ${jsonString(value)}"
            }}
      }""",
        )

        val fields = buildList {
            if (options.client.declaresStdioTransportType()) add(jsonField("type", "stdio"))
            addAll(entry)
        }
        return wrapJson(options.client, fields)
    }

    /**
     * The shared-daemon topology from MULTI_CLIENT_DEPLOYMENT.md. Several
     * clients cannot each own a STDIO process because a TDLib session admits
     * exactly one.
     */
    private fun renderHttp(options: Options): String {
        val url = requireNotNull(options.httpUrl)
        if (options.client.format == Format.TOML) {
            return """
                |[mcp_servers.telegram]
                |url = ${tomlString(url)}
                |
                |[mcp_servers.telegram.http_headers]
                |Authorization = "Bearer <your-MCP_API_KEY>"
            """.trimMargin()
        }
        return wrapJson(
            options.client,
            buildList {
                if (options.client.declaresHttpTransportType()) add(jsonField("type", "http"))
                add(jsonField("url", url))
                add(
                    """      "headers": {
        "Authorization": "Bearer <your-MCP_API_KEY>"
      }""",
                )
            },
        )
    }

    private fun renderToml(options: Options): String {
        val command = options.docker?.let { image ->
            "command = \"docker\"\nargs = [${dockerArgs(options, image).joinToString(", ", transform = ::tomlString)}]"
        } ?: "command = \"telegram-mcp\"\nargs = [\"serve\", \"--transport\", \"stdio\"]"

        val env = environmentEntries(options)
            .joinToString("\n") { (key, value) -> "$key = ${tomlString(value)}" }
        return """
            |[mcp_servers.telegram]
            |$command
            |
            |[mcp_servers.telegram.env]
            |$env
        """.trimMargin()
    }

    private fun wrapJson(client: Client, fields: List<String>): String = """
        |{
        |  "${client.rootKey()}": {
        |    "telegram": {
        |${fields.joinToString(",\n")}
        |    }
        |  }
        |}
    """.trimMargin()

    private fun jsonField(key: String, value: String) = "      ${jsonString(key)}: ${jsonString(value)}"

    /** JSON strings cannot interpolate paths or URLs verbatim: backslashes and quotes are syntax. */
    private fun jsonString(value: String): String = buildString(value.length + 2) {
        append('"')
        value.forEach { character ->
            when (character) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\b' -> append("\\b")
                '\u000C' -> append("\\f")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> {
                    if (character.code < 0x20 || character.code in 0xD800..0xDFFF) {
                        appendUnicodeEscape(character)
                    } else {
                        append(character)
                    }
                }
            }
        }
        append('"')
    }

    /** TOML basic strings use JSON-like escapes, but also forbid DEL. */
    private fun tomlString(value: String): String = buildString(value.length + 2) {
        append('"')
        value.forEach { character ->
            when (character) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\b' -> append("\\b")
                '\t' -> append("\\t")
                '\n' -> append("\\n")
                '\u000C' -> append("\\f")
                '\r' -> append("\\r")
                else -> {
                    if (character.code < 0x20 || character.code == 0x7F) {
                        appendUnicodeEscape(character)
                    } else {
                        append(character)
                    }
                }
            }
        }
        append('"')
    }

    private fun StringBuilder.appendUnicodeEscape(character: Char) {
        append("\\u")
        append(character.code.toString(16).padStart(4, '0'))
    }

    private fun dockerArgs(options: Options, image: String): List<String> = buildList {
        addAll(listOf("run", "-i", "--rm"))
        addAll(listOf("-v", "/absolute/path/to/session:/data/tdlib-data"))
        environmentEntries(options).forEach { (key, value) ->
            // The hash is a file path outside a container; inside, it is a value
            // the caller still has to supply.
            val inline = if (key == "TDLIB_API_HASH_FILE") "TDLIB_API_HASH=<your-api-hash>" else "$key=$value"
            addAll(listOf("-e", inline))
        }
        add(image)
        // Do not rely on the image's default transport. An explicit image may
        // be the ordinary runtime tag rather than the -stdio variant, and a
        // generated desktop config must still start an STDIO server.
        addAll(listOf("serve", "--transport", "stdio"))
    }

    private fun environmentEntries(options: Options): List<Pair<String, String>> = buildList {
        add("TDLIB_API_ID" to options.apiId)
        add("TDLIB_API_HASH_FILE" to options.apiHashFile)
        add("MCP_TOOL_PROFILE" to options.profile)
        add("MCP_READ_ONLY" to options.readOnly.toString())
        // Only meaningful once writes exist; gating tools that are never
        // registered would just be noise. A container gets `elicitation`
        // instead of `auto`: the loopback page would be announced on the
        // container's own 127.0.0.1, which no host browser can open, so `auto`
        // would fall back to a route that cannot be answered.
        if (!options.readOnly) {
            add("MCP_DESTRUCTIVE_APPROVAL" to if (options.docker != null) "elicitation" else "auto")
        }
    }

    /** Guidance that belongs next to the block rather than inside it. */
    fun notes(options: Options): List<String> = buildList {
        when (options.client) {
            Client.CLAUDE -> add(
                "Claude Desktop: Settings > Developer > Edit Config opens the right file. " +
                    "Quit from the tray and start again - closing the window does not reload it.",
            )
            Client.CLAUDE_CODE -> add(
                "Claude Code: ~/.claude.json for every project, or .mcp.json in one project to share it " +
                    "with the repo. `claude mcp add` writes the same entry.",
            )
            Client.CURSOR -> add("Cursor: ~/.cursor/mcp.json, or Settings > MCP > Add.")
            Client.VSCODE -> add("VS Code: .vscode/mcp.json. The top-level key is `servers`, not `mcpServers`.")
            Client.CODEX -> add("Codex: ~/.codex/config.toml. Codex reads TOML tables, not JSON.")
        }

        if (options.httpUrl != null) {
            add(
                "Use one shared HTTP daemon when several clients need the account, because a TDLib session " +
                    "admits a single process and competing STDIO children collide on its lock.",
            )
            add("Set MCP_API_KEY on the daemon and use the same value in the Authorization header.")
        } else if (options.docker != null) {
            add(
                "The image tag is pinned on purpose: `docker run` never re-pulls a tag the machine " +
                    "already has, so a floating tag keeps starting the build it first cached.",
            )
            add(
                "Replace the `TDLIB_API_HASH=<your-api-hash>` placeholder in the generated entry. " +
                    "The generator never copies the host hash file into the container config.",
            )
            if (!options.readOnly) {
                add(
                    "Destructive tools are set to `elicitation`, not `auto`: the loopback approval page " +
                        "would be published on the container's own 127.0.0.1, which your browser cannot " +
                        "reach. A client that does not implement MCP elicitation will therefore refuse " +
                        "them outright — run the server natively if you need the loopback route.",
                )
            }
        } else {
            add("`telegram-mcp` must be on PATH; the Homebrew and Scoop packages put it there.")
        }

        if (options.httpUrl == null) {
            add(
                "Your client starts this server. Do not also run `serve --transport stdio` yourself - " +
                    "two processes cannot share one TDLib session.",
            )
        }
        when {
            // The surface belongs to the daemon's own configuration; this entry
            // only says where to reach it.
            options.httpUrl != null ->
                add("The tool surface comes from the daemon's MCP_TOOL_PROFILE and MCP_READ_ONLY, not from this entry.")
            options.readOnly ->
                add(
                    "Read-only: write tools are absent from the tool list. Re-run with --writes and a " +
                        "write-capable --profile (inbox, community-admin, or all) when you need them.",
                )
            else ->
                add("Writes are enabled, so destructive tools ask a human for approval before running.")
        }
        if (options.client.format == Format.JSON && options.client != Client.VSCODE) {
            add(
                "Clients not listed here that read a standard `mcpServers` object - Zed, LM Studio, " +
                    "Windsurf/Devin and others - take this block as-is; only their file location differs.",
            )
        }
    }
}
