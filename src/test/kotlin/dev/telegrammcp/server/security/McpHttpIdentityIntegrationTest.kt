package dev.telegrammcp.server.security

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import dev.telegrammcp.server.client.TelegramAccountRegistry
import dev.telegrammcp.server.client.TelegramAccountContext
import dev.telegrammcp.server.tool.McpToolHandler
import io.mockk.mockk
import io.modelcontextprotocol.client.McpClient
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport
import io.modelcontextprotocol.server.McpSyncServerExchange
import io.modelcontextprotocol.spec.McpSchema
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.core.env.Environment
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.time.Duration
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Real servlet/SSE + SDK execution boundary; never calls Telegram. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = [
    "spring.ai.mcp.server.streamable-http.disallow-delete=false",
    "mcp.security.clients[0].id=reader", "mcp.security.clients[0].api-key=reader-key",
    "mcp.security.clients[0].allowed-accounts[0]=default",
    "mcp.security.clients[1].id=writer", "mcp.security.clients[1].api-key=writer-key",
    "mcp.security.clients[1].allowed-accounts[0]=personal",
])
@ActiveProfiles("test")
@Import(McpHttpIdentityIntegrationTest.Probes::class)
class McpHttpIdentityIntegrationTest {
    @Autowired lateinit var environment: Environment
    @Autowired lateinit var accounts: TelegramAccountRegistry
    private val mapper = jacksonObjectMapper()
    private val baseUrl get() = "http://127.0.0.1:${environment.getProperty("local.server.port")}"

    @Test fun `real HTTP preserves principals scopes and filtered SSE listings`() {
        if (!accounts.has("personal")) accounts.register(TelegramAccountRegistry.AccountHandle("personal", mockk(relaxed = true)))
        fun client(key: String) = McpClient.sync(HttpClientStreamableHttpTransport.builder(baseUrl)
            .requestBuilder(HttpRequest.newBuilder().header("Authorization", "Bearer $key"))
            .build()).requestTimeout(Duration.ofSeconds(10)).build()
        val reader = client("reader-key")
        val writer = client("writer-key")
        try {
            reader.initialize()
            writer.initialize()
            val names = reader.listTools().tools().map { it.name() }
            assertTrue("identity_probe" in names)
            assertTrue("get_history" in names)
            assertFalse("send_message" in names, "A mutate grant outside the key's account scope must not advertise writes")
            fun probe(client: io.modelcontextprotocol.client.McpSyncClient, account: String): McpSchema.CallToolResult =
                client.callTool(McpSchema.CallToolRequest("identity_probe", mapOf("account" to account)))
            fun identity(result: McpSchema.CallToolResult): List<String> {
                assertFalse(result.isError() == true)
                val text = (result.content().first() as McpSchema.TextContent).text()
                val data = mapper.readTree(text)
                return listOf(data.path("client").asText(), data.path("account").asText())
            }
            assertEquals(listOf("reader", "default"), identity(probe(reader, "default")))
            assertTrue(probe(reader, "personal").isError() == true, "Key scope must survive SDK dispatch")
            assertEquals(listOf("writer", "personal"), identity(probe(writer, "personal")))
            assertEquals(listOf("reader", "default"), identity(probe(reader, "default")))
            assertTrue(reader.callTool(McpSchema.CallToolRequest("identity_probe", mapOf("account" to "default"),
                mapOf("progressToken" to true))).isError() != true)
        } finally { reader.closeGracefully(); writer.closeGracefully() }
    }

    @Test fun `initialize immediately owns session and foreign DELETE never reaches transport`() {
        HttpClient.newHttpClient().use { http ->
            val initialized = http.send(HttpRequest.newBuilder(URI("$baseUrl/mcp"))
                .header("Authorization", "Bearer reader-key")
                .header("Accept", "application/json, text/event-stream")
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("""{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-06-18","capabilities":{},"clientInfo":{"name":"test","version":"1"}}}"""))
                .build(), HttpResponse.BodyHandlers.ofString())
            assertEquals(200, initialized.statusCode())
            val id = initialized.headers().firstValue("Mcp-Session-Id").orElseThrow()
            fun delete(key: String, sessionId: String = id): Int = http.send(HttpRequest.newBuilder(URI("$baseUrl/mcp"))
                .header("Authorization", "Bearer $key").header("Mcp-Session-Id", sessionId)
                .DELETE().build(), HttpResponse.BodyHandlers.ofString()).statusCode()
            assertEquals(404, delete("reader-key", "unissued-session"))
            assertEquals(403, delete("writer-key"))
            assertEquals(200, delete("reader-key"))
            assertEquals(404, delete("reader-key"))
        }
    }

    @TestConfiguration
    class Probes {
        @Bean fun identityProbe(accountContext: TelegramAccountContext): McpToolHandler = object : McpToolHandler {
            override fun definition(): McpSchema.Tool = McpSchema.Tool.builder().name("identity_probe")
                .description("Tests server-validated HTTP identity")
                .inputSchema(McpSchema.JsonSchema("object", mapOf("account" to mapOf("type" to "string",
                    "enum" to listOf("default", "personal"))), emptyList(), false, emptyMap(), emptyMap())).build()
            override fun execute(exchange: McpSyncServerExchange, arguments: Map<String, Any>): McpSchema.CallToolResult {
                val account = accountContext.currentAccount()
                return McpSchema.CallToolResult.builder().addTextContent(jacksonObjectMapper().writeValueAsString(mapOf(
                    "client" to AccessPermissionService.clientIdentity(), "account" to account))).build()
            }
        }
    }

    companion object {
        private val permissions = Files.createTempFile("mcp-http-permissions-", ".json").also {
            Files.writeString(it, jacksonObjectMapper().writeValueAsString(PermissionDocument(1, listOf(
                PermissionGrant("reader", "default", setOf("read"), null),
                PermissionGrant("reader", "personal", setOf("mutate"), null),
                PermissionGrant("writer", "personal", setOf("read"), null),
            ))))
            it.toFile().deleteOnExit()
        }
        @JvmStatic @DynamicPropertySource
        fun properties(registry: DynamicPropertyRegistry) { registry.add("mcp.permissions-file") { permissions.toString() } }
    }
}
