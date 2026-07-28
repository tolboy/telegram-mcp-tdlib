package dev.telegrammcp.server.cli

import com.fasterxml.jackson.dataformat.toml.TomlMapper
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * A generated config is only useful if it can be pasted without editing, so
 * these cases check what a reader cannot verify by eye: that it parses in the
 * format the client actually reads, that the client-specific keys are right,
 * and that safe defaults survive.
 */
class ClientConfigPrinterTest {

    private val json = jacksonObjectMapper()
    private val toml = TomlMapper()

    private fun parse(options: ClientConfigPrinter.Options): Map<*, *> {
        val rendered = ClientConfigPrinter.render(options)
        val mapper = if (options.client.format == ClientConfigPrinter.Format.TOML) toml else json
        return mapper.readValue(rendered, Map::class.java)
    }

    /** Every client entry has to survive the parser its own client uses. */
    @Test
    fun `every client and invocation renders a parseable entry`() {
        ClientConfigPrinter.Client.entries.forEach { client ->
            listOf(null, "ghcr.io/tolboy/telegram-mcp-tdlib:1.12.0-stdio").forEach { image ->
                listOf(true, false).forEach { readOnly ->
                    val options = ClientConfigPrinter.Options(
                        client = client,
                        readOnly = readOnly,
                        docker = image,
                    )
                    val root = parse(options)
                    val container = if (client.format == ClientConfigPrinter.Format.TOML) {
                        (root["mcp_servers"] as Map<*, *>)
                    } else {
                        root[client.rootKey()] as Map<*, *>
                    }
                    assertTrue(
                        container.containsKey("telegram"),
                        "${client.id} (docker=$image, readOnly=$readOnly) must define the server entry",
                    )
                }
            }
        }
    }

    /** An `mcpServers` block is ignored by VS Code, which looks like a broken server. */
    @Test
    fun `vscode uses servers and the other json clients use mcpServers`() {
        assertTrue(parse(ClientConfigPrinter.Options(client = ClientConfigPrinter.Client.VSCODE)).containsKey("servers"))
        listOf(
            ClientConfigPrinter.Client.CLAUDE,
            ClientConfigPrinter.Client.CLAUDE_CODE,
            ClientConfigPrinter.Client.CURSOR,
        ).forEach { client ->
            assertTrue(
                parse(ClientConfigPrinter.Options(client = client)).containsKey("mcpServers"),
                "${client.id} must use mcpServers",
            )
        }
    }

    /** Claude Code states the transport; Claude Desktop's file has no such key. */
    @Test
    fun `only claude code declares a transport type`() {
        val code = ClientConfigPrinter.render(ClientConfigPrinter.Options(client = ClientConfigPrinter.Client.CLAUDE_CODE))
        val desktop = ClientConfigPrinter.render(ClientConfigPrinter.Options(client = ClientConfigPrinter.Client.CLAUDE))
        assertTrue("\"type\": \"stdio\"" in code)
        assertFalse("\"type\"" in desktop)
    }

    @Test
    fun `codex renders a toml table with a nested env table`() {
        val root = parse(ClientConfigPrinter.Options(client = ClientConfigPrinter.Client.CODEX))
        val entry = (root["mcp_servers"] as Map<*, *>)["telegram"] as Map<*, *>
        assertEquals("telegram-mcp", entry["command"])
        assertEquals(listOf("serve", "--transport", "stdio"), entry["args"])
        assertEquals("reader", (entry["env"] as Map<*, *>)["MCP_TOOL_PROFILE"])
    }

    @Test
    fun `the default entry is read-only and does not gate tools it never registers`() {
        val rendered = ClientConfigPrinter.render(ClientConfigPrinter.Options())
        assertTrue("\"MCP_READ_ONLY\": \"true\"" in rendered)
        assertTrue("\"MCP_TOOL_PROFILE\": \"reader\"" in rendered)
        assertFalse("MCP_DESTRUCTIVE_APPROVAL" in rendered, "approval is meaningless with no write tools")
    }

    /** Enabling writes must not be possible without also asking a human. */
    @Test
    fun `enabling writes also asks for approval, in every format`() {
        ClientConfigPrinter.Client.entries.forEach { client ->
            val rendered = ClientConfigPrinter.render(
                ClientConfigPrinter.Options(client = client, readOnly = false),
            )
            assertTrue(
                "MCP_DESTRUCTIVE_APPROVAL" in rendered && "auto" in rendered,
                "${client.id} must enable approval alongside writes",
            )
        }
    }

    @Test
    fun `a docker entry never bakes in the api hash`() {
        val rendered = ClientConfigPrinter.render(
            ClientConfigPrinter.Options(docker = "ghcr.io/tolboy/telegram-mcp-tdlib:1.12.0-stdio", readOnly = false),
        )
        assertTrue("<your-api-hash>" in rendered, "the hash must stay a placeholder")
        assertFalse("TDLIB_API_HASH_FILE=" in rendered, "a file path is meaningless inside the container")
    }

    /** The shared-daemon topology: one process, several clients. */
    @Test
    fun `an http entry carries a url and an auth header instead of a command`() {
        val root = parse(
            ClientConfigPrinter.Options(client = ClientConfigPrinter.Client.CLAUDE_CODE, httpUrl = "http://127.0.0.1:8080/mcp"),
        )
        val entry = (root["mcpServers"] as Map<*, *>)["telegram"] as Map<*, *>
        assertEquals("http", entry["type"])
        assertEquals("http://127.0.0.1:8080/mcp", entry["url"])
        assertFalse(entry.containsKey("command"), "a shared daemon is not started by the client")
        assertTrue((entry["headers"] as Map<*, *>).containsKey("Authorization"))
    }

    @Test
    fun `an http entry parses for codex too`() {
        val root = parse(
            ClientConfigPrinter.Options(client = ClientConfigPrinter.Client.CODEX, httpUrl = "http://127.0.0.1:8080/mcp"),
        )
        val entry = (root["mcp_servers"] as Map<*, *>)["telegram"] as Map<*, *>
        assertEquals("http://127.0.0.1:8080/mcp", entry["url"])
    }

    @Test
    fun `an unknown client is rejected by name`() {
        val error = assertFailsWith<IllegalStateException> { ClientConfigPrinter.Client.parse("emacs") }
        assertTrue("emacs" in error.message.orEmpty())
    }

    /** Every locally started form must warn against the hand-started second server. */
    @Test
    fun `notes warn about running the server yourself, except for a shared daemon`() {
        ClientConfigPrinter.Client.entries.forEach { client ->
            val local = ClientConfigPrinter.notes(ClientConfigPrinter.Options(client = client))
            assertTrue(
                local.any { "serve --transport stdio" in it },
                "${client.id} notes must warn against a second server",
            )
            val shared = ClientConfigPrinter.notes(
                ClientConfigPrinter.Options(client = client, httpUrl = "http://127.0.0.1:8080/mcp"),
            )
            assertFalse(
                shared.any { "serve --transport stdio" in it },
                "${client.id} shares a daemon, so that warning does not apply",
            )
            assertTrue(shared.any { "Cowork" in it }, "${client.id} should explain why one daemon is needed")
            assertFalse(
                shared.any { "--writes" in it },
                "${client.id} shares a daemon whose surface this entry does not set",
            )
        }
    }

    @Test
    fun `docker notes explain why the tag is pinned`() {
        val notes = ClientConfigPrinter.notes(ClientConfigPrinter.Options(docker = "example:1.0-stdio"))
        assertTrue(notes.any { "re-pull" in it }, "the staleness trap must be called out: $notes")
    }

    @Test
    fun `arguments select the client, profile and write mode`() {
        val options = TelegramMcpCli.resolveConfigOptions(
            listOf("--client", "codex", "--profile", "inbox", "--api-id", "999", "--writes"),
        )
        assertEquals(ClientConfigPrinter.Client.CODEX, options.client)
        assertEquals("inbox", options.profile)
        assertEquals("999", options.apiId)
        assertFalse(options.readOnly)
    }

    @Test
    fun `the default docker image pins the running version`() {
        val options = TelegramMcpCli.resolveConfigOptions(listOf("--docker", "default"))
        val image = options.docker.orEmpty()
        assertTrue(image.endsWith("-stdio"), "the stdio variant is the one clients should run: $image")
        assertFalse(image.endsWith(":latest-stdio"), "a generated config must not float: $image")
    }

    @Test
    fun `http and docker cannot be combined`() {
        assertFailsWith<IllegalArgumentException> {
            TelegramMcpCli.resolveConfigOptions(listOf("--http", "default", "--docker", "default"))
        }
    }

    @Test
    fun `an unknown profile is rejected before it reaches startup`() {
        assertFailsWith<IllegalArgumentException> {
            TelegramMcpCli.resolveConfigOptions(listOf("--profile", "everything"))
        }
    }

    @Test
    fun `an unknown argument is rejected rather than ignored`() {
        assertFailsWith<IllegalArgumentException> {
            TelegramMcpCli.resolveConfigOptions(listOf("--read-only"))
        }
    }
}
