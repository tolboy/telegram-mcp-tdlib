package dev.telegrammcp.server.service

import dev.telegrammcp.server.config.AntiSpamProperties
import dev.telegrammcp.server.config.ServerModeProperties
import dev.telegrammcp.server.exception.ConfirmationRequiredException
import dev.telegrammcp.server.exception.ReadOnlyModeException
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertDoesNotThrow
import org.junit.jupiter.api.assertThrows
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class OperationGuardServiceTest {

    private fun guard(props: ServerModeProperties): OperationGuardService {
        // Anti-spam disabled in tests so these assertions stay focused on
        // read-only / confirmation logic only.
        val antiSpamProps = AntiSpamProperties(enabled = false, overridesFile = null)
        val antiSpam = AntiSpamGuardService(
            antiSpamProps,
            SimpleMeterRegistry(),
            AntiSpamPolicyService(antiSpamProps),
        )
        return OperationGuardService(props, antiSpam)
    }

    // ── Read-only mode ──────────────────────────────────────────────────────

    @Test
    fun `read-only mode blocks write tools`() {
        val service = guard(ServerModeProperties(readOnly = true))

        assertThrows<ReadOnlyModeException> {
            service.checkPermission("send_message", emptyMap())
        }
    }

    @Test
    fun `read-only mode allows read tools`() {
        val service = guard(ServerModeProperties(readOnly = true))

        assertDoesNotThrow {
            service.checkPermission("get_history", emptyMap())
        }
    }

    @Test
    fun `normal mode allows write tools`() {
        val service = guard(ServerModeProperties(readOnly = false))

        assertDoesNotThrow {
            service.checkPermission("send_message", emptyMap())
        }
    }

    // ── Confirmation mode ───────────────────────────────────────────────────

    @Test
    fun `confirmation mode blocks destructive tool without confirmation`() {
        val service = guard(
            ServerModeProperties(
                confirmation = ServerModeProperties.ConfirmationProps(enabled = true),
            ),
        )

        assertThrows<ConfirmationRequiredException> {
            service.checkPermission("delete_message", emptyMap())
        }
    }

    @Test
    fun `confirmation mode allows destructive tool with confirmed=true`() {
        val service = guard(
            ServerModeProperties(
                confirmation = ServerModeProperties.ConfirmationProps(enabled = true),
            ),
        )

        assertDoesNotThrow {
            service.checkPermission("delete_message", mapOf("confirmed" to true))
        }
    }

    @Test
    fun `confirmation mode allows destructive tool with confirmed=true string`() {
        val service = guard(
            ServerModeProperties(
                confirmation = ServerModeProperties.ConfirmationProps(enabled = true),
            ),
        )

        assertDoesNotThrow {
            service.checkPermission("delete_message", mapOf("confirmed" to "true"))
        }
    }

    @Test
    fun `guardrail-loosening register_internal_chat requires confirmation`() {
        val service = guard(
            ServerModeProperties(
                confirmation = ServerModeProperties.ConfirmationProps(enabled = true),
            ),
        )

        assertThrows<ConfirmationRequiredException> {
            service.checkPermission("register_internal_chat", mapOf("chat_id" to 1L))
        }
        assertDoesNotThrow {
            service.checkPermission("register_internal_chat", mapOf("chat_id" to 1L, "confirmed" to true))
        }
    }

    @Test
    fun `confirmation mode allows non-destructive write tools without confirmation`() {
        val service = guard(
            ServerModeProperties(
                confirmation = ServerModeProperties.ConfirmationProps(enabled = true),
            ),
        )

        assertDoesNotThrow {
            service.checkPermission("send_message", emptyMap())
        }
        assertDoesNotThrow {
            service.checkPermission("create_topic", emptyMap())
        }
        assertDoesNotThrow {
            service.checkPermission("edit_forum_topic", emptyMap())
        }
        assertDoesNotThrow {
            service.checkPermission("close_forum_topic", emptyMap())
        }
        assertDoesNotThrow {
            service.checkPermission("reopen_forum_topic", emptyMap())
        }
        assertDoesNotThrow {
            service.checkPermission("set_forum_topics_enabled", emptyMap())
        }
    }

    @Test
    fun `confirmation mode blocks delete_profile_photo without confirmation`() {
        val service = guard(
            ServerModeProperties(
                confirmation = ServerModeProperties.ConfirmationProps(enabled = true),
            ),
        )

        assertThrows<ConfirmationRequiredException> {
            service.checkPermission("delete_profile_photo", emptyMap())
        }
    }

    @Test
    fun `custom destructive tools override defaults`() {
        val service = guard(
            ServerModeProperties(
                confirmation = ServerModeProperties.ConfirmationProps(
                    enabled = true,
                    destructiveTools = listOf("send_message"),
                ),
            ),
        )

        // Custom list: send_message now requires confirmation
        assertThrows<ConfirmationRequiredException> {
            service.checkPermission("send_message", emptyMap())
        }

        // Default destructive (delete_message) no longer in list
        assertDoesNotThrow {
            service.checkPermission("delete_message", emptyMap())
        }
    }

    // ── Helper methods ──────────────────────────────────────────────────────

    @Test
    fun `isWriteTool identifies write tools correctly`() {
        val service = guard(ServerModeProperties())

        assertTrue(service.isWriteTool("send_message"))
        assertTrue(service.isWriteTool("ban_user"))
        assertTrue(service.isWriteTool("send_voice"))
        assertTrue(service.isWriteTool("transcribe_voice_note"))
        assertTrue(service.isWriteTool("register_internal_chat"))
        assertTrue(service.isWriteTool("configure_chat_folder"))
        assertTrue(service.isWriteTool("delete_chat_folder"))
        assertTrue(service.isWriteTool("schedule_message"))
        assertTrue(service.isWriteTool("reschedule_message"))
        assertTrue(service.isWriteTool("cancel_scheduled_message"))
        assertTrue(service.isWriteTool("save_draft"))
        assertTrue(service.isWriteTool("create_topic"))
        assertTrue(service.isWriteTool("edit_forum_topic"))
        assertTrue(service.isWriteTool("close_forum_topic"))
        assertTrue(service.isWriteTool("reopen_forum_topic"))
        assertTrue(service.isWriteTool("set_forum_topics_enabled"))
        assertTrue(service.isWriteTool("delete_profile_photo"))
        assertFalse(service.isWriteTool("get_history"))
        assertFalse(service.isWriteTool("list_contacts"))
    }

    @Test
    fun `isReadOnly reflects config`() {
        val readOnlyService = guard(ServerModeProperties(readOnly = true))
        val normalService = guard(ServerModeProperties(readOnly = false))

        assertTrue(readOnlyService.isReadOnly())
        assertFalse(normalService.isReadOnly())
    }
}
