package dev.telegrammcp.server.service

import dev.telegrammcp.server.config.McpSecurityProperties
import dev.telegrammcp.server.config.McpToolProfile
import org.junit.jupiter.api.Test
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ToolSurfacePolicyTest {

    @Test
    fun `reader profile never exposes mutating or quota consuming tools`() {
        val policy = ToolSurfacePolicy(McpSecurityProperties(toolProfile = McpToolProfile.READER))

        assertTrue(policy.isVisible("get_history", readOnly = false))
        assertFalse(policy.isVisible("send_message", readOnly = false))
        assertFalse(policy.isVisible("transcribe_voice_note", readOnly = false))
        assertFalse(policy.isVisible("download_media", readOnly = false))
    }

    @Test
    fun `research profile retains public discovery but hides communication`() {
        val policy = ToolSurfacePolicy(McpSecurityProperties(toolProfile = McpToolProfile.RESEARCH))

        assertTrue(policy.isVisible("search_public_messages", readOnly = false))
        assertTrue(policy.isVisible("get_history", readOnly = false))
        assertFalse(policy.isVisible("send_message", readOnly = false))
        assertFalse(policy.isVisible("create_group", readOnly = false))
        assertFalse(policy.isVisible("download_media", readOnly = false))
    }

    @Test
    fun `read only mode further narrows write-capable profiles`() {
        val policy = ToolSurfacePolicy(McpSecurityProperties(toolProfile = McpToolProfile.COMMUNITY_ADMIN))

        assertTrue(policy.isVisible("get_admins", readOnly = true))
        assertFalse(policy.isVisible("set_admin_rights", readOnly = true))
    }

    @Test
    fun `exact allow and deny compose after profile`() {
        val policy = ToolSurfacePolicy(
            McpSecurityProperties(
                toolProfile = McpToolProfile.INBOX,
                toolAllow = listOf("get_history", "send_message", "create_group"),
                toolDeny = listOf("send_message"),
            ),
        )

        assertTrue(policy.isVisible("get_history", readOnly = false))
        assertFalse(policy.isVisible("send_message", readOnly = false))
        assertFalse(policy.isVisible("create_group", readOnly = false))
        assertFalse(policy.isVisible("list_contacts", readOnly = false))
    }

    @Test
    fun `unknown exact tool names fail validation`() {
        val policy = ToolSurfacePolicy(
            McpSecurityProperties(toolAllow = listOf("get_history", "get_hstory")),
        )

        assertFailsWith<IllegalArgumentException> {
            policy.validateConfiguredNames(listOf("get_history", "send_message"))
        }
    }
}
