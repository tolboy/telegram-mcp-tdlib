package dev.telegrammcp.server.service

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import dev.telegrammcp.server.config.McpSecurityProperties
import dev.telegrammcp.server.config.McpToolProfile
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ToolNameWildcardTest {
    private val registered = listOf(
        "_manifest", "get_history", "get_messages", "get_chat", "send_message", "send_file",
        "delete_message", "delete_chat_photo", "search_global",
    )
    private val logger = LoggerFactory.getLogger(ToolSurfacePolicy::class.java) as Logger
    private val appender = ListAppender<ILoggingEvent>()

    @BeforeEach fun capture() {
        appender.start()
        logger.addAppender(appender)
    }

    @AfterEach fun release() {
        logger.detachAppender(appender)
    }

    private fun policy(allow: List<String> = emptyList(), deny: List<String> = emptyList()) =
        ToolSurfacePolicy(McpSecurityProperties(toolProfile = McpToolProfile.ALL, toolAllow = allow, toolDeny = deny))

    private fun ToolSurfacePolicy.visible() = visibleToolNames(registered, readOnly = false)

    private fun messages(level: Level) = appender.list.filter { it.level == level }.map { it.formattedMessage }

    @Test
    fun `a trailing wildcard matches every tool with that prefix`() {
        assertEquals(listOf("get_history", "get_messages", "get_chat"), policy(allow = listOf("get_*")).visible())
        assertEquals(listOf("get_history", "get_messages"), policy(allow = listOf("get_h*", "get_messages")).visible())
    }

    @Test
    fun `deny wins over allow, for exact names and wildcards alike`() {
        assertEquals(listOf("get_messages", "get_chat"), policy(allow = listOf("get_*"), deny = listOf("get_history")).visible())
        assertEquals(emptyList(), policy(allow = listOf("send_message"), deny = listOf("send_*")).visible())
        assertEquals(
            listOf("_manifest", "get_history", "get_messages", "get_chat", "search_global"),
            policy(deny = listOf("send_*", "delete_*")).visible(),
        )
    }

    @Test
    fun `a bare star in the allow list allows everything and says so at startup`() {
        val policy = policy(allow = listOf("*"), deny = listOf("delete_*"))
        assertEquals(registered.filterNot { it.startsWith("delete_") }, policy.visible())
        policy.validateConfiguredNames(registered)
        assertTrue(messages(Level.INFO).any { "MCP_TOOL_ALLOW contains '*'" in it && "every tool" in it }, appender.list.toString())
        assertTrue(messages(Level.WARN).isEmpty(), messages(Level.WARN).toString())
    }

    @Test
    fun `a bare star in the deny list is refused`() {
        val error = assertFailsWith<IllegalArgumentException> { policy(deny = listOf("*")) }
        assertTrue("MCP_TOOL_DENY" in error.message.orEmpty(), error.message)
    }

    @Test
    fun `only a single trailing star is accepted`() {
        for (bad in listOf("*_message", "get_*_x", "get**", "ge*t", "send message", "get-history", "get.*")) {
            val allow = assertFailsWith<IllegalArgumentException>(bad) { policy(allow = listOf(bad)) }
            assertTrue("Invalid MCP_TOOL_ALLOW entry '$bad'" in allow.message.orEmpty(), allow.message)
            val deny = assertFailsWith<IllegalArgumentException>(bad) { policy(deny = listOf(bad)) }
            assertTrue("Invalid MCP_TOOL_DENY entry '$bad'" in deny.message.orEmpty(), deny.message)
        }
        // Whitespace and blank entries are ignored as before.
        assertEquals(listOf("get_chat"), policy(allow = listOf(" get_chat ", "", "  ")).visible())
    }

    @Test
    fun `an allow wildcard that matches no tool only warns because it fails safe`() {
        val policy = policy(allow = listOf("get_*", "stories_*"), deny = listOf("send_*"))
        policy.validateConfiguredNames(registered)
        val warnings = messages(Level.WARN)
        assertTrue(warnings.any { "MCP_TOOL_ALLOW entry 'stories_*' matches no registered tool" in it }, warnings.toString())
        assertFalse(warnings.any { "get_*" in it || "send_*" in it }, warnings.toString())
        assertEquals(listOf("get_history", "get_messages", "get_chat"), policy.visible())
    }

    @Test
    fun `a deny wildcard that matches no tool warns and stops startup`() {
        val error = assertFailsWith<IllegalArgumentException> {
            policy(deny = listOf("send_*", "delte_*", "nothing_*")).validateConfiguredNames(registered)
        }
        val message = error.message.orEmpty()
        assertTrue("MCP_TOOL_DENY pattern(s) match no registered tool" in message, message)
        assertTrue("delte_*" in message && "nothing_*" in message, message)
        assertFalse("send_*" in message, message)
        val warnings = messages(Level.WARN)
        assertTrue(warnings.any { "MCP_TOOL_DENY entry 'delte_*' matches no registered tool" in it }, warnings.toString())
        assertTrue(warnings.any { "MCP_TOOL_DENY entry 'nothing_*' matches no registered tool" in it }, warnings.toString())
    }

    @Test
    fun `unknown exact names and unmatched deny wildcards are reported together`() {
        val error = assertFailsWith<IllegalArgumentException> {
            policy(allow = listOf("get_histroy", "stories_*"), deny = listOf("delte_*")).validateConfiguredNames(registered)
        }
        val message = error.message.orEmpty()
        assertTrue("Unknown MCP tool name(s)" in message && "get_histroy" in message, message)
        assertTrue("MCP_TOOL_DENY pattern(s)" in message && "delte_*" in message, message)
        assertFalse("stories_*" in message, "an unmatched allow wildcard never fails startup: $message")
    }

    @Test
    fun `an unknown exact name warns and still stops startup`() {
        val error = assertFailsWith<IllegalArgumentException> {
            policy(allow = listOf("get_*", "get_histroy"), deny = listOf("sned_message")).validateConfiguredNames(registered)
        }
        assertTrue("get_histroy" in error.message.orEmpty() && "sned_message" in error.message.orEmpty(), error.message)
        val warnings = messages(Level.WARN)
        assertTrue(warnings.any { "'get_histroy' matches no registered tool" in it }, warnings.toString())
        assertTrue(warnings.any { "'sned_message' matches no registered tool" in it }, warnings.toString())
    }

    @Test
    fun `wildcards compose with the profile and read-only mode`() {
        val reader = ToolSurfacePolicy(McpSecurityProperties(toolProfile = McpToolProfile.READER, toolAllow = listOf("*")))
        assertFalse(reader.isVisible("send_message", readOnly = false), "allow '*' never widens the profile")
        val all = policy(allow = listOf("send_*"))
        assertFalse(all.isVisible("send_message", readOnly = true), "read-only still hides writes")
        assertTrue(all.isVisible("send_message", readOnly = false))
    }
}
