package dev.telegrammcp.server.service

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import dev.telegrammcp.server.client.*
import dev.telegrammcp.server.model.TelegramMessage
import dev.telegrammcp.server.config.McpSecurityProperties
import dev.telegrammcp.server.config.TelegramProperties
import io.mockk.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.context.SecurityContextHolder
import java.nio.file.Path
import java.time.Instant
import kotlin.test.*

class ExportJobServiceTest {
    @TempDir lateinit var root: Path
    private val mapper = jacksonObjectMapper().findAndRegisterModules()
    private val client = mockk<TelegramClientService>()
    private val registry = TelegramAccountRegistry().apply {
        register(TelegramAccountRegistry.AccountHandle("default", client))
        register(TelegramAccountRegistry.AccountHandle("other", client))
    }
    private val accounts = TelegramAccountContext(registry)
    private val guard = GuardrailService(McpSecurityProperties(), TelegramProperties())
    private fun service(g: GuardrailService = guard): ExportJobService {
        val paths = mockk<PlatformPaths>()
        every { paths.applicationDataDirectory } answers { root }
        return ExportJobService(paths, mapper, accounts, client, g)
    }
    private fun message(id: Long) = TelegramMessage(id, 42, null, "Alice", text = "message $id", date = Instant.EPOCH)

    @Test fun `restart resumes exclusive cursor and short page does not imply complete`() = accounts.withAccount("default") {
        every { client.getHistory(42, 0, 0, 100) } returns listOf(message(300), message(200))
        every { client.getHistory(42, 200, 0, 100) } returns listOf(message(100))
        every { client.getHistory(42, 100, 0, 100) } returns emptyList()
        val first = service()
        val id = first.create(42).job.jobId
        assertEquals(0, first.operate(id, "status").job.pages)
        assertEquals("PAUSED", first.operate(id, "resume").job.status)
        val restarted = service()
        assertEquals(3L, restarted.operate(id, "resume").job.messageCount)
        assertEquals("COMPLETE", restarted.operate(id, "resume").job.status)
        assertEquals(listOf(300L, 200L), restarted.operate(id, "page", 0).messages.map { it.messageId })
        assertEquals(listOf(100L), restarted.operate(id, "page", 1).messages.map { it.messageId })
        verify(exactly = 1) { client.getHistory(42, 0, 0, 100) }
    }

    @Test fun `failed fetch keeps checkpoint and cancelled jobs cannot resume`() = accounts.withAccount("default") {
        every { client.getHistory(any(), any(), any(), any()) } throws IllegalStateException("timeout")
        val jobs = service()
        val id = jobs.create(42).job.jobId
        assertFailsWith<IllegalStateException> { jobs.operate(id, "resume") }
        assertEquals(0, service().operate(id, "status").job.pages)
        jobs.operate(id, "cancel")
        assertFailsWith<IllegalArgumentException> { jobs.operate(id, "resume") }
        assertEquals("DELETED", jobs.operate(id, "delete").job.status)
        assertFailsWith<IllegalArgumentException> { jobs.operate(id, "status") }
    }

    @Test fun `jobs are isolated by client account and current chat policy`() = accounts.withAccount("default") {
        val jobs = service()
        val id = jobs.create(42).job.jobId
        accounts.withAccount("other") { assertFailsWith<IllegalArgumentException> { jobs.operate(id, "status") } }
        SecurityContextHolder.getContext().authentication = UsernamePasswordAuthenticationToken("other-client", null)
        try { assertFailsWith<IllegalArgumentException> { jobs.operate(id, "status") } }
        finally { SecurityContextHolder.clearContext() }
        val denied = mockk<GuardrailService>()
        every { denied.validateChatAccess(42) } throws IllegalArgumentException("revoked")
        for (action in listOf("status", "resume", "page", "cancel", "delete")) {
            assertFailsWith<IllegalArgumentException> { service(denied).operate(id, action, 0) }
        }
    }

    @Test fun `unexpected chat cannot be persisted`() = accounts.withAccount("default") {
        every { client.getHistory(any(), any(), any(), any()) } returns listOf(message(3).copy(chatId = 99))
        val jobs = service()
        val id = jobs.create(42).job.jobId
        assertFailsWith<IllegalArgumentException> { jobs.operate(id, "resume") }
        assertEquals(0, jobs.operate(id, "status").job.pages)
    }

    @Test fun `checkpoint failure leaves page uncommitted and retry preserves the native cursor`() = accounts.withAccount("default") {
        val nativeId = 300L shl 20
        every { client.getHistory(42, 0, 0, 100) } returns listOf(message(nativeId))
        every { client.getHistory(42, nativeId, 0, 100) } returns emptyList()
        val jobs = service()
        val id = jobs.create(42).job.jobId
        mockkObject(DurableFiles)
        try {
            every { DurableFiles.write(match { it.fileName.toString() == "$id.json" }, any()) } throws java.io.IOException("simulated checkpoint failure")
            assertFailsWith<java.io.IOException> { jobs.operate(id, "resume") }
        } finally { unmockkObject(DurableFiles) }
        assertEquals(0, service().operate(id, "status").job.pages)
        assertFailsWith<IllegalArgumentException> { jobs.operate(id, "page", 0) }
        assertEquals(1L, jobs.operate(id, "resume").job.messageCount)
        assertEquals("COMPLETE", jobs.operate(id, "resume").job.status)
        assertEquals(listOf(nativeId), jobs.operate(id, "page", 0).messages.map { it.messageId })
        verify(exactly = 1) { client.getHistory(42, nativeId, 0, 100) }
    }
}
