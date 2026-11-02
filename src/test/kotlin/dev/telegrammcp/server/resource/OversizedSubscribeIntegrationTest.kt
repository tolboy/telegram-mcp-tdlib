package dev.telegrammcp.server.resource

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import io.modelcontextprotocol.server.McpSyncServer
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.core.env.Environment
import org.springframework.test.context.ActiveProfiles
import java.io.ByteArrayInputStream
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Real HTTP and the real SDK, no TDLib: a subscribe padded past 1 MiB, with or
 * without Content-Length, must not reach the SDK. Adapted from the external
 * review's reproducer.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = [
        "mcp.resources.enabled=true",
        "mcp.resources.max-subscriptions-per-session=1",
        "mcp.security.api-key=oversized-test-key",
    ],
)
@ActiveProfiles("test")
class OversizedSubscribeIntegrationTest {
    @Autowired lateinit var environment: Environment
    @Autowired lateinit var registry: ResourceSubscriptionRegistry
    @Autowired lateinit var server: McpSyncServer
    private val http = HttpClient.newHttpClient()
    private val mapper = jacksonObjectMapper()

    private fun post(body: String, session: String? = null, chunked: Boolean = false): HttpResponse<String> {
        val bytes = body.toByteArray()
        val publisher = if (chunked) {
            HttpRequest.BodyPublishers.ofInputStream { ByteArrayInputStream(bytes) } // no Content-Length
        } else {
            HttpRequest.BodyPublishers.ofByteArray(bytes)
        }
        val request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:${environment.getProperty("local.server.port")}/mcp"))
            .header("Authorization", "Bearer oversized-test-key")
            .header("Content-Type", "application/json")
            .header("Accept", "application/json, text/event-stream")
            .POST(publisher)
        if (session != null) request.header("Mcp-Session-Id", session)
        return http.send(request.build(), HttpResponse.BodyHandlers.ofString())
    }

    private fun sdkSubscriptions(): Set<Any?> {
        val field = server.asyncServer.javaClass.getDeclaredField("resourceSubscriptions").apply { isAccessible = true }
        return (field.get(server.asyncServer) as Map<*, *>).keys
    }

    @Test fun `oversized subscribes are refused before the SDK, with and without Content-Length`() {
        val init = post("""{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-06-18","capabilities":{},"clientInfo":{"name":"test","version":"1"}}}""")
        assertEquals(200, init.statusCode())
        val session = init.headers().firstValue("mcp-session-id").orElseThrow()
        post("""{"jsonrpc":"2.0","method":"notifications/initialized"}""", session)
        val allowed = post("""{"jsonrpc":"2.0","id":2,"method":"resources/subscribe","params":{"uri":"telegram://default/chats"}}""", session)
        assertEquals(200, allowed.statusCode())
        assertTrue(registry.isSubscribed("telegram://default/chats"))

        val forbiddenUri = "telegram://unknown/chat/42"
        val ordinary = post("""{"jsonrpc":"2.0","id":3,"method":"resources/subscribe","params":{"uri":"$forbiddenUri"}}""", session)
        assertTrue(ordinary.body().contains("-32002"))

        for ((id, chunked) in listOf(4 to false, 5 to true)) {
            val padded = mapper.writeValueAsString(mapOf("jsonrpc" to "2.0", "id" to id, "method" to "resources/subscribe",
                "params" to mapOf("uri" to forbiddenUri))) + " ".repeat(1_048_576)
            val response = post(padded, session, chunked)
            assertEquals(413, response.statusCode(), "chunked=$chunked")
            assertEquals(-32600, mapper.readTree(response.body()).path("error").path("code").asInt(), "chunked=$chunked")
            assertFalse(forbiddenUri in sdkSubscriptions(), "chunked=$chunked: the SDK recorded an unauthorized subscription")
        }
        assertTrue(registry.isSubscribed("telegram://default/chats"))
    }
}
