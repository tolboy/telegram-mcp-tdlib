package dev.telegrammcp.server.resource

import org.junit.jupiter.api.Test
import org.springframework.security.authentication.TestingAuthenticationToken
import java.time.Duration
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ResourceSubscriptionRegistryTest {
    private val alice = TestingAuthenticationToken("alice", null, "ROLE_X")
    private val bob = TestingAuthenticationToken("bob", null, "ROLE_X")
    private val t0 = Instant.parse("2026-11-01T00:00:00Z")

    @Test fun `subscribers are per session and per principal`() {
        val registry = ResourceSubscriptionRegistry()
        assertTrue(registry.add("s1", alice, "telegram://a/chat/1", t0))
        assertTrue(registry.add("s2", bob, "telegram://a/chat/1", t0))
        assertFalse(registry.add("s1", bob, "telegram://a/chat/2", t0), "a session never changes principal")
        assertEquals(setOf("s1" to "alice", "s2" to "bob"),
            registry.subscribers("telegram://a/chat/1", t0).map { it.sessionId to it.authentication.name }.toSet())
        registry.remove("s1", "telegram://a/chat/1")
        assertEquals(listOf("s2"), registry.subscribers("telegram://a/chat/1", t0).map { it.sessionId })
        assertEquals(1, registry.sessionCount())
        registry.forget("s2")
        assertFalse(registry.isSubscribed("telegram://a/chat/1"))
    }

    @Test fun `caps bound sessions and subscriptions`() {
        val registry = ResourceSubscriptionRegistry(maxPerSession = 2, maxSessions = 1)
        assertTrue(registry.add("s1", alice, "telegram://a/chat/1", t0))
        assertTrue(registry.add("s1", alice, "telegram://a/chat/2", t0))
        assertTrue(registry.add("s1", alice, "telegram://a/chat/2", t0), "re-subscribing is idempotent")
        assertFalse(registry.add("s1", alice, "telegram://a/chat/3", t0))
        assertFalse(registry.canAdd("s2", "telegram://a/chat/1", t0))
    }

    @Test fun `idle sessions expire and activity keeps them`() {
        val registry = ResourceSubscriptionRegistry(idleTimeout = Duration.ofMinutes(10))
        registry.add("s1", alice, "telegram://a/chat/1", t0)
        registry.add("s2", bob, "telegram://a/chat/1", t0)
        registry.touch("s1", now = t0.plus(Duration.ofMinutes(8)))
        assertEquals(listOf("s1"), registry.subscribers("telegram://a/chat/1", t0.plus(Duration.ofMinutes(12))).map { it.sessionId })
    }

    @Test fun `gaps cover every subscribed URI of the account`() {
        val registry = ResourceSubscriptionRegistry()
        registry.add("s1", alice, "telegram://a/chat/1", t0)
        registry.add("s1", alice, "telegram://a/chats", t0)
        registry.add("s2", bob, "telegram://b/chat/1", t0)
        assertEquals(setOf("telegram://a/chat/1", "telegram://a/chats"), registry.urisForAccount("a"))
    }
}
