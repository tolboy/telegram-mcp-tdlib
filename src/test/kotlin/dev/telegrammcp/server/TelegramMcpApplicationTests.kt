package dev.telegrammcp.server

import dev.telegrammcp.server.tool.McpToolHandler
import io.modelcontextprotocol.spec.McpStreamableServerTransportProvider
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.ApplicationContext
import org.springframework.test.context.ActiveProfiles
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Integration smoke tests for application auto-configuration, security, MCP
 * transport, and the documented tool inventory.
 *
 * TDLib is not configured in the test profile, so the account registry exposes
 * supplies a no-op `TelegramClientService`. Tools return a clear unavailable
 * error if invoked without a real Telegram client.
 */
@SpringBootTest
@ActiveProfiles("test")
class TelegramMcpApplicationTests {

    @Autowired
    private lateinit var applicationContext: ApplicationContext

    @Autowired
    private lateinit var handlers: List<McpToolHandler>

    @Test
    fun contextLoads() {
        // The injected dependencies verify that the full application boots.
    }

    @Test
    fun `registers the documented tool inventory on Streamable HTTP`() {
        val names = handlers.map { it.definition().name() }

        assertEquals(110, names.size)
        assertEquals(names.size, names.toSet().size)
        assertTrue("search_public_messages" in names)
        assertFalse("get_chat_promotion_policy" in names)
        assertTrue("discover_public_chats" in names)
        assertTrue("list_accounts" in names)
        assertTrue("transcribe_voice_note" in names)
        assertTrue("get_message_link" in names)
        assertTrue("configure_chat_folder" in names)
        assertTrue("list_scheduled_messages" in names)
        assertTrue("schedule_message" in names)
        assertTrue("set_privacy_settings" in names)
        assertTrue("set_bot_commands" in names)
        assertTrue("set_group_permissions" in names)
        assertTrue("set_admin_rights" in names)
        assertTrue("vote_poll" in names)
        assertTrue("close_poll" in names)
        assertTrue("get_message_viewers" in names)
        assertTrue("set_chat_description" in names)
        assertTrue("set_slow_mode" in names)
        assertTrue("list_invite_links" in names)
        assertTrue("revoke_invite_link" in names)
        assertTrue("reorder_chat_folders" in names)
        assertTrue("get_common_chats" in names)
        assertTrue(
            applicationContext.getBeansOfType(McpStreamableServerTransportProvider::class.java).isNotEmpty(),
        )
    }
}
