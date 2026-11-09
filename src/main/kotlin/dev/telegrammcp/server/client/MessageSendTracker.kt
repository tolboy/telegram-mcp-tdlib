package dev.telegrammcp.server.client

import it.tdlight.jni.TdApi
import dev.telegrammcp.server.exception.SendOutcomeUnknownException
import dev.telegrammcp.server.exception.TelegramApiException
import java.util.concurrent.CancellationException
import java.util.concurrent.CompletableFuture
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
class MessageSendTracker : AutoCloseable {
    private data class Key(val chatId: Long, val oldMessageId: Long)
    private data class TimedOutcome(val outcome: Outcome, val createdAtNanos: Long)
    private class PendingOutcome {
        val future = CompletableFuture<Outcome>()
        var users = 0 // guarded by lifecycleLock
    }

    private sealed interface Outcome {
        data class Success(val message: TdApi.Message) : Outcome
        data class Failure(val error: TdApi.Error) : Outcome
    }

    private val lifecycleLock = Any()
    private var closed = false
    private val waiters = HashMap<Key, PendingOutcome>()
    private val outcomes = HashMap<Key, TimedOutcome>()
    private val observers = HashMap<Key, CompletableFuture<TdApi.Message>>()
    @Volatile internal var nanoTime: () -> Long = System::nanoTime

    /** Independent of blocking waiters, so a caller timeout does not discard late delivery. */
    fun observeFinal(message: TdApi.Message): CompletableFuture<TdApi.Message> {
        val key = Key(message.chatId, message.id)
        val known: Outcome?
        val future: CompletableFuture<TdApi.Message>
        synchronized(lifecycleLock) {
            if (message.sendingState == null) return CompletableFuture.completedFuture(message)
            known = knownOutcome(key)
            if (closed && known == null) return CompletableFuture.failedFuture(closedFailure(key))
            future = if (known == null) observers.getOrPut(key) {
                CompletableFuture<TdApi.Message>().orTimeout(24, TimeUnit.HOURS)
            } else CompletableFuture()
        }
        // Completing a future can run arbitrary dependent callbacks. Never do
        // that while holding the state lock needed by close or another caller.
        if (known != null) completeObserver(future, key, known)
        else future.whenComplete { _, _ -> synchronized(lifecycleLock) { observers.remove(key, future) } }
        return future
    }

    private fun completeObserver(observer: CompletableFuture<TdApi.Message>, key: Key, outcome: Outcome) {
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
        val key = Key(message.chatId, message.id)
        val active = synchronized(lifecycleLock) {
            if (message.sendingState == null) return message
            knownOutcome(key)?.let { return it.unwrap(key) }
            if (closed) throw closedFailure(key)
            waiters.getOrPut(key) { PendingOutcome() }.also { it.users++ }
        }

        return try {
            McpCallCancellation.await(active.future, timeoutNanos.coerceAtLeast(0)).unwrap(key)
        } catch (error: McpCallCancelledException) {
            throw SendOutcomeUnknownException(key.chatId, key.oldMessageId, error).apply { healthFailure = false }
        } catch (error: ExecutionException) {
            throw error.cause ?: error
        } catch (error: TimeoutException) {
            throw SendOutcomeUnknownException(key.chatId, key.oldMessageId, error, deadlineExceeded)
        } catch (error: InterruptedException) {
            Thread.currentThread().interrupt()
            throw SendOutcomeUnknownException(key.chatId, key.oldMessageId, error)
        } finally {
            // A timed-out caller must not detach another caller's live wait.
            synchronized(lifecycleLock) {
                if (waiters[key] === active && --active.users == 0) waiters.remove(key)
            }
        }
    }

    private fun complete(key: Key, outcome: Outcome) {
        val waiter: PendingOutcome?
        val observer: CompletableFuture<TdApi.Message>?
        synchronized(lifecycleLock) {
            if (closed) return // A late native callback cannot resurrect a closed session.
            outcomes.entries.removeIf { it.value.isExpired() }
            outcomes[key] = TimedOutcome(outcome, nanoTime())
            waiter = waiters.remove(key)
            observer = observers.remove(key)
        }
        // Release the caller before an observer can run a slow dependent callback.
        waiter?.future?.complete(outcome)
        observer?.let { completeObserver(it, key, outcome) }
    }

    /**
     * Terminal session teardown: unresolved waits become UNKNOWN and no new
     * pending observation is accepted. Definite outcomes observed before close
     * retain their bounded TTL, so batch reconciliation cannot lose a delivery
     * merely because an earlier item was still pending at teardown. Late native
     * callbacks are ignored; close never authorizes a resend.
     */
    override fun close() {
        val pendingWaiters: Map<Key, PendingOutcome>
        val pendingObservers: Map<Key, CompletableFuture<TdApi.Message>>
        synchronized(lifecycleLock) {
            if (closed) return
            closed = true
            pendingWaiters = waiters.toMap()
            pendingObservers = observers.toMap()
            waiters.clear()
            observers.clear()
            outcomes.entries.removeIf { it.value.isExpired() }
        }
        pendingWaiters.forEach { (key, pending) -> pending.future.completeExceptionally(closedFailure(key)) }
        pendingObservers.forEach { (key, future) -> future.completeExceptionally(closedFailure(key)) }
    }

    private fun closedFailure(key: Key) = SendOutcomeUnknownException(
        key.chatId, key.oldMessageId, CancellationException("TDLib session closed"),
    ).apply { healthFailure = false }

    /** Caller holds lifecycleLock. */
    private fun knownOutcome(key: Key): Outcome? {
        // Once closed, no further completion arrives to perform regular TTL
        // cleanup. Reads must evict expired evidence rather than retain it.
        if (closed) outcomes.entries.removeIf { it.value.isExpired() }
        return outcomes[key]?.takeUnless { it.isExpired() }?.outcome
    }

    private fun TimedOutcome.isExpired(): Boolean =
        nanoTime() - createdAtNanos > EARLY_OUTCOME_TTL_NANOS

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
