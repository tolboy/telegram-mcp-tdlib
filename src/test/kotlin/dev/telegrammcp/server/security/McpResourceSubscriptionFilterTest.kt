package dev.telegrammcp.server.security

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import dev.telegrammcp.server.resource.ResourceSubscriptionRegistry
import dev.telegrammcp.server.resource.TelegramResourceAccess
import io.mockk.every
import io.mockk.mockk
import jakarta.servlet.FilterChain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.security.authentication.TestingAuthenticationToken
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class McpResourceSubscriptionFilterTest {
    private val mapper = jacksonObjectMapper()
    private val registry = ResourceSubscriptionRegistry(maxPerSession = 2)
    private val access = mockk<TelegramResourceAccess>()
    private val filter = McpResourceSubscriptionFilter(registry, access)
    private val alice = TestingAuthenticationToken("alice", null, "ROLE_X")
    private var forwardedBody: String? = null
    private var downstreamStatus = 200

    @BeforeEach fun setUp() {
        SecurityContextHolder.getContext().authentication = alice
        every { access.permits(any()) } answers { firstArg<Any>().toString().endsWith("/42") || firstArg<Any>().toString().endsWith("/chats") }
    }

    @AfterEach fun tearDown() = SecurityContextHolder.clearContext()

    private val chain = FilterChain { request, response ->
        forwardedBody = request.inputStream.readAllBytes().decodeToString()
        (response as jakarta.servlet.http.HttpServletResponse).status = downstreamStatus
    }

    private fun post(body: String, session: String? = "s1"): MockHttpServletResponse {
        val request = MockHttpServletRequest("POST", "/mcp").apply {
            setContent(body.toByteArray())
            contentType = "application/json"
            session?.let { addHeader("Mcp-Session-Id", it) }
        }
        forwardedBody = null
        return MockHttpServletResponse().also { filter.doFilter(request, it, chain) }
    }

    private fun subscribe(uri: String, id: Int = 1) =
        """{"jsonrpc":"2.0","id":$id,"method":"resources/subscribe","params":{"uri":"$uri"}}"""

    @Test fun `an authorized subscription is forwarded intact and recorded for the caller`() {
        val body = subscribe("telegram://default/chat/42")
        post(body)
        assertEquals(body, forwardedBody)
        assertEquals(listOf("s1" to "alice"), registry.subscribers("telegram://default/chat/42").map { it.sessionId to it.authentication.name })
    }

    @Test fun `unauthorized, unknown and non-canonical URIs get one generic error and never reach the SDK`() {
        val bodies = listOf("telegram://default/chat/43", "telegram://default/chat/042", "file:///etc/passwd").map { uri ->
            val response = post(subscribe(uri, 7))
            assertEquals(null, forwardedBody)
            assertEquals(200, response.status)
            val json = mapper.readTree(response.contentAsString)
            assertEquals(7, json.path("id").asInt())
            json.path("error").toString()
        }
        assertEquals(1, bodies.toSet().size)
        assertTrue("-32002" in bodies.first())
        assertFalse(registry.isSubscribed("telegram://default/chat/43"))
    }

    @Test fun `failed SDK responses are not recorded`() {
        downstreamStatus = 500
        post(subscribe("telegram://default/chat/42"))
        assertFalse(registry.isSubscribed("telegram://default/chat/42"))
    }

    @Test fun `per-session limit is enforced before the SDK sees the request`() {
        post(subscribe("telegram://default/chat/42"))
        post(subscribe("telegram://default/chats"))
        every { access.permits(any()) } returns true
        val response = post(subscribe("telegram://default/chat/1/message/42"))
        assertEquals(null, forwardedBody)
        assertEquals(-32602, mapper.readTree(response.contentAsString).path("error").path("code").asInt())
    }

    @Test fun `unsubscribe removes the record and batches are refused`() {
        post(subscribe("telegram://default/chat/42"))
        post("""{"jsonrpc":"2.0","id":2,"method":"resources/unsubscribe","params":{"uri":"telegram://default/chat/42"}}""")
        assertFalse(registry.isSubscribed("telegram://default/chat/42"))
        val response = post("[${subscribe("telegram://default/chat/42")}]")
        assertEquals(null, forwardedBody)
        assertEquals(-32600, mapper.readTree(response.contentAsString).path("error").path("code").asInt())
    }

    @Test fun `other traffic passes through and a vanished session is forgotten`() {
        post(subscribe("telegram://default/chat/42"))
        val body = """{"jsonrpc":"2.0","id":3,"method":"tools/list"}"""
        post(body)
        assertEquals(body, forwardedBody)
        assertTrue(registry.isSubscribed("telegram://default/chat/42"))
        downstreamStatus = 404
        post(body)
        assertFalse(registry.isSubscribed("telegram://default/chat/42"))
    }

    @Test fun `bodies over 1 MiB are refused before the SDK, with or without Content-Length`() {
        val padded = subscribe("telegram://default/chat/43") + " ".repeat(1_048_576)
        for (chunked in listOf(false, true)) {
            val request = object : MockHttpServletRequest("POST", "/mcp") {
                override fun getContentLengthLong(): Long = if (chunked) -1 else super.getContentLengthLong()
                override fun getContentLength(): Int = if (chunked) -1 else super.getContentLength()
            }.apply {
                setContent(padded.toByteArray())
                contentType = "application/json"
                addHeader("Mcp-Session-Id", "s1")
            }
            forwardedBody = null
            val response = MockHttpServletResponse().also { filter.doFilter(request, it, chain) }
            assertEquals(null, forwardedBody, "chunked=$chunked")
            assertEquals(413, response.status, "chunked=$chunked")
            val json = mapper.readTree(response.contentAsString)
            assertEquals(-32600, json.path("error").path("code").asInt())
            assertTrue(json.path("id").isNull)
        }
        assertFalse(registry.isSubscribed("telegram://default/chat/43"))
    }

    @Test fun `a body of exactly 1 MiB is still read and authorized`() {
        val body = subscribe("telegram://default/chat/42")
        val padded = body + " ".repeat(1_048_576 - body.length)
        post(padded)
        assertEquals(padded, forwardedBody)
        assertTrue(registry.isSubscribed("telegram://default/chat/42"))
    }

    @Test fun `any request with a refreshed token updates the identity notifications are checked with`() {
        val now = Instant.now()
        fun jwt(token: String, subject: String, expiry: Instant) = JwtAuthenticationToken(
            Jwt.withTokenValue(token).header("alg", "none").subject(subject)
                .issuedAt(now.minusSeconds(120)).expiresAt(expiry).build(),
            emptyList(),
        )
        val uri = "telegram://default/chats"
        registry.add("s1", jwt("old", "alice", now.minusSeconds(1)), uri)
        SecurityContextHolder.getContext().authentication = jwt("fresh", "alice", now.plusSeconds(3600))
        post("""{"jsonrpc":"2.0","id":1,"method":"resources/read","params":{"uri":"$uri"}}""")
        assertEquals("fresh", (registry.subscribers(uri).single().authentication as JwtAuthenticationToken).token.tokenValue)

        // A different principal on the same session id never takes the subscription over.
        SecurityContextHolder.getContext().authentication = jwt("other", "mallory", now.plusSeconds(3600))
        post("""{"jsonrpc":"2.0","id":2,"method":"tools/list"}""")
        assertEquals("fresh", (registry.subscribers(uri).single().authentication as JwtAuthenticationToken).token.tokenValue)
    }

    @Test fun `requests without a session are untouched`() {
        val body = subscribe("telegram://default/chat/43")
        post(body, session = null)
        assertEquals(body, forwardedBody)
    }
}
