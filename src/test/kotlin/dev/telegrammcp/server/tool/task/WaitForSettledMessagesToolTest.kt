package dev.telegrammcp.server.tool.task

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import dev.telegrammcp.server.client.TelegramAccountContext
import dev.telegrammcp.server.client.TelegramClientService
import dev.telegrammcp.server.config.McpSecurityProperties
import dev.telegrammcp.server.config.McpToolProfile
import dev.telegrammcp.server.config.TelegramProperties
import dev.telegrammcp.server.config.assertToolError
import dev.telegrammcp.server.exception.ChatNotAllowedException
import dev.telegrammcp.server.security.SensitiveChatBlockedException
import dev.telegrammcp.server.model.MessageAddressing
import dev.telegrammcp.server.security.AccessPermissionService
import dev.telegrammcp.server.security.PermissionDocument
import dev.telegrammcp.server.security.PermissionGrant
import dev.telegrammcp.server.security.SensitiveChats
import dev.telegrammcp.server.service.*
import dev.telegrammcp.server.tool.ToolContractCatalog
import dev.telegrammcp.server.tool.executeChecked
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import io.mockk.*
import io.modelcontextprotocol.spec.McpSchema
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*

class WaitForSettledMessagesToolTest {
    @TempDir lateinit var root: Path
    private val mapper = jacksonObjectMapper()
    private val accounts = mockk<TelegramAccountContext>()
    private val resolver = mockk<EntityResolverService>()
    private val operationGuard = mockk<OperationGuardService>(relaxed = true)
    private val audit = mockk<AuditService>(relaxed = true)
    private val client = mockk<TelegramClientService>()
    private val journals = mutableListOf<ChangeJournal>()
    @AfterEach fun close() { journals.forEach(ChangeJournal::close) }

    private fun journal(): ChangeJournal {
        val paths = mockk<PlatformPaths>()
        every { paths.applicationDataDirectory } returns root
        every { accounts.currentAccount() } returns "default"
        every { client.getMessageAddressing(any(), any()) } answers {
            val chatId = firstArg<Long>()
            secondArg<List<Long>>().map { MessageAddressing(chatId, it, 5, false, chatId > 0, false, false) }
        }
        return ChangeJournal(paths, mapper).also { journals += it }
    }

    private fun tool(journal: ChangeJournal, guard: GuardrailService = GuardrailService(McpSecurityProperties(), TelegramProperties())) =
        WaitForSettledMessagesTool(SettledMessageWaiter(journal, client), accounts, resolver, guard, operationGuard, audit,
            mapper, SimpleMeterRegistry())

    @Test fun `arguments are validated as typed input errors and every execution is audited`() {
        val tool = tool(journal())
        assertFalse(tool.executeChecked(mockk(), mapOf("timeout_seconds" to 0)).isError ?: false)
        for (args in listOf(mapOf("timeout_seconds" to 31), mapOf("timeout_seconds" to -1), mapOf("settle_seconds" to 21),
            mapOf("settle_seconds" to -1), mapOf("limit" to 0), mapOf("limit" to 201), mapOf("cursor" to ""),
            mapOf("cursor" to 5), mapOf("addressed_only" to "yes"), mapOf("settle_seconds" to 1.5))) {
            assertToolError("INVALID_INPUT") { tool.executeChecked(mockk(), args + ("timeout_seconds" to (args["timeout_seconds"] ?: 0))) }
        }
        verify(exactly = 11) { audit.record(eq("wait_for_settled_messages"), any(), any(), any(), any(), any()) }
        verify { operationGuard.checkPermission("wait_for_settled_messages", any()) }
    }

    @Test fun `results are marked as untrusted Telegram data`() {
        val result = tool(journal()).executeChecked(mockk(), mapOf("timeout_seconds" to 0))
        assertEquals(true, result.meta()?.get("io.github.tolboy/untrusted-content"))
        val text = (result.content().first() as McpSchema.TextContent).text()
        assertTrue("\"status\"" in text && "timed_out" in text, text)
    }

    @Test fun `chat scoped grants filter results and reject a denied explicit target`() {
        val permissionFile = root.resolve("permissions.json")
        Files.writeString(permissionFile, mapper.writeValueAsString(PermissionDocument(1,
            listOf(PermissionGrant("stdio", "default", setOf("read"), setOf(42))))))
        val policy = AccessPermissionService(McpSecurityProperties(permissionsFile = permissionFile.toString()),
            PlatformPaths(), mapper)
        val guard = GuardrailService(McpSecurityProperties(), TelegramProperties(), policy)
        val journal = journal()
        val cursor = journal.tailCursor("default")
        journal.append("default", "new", 99, 200)
        journal.append("default", "new", 42, 100)
        journal.tailCursor("default")
        val tool = tool(journal, guard)
        policy.withPermission("wait_for_settled_messages", "default") {
            val result = tool.run(mapOf("cursor" to cursor, "settle_seconds" to 0, "timeout_seconds" to 1))
            assertEquals(listOf(42L), result.messages.map { it.chatId })
            every { resolver.resolve(99L) } returns 99L
            assertFailsWith<ChatNotAllowedException> { tool.run(mapOf("chat_id" to 99L, "timeout_seconds" to 0)) }
        }
        verify(exactly = 0) { client.getMessageAddressing(99, any()) }
        // Without an invocation grant every chat is denied.
        assertTrue(tool.run(mapOf("cursor" to cursor, "settle_seconds" to 0, "timeout_seconds" to 0)).messages.isEmpty())
        assertTrue("wait_for_settled_messages" in AccessPermissionService.CHAT_SCOPED_TOOLS)
    }

    @Test fun `sensitive chats are omitted and refused as an explicit target`() {
        val journal = journal()
        val cursor = journal.tailCursor("default")
        journal.append("default", "new", SensitiveChats.TELEGRAM_SERVICE_CHAT_ID, 1)
        journal.append("default", "new", 42, 2)
        journal.tailCursor("default")
        val tool = tool(journal)
        val result = tool.run(mapOf("cursor" to cursor, "settle_seconds" to 0, "timeout_seconds" to 1))
        assertEquals(listOf(42L), result.messages.map { it.chatId })
        verify(exactly = 0) { client.getMessageAddressing(SensitiveChats.TELEGRAM_SERVICE_CHAT_ID, any()) }
        every { resolver.resolve(SensitiveChats.TELEGRAM_SERVICE_CHAT_ID) } returns SensitiveChats.TELEGRAM_SERVICE_CHAT_ID
        assertFailsWith<SensitiveChatBlockedException> {
            tool.run(mapOf("chat_id" to SensitiveChats.TELEGRAM_SERVICE_CHAT_ID, "timeout_seconds" to 0))
        }
    }

    @Test fun `chat filter resolves the target and addressed_only is passed through`() {
        val journal = journal()
        val cursor = journal.tailCursor("default")
        journal.append("default", "new", -100, 1) // group, not addressed
        journal.append("default", "new", 43, 2) // private chat
        journal.tailCursor("default")
        every { resolver.resolve("@friend") } returns 43L
        val tool = tool(journal)
        val filtered = tool.run(mapOf("cursor" to cursor, "chat_id" to "@friend", "settle_seconds" to 0, "timeout_seconds" to 1))
        assertEquals(listOf(43L), filtered.messages.map { it.chatId })
        val addressed = tool.run(mapOf("cursor" to cursor, "addressed_only" to true, "settle_seconds" to 0, "timeout_seconds" to 1))
        assertEquals(listOf(2L), addressed.messages.map { it.messageId })
        assertEquals(listOf("private_chat"), addressed.messages.single().addressedBy)
    }

    @Test fun `every profile exposes the wait as a read only operation with a declared output schema`() {
        for (profile in McpToolProfile.entries) {
            assertTrue(ToolSurfacePolicy(McpSecurityProperties(toolProfile = profile))
                .isVisible("wait_for_settled_messages", readOnly = true), profile.name)
        }
        assertEquals("read", AccessPermissionService.actionFor("wait_for_settled_messages"))
        assertTrue(OperationGuardService.annotationsFor("wait_for_settled_messages").readOnlyHint())
        assertNotNull(ToolContractCatalog.schemas["wait_for_settled_messages"])
    }
}
