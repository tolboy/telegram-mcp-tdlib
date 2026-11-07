package dev.telegrammcp.server.client

import it.tdlight.jni.TdApi
import dev.telegrammcp.server.exception.SendOutcomeUnknownException
import dev.telegrammcp.server.exception.TelegramApiException
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
    private class PendingOutcome {
        val future = CompletableFuture<Outcome>()
        var users = 0 // accessed only inside waiters.compute
    }

    private sealed interface Outcome {
        data class Success(val message: TdApi.Message) : Outcome
        data class Failure(val error: TdApi.Error) : Outcome
    }

    private val waiters = ConcurrentHashMap<Key, PendingOutcome>()
    private val outcomes = ConcurrentHashMap<Key, TimedOutcome>()
    private val observers = ConcurrentHashMap<Key, CompletableFuture<TdApi.Message>>()

    /** Independent of blocking waiters, so a caller timeout does not discard late delivery. */
    fun observeFinal(message: TdApi.Message): CompletableFuture<TdApi.Message> {
        if (message.sendingState == null) return CompletableFuture.completedFuture(message)
        val key = Key(message.chatId, message.id)
        val future = observers.computeIfAbsent(key) { CompletableFuture<TdApi.Message>().orTimeout(24, TimeUnit.HOURS) }
        future.whenComplete { _, _ -> observers.remove(key, future) }
        knownOutcome(key)?.let { completeObserver(key, it) }
        return future
    }

    private fun completeObserver(key: Key, outcome: Outcome) {
        val observer = observers.remove(key) ?: return
        try { observer.complete(outcome.unwrap(key)) }
        catch (error: Exception) { observer.completeExceptionally(error) }
    }

    fun onSucceeded(update: TdApi.UpdateMessageSendSucceeded) {
        complete(Key(update.message.chatId, update.oldMessageId), Outcome.Success(update.message))
    }

    fun onFailed(update: TdApi.UpdateMessageSendFailed) {
        complete(Key(update.message.chatId, update.oldMessageId), Outcome.Failure(update.error))
    }

    fun awaitFinal(message: TdApi.Message, timeoutSeconds: Long): TdApi.Message =
        awaitFinal(message, TimeUnit.SECONDS.toNanos(timeoutSeconds), deadlineExceeded = false)

    /**
     * Waits up to [timeoutNanos] for the final state of [message]. [deadlineExceeded]
     * marks the resulting [SendOutcomeUnknownException] as caused by the tool
     * call's overall deadline rather than the full send timeout.
     */
    fun awaitFinal(message: TdApi.Message, timeoutNanos: Long, deadlineExceeded: Boolean): TdApi.Message {
        if (message.sendingState == null) return message

        val key = Key(message.chatId, message.id)
        knownOutcome(key)?.let { return it.unwrap(key) }

        val active = waiters.compute(key) { _, previous ->
            (previous ?: PendingOutcome()).also { it.users++ }
        }!!
        knownOutcome(key)?.let { outcome ->
            if (waiters.remove(key, active)) active.future.complete(outcome)
        }

        return try {
            active.future.get(timeoutNanos.coerceAtLeast(0), TimeUnit.NANOSECONDS).unwrap(key)
        } catch (error: ExecutionException) {
            throw error.cause ?: error
        } catch (error: TimeoutException) {
            throw SendOutcomeUnknownException(key.chatId, key.oldMessageId, error, deadlineExceeded)
        } catch (error: InterruptedException) {
            Thread.currentThread().interrupt()
            throw SendOutcomeUnknownException(key.chatId, key.oldMessageId, error)
        } finally {
            // A timed-out caller must not detach another caller's live wait.
            waiters.computeIfPresent(key) { _, pending ->
                if (pending !== active) pending
                else pending.takeIf { --it.users > 0 }
            }
        }
    }

    private fun complete(key: Key, outcome: Outcome) {
        outcomes.entries.removeIf { it.value.isExpired() }
        // Publish before notifying either consumer. An observer/waiter joining
        // during completion must also see the final outcome.
        outcomes[key] = TimedOutcome(outcome, System.nanoTime())
        completeObserver(key, outcome)
        val waiter = waiters.remove(key)
        if (waiter != null) {
            waiter.future.complete(outcome)
        }
    }

    private fun knownOutcome(key: Key): Outcome? =
        outcomes[key]?.takeUnless { it.isExpired() }?.outcome

    private fun TimedOutcome.isExpired(): Boolean =
        System.nanoTime() - createdAtNanos > EARLY_OUTCOME_TTL_NANOS

    private fun Outcome.unwrap(key: Key): TdApi.Message = when (this) {
        is Outcome.Success -> message
        // updateMessageSendFailed is a definite rejection (e.g. a flood wait
        // that hit after the provisional message was created); keep its code
        // so tool errors classify it like a direct request error.
        is Outcome.Failure -> throw TelegramApiException(
            error.code,
            error.message.orEmpty(),
            IllegalStateException("Telegram failed to send message ${key.oldMessageId} in chat ${key.chatId}"),
        )
    }

    private companion object {
        val EARLY_OUTCOME_TTL_NANOS: Long = TimeUnit.MINUTES.toNanos(5)
    }
}
