package dev.telegrammcp.server.tool.message

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import dev.telegrammcp.server.tool.executeChecked
import dev.telegrammcp.server.exception.ChatNotAllowedException
import dev.telegrammcp.server.service.*
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class GetSendOperationToolTest {
    private val operations = mockk<SendOperationService>()
    private val resolver = mockk<EntityResolverService>()
    private val guard = mockk<GuardrailService>(relaxed = true)
    private val tool = GetSendOperationTool(operations, resolver, guard, mockk(relaxed = true),
        jacksonObjectMapper(), SimpleMeterRegistry())

    @Test
    fun `status requires current chat permission before accessing journal`() {
        every { resolver.resolve(42 as Any) } returns 42L
        every { guard.validateChatAccess(42L) } throws ChatNotAllowedException(42)
        val result = tool.executeChecked(mockk(relaxed = true), mapOf("chat_id" to 42, "idempotency_key" to "key"))
        assertTrue(result.isError)
        verify(exactly = 0) { operations.status(any(), any()) }
    }

    @Test
    fun `valid status reads account scoped receipt`() {
        every { resolver.resolve(42 as Any) } returns 42L
        every { operations.status("key", 42L) } returns SendOperationJournal.Receipt("a".repeat(64), 42, "SENT", 100).payload()
        assertFalse(tool.executeChecked(mockk(relaxed = true), mapOf("chat_id" to 42, "idempotency_key" to "key")).isError)
        verify { guard.validateChatAccess(42L) }
    }
}
