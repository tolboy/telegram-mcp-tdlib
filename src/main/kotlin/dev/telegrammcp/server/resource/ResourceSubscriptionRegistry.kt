package dev.telegrammcp.server.resource

import org.springframework.security.core.Authentication
import java.time.Duration
import java.time.Instant

/**
 * HTTP subscriptions per MCP session, recorded by [McpResourceSubscriptionFilter].
 *
 * The SDK keeps its own subscription map but exposes no lookup and notifies
 * every subscriber of a URI. This registry keeps the principal that subscribed
 * so each notification is re-authorized for that session alone.
 */
class ResourceSubscriptionRegistry(
    private val maxPerSession: Int = 50,
    private val maxSessions: Int = MAX_SESSIONS,
    private val idleTimeout: Duration = Duration.ofHours(24),
) {
    private class Session(var authentication: Authentication, var lastSeen: Instant) {
        val uris = LinkedHashSet<String>()
    }

    data class Subscriber(val sessionId: String, val authentication: Authentication)

    private val sessions = HashMap<String, Session>()

    /** False when a per-session or global cap would be exceeded. Re-subscribing is always accepted. */
    @Synchronized
    fun canAdd(sessionId: String, uri: String, now: Instant = Instant.now()): Boolean {
        evictIdle(now)
        val session = sessions[sessionId] ?: return sessions.size < maxSessions
        return uri in session.uris || session.uris.size < maxPerSession
    }

    @Synchronized
    fun add(sessionId: String, authentication: Authentication, uri: String, now: Instant = Instant.now()): Boolean {
        if (!canAdd(sessionId, uri, now)) return false
        val session = sessions.getOrPut(sessionId) { Session(authentication, now) }
        // The session filter pins a session to one principal; a refreshed token replaces the stored one.
        if (session.authentication.name != authentication.name) return false
        session.authentication = authentication
        session.lastSeen = now
        session.uris += uri
        return true
    }

    @Synchronized
    fun remove(sessionId: String, uri: String) {
        val session = sessions[sessionId] ?: return
        session.uris -= uri
        if (session.uris.isEmpty()) sessions -= sessionId
    }

    /**
     * Marks the session active. A newer [authentication] for the same principal
     * (a refreshed bearer token) replaces the stored one, so notifications keep
     * being authorized after the token the client subscribed with expires.
     */
    @Synchronized
    fun touch(sessionId: String, authentication: Authentication? = null, now: Instant = Instant.now()) {
        val session = sessions[sessionId] ?: return
        session.lastSeen = now
        if (authentication != null && authentication.name == session.authentication.name) {
            session.authentication = authentication
        }
    }

    @Synchronized
    fun forget(sessionId: String) {
        sessions -= sessionId
    }

    @Synchronized
    fun subscribers(uri: String, now: Instant = Instant.now()): List<Subscriber> {
        evictIdle(now)
        return sessions.filterValues { uri in it.uris }.map { (id, session) -> Subscriber(id, session.authentication) }
    }

    @Synchronized
    fun isSubscribed(uri: String): Boolean = sessions.values.any { uri in it.uris }

    /** Every URI subscribed for [account]; a coverage gap invalidates all of them. */
    @Synchronized
    fun urisForAccount(account: String): Set<String> =
        sessions.values.flatMapTo(LinkedHashSet()) { session ->
            session.uris.filter { TelegramResourceUri.parse(it)?.account == account }
        }

    @Synchronized
    fun sessionCount(): Int = sessions.size

    private fun evictIdle(now: Instant) {
        sessions.entries.removeIf { it.value.lastSeen.plus(idleTimeout) < now }
    }

    companion object {
        const val MAX_SESSIONS = 4096
    }
}
