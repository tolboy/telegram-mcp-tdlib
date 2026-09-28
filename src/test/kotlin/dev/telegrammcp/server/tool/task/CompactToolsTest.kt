package dev.telegrammcp.server.tool.task

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import dev.telegrammcp.server.client.TelegramClientService
import dev.telegrammcp.server.client.TelegramAccountContext
import dev.telegrammcp.server.client.TelegramAccountRegistry
import dev.telegrammcp.server.model.*
import dev.telegrammcp.server.service.*
import dev.telegrammcp.server.tool.executeChecked
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import io.mockk.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.time.Instant
import kotlin.test.*

class CompactToolsTest {
    @TempDir lateinit var root: Path
    private val mapper = jacksonObjectMapper().findAndRegisterModules()
    private val client = mockk<TelegramClientService>()
    private val guard = mockk<GuardrailService>()
    private val resolver = mockk<EntityResolverService>()

    @Test fun `persistent tool success and failure results obey advertised schemas`() {
        val paths = mockk<PlatformPaths>()
        every { paths.applicationDataDirectory } returns root
        every { guard.validateChatAccess(42) } just Runs
        every { guard.isChatAllowed(42) } returns true
        every { resolver.resolve(42L) } returns 42L
        every { client.getHistory(42, 0, 0, 100) } returns listOf(TelegramMessage(100, 42, null, "A", text = "hello", date = Instant.EPOCH))
        val accounts = TelegramAccountContext(TelegramAccountRegistry().apply {
            register(TelegramAccountRegistry.AccountHandle("default", client))
        })
        accounts.withAccount("default") {
            val jobs = ExportJobService(paths, mapper, accounts, client, guard)
            val export = ExportJobTool(jobs, resolver, mapper, SimpleMeterRegistry())
            assertFalse(export.executeChecked(mockk(), mapOf("action" to "start", "chat_id" to 42L)).isError ?: false)
            val job = jobs.create(42).job
            for (args in listOf(mapOf("action" to "resume", "job_id" to job.jobId),
                mapOf("action" to "page", "job_id" to job.jobId, "page" to 0),
                mapOf("action" to "status", "job_id" to job.jobId))) {
                assertFalse(export.executeChecked(mockk(), args).isError ?: false)
            }
            assertTrue(export.executeChecked(mockk(), mapOf("action" to "status", "job_id" to "../escape")).isError ?: false)
            val journal = ChangeJournal(paths, mapper)
            journal.append("default", "edited", 42, 100)
            val changes = ChangesSinceTool(journal, accounts, guard, mapper, SimpleMeterRegistry())
            assertFalse(changes.executeChecked(mockk(), mapOf("max_chars" to 512)).isError ?: false)
            assertTrue(changes.executeChecked(mockk(), mapOf("cursor" to "wrong:0")).isError ?: false)
        }
    }

    @Test fun `inbox excludes denied and read chats and fits normalized budget`() {
        every { client.getChats(50) } returns (1L..50L).map { ChatInfo(it, "\u202e".repeat(160), ChatType.PRIVATE, unreadCount = if (it == 1L) 0 else 1) }
        every { guard.isChatAllowed(any()) } answers { firstArg<Long>() != 2L }
        val tool = InboxSnapshotTool(client, guard, mapper, SimpleMeterRegistry())
        val result = tool.run(mapOf("max_chars" to 512))
        assertTrue(jsonSize(result, mapper) <= 512)
        assertTrue(result.chats.none { it.chatId in setOf(1L, 2L) })
        assertTrue(result.truncated)
        assertFalse(tool.executeChecked(mockk(), mapOf("max_chars" to 512)).isError ?: false)
    }

    @Test fun `conversation budget retains resumable IDs and checks access before fetching`() {
        every { resolver.resolve(42L) } returns 42L
        every { guard.validateChatAccess(42) } just Runs
        every { client.getHistory(42, 0, 0, 20) } returns (100L downTo 90L).map {
            TelegramMessage(it, 42, null, "Alice", text = "\u202e".repeat(500), date = Instant.EPOCH)
        }
        val tool = ConversationBundleTool(client, resolver, guard, mapper, SimpleMeterRegistry())
        val result = tool.run(mapOf("chat_id" to 42L, "max_chars" to 512))
        assertTrue(result.messages.isNotEmpty())
        assertTrue(jsonSize(result, mapper) <= 512)
        assertEquals(result.messages.last().messageId, result.nextMessageId)
        assertTrue(result.messages.first().contentTruncated)
        assertFalse(tool.executeChecked(mockk(), mapOf("chat_id" to 42L, "max_chars" to 512)).isError ?: false)
        every { guard.validateChatAccess(42) } throws IllegalArgumentException("denied")
        assertFailsWith<IllegalArgumentException> { tool.run(mapOf("chat_id" to 42L)) }
        verify(exactly = 2) { client.getHistory(any(), any(), any(), any()) }
    }
}
