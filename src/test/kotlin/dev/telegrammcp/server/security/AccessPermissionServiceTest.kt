package dev.telegrammcp.server.security

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import dev.telegrammcp.server.client.*
import dev.telegrammcp.server.config.*
import dev.telegrammcp.server.exception.ChatNotAllowedException
import dev.telegrammcp.server.service.GuardrailService
import dev.telegrammcp.server.service.PlatformPaths
import dev.telegrammcp.server.service.OperationGuardService
import io.mockk.*
import kotlinx.coroutines.*
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.springframework.security.core.context.SecurityContextHolder
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*

class AccessPermissionServiceTest {
    @TempDir lateinit var directory: Path
    private val mapper = jacksonObjectMapper()
    @AfterEach fun cleanIdentity() { SecurityContextHolder.clearContext() }

    private fun policy(grants: List<PermissionGrant>): AccessPermissionService {
        val file = directory.resolve("permissions.json")
        Files.writeString(file, mapper.writeValueAsString(PermissionDocument(1, grants)))
        return AccessPermissionService(McpSecurityProperties(permissionsFile = file.toString()), PlatformPaths(), mapper)
    }

    @Test
    fun `grants are an intersection with global chat policy and deny missing invocation context`() {
        val policy = policy(listOf(PermissionGrant("stdio", "work", setOf("read", "download"), setOf(42, 43))))
        val guard = GuardrailService(McpSecurityProperties(), TelegramProperties(
            security = TelegramProperties.SecurityProperties(allowedChatIds = listOf(42))), policy)
        assertFalse(guard.isChatAllowed(42))
        policy.withPermission("get_history", "work") {
            assertTrue(guard.isChatAllowed(42))
            assertFalse(guard.isChatAllowed(43))
            assertFailsWith<ChatNotAllowedException> { guard.validateChatAccess(44) }
            assertTrue(guard.hasChatAllowList())
        }
        policy.withPermission("download_media", "work") { guard.validateChatAccess(42) }
        for (tool in listOf("send_message", "transcribe_voice_note", "register_internal_chat", "get_me")) {
            assertFailsWith<PermissionDeniedException>(tool) { policy.withPermission(tool, "work") { fail("must not execute") } }
        }
        assertFalse(guard.isChatAllowed(42))
        assertNull(AccessPermissionContext.current())
    }

    @Test
    fun `client account and action scopes cannot substitute for each other`() {
        val policy = policy(listOf(
            PermissionGrant("reader", "work", setOf("read"), null),
            PermissionGrant("writer", "personal", setOf("mutate"), setOf(42)),
        ))
        SecurityContextHolder.getContext().authentication = ApiKeyAuthToken("reader")
        assertTrue(policy.isAccountVisible("work"))
        assertFalse(policy.isAccountVisible("personal"))
        policy.withPermission("get_me", "work") { assertNull(policy.requestChatIds()) }
        assertFailsWith<PermissionDeniedException> { policy.withPermission("send_message", "work") {} }
        SecurityContextHolder.getContext().authentication = ApiKeyAuthToken("writer")
        policy.withPermission("send_message", "personal") { assertEquals(setOf(42L), policy.requestChatIds()) }
        assertFailsWith<PermissionDeniedException> { policy.withPermission("get_history", "personal") {} }
        assertFailsWith<PermissionDeniedException> { policy.withPermission("send_message", "work") {} }
    }

    @Test
    fun `account listings respect both API key scopes and grants`() {
        val policy = policy(listOf(PermissionGrant("agent", "work", setOf("read"), null)))
        val registry = TelegramAccountRegistry().also {
            for (label in listOf("work", "personal")) it.register(TelegramAccountRegistry.AccountHandle(label, mockk(relaxed = true)))
        }
        SecurityContextHolder.getContext().authentication = ApiKeyAuthToken("agent", setOf("work", "personal"))
        val access = AccountAccessPolicy(registry, permissions = policy)
        assertEquals(listOf("work"), access.visibleAccounts())
        assertFailsWith<dev.telegrammcp.server.exception.AccountAccessDeniedException> { access.selectAccount(mapOf("account" to "personal")) }
        SecurityContextHolder.getContext().authentication = ApiKeyAuthToken("agent", setOf("personal"))
        assertTrue(access.visibleAccounts().isEmpty())
    }

    @Test
    fun `all writes have separate capabilities and scoped unknown tools fail closed`() {
        for (tool in OperationGuardService.WRITE_TOOLS) assertNotEquals("read", AccessPermissionService.actionFor(tool), tool)
        assertEquals("download", AccessPermissionService.actionFor("download_media"))
        assertEquals("quota", AccessPermissionService.actionFor("transcribe_voice_note"))
        assertEquals("policy", AccessPermissionService.actionFor("register_internal_chat"))
        val policy = policy(listOf(PermissionGrant("stdio", "work", setOf("read"), setOf(42))))
        assertFailsWith<PermissionDeniedException> { policy.withPermission("future_unreviewed_tool", "work") {} }
    }

    @Test
    fun `invalid configuration never silently falls back to permissive mode`() {
        for (json in listOf(
            "{\"version\":2,\"grants\":[]}",
            "{\"version\":1,\"grants\":[{\"client\":\"stdio\",\"account\":\"work\",\"actions\":[\"read\"]}]}",
            "{\"version\":1,\"grants\":[{\"client\":\"stdio\",\"account\":\"work\",\"actions\":[\"typo\"],\"chat_ids\":null}]}",
            "{\"version\":1,\"grants\":[],\"typo\":true}",
        )) {
            val file = Files.writeString(directory.resolve("bad.json"), json)
            assertFails { AccessPermissionService(McpSecurityProperties(permissionsFile = file.toString()), PlatformPaths(), mapper) }
        }
        val denyAll = policy(emptyList())
        assertFailsWith<PermissionDeniedException> { denyAll.withPermission("get_me", "work") {} }
    }

    @Test
    fun `account and grants propagate into reused coroutine workers without leaking between requests`() {
        val registry = TelegramAccountRegistry().also {
            for (label in listOf("work", "personal")) it.register(TelegramAccountRegistry.AccountHandle(label, mockk(relaxed = true)))
        }
        val account = TelegramAccountContext(registry)
        for ((label, chat) in listOf("work" to 42L, "personal" to 43L)) {
            account.withAccount(label) {
                AccessPermissionContext.withScope(AccessPermissionContext.Scope(setOf(chat))) {
                    runBlocking(account.coroutineContext() + AccessPermissionContext.coroutineContext()) {
                        (1..12).map {
                            async(Dispatchers.IO) {
                                runInterruptible {
                                    assertEquals(label, account.currentAccount())
                                    assertEquals(setOf(chat), AccessPermissionContext.current()?.chatIds)
                                }
                            }
                        }.awaitAll()
                    }
                }
            }
            assertNull(AccessPermissionContext.current())
            assertFails { account.currentAccount() }
        }
    }
}
