package dev.telegrammcp.server.client

import dev.telegrammcp.server.exception.SendOutcomeUnknownException
import dev.telegrammcp.server.exception.TelegramApiException
import it.tdlight.client.TelegramError
import it.tdlight.jni.TdApi
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/** Keeps local wait failures distinct from a Telegram rejection of a request. */
internal object TdLibRequestAwaiter {
    fun await(
        future: CompletableFuture<TdApi.Object>,
        request: TdApi.Function<*>,
        timeoutSeconds: Long,
    ): TdApi.Object = try {
        future.get(timeoutSeconds, TimeUnit.SECONDS)
    } catch (error: ExecutionException) {
        throw translate(error.cause ?: error, request)
    } catch (error: TimeoutException) {
        throw waitFailure(request, error)
    } catch (error: InterruptedException) {
        Thread.currentThread().interrupt()
        throw waitFailure(request, error)
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
     * TDLib functions without server-side effects. Unknown functions are
     * treated as writes, so a transient failure is never advertised as safe to
     * retry for them.
     */
    fun isReadOnly(request: TdApi.Function<*>): Boolean {
        val name = request.javaClass.simpleName
        if (name in NOT_READ_ONLY) return false
        return READ_ONLY_PREFIXES.any { name.startsWith(it) } || name in READ_ONLY_NAMES
    }

    private val READ_ONLY_PREFIXES = listOf("Get", "Search", "Load")
    private val READ_ONLY_NAMES = setOf("ParseTextEntities", "DownloadFile", "RecognizeSpeech")

    /** Get* functions that act on behalf of the user (press a bot button, authorize a login, query a bot). */
    private val NOT_READ_ONLY = setOf("GetCallbackQueryAnswer", "GetLoginUrl", "GetExternalLink", "GetInlineQueryResults")

    private fun waitFailure(request: TdApi.Function<*>, error: Exception): Exception = when (request) {
        is TdApi.SendMessage -> SendOutcomeUnknownException(request.chatId, cause = error)
        is TdApi.ForwardMessages -> SendOutcomeUnknownException(request.chatId, cause = error)
        else -> error
    }
}
