package dev.telegrammcp.server.service

import com.fasterxml.jackson.databind.ObjectMapper
import dev.telegrammcp.server.client.TelegramAccountRegistry
import it.tdlight.client.SimpleTelegramClientBuilder
import it.tdlight.jni.TdApi
import org.springframework.stereotype.Service
import java.nio.file.Files
import java.time.Instant
import java.util.UUID

data class ChangeEntry(val sequence: Long, val kind: String, val chatId: Long?, val messageId: Long?, val observedAt: String)
data class ChangeState(val epoch: String, val sequence: Long, val entries: List<ChangeEntry>)
data class ChangePage(val entries: List<ChangeEntry>, val nextCursor: String, val hasMore: Boolean,
    val gap: Boolean, val scope: String = "locally_observed_updates_only")

/** Bounded metadata journal. Restart/reconnect markers explicitly invalidate continuous coverage. */
@Service
class ChangeJournal(private val paths: PlatformPaths, private val mapper: ObjectMapper) {
    private val failedAccounts = mutableSetOf<String>()

    @Synchronized
    private fun state(account: String): ChangeState {
        val file = file(account)
        return if (Files.exists(file)) mapper.readValue(Files.readAllBytes(file), ChangeState::class.java)
        else ChangeState(UUID.randomUUID().toString(), 0, emptyList())
    }

    private fun file(account: String) = paths.applicationDataDirectory.resolve("changes")
        .resolve(TelegramAccountRegistry.normalizeLabel(account) + ".json")

    @Synchronized
    fun append(account: String, kind: String, chatId: Long? = null, messageId: Long? = null) {
        try {
            DurableFiles.locked(file(account).parent) {
                val old = state(account)
                val gap = if (account in failedAccounts) listOf(ChangeEntry(old.sequence + 1, "coverage_gap", null, null, Instant.now().toString())) else emptyList()
                val next = old.sequence + gap.size + 1
                val updated = old.copy(sequence = next, entries = (old.entries + gap +
                    ChangeEntry(next, kind, chatId, messageId, Instant.now().toString())).takeLast(10_000))
                DurableFiles.write(file(account), mapper.writeValueAsBytes(updated))
            }
            failedAccounts.remove(account)
        } catch (failure: Exception) {
            failedAccounts.add(account)
            throw failure
        }
    }

    @Synchronized
    fun page(account: String, cursor: String?, limit: Int, allowed: (Long) -> Boolean): ChangePage =
        DurableFiles.locked(file(account).parent) {
        require(limit in 1..200)
        val state = state(account)
        // Even an empty journal needs a persistent epoch for its returned cursor.
        if (!Files.exists(file(account))) DurableFiles.write(file(account), mapper.writeValueAsBytes(state))
        val parts = cursor?.split(":")
        require(parts == null || (parts.size == 2 && parts[0] == state.epoch)) { "Cursor belongs to a different journal; restart without cursor" }
        val after = if (parts == null) 0L else requireNotNull(parts[1].toLongOrNull()) { "Invalid change cursor" }
        require(after in 0..state.sequence) { "Invalid change cursor" }
        val scanned = state.entries.filter { it.sequence > after }.take(limit)
        ChangePage(scanned.filter { it.chatId == null || allowed(it.chatId) },
            "${state.epoch}:${scanned.lastOrNull()?.sequence ?: state.sequence}",
            scanned.lastOrNull()?.sequence?.let { it < state.sequence } ?: false,
            account in failedAccounts || after < (state.entries.firstOrNull()?.sequence ?: 1) - 1 || scanned.any { it.kind == "coverage_gap" })
    }

    fun attach(builder: SimpleTelegramClientBuilder, account: String) {
        append(account, "coverage_gap")
        builder.addUpdateHandler(TdApi.UpdateNewMessage::class.java) { append(account, "new", it.message.chatId, it.message.id) }
        builder.addUpdateHandler(TdApi.UpdateMessageContent::class.java) { append(account, "content_changed", it.chatId, it.messageId) }
        builder.addUpdateHandler(TdApi.UpdateMessageEdited::class.java) { append(account, "edited", it.chatId, it.messageId) }
        builder.addUpdateHandler(TdApi.UpdateDeleteMessages::class.java) { update ->
            if (update.isPermanent) update.messageIds.forEach { append(account, "deleted", update.chatId, it) }
        }
        builder.addUpdateHandler(TdApi.UpdateConnectionState::class.java) {
            if (it.state !is TdApi.ConnectionStateReady) append(account, "coverage_gap")
        }
    }
}
