package dev.telegrammcp.server.client

import it.tdlight.jni.TdApi
import java.util.concurrent.CompletableFuture

/** Captured before dispatch: callbacks must not rely on the caller's thread or account context. */
object SendObservationContext {
    private val current = ThreadLocal<((List<TdApi.Message>) -> Unit)?>()

    fun capture(): ((List<TdApi.Message>) -> Unit)? = current.get()

    fun <T> observe(observer: (List<TdApi.Message>) -> Unit, block: () -> T): T {
        val previous = current.get()
        current.set(observer)
        return try { block() } finally {
            if (previous == null) current.remove() else current.set(previous)
        }
    }

    fun delivered(result: TdApi.Object, tracker: MessageSendTracker?, observer: (List<TdApi.Message>) -> Unit) {
        val messages = when (result) {
            is TdApi.Message -> listOf(result)
            is TdApi.Messages -> result.messages?.filterNotNull().orEmpty()
            else -> return
        }
        if (messages.isEmpty()) return
        val futures = messages.map { message ->
            if (message.sendingState == null) CompletableFuture.completedFuture(message)
            else tracker?.observeFinal(message) ?: return
        }
        CompletableFuture.allOf(*futures.toTypedArray()).thenRun {
            observer(futures.map { it.join() })
        }
    }
}
