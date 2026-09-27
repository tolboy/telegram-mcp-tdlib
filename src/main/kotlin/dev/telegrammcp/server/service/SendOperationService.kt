package dev.telegrammcp.server.service

import com.fasterxml.jackson.databind.ObjectMapper
import dev.telegrammcp.server.client.TelegramAccountContext
import dev.telegrammcp.server.client.SendObservationContext
import dev.telegrammcp.server.model.TelegramMessage
import dev.telegrammcp.server.model.ScheduledMessage
import dev.telegrammcp.server.exception.InvalidToolInputException
import org.springframework.stereotype.Service

@Service
class SendOperationService(
    private val accountContext: TelegramAccountContext,
    paths: PlatformPaths,
    private val mapper: ObjectMapper,
) {
    private val journal = SendOperationJournal(paths.applicationDataDirectory.resolve("send-operations"), mapper)

    fun execute(arguments: Map<String, Any>, chatId: Long, canonicalRequest: List<Any?>,
        beforeSend: () -> Unit, expectedCount: Int = 1, scheduled: Boolean = false, send: () -> Any): Any {
        val key = key(arguments) ?: return send()
        val account = accountContext.currentAccount()
        val result = journal.sendCompletion(account, key, chatId,
            SendOperationJournal.digest(mapper.writeValueAsBytes(canonicalRequest)), beforeSend) {
            val payload = SendObservationContext.observe({ messages ->
                if (messages.size == expectedCount && messages.all { it.chatId == chatId && it.id > 0 }) {
                    val ids = messages.map { it.id }
                    journal.recordLateCompletion(account, key, chatId,
                        SendOperationJournal.Completion(ids.first(), if (expectedCount > 1) ids else null, scheduled))
                }
            }, send)
            val messages = when (payload) {
                is TelegramMessage -> listOf(payload)
                is ScheduledMessage -> listOf(payload.message)
                is List<*> -> payload.map { it as TelegramMessage }
                else -> error("Unsupported delivery result")
            }
            check(messages.size == expectedCount && messages.all { it.chatId == chatId }) { "Incomplete delivery result" }
            val ids = messages.map { it.messageId }
            SendOperationJournal.Completion(ids.first(), if (expectedCount > 1) ids else null, scheduled)
        }
        return journal.recoverLate(account, key, chatId, result)
    }

    companion object {
        fun key(arguments: Map<String, Any>): String? {
            if (!arguments.containsKey("idempotency_key")) return null
            return (arguments["idempotency_key"] as? String
                ?: throw InvalidToolInputException("idempotency_key must be a string"))
                .also(SendOperationJournal::validateKey)
        }
    }

    fun send(key: String, chatId: Long, canonicalRequest: List<Any?>, beforeSend: () -> Unit, send: () -> TelegramMessage): Map<String, Any?> {
        val account = accountContext.currentAccount()
        val result = journal.send(account, key, chatId,
            SendOperationJournal.digest(mapper.writeValueAsBytes(canonicalRequest)), beforeSend) {
            val message = SendObservationContext.observe({ messages ->
                if (messages.size == 1 && messages.single().chatId == chatId) {
                    journal.recordLate(account, key, chatId, messages.single().id)
                }
            }, send)
            check(message.chatId == chatId) { "Telegram returned a different destination chat" }
            message.messageId
        }
        return journal.recoverLate(account, key, chatId, result)
    }

    fun status(key: String, chatId: Long): Map<String, Any?> {
        val account = accountContext.currentAccount()
        return journal.recoverLate(account, key, chatId, journal.status(account, key, chatId))
    }
}
