package dev.telegrammcp.server.service

import com.github.benmanes.caffeine.cache.Caffeine
import dev.telegrammcp.server.client.TelegramAccountContext
import dev.telegrammcp.server.client.TelegramClientService
import dev.telegrammcp.server.exception.EntityNotFoundException
import dev.telegrammcp.server.exception.TelegramApiException
import dev.telegrammcp.server.exception.TelegramErrorKind
import dev.telegrammcp.server.model.EntityIdentifier
import dev.telegrammcp.server.util.StructuredLogger
import org.springframework.stereotype.Service
import java.time.Duration
import java.util.Locale

/**
 * Resolves any [EntityIdentifier] (numeric ID, @username, +phone, canonical self chat) to a
 * numeric Telegram chat/user ID.
 *
 * Results are cached with a 10-minute TTL to avoid redundant TDLib calls.
 * Usernames may change, so the cache has a reasonable expiry.
 *
 * Cache keys are scoped to the Telegram account selected for the current MCP
 * call: [telegramClient] routes per account, and identifiers such as the
 * canonical `self` chat resolve to *different* IDs per account. A shared key
 * would leak one account's resolution into another.
 */
@Service
class EntityResolverService(
    private val telegramClient: TelegramClientService,
    private val accountContext: TelegramAccountContext? = null,
) {

    private val log = StructuredLogger.forClass<EntityResolverService>()

    /** Cache: account-scoped identifier string → resolved numeric ID. */
    private val cache = Caffeine.newBuilder()
        .maximumSize(1_000)
        .expireAfterWrite(Duration.ofMinutes(10))
        .build<String, Long>()

    /**
     * Resolves the given identifier to a numeric Telegram ID.
     *
     * - [EntityIdentifier.NumericId] — returned as-is
     * - [EntityIdentifier.Username] — resolved via TDLib `searchPublicChat`
     * - [EntityIdentifier.PhoneNumber] — resolved via TDLib `searchUserByPhoneNumber`
     * - [EntityIdentifier.SelfChat] — resolved to the current account's Saved Messages chat via TDLib
     *
     * @throws EntityNotFoundException if the identifier cannot be resolved
     */
    fun resolve(identifier: EntityIdentifier): Long {
        return when (identifier) {
            is EntityIdentifier.NumericId -> identifier.id
            is EntityIdentifier.Username -> resolveUsername(identifier.username)
            is EntityIdentifier.PhoneNumber -> resolvePhone(identifier.phone)
            is EntityIdentifier.SelfChat -> resolveSelfChat(identifier.identifier)
        }
    }

    /**
     * Convenience: parses [raw] into an [EntityIdentifier] and resolves it.
     */
    fun resolve(raw: Any): Long {
        val identifier = EntityIdentifier.parse(raw)
        return resolve(identifier)
    }

    private fun resolveUsername(username: String): Long {
        // Telegram usernames are case-insensitive; alternate casing shares a TTL.
        return resolveCached("username:${username.lowercase(Locale.ROOT)}", "@$username") {
            telegramClient.resolveUsername(username)
        }
    }

    private fun resolvePhone(phone: String): Long =
        resolveCached("phone:$phone", phone) {
            telegramClient.resolvePhone(phone)
        }

    private fun resolveSelfChat(identifier: String): Long =
        resolveCached("self", identifier) {
            telegramClient.resolveSelfChat()
        }

    private fun resolveCached(identifierKey: String, displayIdentifier: String, resolve: () -> Long): Long {
        // A configured context must select an account even for cache hits.
        val cacheKey = "${accountScope()}|$identifierKey"
        cache.getIfPresent(cacheKey)?.let {
            log.debug("Entity resolution cache hit → {}", it)
            return it
        }
        return try {
            resolve().also { cache.put(cacheKey, it) }
        } catch (error: TelegramApiException) {
            // Preserve auth, permission, flood-wait and timeout semantics for callers.
            if (error.kind == TelegramErrorKind.NOT_FOUND) throw EntityNotFoundException(displayIdentifier)
            throw error
        }
    }

    private fun accountScope(): String =
        accountContext?.currentAccount() ?: "default"
}
