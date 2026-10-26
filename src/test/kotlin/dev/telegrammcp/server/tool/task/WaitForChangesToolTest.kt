package dev.telegrammcp.server.tool.task

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import dev.telegrammcp.server.client.TelegramAccountContext
import dev.telegrammcp.server.config.McpSecurityProperties
import dev.telegrammcp.server.config.McpToolProfile
import dev.telegrammcp.server.config.TelegramProperties
import dev.telegrammcp.server.exception.ChatNotAllowedException
import dev.telegrammcp.server.security.AccessPermissionService
import dev.telegrammcp.server.security.PermissionDocument
import dev.telegrammcp.server.security.PermissionGrant
import dev.telegrammcp.server.service.*
import dev.telegrammcp.server.tool.executeChecked
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import io.mockk.*
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*

class WaitForChangesToolTest {
    @TempDir lateinit var root: Path
    private val mapper = jacksonObjectMapper()
    private val accounts = mockk<TelegramAccountContext>()
    private val resolver = mockk<EntityResolverService>()
    private val operationGuard = mockk<OperationGuardService>(relaxed = true)
    private val audit = mockk<AuditService>(relaxed = true)
    private val journals = mutableListOf<ChangeJournal>()
    @AfterEach fun close() { journals.forEach(ChangeJournal::close) }

    private fun journal(): ChangeJournal {
        val paths = mockk<PlatformPaths>()
        every { paths.applicationDataDirectory } returns root
        every { accounts.currentAccount() } returns "default"
        return ChangeJournal(paths, mapper).also { journals += it }
    }

    private fun tool(journal: ChangeJournal, guard: GuardrailService) = WaitForChangesTool(
        journal, accounts, resolver, guard, operationGuard, audit, mapper, SimpleMeterRegistry())

    @Test fun `wait schema and errors remain typed and executions are audited`() {
        val journal = journal()
        val guard = GuardrailService(McpSecurityProperties(), TelegramProperties())
        val tool = tool(journal, guard)
        assertFalse(tool.executeChecked(mockk(), mapOf("timeout_seconds" to 0)).isError ?: false)
        assertTrue(tool.executeChecked(mockk(), mapOf("timeout_seconds" to 31)).isError ?: false)
        assertTrue(tool.executeChecked(mockk(), mapOf("timeout_seconds" to 0, "cursor" to "wrong:0")).isError ?: false)
        verify(exactly = 3) { audit.record(eq("wait_for_changes"), any(), any(), any(), any(), any()) }
    }

    @Test fun `chat scoped grants filter denied metadata and reject an explicit denied target`() {
        val permissionFile = root.resolve("permissions.json")
        Files.writeString(permissionFile, mapper.writeValueAsString(PermissionDocument(1,
            listOf(PermissionGrant("stdio", "default", setOf("read"), setOf(42))))))
        val policy = AccessPermissionService(McpSecurityProperties(permissionsFile = permissionFile.toString()),
            PlatformPaths(), mapper)
        val guard = GuardrailService(McpSecurityProperties(), TelegramProperties(), policy)
        val journal = journal()
        val cursor = journal.page("default", null, 10) { true }.nextCursor
        journal.append("default", "new", 99, 200)
        journal.append("default", "new", 42, 100)
        journal.page("default", cursor, 10) { true } // flush before a zero-timeout snapshot
        val tool = tool(journal, guard)
        policy.withPermission("wait_for_changes", "default") {
            val result = tool.run(mapOf("cursor" to cursor, "timeout_seconds" to 0))
            assertEquals(listOf(42L), result.entries.map { it.chatId })
            every { resolver.resolve(99L) } returns 99L
            assertFailsWith<ChatNotAllowedException> { tool.run(mapOf("chat_id" to 99L, "timeout_seconds" to 0)) }
        }
        // A direct handler invocation without an invocation grant fails closed for every chat.
        val denied = tool.run(mapOf("cursor" to cursor, "timeout_seconds" to 0))
        assertTrue(denied.entries.isEmpty())
    }

    @Test fun `optional chat filter returns only its target and validates integer bounds`() {
        val journal = journal()
        val cursor = journal.page("default", null, 10) { true }.nextCursor
        journal.append("default", "new", 42, 100)
        journal.append("default", "new", 43, 200)
        journal.page("default", cursor, 10) { true } // flush before a zero-timeout snapshot
        every { resolver.resolve("@target") } returns 43L
        val tool = tool(journal, GuardrailService(McpSecurityProperties(), TelegramProperties()))
        val result = tool.run(mapOf("cursor" to cursor, "chat_id" to "@target", "timeout_seconds" to 0))
        assertEquals(listOf(43L), result.entries.map { it.chatId })
        for (args in listOf(mapOf("timeout_seconds" to -1), mapOf("timeout_seconds" to 1.5),
            mapOf("limit" to 0), mapOf("limit" to 201), mapOf("cursor" to 123), mapOf("cursor" to ""))) {
            assertFailsWith<IllegalArgumentException> { tool.run(args) }
        }
    }

    @Test fun `all profiles expose waits as read only operations`() {
        for (profile in McpToolProfile.entries) {
            assertTrue(ToolSurfacePolicy(McpSecurityProperties(toolProfile = profile))
                .isVisible("wait_for_changes", readOnly = true), profile.name)
        }
        assertEquals("read", AccessPermissionService.actionFor("wait_for_changes"))
        assertTrue(OperationGuardService.annotationsFor("wait_for_changes").readOnlyHint())
    }
}
