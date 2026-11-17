package dev.telegrammcp.server.security

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import dev.telegrammcp.server.client.TelegramAccountContext
import dev.telegrammcp.server.config.McpSecurityProperties
import dev.telegrammcp.server.config.ServerModeProperties
import dev.telegrammcp.server.config.TelegramProperties
import dev.telegrammcp.server.exception.ChatNotAllowedException
import dev.telegrammcp.server.model.ChatInfo
import dev.telegrammcp.server.model.ChatType
import dev.telegrammcp.server.resource.TelegramResourceAccess
import dev.telegrammcp.server.resource.TelegramResourceUri
import dev.telegrammcp.server.service.AuditService
import dev.telegrammcp.server.service.ChangeJournal
import dev.telegrammcp.server.service.EntityResolverService
import dev.telegrammcp.server.service.GuardrailService
import dev.telegrammcp.server.service.OperationGuardService
import dev.telegrammcp.server.service.PlatformPaths
import dev.telegrammcp.server.service.ToolSurfacePolicy
import dev.telegrammcp.server.tool.chat.ListChatsTool
import dev.telegrammcp.server.tool.executeChecked
import dev.telegrammcp.server.tool.task.WaitForChangesTool
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import io.mockk.every
import io.mockk.mockk
import io.modelcontextprotocol.spec.McpSchema
import it.tdlight.jni.TdApi
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.springframework.security.core.context.SecurityContextHolder
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SensitiveChatsTest {
    @TempDir lateinit var directory: Path
    private val mapper = jacksonObjectMapper()
    private val sensitive = listOf(
        SensitiveChats.TELEGRAM_SERVICE_CHAT_ID,
        SensitiveChats.VERIFICATION_CODES_CHAT_ID,
        SensitiveChats.BOT_FATHER_USER_ID,
        SensitiveChats.SPAM_BOT_USER_ID,
    )

    @AfterEach fun reset() {
        SecurityContextHolder.clearContext()
        SensitiveChats.clearResolvedForTests()
    }

    private fun guard(
        security: TelegramProperties.SecurityProperties = TelegramProperties.SecurityProperties(),
        permissions: AccessPermissionService? = null,
    ) = GuardrailService(McpSecurityProperties(), TelegramProperties(security = security), permissions)

    private fun permissions(vararg grants: PermissionGrant): AccessPermissionService {
        val file = directory.resolve("permissions.json")
        Files.writeString(file, mapper.writeValueAsString(PermissionDocument(1, grants.toList())))
        return AccessPermissionService(McpSecurityProperties(permissionsFile = file.toString()), PlatformPaths(), mapper)
    }

    @Test
    fun `code chats and sensitive bots are blocked by default even when every chat is allowed`() {
        val guard = guard()
        sensitive.forEach { chat ->
            assertFalse(guard.isChatAllowed(chat), "chat $chat")
            val error = assertFailsWith<SensitiveChatBlockedException> { guard.validateChatAccess(chat) }
            assertTrue("$chat" in error.message, error.message)
            assertTrue("TELEGRAM_ALLOW_SENSITIVE_CHATS" in error.message, error.message)
        }
        assertTrue(guard.isChatAllowed(42))
        guard.validateChatAccess(42)
    }

    @Test
    fun `a derived sensitive chat is refused without echoing its ID`() {
        val error = assertFailsWith<SensitiveChatBlockedException> {
            guard().validateDerivedChatAccess(SensitiveChats.BOT_FATHER_USER_ID)
        }
        assertFalse("${SensitiveChats.BOT_FATHER_USER_ID}" in error.message, error.message)
        // Still reported as a chat permission error to existing error mapping.
        assertTrue(error is ChatNotAllowedException)
    }

    @Test
    fun `the global opt-in lifts the block for every sensitive chat`() {
        val guard = guard(TelegramProperties.SecurityProperties(allowSensitiveChats = true))
        sensitive.forEach { assertTrue(guard.isChatAllowed(it)) }
    }

    @Test
    fun `naming a sensitive chat in the static allow-list lifts the block for that chat only`() {
        val guard = guard(TelegramProperties.SecurityProperties(
            allowedChatIds = listOf(SensitiveChats.TELEGRAM_SERVICE_CHAT_ID, SensitiveChats.BOT_FATHER_USER_ID)))
        assertTrue(guard.isChatAllowed(SensitiveChats.TELEGRAM_SERVICE_CHAT_ID))
        assertTrue(guard.isChatAllowed(SensitiveChats.BOT_FATHER_USER_ID))
        assertFalse(guard.isChatAllowed(SensitiveChats.SPAM_BOT_USER_ID))
    }

    @Test
    fun `an all-chats grant keeps the block and only an explicit grant opt-in lifts it`() {
        val policy = permissions(
            PermissionGrant("stdio", "work", setOf("read"), null),
            PermissionGrant("opted", "work", setOf("read"), null, allowSensitiveChats = true),
            PermissionGrant("named", "work", setOf("read"), setOf(SensitiveChats.TELEGRAM_SERVICE_CHAT_ID, 42)),
        )
        val guard = guard(permissions = policy)
        policy.withPermission("get_history", "work") {
            assertTrue(guard.isChatAllowed(42))
            sensitive.forEach { assertFalse(guard.isChatAllowed(it)) }
            assertFailsWith<SensitiveChatBlockedException> { guard.validateChatAccess(SensitiveChats.TELEGRAM_SERVICE_CHAT_ID) }
        }
        SecurityContextHolder.getContext().authentication = ApiKeyAuthToken("opted")
        policy.withPermission("get_history", "work") {
            sensitive.forEach { assertTrue(guard.isChatAllowed(it)) }
        }
        SecurityContextHolder.getContext().authentication = ApiKeyAuthToken("named")
        policy.withPermission("get_history", "work") {
            assertTrue(guard.isChatAllowed(SensitiveChats.TELEGRAM_SERVICE_CHAT_ID))
            assertFalse(guard.isChatAllowed(SensitiveChats.BOT_FATHER_USER_ID))
        }
        // The grant flag never outlives its invocation.
        sensitive.forEach { assertFalse(policy.requestAllowsSensitiveChat(it)) }
    }

    @Test
    fun `an unrestricted ordinary grant does not widen a scoped sensitive opt-in`() {
        val policy = permissions(
            PermissionGrant("stdio", "*", setOf("read"), null),
            PermissionGrant("stdio", "work", setOf("read"), setOf(SensitiveChats.SPAM_BOT_USER_ID), allowSensitiveChats = true),
        )
        val guard = guard(permissions = policy)
        policy.withPermission("get_history", "work") {
            assertTrue(guard.isChatAllowed(42))
            assertTrue(guard.isChatAllowed(SensitiveChats.SPAM_BOT_USER_ID))
            assertFalse(guard.isChatAllowed(SensitiveChats.TELEGRAM_SERVICE_CHAT_ID))
            assertFalse(guard.isChatAllowed(SensitiveChats.BOT_FATHER_USER_ID))
        }
        policy.withPermission("get_history", "personal") {
            assertFalse(guard.isChatAllowed(SensitiveChats.SPAM_BOT_USER_ID))
        }
    }

    @Test
    fun `an unrestricted ordinary grant preserves an explicit sensitive ID from another grant`() {
        val policy = permissions(
            PermissionGrant("stdio", "work", setOf("read", "download"), null),
            PermissionGrant("stdio", "work", setOf("read"), setOf(SensitiveChats.TELEGRAM_SERVICE_CHAT_ID)),
        )
        val guard = guard(permissions = policy)
        policy.withPermission("get_history", "work") {
            assertTrue(guard.isChatAllowed(SensitiveChats.TELEGRAM_SERVICE_CHAT_ID))
            assertFalse(guard.isChatAllowed(SensitiveChats.SPAM_BOT_USER_ID))
        }
        policy.withPermission("download_media", "work") {
            assertFalse(guard.isChatAllowed(SensitiveChats.TELEGRAM_SERVICE_CHAT_ID))
        }
    }

    @Test
    fun `resource permissions keep a sensitive opt-in specific to the subscribed chat`() {
        val policy = permissions(
            PermissionGrant("reader", "*", setOf("read"), null),
            PermissionGrant("reader", "work", setOf("read"), setOf(SensitiveChats.SPAM_BOT_USER_ID), allowSensitiveChats = true),
        )
        val accounts = mockk<AccountAccessPolicy>()
        every { accounts.selectAccount(mapOf("account" to "work")) } returns "work"
        val surface = mockk<ToolSurfacePolicy>()
        every { surface.isVisible(any(), any(), any()) } returns true
        val resources = TelegramResourceAccess(accounts, surface, ServerModeProperties(), guard(permissions = policy), policy)
        val reader = ApiKeyAuthToken("reader")
        val chatList = TelegramResourceUri.Chats("work")

        assertTrue(resources.permits(TelegramResourceUri.Message("work", SensitiveChats.SPAM_BOT_USER_ID, 1), reader))
        assertFalse(resources.permits(TelegramResourceUri.Message("work", SensitiveChats.TELEGRAM_SERVICE_CHAT_ID, 1), reader))
        assertTrue(resources.permitsChatWithin(chatList, SensitiveChats.SPAM_BOT_USER_ID, reader))
        assertFalse(resources.permitsChatWithin(chatList, SensitiveChats.VERIFICATION_CODES_CHAT_ID, reader))
    }

    @Test
    fun `permission files may omit allow_sensitive_chats and parse it when present`() {
        val file = directory.resolve("permissions.json")
        Files.writeString(file, """
            {"version":1,"grants":[
              {"client":"stdio","account":"work","actions":["read"],"chat_ids":null},
              {"client":"ops","account":"work","actions":["read"],"chat_ids":null,"allow_sensitive_chats":true}
            ]}
        """.trimIndent())
        val policy = AccessPermissionService(McpSecurityProperties(permissionsFile = file.toString()), PlatformPaths(), mapper)
        policy.withPermission("get_history", "work") {
            sensitive.forEach { assertFalse(policy.requestAllowsSensitiveChat(it)) }
        }
        SecurityContextHolder.getContext().authentication = ApiKeyAuthToken("ops")
        policy.withPermission("get_history", "work") {
            sensitive.forEach { assertTrue(policy.requestAllowsSensitiveChat(it)) }
        }
    }

    @Test
    fun `permission files require a boolean sensitive opt-in`() {
        val file = directory.resolve("permissions.json")
        for (value in listOf("\"true\"", "1", "null")) {
            Files.writeString(file, """
                {"version":1,"grants":[
                  {"client":"stdio","account":"work","actions":["read"],"chat_ids":null,"allow_sensitive_chats":$value}
                ]}
            """.trimIndent())
            assertFailsWith<IllegalArgumentException> {
                AccessPermissionService(McpSecurityProperties(permissionsFile = file.toString()), PlatformPaths(), mapper)
            }
        }
    }

    @Test
    fun `runtime-resolved bot chats are blocked and the set only grows`() {
        val guard = guard()
        assertTrue(guard.isChatAllowed(5_000_001))
        assertTrue(SensitiveChats.registerResolved(5_000_001))
        assertFalse(guard.isChatAllowed(5_000_001))
        assertFalse(SensitiveChats.registerResolved(-100123), "group and channel IDs are never bot chats")
        assertTrue(guard.isChatAllowed(-100123))
    }

    @Test
    fun `discovery accepts only a bot's own private chat`() {
        val botChat = TdApi.Chat().apply { id = 5_000_002; type = TdApi.ChatTypePrivate(5_000_002) }
        val mismatched = TdApi.Chat().apply { id = 5_000_003; type = TdApi.ChatTypePrivate(5_000_004) }
        val group = TdApi.Chat().apply { id = -100_5L; type = TdApi.ChatTypeSupergroup(5, false) }
        assertTrue(dev.telegrammcp.server.client.SensitiveChatDiscovery.accept(botChat))
        assertFalse(dev.telegrammcp.server.client.SensitiveChatDiscovery.accept(mismatched))
        assertFalse(dev.telegrammcp.server.client.SensitiveChatDiscovery.accept(group))
        assertFalse(dev.telegrammcp.server.client.SensitiveChatDiscovery.accept(null))

        val looked = mutableListOf<String>()
        dev.telegrammcp.server.client.SensitiveChatDiscovery.discover("work") { username ->
            looked += username
            java.util.concurrent.CompletableFuture.completedFuture(botChat)
        }
        assertEquals(SensitiveChats.BOT_USERNAMES, looked)
        assertTrue(SensitiveChats.isSensitive(5_000_002))
    }

    @Test
    fun `multi-chat results omit sensitive chats`() {
        val client = mockk<dev.telegrammcp.server.client.TelegramClientService>()
        every { client.getChats(50) } returns listOf(
            ChatInfo(chatId = SensitiveChats.TELEGRAM_SERVICE_CHAT_ID, title = "Telegram", type = ChatType.PRIVATE),
            ChatInfo(chatId = SensitiveChats.BOT_FATHER_USER_ID, title = "BotFather", type = ChatType.PRIVATE),
            ChatInfo(chatId = 42, title = "Friend", type = ChatType.PRIVATE),
        )
        val tool = ListChatsTool(client, guard(), mapper, SimpleMeterRegistry())
        val text = (tool.executeChecked(mockk(relaxed = true), emptyMap()).content.first() as McpSchema.TextContent).text()
        assertTrue("Friend" in text, text)
        assertFalse("BotFather" in text || "777000" in text, text)
    }

    @Test
    fun `wait_for_changes omits sensitive chats and refuses them as an explicit target`() {
        val accounts = mockk<TelegramAccountContext>()
        every { accounts.currentAccount() } returns "default"
        val paths = mockk<PlatformPaths>()
        every { paths.applicationDataDirectory } returns directory
        val resolver = mockk<EntityResolverService>()
        every { resolver.resolve(SensitiveChats.TELEGRAM_SERVICE_CHAT_ID) } returns SensitiveChats.TELEGRAM_SERVICE_CHAT_ID
        ChangeJournal(paths, mapper).use { journal ->
            val cursor = journal.page("default", null, 10) { true }.nextCursor
            journal.append("default", "new", SensitiveChats.TELEGRAM_SERVICE_CHAT_ID, 1)
            journal.append("default", "new", 42, 2)
            journal.page("default", cursor, 10) { true }
            val tool = WaitForChangesTool(journal, accounts, resolver, guard(), mockk<OperationGuardService>(relaxed = true),
                mockk<AuditService>(relaxed = true), mapper, SimpleMeterRegistry())
            assertEquals(listOf(42L), tool.run(mapOf("cursor" to cursor, "timeout_seconds" to 0)).entries.map { it.chatId })
            assertFailsWith<SensitiveChatBlockedException> {
                tool.run(mapOf("chat_id" to SensitiveChats.TELEGRAM_SERVICE_CHAT_ID, "timeout_seconds" to 0))
            }
        }
    }

    @Test
    fun `search_global drops hits from sensitive chats`() {
        val client = mockk<dev.telegrammcp.server.client.TelegramClientService>()
        val now = java.time.Instant.parse("2026-11-17T12:00:00Z")
        every { client.searchGlobal("login code", 20) } returns listOf(
            dev.telegrammcp.server.model.TelegramMessage(1, SensitiveChats.TELEGRAM_SERVICE_CHAT_ID, "Telegram", "Telegram", text = "Login code: 12345", date = now),
            dev.telegrammcp.server.model.TelegramMessage(2, 42, "Friend", "Friend", text = "what was the login code thing?", date = now),
            dev.telegrammcp.server.model.TelegramMessage(3, SensitiveChats.VERIFICATION_CODES_CHAT_ID, "Verification Codes", "External service", text = "Third-party code: 54321", date = now),
        )
        val tool = dev.telegrammcp.server.tool.message.SearchGlobalTool(client, guard(), mockk<AuditService>(relaxed = true), jacksonObjectMapper().findAndRegisterModules(), SimpleMeterRegistry())
        val text = (tool.executeChecked(mockk(relaxed = true), mapOf("query" to "login code")).content.first() as McpSchema.TextContent).text()
        assertTrue("Friend" in text, text)
        assertFalse("12345" in text, text)
        assertFalse("54321" in text, text)
    }

    @Test
    fun `changes_since omits sensitive chats from the change journal`() {
        val accounts = mockk<TelegramAccountContext>()
        every { accounts.currentAccount() } returns "default"
        val paths = mockk<PlatformPaths>()
        every { paths.applicationDataDirectory } returns directory
        ChangeJournal(paths, mapper).use { journal ->
            val cursor = journal.page("default", null, 10) { true }.nextCursor
            journal.append("default", "new", SensitiveChats.BOT_FATHER_USER_ID, 1)
            journal.append("default", "new", 42, 2)
            val tool = dev.telegrammcp.server.tool.task.ChangesSinceTool(journal, accounts, guard(), mapper, SimpleMeterRegistry())
            assertEquals(listOf(42L), tool.run(mapOf("cursor" to cursor)).entries.map { it.chatId })
        }
    }

    @Test
    fun `exports refuse sensitive chats, including jobs created before the block`() {
        val accounts = mockk<TelegramAccountContext>()
        every { accounts.currentAccount() } returns "default"
        val paths = mockk<PlatformPaths>()
        every { paths.applicationDataDirectory } returns directory
        val client = mockk<dev.telegrammcp.server.client.TelegramClientService>()
        val exportMapper = mapper.copy().findAndRegisterModules()
        every { client.getHistory(SensitiveChats.TELEGRAM_SERVICE_CHAT_ID, 0, 0, 100) } returns listOf(
            dev.telegrammcp.server.model.TelegramMessage(1, SensitiveChats.TELEGRAM_SERVICE_CHAT_ID,
                "Telegram", "Telegram", text = "Login code", date = java.time.Instant.EPOCH),
        )
        val opened = dev.telegrammcp.server.service.ExportJobService(paths, exportMapper, accounts, client,
            guard(TelegramProperties.SecurityProperties(allowSensitiveChats = true)))
        val job = opened.create(SensitiveChats.TELEGRAM_SERVICE_CHAT_ID).job
        assertEquals(1, opened.operate(job.jobId, "resume").job.pages)

        val blocked = dev.telegrammcp.server.service.ExportJobService(paths, exportMapper, accounts, client, guard())
        assertFailsWith<SensitiveChatBlockedException> { blocked.create(SensitiveChats.TELEGRAM_SERVICE_CHAT_ID) }
        assertFailsWith<SensitiveChatBlockedException> { blocked.operate(job.jobId, "resume") }
        assertFailsWith<SensitiveChatBlockedException> { blocked.operate(job.jobId, "page", 0) }
    }
}
