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

    /** Claude Code and VS Code require a type; Claude Desktop's file has no such key. */
    @Test
    fun `stdio transport type is explicit where required`() {
        val code = ClientConfigPrinter.render(ClientConfigPrinter.Options(client = ClientConfigPrinter.Client.CLAUDE_CODE))
        val vscode = ClientConfigPrinter.render(ClientConfigPrinter.Options(client = ClientConfigPrinter.Client.VSCODE))
        val desktop = ClientConfigPrinter.render(ClientConfigPrinter.Options(client = ClientConfigPrinter.Client.CLAUDE))
        assertTrue("\"type\": \"stdio\"" in code)
        assertTrue("\"type\": \"stdio\"" in vscode)
        assertFalse("\"type\"" in desktop)
    }

    @Test
    fun `vscode declares the http transport type`() {
        val root = parse(
            ClientConfigPrinter.Options(
                client = ClientConfigPrinter.Client.VSCODE,
                httpUrl = "https://mcp.example.com/mcp",
            ),
        )
        val entry = (root["servers"] as Map<*, *>)["telegram"] as Map<*, *>
        assertEquals("http", entry["type"])
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

    @Test
    fun `a docker entry explicitly starts stdio regardless of the image default`() {
        val root = parse(
            ClientConfigPrinter.Options(
                client = ClientConfigPrinter.Client.VSCODE,
                docker = "ghcr.io/tolboy/telegram-mcp-tdlib:1.13.0",
            ),
        )
        val entry = (root["servers"] as Map<*, *>)["telegram"] as Map<*, *>
        val args = entry["args"] as List<*>
        assertEquals(
            listOf("serve", "--transport", "stdio"),
            args.takeLast(3),
            "an ordinary runtime image must not silently start HTTP in an STDIO client",
        )
    }

    @Test
    fun `caller controlled values are escaped without changing windows paths`() {
        val hashPath = """C:\Users\Anatoly\Telegram "production"\api.hash"""
        listOf(ClientConfigPrinter.Client.CLAUDE, ClientConfigPrinter.Client.CODEX).forEach { client ->
            val root = parse(ClientConfigPrinter.Options(client = client, apiHashFile = hashPath))
            val entry = if (client == ClientConfigPrinter.Client.CODEX) {
                (root["mcp_servers"] as Map<*, *>)["telegram"] as Map<*, *>
            } else {
                (root["mcpServers"] as Map<*, *>)["telegram"] as Map<*, *>
            }
            assertEquals(hashPath, (entry["env"] as Map<*, *>)["TDLIB_API_HASH_FILE"])
        }
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
            assertTrue(
                shared.any { "shared HTTP daemon" in it && "competing STDIO" in it },
                "${client.id} should explain why one daemon is needed",
            )
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
        assertTrue(
            notes.any { "TDLIB_API_HASH=<your-api-hash>" in it },
            "the caller must know that Docker still needs an explicitly supplied API hash: $notes",
        )
    }

    @Test
    fun `config writes only the document to stdout and notes to stderr`() {
        val stdout = mutableListOf<String>()
        val stderr = mutableListOf<String>()

        TelegramMcpCli.printClientConfig(
            listOf("--client", "codex"),
            stdout = stdout::add,
            stderr = stderr::add,
        )

        assertEquals(1, stdout.size)
        toml.readValue(stdout.single(), Map::class.java)
        assertFalse(stdout.single().contains("# Codex:"), "human notes must not contaminate the TOML document")
        assertTrue(stderr.any { "# Codex:" in it })
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
        val options = TelegramMcpCli.resolveConfigOptions(
            listOf("--docker", "default"),
            runningVersion = "1.13.0",
        )
        val image = options.docker.orEmpty()
        assertEquals("ghcr.io/tolboy/telegram-mcp-tdlib:1.13.0-stdio", image)
        assertTrue(image.endsWith("-stdio"), "the stdio variant is the one clients should run: $image")
        assertFalse(image.endsWith(":latest-stdio"), "a generated config must not float: $image")
    }

    @Test
    fun `default docker image refuses an unpublished development version`() {
        val error = assertFailsWith<IllegalArgumentException> {
            TelegramMcpCli.resolveConfigOptions(
                listOf("--docker", "default"),
                runningVersion = "1.13.0-2-gabcdef-dirty",
            )
        }
        assertTrue("explicit published image reference" in error.message.orEmpty())
    }

    @Test
    fun `http and docker cannot be combined`() {
        assertFailsWith<IllegalArgumentException> {
            TelegramMcpCli.resolveConfigOptions(listOf("--http", "default", "--docker", "default"))
        }
    }

    @Test
    fun `claude desktop http points to the supported connectors flow`() {
        val error = assertFailsWith<IllegalArgumentException> {
            TelegramMcpCli.resolveConfigOptions(
                listOf("--client", "claude", "--http", "https://mcp.example.com/mcp"),
            )
        }
        assertTrue("Settings > Connectors" in error.message.orEmpty())
        assertTrue("network-reachable" in error.message.orEmpty())
        assertTrue("MCP_AUTH_MODE=oauth" in error.message.orEmpty())
        assertTrue("API-key Authorization header" in error.message.orEmpty())
    }

    @Test
    fun `http rejects local server configuration flags`() {
        listOf(
            listOf("--writes"),
            listOf("--profile", "reader"),
            listOf("--api-id", "123456"),
        ).forEach { conflicting ->
            assertFailsWith<IllegalArgumentException>("must reject ${conflicting.joinToString(" ")}") {
                TelegramMcpCli.resolveConfigOptions(
                    listOf("--client", "codex", "--http", "https://mcp.example.com/mcp") + conflicting,
                )
            }
        }
    }

    /**
     * `auto` in a container promises a fallback it cannot deliver: the page is
     * announced on the container's loopback, which no host browser can open.
     * Pinning `elicitation` fails closed on clients that cannot ask instead.
     */
    @Test
    fun `docker writes use the only approval route a container can serve`() {
        val options = TelegramMcpCli.resolveConfigOptions(
            listOf(
                "--client", "vscode",
                "--profile", "community-admin",
                "--docker", "example.com/telegram-mcp:1.0",
                "--writes",
            ),
        )
        val entry = (parse(options)["servers"] as Map<*, *>)["telegram"] as Map<*, *>
        val args = (entry["args"] as List<*>).map(Any?::toString)

        assertTrue("MCP_DESTRUCTIVE_APPROVAL=elicitation" in args, "$args")
        assertFalse("MCP_DESTRUCTIVE_APPROVAL=auto" in args, "an unreachable loopback page is not a fallback")
        assertTrue(
            ClientConfigPrinter.notes(options).any { "container's own 127.0.0.1" in it },
            "the caller must learn why a container cannot show the loopback page",
        )
    }

    @Test
    fun `a native writes entry keeps the loopback fallback`() {
        val options = TelegramMcpCli.resolveConfigOptions(
            listOf("--client", "vscode", "--profile", "inbox", "--writes"),
        )
        val entry = (parse(options)["servers"] as Map<*, *>)["telegram"] as Map<*, *>

        assertEquals("auto", (entry["env"] as Map<*, *>)["MCP_DESTRUCTIVE_APPROVAL"])
    }

    /**
     * The profile filter runs before the read-only one, so `--writes` with the
     * default profile would emit `MCP_READ_ONLY=false` next to a profile that
     * still hides every write tool — a config that promises what it withholds.
     */
    @Test
    fun `writes must name a profile that contains write tools`() {
        listOf(emptyList(), listOf("--profile", "reader"), listOf("--profile", "research")).forEach { profile ->
            val error = assertFailsWith<IllegalArgumentException>("must reject --writes with $profile") {
                TelegramMcpCli.resolveConfigOptions(listOf("--client", "cursor", "--writes") + profile)
            }
            assertTrue("hides every write tool" in error.message.orEmpty())
        }

        listOf("inbox", "community-admin", "all").forEach { profile ->
            val options = TelegramMcpCli.resolveConfigOptions(
                listOf("--client", "cursor", "--profile", profile, "--writes"),
            )
            assertFalse(options.readOnly, "$profile must keep writes enabled")
        }
    }

    @Test
    fun `api id must fit a positive int`() {
        listOf("0", "-1", "not-a-number", "2147483648").forEach { invalid ->
            assertFailsWith<IllegalArgumentException>("must reject API ID $invalid") {
                TelegramMcpCli.resolveConfigOptions(listOf("--api-id", invalid))
            }
        }
        assertEquals(
            Int.MAX_VALUE.toString(),
            TelegramMcpCli.resolveConfigOptions(listOf("--api-id", Int.MAX_VALUE.toString())).apiId,
        )
    }

    @Test
    fun `http url must be an absolute http or https uri`() {
        listOf(
            "/mcp",
            "mcp.example.com/mcp",
            "ftp://mcp.example.com/mcp",
            "https:/mcp",
            "https://mcp.example.com/a path",
        ).forEach { invalid ->
            assertFailsWith<IllegalArgumentException>("must reject HTTP URL $invalid") {
                TelegramMcpCli.resolveConfigOptions(listOf("--client", "codex", "--http", invalid))
            }
        }
        assertEquals(
            "https://mcp.example.com/mcp",
            TelegramMcpCli.resolveConfigOptions(
                listOf("--client", "codex", "--http", "https://mcp.example.com/mcp"),
            ).httpUrl,
        )
    }

    @Test
    fun `docker image uses a conservative reference grammar`() {
        listOf(
            "example.com/repo/image:tag with-space",
            "--privileged",
            "example.com/repo/image:\u0001tag",
            """example.com\repo\image:tag""",
            "A@@@",
            "repo::tag",
            "repo@",
            "repo/../image",
            "registry.example.com:99999/repo:tag",
        ).forEach { invalid ->
            assertFailsWith<IllegalArgumentException>("must reject Docker image $invalid") {
                TelegramMcpCli.resolveConfigOptions(listOf("--docker", invalid))
            }
        }
        assertEquals(
            "ghcr.io/example/telegram-mcp@sha256:${"ab".repeat(32)}",
            TelegramMcpCli.resolveConfigOptions(
                listOf("--docker", "ghcr.io/example/telegram-mcp@sha256:${"ab".repeat(32)}"),
            ).docker,
        )
        assertEquals(
            "localhost:5000/example/my__image:Release_1",
            TelegramMcpCli.resolveConfigOptions(
                listOf("--docker", "localhost:5000/example/my__image:Release_1"),
            ).docker,
        )
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
