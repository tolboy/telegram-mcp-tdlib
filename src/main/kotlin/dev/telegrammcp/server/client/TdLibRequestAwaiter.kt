package dev.telegrammcp.server.client

import dev.telegrammcp.server.exception.SendOutcomeUnknownException
import dev.telegrammcp.server.exception.TelegramApiException
import dev.telegrammcp.server.exception.TelegramTimeoutException
import dev.telegrammcp.server.exception.TelegramTimeoutException.Reason
import it.tdlight.client.TelegramError
import it.tdlight.jni.TdApi
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/** Keeps local wait failures distinct from a Telegram rejection of a request. */
internal object TdLibRequestAwaiter {

    /** How long to wait for one TDLib answer, and which limit ends the wait. */
    data class Wait(val nanos: Long, val reason: Reason)

    /**
     * The wait for [request]: its own [timeout], capped at the time left in the
     * open [ToolCallDeadline]. Throws a not-sent [TelegramTimeoutException] when
     * the deadline has already run out, so nothing is handed to TDLib that the
     * caller could no longer hear about.
     */
    fun waitFor(request: TdApi.Function<*>, timeout: Duration): Wait {
        val reason = if (request is TdApi.DownloadFile) Reason.DOWNLOAD_TIMEOUT else Reason.REQUEST_TIMEOUT
        val timeoutNanos = timeout.toNanos()
        val remaining = ToolCallDeadline.remainingNanos() ?: return Wait(timeoutNanos, reason)
        if (remaining <= 0) {
            throw TelegramTimeoutException(
                request.javaClass.simpleName, Reason.TOOL_DEADLINE, requestSent = false,
                retryable = !ToolCallDeadline.writeDispatched(), waitedMillis = 0,
            )
        }
        return if (remaining < timeoutNanos) Wait(remaining, Reason.TOOL_DEADLINE) else Wait(timeoutNanos, reason)
    }

    fun await(
        future: CompletableFuture<TdApi.Object>,
        request: TdApi.Function<*>,
        timeoutSeconds: Long,
    ): TdApi.Object = await(future, request, Wait(TimeUnit.SECONDS.toNanos(timeoutSeconds), Reason.REQUEST_TIMEOUT))

    fun await(
        future: CompletableFuture<TdApi.Object>,
        request: TdApi.Function<*>,
        wait: Wait,
    ): TdApi.Object = try {
        future.get(wait.nanos, TimeUnit.NANOSECONDS)
    } catch (error: ExecutionException) {
        throw translate(error.cause ?: error, request)
    } catch (error: TimeoutException) {
        throw timeoutFailure(request, wait, error)
    } catch (error: InterruptedException) {
        Thread.currentThread().interrupt()
        throw sendChatId(request)?.let { SendOutcomeUnknownException(it, cause = error) } ?: error
    }

    /** Maps tdlight's TelegramError to the server's typed, classifiable exception. */
    fun translate(error: Throwable, request: TdApi.Function<*>? = null): Throwable = when (error) {
        is TelegramError -> TelegramApiException(
            error.errorCode, error.errorMessage.orEmpty(), error,
            readOnlyRequest = request != null && isReadOnly(request),
        )
        else -> error
    }

    /**
     * True only for functions on the explicit [TelegramReadRequests] allowlist.
     * Unknown functions are treated as writes, so a transient failure is never
     * advertised as safe to retry for them.
     */
    fun isReadOnly(request: TdApi.Function<*>): Boolean = TelegramReadRequests.isSafeRead(request)

    private fun timeoutFailure(request: TdApi.Function<*>, wait: Wait, error: TimeoutException): Exception {
        sendChatId(request)?.let {
            return SendOutcomeUnknownException(it, cause = error, deadlineExceeded = wait.reason == Reason.TOOL_DEADLINE)
        }
        return TelegramTimeoutException(
            request.javaClass.simpleName, wait.reason, requestSent = true,
            retryable = isReadOnly(request) && !ToolCallDeadline.writeDispatched(),
            waitedMillis = TimeUnit.NANOSECONDS.toMillis(wait.nanos), cause = error,
        )
    }

    /** Target chat of a message send, whose lost answer means "outcome unknown". */
    private fun sendChatId(request: TdApi.Function<*>): Long? = when (request) {
        is TdApi.SendMessage -> request.chatId
        is TdApi.SendMessageAlbum -> request.chatId
        is TdApi.ForwardMessages -> request.chatId
        else -> null
    }
}
