package dev.telegrammcp.server.client

import dev.telegrammcp.server.config.TdLibProperties
import dev.telegrammcp.server.exception.SendOutcomeUnknownException
import dev.telegrammcp.server.exception.TelegramApiException
import dev.telegrammcp.server.exception.TelegramBusyException
import dev.telegrammcp.server.exception.TelegramTimeoutException
import dev.telegrammcp.server.exception.TelegramUnavailableException
import dev.telegrammcp.server.util.StructuredLogger
import io.github.resilience4j.bulkhead.Bulkhead
import io.github.resilience4j.circuitbreaker.CircuitBreaker
import io.github.resilience4j.ratelimiter.RateLimiter
import io.github.resilience4j.ratelimiter.RequestNotPermitted
import io.micrometer.core.instrument.MeterRegistry
import it.tdlight.client.SimpleTelegramClient
import it.tdlight.jni.TdApi
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

/**
 * One account's execution policy, shared by every TDLib capability adapter.
 *
 * Owns request dispatch, rate/flood control, the circuit breaker, bulkhead and
 * bounded waits. Domain adapters build requests and map results; they must not
 * duplicate this policy or create independent permits for nested operations.
 */
internal class TdLibRequestExecutor(
    private val client: SimpleTelegramClient,
    private val rateLimiter: RateLimiter,
    private val circuitBreaker: CircuitBreaker,
    private val meterRegistry: MeterRegistry,
    private val messageSendTracker: MessageSendTracker?,
    private val floodGate: TelegramFloodGate,
    private val requestSettings: TdLibProperties.Requests,
    private val bulkhead: Bulkhead,
    private val timeoutHealth: TdLibTimeoutHealth,
) {
    private val log = StructuredLogger.forClass<TdLibRequestExecutor>()

    /**
     * Sends a TDLib request synchronously by bridging the async callback API
     * with a [CompletableFuture].
     *
     * Order of checks: bulkhead and circuit breaker (in [withResilience]) ->
     * flood gate -> rate limiter -> TDLib. The gate runs before a rate-limiter
     * permit is taken, so calls rejected locally during a flood wait do not use
     * up the shared permits. A gate rejection is ignored by the breaker
     * (TelegramBreakerExclusionPredicate), so it never affects breaker state.
     *
     * Every wait is capped by the open [ToolCallDeadline]. A request that got no
     * answer in time gates its scope for [TdLibProperties.Requests.localTimeoutGate]:
     * TDLib may still hold it in its flood-wait delayer.
     */
    @Suppress("UNCHECKED_CAST")
    fun <T : TdApi.Object> send(function: TdApi.Function<*>): T {
        val scope = TelegramFloodGate.scopeOf(function)
        val priorWriteDispatched = ToolCallDeadline.writeDispatched()
        return try {
            floodGate.check(scope)
            acquirePermitIfPending(function)
            sendUngated(function)
        } catch (error: dev.telegrammcp.server.exception.TelegramFloodGateException) {
            error.priorWriteDispatched = priorWriteDispatched
            throw error
        } catch (error: TelegramApiException) {
            error.priorWriteDispatched = priorWriteDispatched
            floodGate.record(scope, error)
            throw error
        } catch (error: SendOutcomeUnknownException) {
            floodGate.recordLocalTimeout(scope, requestSettings.localTimeoutGate)
            throw error
        } catch (error: TelegramTimeoutException) {
            if (error.requestSent) {
                floodGate.recordLocalTimeout(scope, requestSettings.localTimeoutGate)
                // A retry before the scope's gate ends is rejected locally, so
                // advertise the gate's remaining time, not a fixed second.
                error.retryAfterSeconds = floodGate.remainingSeconds(scope)
                    ?: maxOf(1L, requestSettings.localTimeoutGate.toSeconds())
            }
            throw error
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun <T : TdApi.Object> sendUngated(function: TdApi.Function<*>): T {
        val timeout = if (function is TdApi.DownloadFile) requestSettings.downloadTimeout else requestSettings.timeout
        val wait = TdLibRequestAwaiter.waitFor(function, timeout)
        val future = CompletableFuture<TdApi.Object>()
        val observer = if (function is TdApi.SendMessage || function is TdApi.SendMessageAlbum || function is TdApi.ForwardMessages)
            SendObservationContext.capture() else null
        if (!TdLibRequestAwaiter.isReadOnly(function)) ToolCallDeadline.markWriteDispatched()
        timeoutHealth.onDispatch()
        client.send(function) { result ->
            // Any answer, including a TDLib error, shows that TDLib is alive.
            timeoutHealth.onAnswer()
            try {
                val value = result.get()
                if (observer != null) SendObservationContext.delivered(value, messageSendTracker, observer)
                future.complete(value)
            } catch (e: Exception) {
                future.completeExceptionally(e)
            }
        }
        return try {
            TdLibRequestAwaiter.await(future, function, wait) as T
        } catch (error: SendOutcomeUnknownException) {
            if (!error.deadlineExceeded) error.healthFailure = timeoutHealth.countsTowardBreaker(scopeOf(function))
            throw error
        } catch (error: TelegramTimeoutException) {
            if (error.reason == TelegramTimeoutException.Reason.REQUEST_TIMEOUT) {
                error.healthFailure = timeoutHealth.countsTowardBreaker(scopeOf(function))
            }
            throw error
        }
    }

    private fun scopeOf(function: TdApi.Function<*>): String = TelegramFloodGate.scopeOf(function)

    /**
     * Sends [request] and waits for the message's final state while holding
     * one bulkhead permit for both, so concurrent sends parked in the
     * final-state wait still count toward the account's limit.
     */
    fun sendAndAwaitFinal(operation: String, request: TdApi.Function<*>): TdApi.Message {
        val priorWriteDispatched = ToolCallDeadline.writeDispatched()
        return holdingBulkhead(operation) {
            awaitFinalMessage(withResilience(operation) { send<TdApi.Message>(request) }, request, priorWriteDispatched)
        }
    }

    /** Waits for the final state of a sent message; [request] is the TDLib function that created it. */
    fun awaitFinalMessage(
        message: TdApi.Message, request: TdApi.Function<*>, priorWriteDispatched: Boolean, pollOnly: Boolean = false,
    ): TdApi.Message {
        val tracker = messageSendTracker ?: return message
        if (message.sendingState == null) return message
        val scope = TelegramFloodGate.scopeOf(request)
        val timeoutNanos = if (pollOnly) 0L else requestSettings.messageSendTimeout.toNanos()
        val remaining = ToolCallDeadline.remainingNanos()
        val deadlineBound = remaining != null && remaining < timeoutNanos
        return try {
            tracker.awaitFinal(message, if (deadlineBound) remaining!! else timeoutNanos, deadlineBound)
        } catch (error: TelegramApiException) {
            error.priorWriteDispatched = priorWriteDispatched
            // updateMessageSendFailed with a flood wait gates the same scope as a direct 429.
            floodGate.record(scope, error)
            throw error
        } catch (error: SendOutcomeUnknownException) {
            floodGate.recordLocalTimeout(scope, requestSettings.localTimeoutGate)
            throw error
        }
    }

    /**
     * One rate-limiter permit per [withResilience] operation, taken lazily by
     * the first [send] inside it after the flood-gate check. Holds `true` once
     * the permit has been taken.
     */
    private val pendingPermit = ThreadLocal<BooleanArray?>()

    /**
     * Takes the operation's rate-limiter permit. The wait for it is bounded by
     * the limiter's own timeout and by the open [ToolCallDeadline]: a permit
     * that would only be available after the deadline is not waited for.
     */
    private fun acquirePermitIfPending(function: TdApi.Function<*>) {
        val permit = pendingPermit.get() ?: return
        if (permit[0]) return
        val waitNanos = rateLimiter.reservePermission()
        if (waitNanos < 0) throw RequestNotPermitted.createRequestNotPermitted(rateLimiter)
        if (waitNanos > 0) {
            val remaining = ToolCallDeadline.remainingNanos()
            if (remaining != null && remaining <= waitNanos) {
                throw TelegramTimeoutException(
                    function.javaClass.simpleName, TelegramTimeoutException.Reason.TOOL_DEADLINE,
                    requestSent = false, retryable = !ToolCallDeadline.writeDispatched(), waitedMillis = 0,
                )
            }
            try {
                TimeUnit.NANOSECONDS.sleep(waitNanos)
            } catch (interrupted: InterruptedException) {
                Thread.currentThread().interrupt()
                throw TelegramUnavailableException(interrupted)
            }
        }
        permit[0] = true
    }

    /**
     * Wraps a block with the per-account bulkhead, the Resilience4j circuit
     * breaker and the rate limiter. Only the outermost operation on a thread
     * takes a bulkhead permit, so nested operations cannot deadlock on it. The
     * rate-limiter permit is taken by the first TDLib request in [block], after
     * its flood-gate check (see [send]).
     */
    fun <T> withResilience(operation: String, block: () -> T): T = holdingBulkhead(operation) {
        val outer = pendingPermit.get()
        pendingPermit.set(BooleanArray(1))
        try {
            CircuitBreaker.decorateSupplier(circuitBreaker, block).get()
        } catch (ex: RequestNotPermitted) {
            meterRegistry.counter("telegram.api.rate_limited", "operation", operation).increment()
            throw TelegramUnavailableException(ex)
        } catch (ex: io.github.resilience4j.circuitbreaker.CallNotPermittedException) {
            meterRegistry.counter("telegram.api.circuit_open", "operation", operation).increment()
            throw TelegramUnavailableException(ex)
        } finally {
            if (outer == null) pendingPermit.remove() else pendingPermit.set(outer)
        }
    }

    /** True while this thread holds the account's bulkhead permit. */
    private val bulkheadHeld = ThreadLocal<Boolean>()

    /**
     * Runs [block] holding one permit of the per-account bulkhead. Only the
     * outermost call on a thread takes it, so nested operations share it and
     * cannot deadlock. A send holds it through the wait for the message's
     * final state ([sendAndAwaitFinal]), not just through the TDLib request.
     */
    fun <T> holdingBulkhead(operation: String, block: () -> T): T {
        if (bulkheadHeld.get() == true) return block()
        if (!bulkhead.tryAcquirePermission()) {
            meterRegistry.counter("telegram.api.bulkhead_full", "operation", operation).increment()
            throw TelegramBusyException(
                bulkhead.name, bulkhead.bulkheadConfig.maxConcurrentCalls,
                retryable = !ToolCallDeadline.writeDispatched(),
            )
        }
        bulkheadHeld.set(true)
        try {
            return block()
        } finally {
            bulkheadHeld.remove()
            bulkhead.onComplete()
        }
    }

    /**
     * A best-effort lookup (chat title, sender name) that decorates a result.
     * It skips the flood-gate check: a gate on the lookup method (for example
     * after get_chat hit a flood wait) must not quietly blank the titles of
     * messages that were sent successfully. GetChat and GetUser for known IDs
     * are answered from TDLib's local cache, so skipping the gate does not add
     * Telegram traffic during a flood wait. A failed lookup is logged and
     * counted (`telegram.lookup.degraded`) rather than hidden.
     */
    fun <T : TdApi.Object> lookupBestEffort(lookup: String, function: TdApi.Function<*>): T? = try {
        withResilience(lookup) {
            acquirePermitIfPending(function)
            try {
                sendUngated<T>(function)
            } catch (error: TelegramApiException) {
                floodGate.record(TelegramFloodGate.scopeOf(function), error)
                throw error
            }
        }
    } catch (error: Exception) {
        meterRegistry.counter("telegram.lookup.degraded", "lookup", lookup).increment()
        log.warn("Best-effort {} lookup failed ({}); continuing without it", lookup, error.javaClass.simpleName)
        null
    }

}
