package dev.telegrammcp.server.client

import it.tdlight.jni.TdApi
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/**
 * Resolves TDLib's provisional outbound message IDs to their final server IDs.
 *
 * Send functions initially return a message whose ID is replaced asynchronously
 * by [TdApi.UpdateMessageSendSucceeded]. Exposing that provisional ID makes an
 * immediate follow-up edit, delete, pin, or reaction fail.
 */
class MessageSendTracker {
    private data class Key(val chatId: Long, val oldMessageId: Long)
    private data class TimedOutcome(val outcome: Outcome, val createdAtNanos: Long)

    private sealed interface Outcome {
        data class Success(val message: TdApi.Message) : Outcome
        data class Failure(val error: TdApi.Error) : Outcome
    }

    private val waiters = ConcurrentHashMap<Key, CompletableFuture<Outcome>>()
    private val earlyOutcomes = ConcurrentHashMap<Key, TimedOutcome>()

    fun onSucceeded(update: TdApi.UpdateMessageSendSucceeded) {
        complete(Key(update.message.chatId, update.oldMessageId), Outcome.Success(update.message))
    }

    fun onFailed(update: TdApi.UpdateMessageSendFailed) {
        complete(Key(update.message.chatId, update.oldMessageId), Outcome.Failure(update.error))
    }

    fun awaitFinal(message: TdApi.Message, timeoutSeconds: Long): TdApi.Message {
        if (message.sendingState == null) return message

        val key = Key(message.chatId, message.id)
        consumeEarly(key)?.let { return it.unwrap(key) }

        val waiter = CompletableFuture<Outcome>()
        val active = waiters.putIfAbsent(key, waiter) ?: waiter
        consumeEarly(key)?.let { outcome ->
            if (waiters.remove(key, active)) active.complete(outcome)
        }

        return try {
            active.get(timeoutSeconds, TimeUnit.SECONDS).unwrap(key)
        } catch (error: ExecutionException) {
            throw error.cause ?: error
        } catch (error: TimeoutException) {
            throw IllegalStateException(
                "Timed out waiting for Telegram to finalize message ${key.oldMessageId} in chat ${key.chatId}",
                error,
            )
        } finally {
            waiters.remove(key, active)
            earlyOutcomes.remove(key)
        }
    }

    private fun complete(key: Key, outcome: Outcome) {
        val waiter = waiters.remove(key)
        if (waiter != null) {
            waiter.complete(outcome)
        } else {
            purgeExpiredOutcomes()
            earlyOutcomes[key] = TimedOutcome(outcome, System.nanoTime())
        }
    }

    private fun consumeEarly(key: Key): Outcome? =
        earlyOutcomes.remove(key)?.takeUnless { it.isExpired() }?.outcome

    private fun purgeExpiredOutcomes() {
        earlyOutcomes.entries.removeIf { it.value.isExpired() }
    }

    private fun TimedOutcome.isExpired(): Boolean =
        System.nanoTime() - createdAtNanos > EARLY_OUTCOME_TTL_NANOS

    private fun Outcome.unwrap(key: Key): TdApi.Message = when (this) {
        is Outcome.Success -> message
        is Outcome.Failure -> throw IllegalStateException(
            "Telegram failed to send message ${key.oldMessageId} in chat ${key.chatId} " +
            "(${error.code}): ${error.message}",
        )
    }

    private companion object {
        val EARLY_OUTCOME_TTL_NANOS: Long = TimeUnit.MINUTES.toNanos(5)
    }
}
