package dev.telegrammcp.server.cli

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * A generated config is only useful if it can be pasted without editing, so
 * these cases check the properties a reader cannot verify by eye: that it
 * parses, that the client-specific key is right, and that the safe defaults
 * survive.
 */
class ClientConfigPrinterTest {

    private val mapper = jacksonObjectMapper()

    private fun parse(options: ClientConfigPrinter.Options): Map<*, *> =
        mapper.readValue(ClientConfigPrinter.render(options), Map::class.java)

    @Test
    fun `every client and invocation renders parseable JSON`() {
        ClientConfigPrinter.Client.entries.forEach { client ->
            listOf(null, "ghcr.io/tolboy/telegram-mcp-tdlib:1.11.0-stdio").forEach { image ->
                listOf(true, false).forEach { readOnly ->
                    val options = ClientConfigPrinter.Options(
                        client = client,
                        readOnly = readOnly,
                        docker = image,
                    )
                    val root = parse(options)
                    assertTrue(
                        root.containsKey(client.rootKey()),
                        "${client.id} (docker=$image, readOnly=$readOnly) must use ${client.rootKey()}",
                    )
                }
            }
        }
    }

    /** An `mcpServers` block is ignored by VS Code, which looks like a broken server. */
    @Test
    fun `vscode uses servers and the others use mcpServers`() {
        assertTrue(parse(ClientConfigPrinter.Options(client = ClientConfigPrinter.Client.VSCODE)).containsKey("servers"))
        assertTrue(parse(ClientConfigPrinter.Options(client = ClientConfigPrinter.Client.CLAUDE)).containsKey("mcpServers"))
        assertTrue(parse(ClientConfigPrinter.Options(client = ClientConfigPrinter.Client.CURSOR)).containsKey("mcpServers"))
    }

    @Test
    fun `the default entry is read-only and does not gate tools it never registers`() {
        val rendered = ClientConfigPrinter.render(ClientConfigPrinter.Options())
        assertTrue("\"MCP_READ_ONLY\": \"true\"" in rendered)
        assertTrue("\"MCP_TOOL_PROFILE\": \"reader\"" in rendered)
        assertFalse("MCP_DESTRUCTIVE_APPROVAL" in rendered, "approval is meaningless with no write tools")
    }

    @Test
    fun `enabling writes also asks for approval`() {
        val rendered = ClientConfigPrinter.render(ClientConfigPrinter.Options(readOnly = false))
        assertTrue("\"MCP_READ_ONLY\": \"false\"" in rendered)
        assertTrue("\"MCP_DESTRUCTIVE_APPROVAL\": \"elicitation\"" in rendered)
    }

    @Test
    fun `a docker entry carries settings as -e flags and never bakes in the api hash`() {
        val rendered = ClientConfigPrinter.render(
            ClientConfigPrinter.Options(docker = "ghcr.io/tolboy/telegram-mcp-tdlib:1.11.0-stdio", readOnly = false),
        )
        assertTrue("\"MCP_READ_ONLY=false\"" in rendered)
        assertTrue("\"MCP_DESTRUCTIVE_APPROVAL=elicitation\"" in rendered)
        assertTrue("<your-api-hash>" in rendered, "the hash must stay a placeholder, not a real value")
    }

    @Test
    fun `an unknown client is rejected by name`() {
        val error = assertFailsWith<IllegalStateException> { ClientConfigPrinter.Client.parse("emacs") }
        assertTrue("emacs" in error.message.orEmpty())
    }

    /** Every rendered form must warn against the hand-started second server. */
    @Test
    fun `notes always warn about running the server yourself`() {
        ClientConfigPrinter.Client.entries.forEach { client ->
            val notes = ClientConfigPrinter.notes(ClientConfigPrinter.Options(client = client))
            assertTrue(
                notes.any { "serve --transport stdio" in it },
                "${client.id} notes must warn against a second server",
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
            listOf("--client", "vscode", "--profile", "inbox", "--api-id", "999", "--writes"),
        )
        assertEquals(ClientConfigPrinter.Client.VSCODE, options.client)
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
