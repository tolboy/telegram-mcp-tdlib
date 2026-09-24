package dev.telegrammcp.server.service

import com.fasterxml.jackson.databind.ObjectMapper
import dev.telegrammcp.server.client.TelegramAccountContext
import dev.telegrammcp.server.model.TelegramMessage
import org.springframework.stereotype.Service

@Service
class SendOperationService(
    private val accountContext: TelegramAccountContext,
    paths: PlatformPaths,
    private val mapper: ObjectMapper,
) {
    private val journal = SendOperationJournal(paths.applicationDataDirectory.resolve("send-operations"), mapper)

    fun send(key: String, chatId: Long, canonicalRequest: List<Any?>, beforeSend: () -> Unit, send: () -> TelegramMessage): Map<String, Any?> =
        journal.send(accountContext.currentAccount(), key, chatId,
            SendOperationJournal.digest(mapper.writeValueAsBytes(canonicalRequest)), beforeSend) {
            val message = send()
            check(message.chatId == chatId) { "Telegram returned a different destination chat" }
            message.messageId
        }

    fun status(key: String, chatId: Long): Map<String, Any?> = journal.status(accountContext.currentAccount(), key, chatId)
}
