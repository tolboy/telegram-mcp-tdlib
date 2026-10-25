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
        throw translate(error.cause ?: error)
    } catch (error: TimeoutException) {
        throw waitFailure(request, error)
    } catch (error: InterruptedException) {
        Thread.currentThread().interrupt()
        throw waitFailure(request, error)
    }

    /** Maps tdlight's TelegramError to the server's typed, classifiable exception. */
    fun translate(error: Throwable): Throwable = when (error) {
        is TelegramError -> TelegramApiException(error.errorCode, error.errorMessage.orEmpty(), error)
        else -> error
    }

    private fun waitFailure(request: TdApi.Function<*>, error: Exception): Exception = when (request) {
        is TdApi.SendMessage -> SendOutcomeUnknownException(request.chatId, cause = error)
        is TdApi.ForwardMessages -> SendOutcomeUnknownException(request.chatId, cause = error)
        else -> error
    }
}
