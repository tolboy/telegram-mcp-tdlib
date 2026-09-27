package dev.telegrammcp.server.client

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import dev.telegrammcp.server.service.SendOperationJournal
import it.tdlight.jni.TdApi
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.*

class SendObservationContextTest {
    @TempDir lateinit var directory: Path

    @Test
    fun `forward observation waits for every final ID`() {
        val tracker = MessageSendTracker()
        var ids: List<Long>? = null
        val first = TdApi.Message().apply { id = 10; chatId = 42; sendingState = TdApi.MessageSendingStatePending(1) }
        val second = TdApi.Message().apply { id = 11; chatId = 42; sendingState = TdApi.MessageSendingStatePending(1) }
        val result = TdApi.Messages().apply { messages = arrayOf(first, second); totalCount = 2 }
        SendObservationContext.delivered(result, tracker) { ids = it.map { message -> message.id } }
        tracker.onSucceeded(TdApi.UpdateMessageSendSucceeded(TdApi.Message().apply { id = 20; chatId = 42 }, 10))
        assertNull(ids)
        tracker.onSucceeded(TdApi.UpdateMessageSendSucceeded(TdApi.Message().apply { id = 21; chatId = 42 }, 11))
        assertEquals(listOf(20L, 21L), ids)
    }

    @Test
    fun `callback arriving after request timeout persists receipt outside caller context`() {
        val store = SendOperationJournal(directory, jacksonObjectMapper())
        val tracker = MessageSendTracker()
        var captured: ((List<TdApi.Message>) -> Unit)? = null
        assertFailsWith<IllegalStateException> {
            store.send("work", "key", 42, "request", {}) {
                SendObservationContext.observe({ messages -> store.recordLate("work", "key", 42, messages.single().id) }) {
                    captured = SendObservationContext.capture()
                    error("request timed out before provisional response")
                }
            }
        }
        assertNull(SendObservationContext.capture())
        val provisional = TdApi.Message().apply { id = 10; chatId = 42; sendingState = TdApi.MessageSendingStatePending(1) }
        SendObservationContext.delivered(provisional, tracker, requireNotNull(captured))
        tracker.onSucceeded(TdApi.UpdateMessageSendSucceeded(TdApi.Message().apply { id = 20; chatId = 42 }, 10))
        val reopened = SendOperationJournal(directory, jacksonObjectMapper())
        assertEquals(20L, reopened.recoverLate("work", "key", 42, reopened.status("work", "key", 42))["message_id"])
    }
}
