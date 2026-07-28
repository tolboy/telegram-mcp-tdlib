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

        /** Claude Code states the transport explicitly; the others infer it. */
        fun declaresTransportType(): Boolean = this == CLAUDE_CODE

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
                """      "args": [${dockerArgs(options, image).joinToString(", ") { "\"$it\"" }}]""",
            )
        } ?: listOf(
            jsonField("command", "telegram-mcp"),
            """      "args": ["serve", "--transport", "stdio"]""",
            """      "env": {
${environmentEntries(options).joinToString(",\n") { (key, value) -> "        \"$key\": \"$value\"" }}
      }""",
        )

        val fields = buildList {
            if (options.client.declaresTransportType()) add(jsonField("type", "stdio"))
            addAll(entry)
        }
        return wrapJson(options.client, fields)
    }

    /**
     * The shared-daemon topology from MULTI_CLIENT_DEPLOYMENT.md. Several
     * clients — or one client that starts more than one server, as Claude
     * Desktop does for Cowork — cannot each own a STDIO process, because a
     * TDLib session admits exactly one.
     */
    private fun renderHttp(options: Options): String {
        val url = requireNotNull(options.httpUrl)
        if (options.client.format == Format.TOML) {
            return """
                |[mcp_servers.telegram]
                |url = "$url"
                |
                |[mcp_servers.telegram.http_headers]
                |Authorization = "Bearer <your-MCP_API_KEY>"
            """.trimMargin()
        }
        return wrapJson(
            options.client,
            buildList {
                if (options.client.declaresTransportType()) add(jsonField("type", "http"))
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
            "command = \"docker\"\nargs = [${dockerArgs(options, image).joinToString(", ") { "\"$it\"" }}]"
        } ?: "command = \"telegram-mcp\"\nargs = [\"serve\", \"--transport\", \"stdio\"]"

        val env = environmentEntries(options)
            .joinToString("\n") { (key, value) -> "$key = \"$value\"" }
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

    private fun jsonField(key: String, value: String) = """      "$key": "$value""""

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
    }

    private fun environmentEntries(options: Options): List<Pair<String, String>> = buildList {
        add("TDLIB_API_ID" to options.apiId)
        add("TDLIB_API_HASH_FILE" to options.apiHashFile)
        add("MCP_TOOL_PROFILE" to options.profile)
        add("MCP_READ_ONLY" to options.readOnly.toString())
        // Only meaningful once writes exist; gating tools that are never
        // registered would just be noise.
        if (!options.readOnly) add("MCP_DESTRUCTIVE_APPROVAL" to "auto")
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
                "One shared HTTP daemon, because a TDLib session admits a single process. Claude Desktop " +
                    "starts a second server for Cowork from the same config, which is why two STDIO entries " +
                    "collide on the session lock.",
            )
            add("Set MCP_API_KEY on the daemon and use the same value in the Authorization header.")
        } else if (options.docker != null) {
            add(
                "The image tag is pinned on purpose: `docker run` never re-pulls a tag the machine " +
                    "already has, so a floating tag keeps starting the build it first cached.",
            )
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
                add("Read-only: write tools are absent from the tool list. Re-run with --writes when you need them.")
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
