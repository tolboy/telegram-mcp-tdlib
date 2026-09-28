package dev.telegrammcp.server.service

import com.fasterxml.jackson.databind.ObjectMapper
import dev.telegrammcp.server.client.TelegramAccountContext
import dev.telegrammcp.server.client.TelegramClientService
import dev.telegrammcp.server.model.TelegramMessage
import dev.telegrammcp.server.security.AccessPermissionService
import org.springframework.stereotype.Service
import java.nio.file.Files
import java.security.MessageDigest
import java.util.UUID

data class ExportJob(val jobId: String, val chatId: Long, val status: String = "PAUSED", val cursor: Long = 0,
    val pages: Int = 0, val messageCount: Long = 0, val reason: String? = null)
data class ExportJobResult(val job: ExportJob, val messages: List<TelegramMessage> = emptyList(),
    val page: Int? = null, val scope: String = "accessible_history_at_fetch_time")

/** Pull-driven jobs: each resume commits at most one page under the caller's current authority. */
@Service
class ExportJobService(private val paths: PlatformPaths, private val mapper: ObjectMapper,
    private val accounts: TelegramAccountContext, private val client: TelegramClientService,
    private val guard: GuardrailService) {

    private fun directory() = paths.applicationDataDirectory.resolve("exports").resolve(
        MessageDigest.getInstance("SHA-256").digest(
            (accounts.currentAccount() + "\u0000" + AccessPermissionService.clientIdentity()).toByteArray()
        ).joinToString("") { "%02x".format(it) })

    @Synchronized
    fun create(chatId: Long): ExportJobResult = DurableFiles.locked(directory()) {
        guard.validateChatAccess(chatId)
        val root = directory()
        Files.createDirectories(root)
        require(Files.list(root).use { it.filter { p -> p.fileName.toString().endsWith(".json") }.count() } < 100) {
            "Export job limit reached (100); remove old jobs with action=delete"
        }
        val job = ExportJob(UUID.randomUUID().toString(), chatId)
        save(job)
        ExportJobResult(job)
    }

    @Synchronized
    fun operate(id: String, action: String, page: Int? = null): ExportJobResult = DurableFiles.locked(directory()) {
        require(id.matches(Regex("[a-f0-9-]{36}"))) { "Invalid job_id" }
        val root = directory()
        val file = root.resolve("$id.json")
        require(Files.exists(file)) { "Unknown job in this account/client scope" }
        val job = mapper.readValue(Files.readAllBytes(file), ExportJob::class.java)
        guard.validateChatAccess(job.chatId)
        when (action) {
            "status" -> ExportJobResult(job)
            "cancel" -> ExportJobResult(job.copy(status = "CANCELLED").also(::save))
            "delete" -> {
                // All names are derived from a validated UUID and bounded committed page count.
                for (index in 0..job.pages) Files.deleteIfExists(root.resolve("$id-$index.page"))
                Files.delete(file)
                ExportJobResult(job.copy(status = "DELETED"))
            }
            "page" -> {
                require(page != null && page in 0 until job.pages) { "page must identify a committed page (zero based)" }
                val messages = mapper.readValue(Files.readAllBytes(root.resolve("$id-$page.page")),
                    mapper.typeFactory.constructCollectionType(List::class.java, TelegramMessage::class.java)) as List<TelegramMessage>
                ExportJobResult(job, messages, page)
            }
            "resume" -> {
                require(job.status != "CANCELLED") { "Cancelled jobs cannot resume" }
                if (job.status == "COMPLETE") return@locked ExportJobResult(job)
                require(job.pages < 10_000) { "Export page limit reached" }
                // TDLib OrderedMessages::get_history excludes the anchor at offset=0.
                // Keep its ID intact: subtracting one can produce an invalid TDLib message ID.
                val raw = client.getHistory(job.chatId, job.cursor, 0, 100)
                require(raw.all { it.chatId == job.chatId && it.messageId > 0 }) { "History returned an unexpected chat or message ID" }
                val messages = raw.filter { job.cursor == 0L || it.messageId < job.cursor }.distinctBy { it.messageId }
                if (raw.isEmpty()) return@locked ExportJobResult(job.copy(status = "COMPLETE", reason = "empty_history_page").also(::save))
                if (messages.isEmpty()) return@locked ExportJobResult(job.copy(reason = "cursor_did_not_advance").also(::save))
                DurableFiles.write(root.resolve("$id-${job.pages}.page"), mapper.writeValueAsBytes(messages))
                val updated = job.copy(cursor = messages.minOf { it.messageId }, pages = job.pages + 1,
                    messageCount = job.messageCount + messages.size, reason = null)
                save(updated)
                ExportJobResult(updated)
            }
            else -> throw IllegalArgumentException("action must be start, resume, status, cancel, page or delete")
        }
    }

    private fun save(job: ExportJob) = DurableFiles.write(directory().resolve("${job.jobId}.json"), mapper.writeValueAsBytes(job))
}
