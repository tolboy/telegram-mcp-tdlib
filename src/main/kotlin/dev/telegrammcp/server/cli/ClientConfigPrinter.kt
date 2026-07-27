package dev.telegrammcp.server.cli

/**
 * Emits a ready-to-paste MCP client entry.
 *
 * Connecting this server is the step where most first runs go wrong, and the
 * mistakes are not interesting ones: the top-level key differs per client, the
 * safe profile has to be spelled correctly, and a config written once keeps
 * running whatever it first said. Generating the block removes that class of
 * error rather than documenting it again.
 */
internal object ClientConfigPrinter {

    /** Clients whose entry differs in more than cosmetics. */
    enum class Client(val id: String, private val rootKey: String) {
        CLAUDE("claude", "mcpServers"),
        CURSOR("cursor", "mcpServers"),

        // VS Code names the same structure `servers`; an `mcpServers` block is
        // silently ignored there, which looks exactly like a broken server.
        VSCODE("vscode", "servers"),
        ;

        fun rootKey(): String = rootKey

        companion object {
            fun parse(value: String): Client = entries.firstOrNull { it.id == value.lowercase() }
                ?: error("Unknown client '$value'; use one of ${entries.joinToString("|") { it.id }}")
        }
    }

    data class Options(
        val client: Client = Client.CLAUDE,
        val profile: String = "reader",
        val readOnly: Boolean = true,
        val apiId: String = "123456",
        val apiHashFile: String = "/absolute/path/to/telegram-api-hash",
        val docker: String? = null,
    )

    fun render(options: Options): String {
        val environment = buildList {
            add("\"TDLIB_API_ID\": \"${options.apiId}\"")
            add("\"TDLIB_API_HASH_FILE\": \"${options.apiHashFile}\"")
            add("\"MCP_TOOL_PROFILE\": \"${options.profile}\"")
            add("\"MCP_READ_ONLY\": \"${options.readOnly}\"")
            // Only meaningful once writes exist; suggesting it in a read-only
            // block would gate tools that are not registered anyway.
            if (!options.readOnly) add("\"MCP_DESTRUCTIVE_APPROVAL\": \"elicitation\"")
        }

        val invocation = options.docker?.let { image ->
            val dockerArgs = buildList {
                addAll(listOf("run", "-i", "--rm"))
                addAll(listOf("-v", "/absolute/path/to/session:/data/tdlib-data"))
                environmentPairs(options).forEach { pair -> addAll(listOf("-e", pair)) }
                add(image)
            }
            """
            |      "command": "docker",
            |      "args": [${dockerArgs.joinToString(", ") { "\"$it\"" }}]
            """.trimMargin()
        } ?: """
            |      "command": "telegram-mcp",
            |      "args": ["serve", "--transport", "stdio"],
            |      "env": {
            |${environment.joinToString(",\n") { "        $it" }}
            |      }
        """.trimMargin()

        return """
            |{
            |  "${options.client.rootKey()}": {
            |    "telegram": {
            |$invocation
            |    }
            |  }
            |}
        """.trimMargin()
    }

    private fun environmentPairs(options: Options): List<String> = buildList {
        add("TDLIB_API_ID=${options.apiId}")
        add("TDLIB_API_HASH=<your-api-hash>")
        add("MCP_TOOL_PROFILE=${options.profile}")
        add("MCP_READ_ONLY=${options.readOnly}")
        if (!options.readOnly) add("MCP_DESTRUCTIVE_APPROVAL=elicitation")
    }

    /** Guidance that belongs next to the block rather than inside it. */
    fun notes(options: Options): List<String> = buildList {
        when (options.client) {
            Client.CLAUDE -> add(
                "Claude Desktop: Settings > Developer > Edit Config opens the right file. " +
                    "Quit from the tray and start again - closing the window does not reload it.",
            )
            Client.CURSOR -> add("Cursor: ~/.cursor/mcp.json, or Settings > MCP > Add.")
            Client.VSCODE -> add("VS Code: .vscode/mcp.json. The top-level key is `servers`, not `mcpServers`.")
        }
        if (options.docker != null) {
            add(
                "The image tag is pinned on purpose: `docker run` never re-pulls a tag the machine " +
                    "already has, so a floating tag keeps starting the build it first cached.",
            )
        } else {
            add("`telegram-mcp` must be on PATH; the Homebrew and Scoop packages put it there.")
        }
        add("Your client starts this server. Do not also run `serve --transport stdio` yourself - two processes cannot share one TDLib session.")
        if (options.readOnly) {
            add("Read-only: write tools are absent from the tool list. Re-run with --writes when you need them.")
        } else {
            add("Writes are enabled, so destructive tools ask for your approval in the client before running.")
        }
    }
}
