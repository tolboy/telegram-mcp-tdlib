package dev.telegrammcp.server.client

import dev.telegrammcp.server.security.SensitiveChats
import dev.telegrammcp.server.util.StructuredLogger
import it.tdlight.jni.TdApi
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

/**
 * Resolves the sensitive bots by username once an account is ready and adds
 * their chat IDs to [SensitiveChats]. The production IDs are built in; this
 * covers the test DC and any future change of ID. Lookups run asynchronously
 * and a failure only logs: the built-in IDs stay blocked regardless.
 */
internal object SensitiveChatDiscovery {
    private val log = StructuredLogger.forClass<SensitiveChatDiscovery>()

    fun discover(label: String, search: (String) -> CompletableFuture<TdApi.Chat>) {
        SensitiveChats.BOT_USERNAMES.forEach { username ->
            val lookup = try {
                search(username)
            } catch (error: Exception) {
                CompletableFuture.failedFuture(error)
            }
            lookup.orTimeout(LOOKUP_TIMEOUT_SECONDS, TimeUnit.SECONDS).whenComplete { chat, error ->
                when {
                    error != null ->
                        log.debug("Account '{}' could not resolve @{}: {}", label, username, error.message)
                    accept(chat) && SensitiveChats.registerResolved(chat.id) ->
                        log.info("Account '{}' resolved @{} to chat {}; blocked as a sensitive chat", label, username, chat.id)
                }
            }
        }
    }

    /** Only a private chat whose ID is the bot's own user ID is accepted. */
    fun accept(chat: TdApi.Chat?): Boolean {
        val type = chat?.type as? TdApi.ChatTypePrivate ?: return false
        return chat.id > 0 && chat.id == type.userId
    }

    private const val LOOKUP_TIMEOUT_SECONDS = 30L
}
