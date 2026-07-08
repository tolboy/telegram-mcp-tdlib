package dev.telegrammcp.server.service

import com.github.benmanes.caffeine.cache.Caffeine
import dev.telegrammcp.server.client.TelegramAccountContext
import dev.telegrammcp.server.client.TelegramClientService
import dev.telegrammcp.server.exception.EntityNotFoundException
import dev.telegrammcp.server.model.EntityIdentifier
import dev.telegrammcp.server.util.StructuredLogger
import org.springframework.stereotype.Service
import java.time.Duration

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
        val cacheKey = "${accountScope()}|username:$username"
        cache.getIfPresent(cacheKey)?.let {
            log.debug("Cache hit for @{} → {}", username, it)
            return it
        }

        return try {
            val chatId = telegramClient.resolveUsername(username)
            cache.put(cacheKey, chatId)
            log.debug("Resolved @{} → {}", username, chatId)
            chatId
        } catch (_: Exception) {
            throw EntityNotFoundException("@$username")
        }
    }

    private fun resolvePhone(phone: String): Long {
        val cacheKey = "${accountScope()}|phone:$phone"
        cache.getIfPresent(cacheKey)?.let {
            log.debug("Cache hit for phone → {}", it)
            return it
        }

        return try {
            val userId = telegramClient.resolvePhone(phone)
            cache.put(cacheKey, userId)
            log.debug("Resolved phone → {}", userId)
            userId
        } catch (_: Exception) {
            throw EntityNotFoundException(phone)
        }
    }

    private fun resolveSelfChat(identifier: String): Long {
        val cacheKey = "${accountScope()}|self"
        cache.getIfPresent(cacheKey)?.let {
            log.debug("Cache hit for self chat '{}' → {}", identifier, it)
            return it
        }

        return try {
            val chatId = telegramClient.resolveSelfChat()
            cache.put(cacheKey, chatId)
            log.debug("Resolved self chat '{}' → {}", identifier, chatId)
            chatId
        } catch (_: Exception) {
            throw EntityNotFoundException(identifier)
        }
    }

    private fun accountScope(): String =
        runCatching { accountContext?.currentAccount() }.getOrNull() ?: "default"
}
