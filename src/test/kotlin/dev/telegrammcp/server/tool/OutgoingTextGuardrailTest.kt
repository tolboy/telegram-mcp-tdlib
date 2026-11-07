package dev.telegrammcp.server.tool

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import dev.telegrammcp.server.client.TelegramClientService
import dev.telegrammcp.server.config.McpSecurityProperties
import dev.telegrammcp.server.config.TelegramProperties
import dev.telegrammcp.server.model.TelegramMessage
import dev.telegrammcp.server.service.AuditService
import dev.telegrammcp.server.service.EntityResolverService
import dev.telegrammcp.server.service.FileSecurityService
import dev.telegrammcp.server.service.GuardrailService
import dev.telegrammcp.server.service.OperationGuardService
import dev.telegrammcp.server.tool.chat.ListJoinRequestsTool
import dev.telegrammcp.server.tool.media.SendAlbumTool
import dev.telegrammcp.server.tool.media.SendFileTool
import dev.telegrammcp.server.tool.message.SendContactTool
import dev.telegrammcp.server.tool.message.SendVenueTool
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.modelcontextprotocol.server.McpSyncServerExchange
import io.modelcontextprotocol.spec.McpSchema
import org.junit.jupiter.api.Test
import java.nio.file.Path
import java.time.Instant
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Every outgoing text field of the send tools goes through the configured input
 * filter (`mcp.guardrails.blocked-patterns`, `max-tool-input-length`), as
 * `send_file` captions always did. Adapted from the external review's reproducer.
 */
class OutgoingTextGuardrailTest {
    private val client = mockk<TelegramClientService>()
    private val resolver = mockk<EntityResolverService>()
    private val guard = GuardrailService(
        McpSecurityProperties(
            guardrails = McpSecurityProperties.GuardrailProps(blockedPatterns = listOf("SECRET_TOKEN"), maxToolInputLength = 100),
        ),
        TelegramProperties(),
    )
    private val ops = mockk<OperationGuardService>(relaxed = true)
    private val audit = mockk<AuditService>(relaxed = true)
    private val mapper = jacksonObjectMapper().findAndRegisterModules()
    private val meter = SimpleMeterRegistry()
    private val exchange = mockk<McpSyncServerExchange>(relaxed = true)
    private val files = mockk<FileSecurityService>()
    private val message = TelegramMessage(1L, 42L, "Test", "Me", text = null, date = Instant.EPOCH, mediaType = "photo")

    init {
        every { resolver.resolve(42 as Any) } returns 42L
        every { files.withStagedUpload<Any?>(any(), any()) } answers {
            @Suppress("UNCHECKED_CAST")
            (args[1] as (Path) -> Any?).invoke(Path.of(args[0] as String))
        }
        every { files.withStagedUploads<Any?>(any(), any()) } answers {
            @Suppress("UNCHECKED_CAST")
            (args[1] as (List<Path>) -> Any?).invoke((args[0] as List<String>).map { Path.of(it) })
        }
        every { client.sendFile(any(), any(), any()) } returns message
        every { client.sendAlbum(any(), any()) } returns listOf(message, message.copy(messageId = 2L))
        every { client.sendVenue(any(), any()) } returns message
        every { client.sendContact(any(), any()) } returns message
    }

    private fun album() = SendAlbumTool(client, resolver, guard, files, ops, audit, mapper, meter)
    private fun venue() = SendVenueTool(client, resolver, guard, ops, audit, mapper, meter)
    private fun contact() = SendContactTool(client, resolver, guard, ops, audit, mapper, meter)

    private fun assertRejected(result: McpSchema.CallToolResult) =
        assertTrue(result.isError, "expected the input filter to reject: ${result.content}")

    @Test fun `send_file caption is rejected (control)`() {
        val tool = SendFileTool(client, resolver, guard, files, ops, audit, mapper, meter)
        assertRejected(tool.execute(exchange, mapOf("chat_id" to 42, "file_path" to "a.jpg", "caption" to "x SECRET_TOKEN")))
        verify(exactly = 0) { client.sendFile(any(), any(), any()) }
    }

    @Test fun `send_album rejects a blocked caption on any item`() {
        assertRejected(album().execute(exchange, mapOf("chat_id" to 42, "items" to listOf(
            mapOf("type" to "photo", "file_path" to "a.jpg"),
            mapOf("type" to "photo", "file_path" to "b.jpg", "caption" to "SECRET_TOKEN"),
        ))))
        verify(exactly = 0) { client.sendAlbum(any(), any()) }
    }

    @Test fun `send_album rejects a caption over the configured input length`() {
        assertRejected(album().execute(exchange, mapOf("chat_id" to 42, "items" to listOf(
            mapOf("type" to "photo", "file_path" to "a.jpg", "caption" to "x".repeat(101)),
            mapOf("type" to "photo", "file_path" to "b.jpg"),
        ))))
        verify(exactly = 0) { client.sendAlbum(any(), any()) }
    }

    @Test fun `send_album with clean captions is sent`() {
        val result = album().execute(exchange, mapOf("chat_id" to 42, "items" to listOf(
            mapOf("type" to "photo", "file_path" to "a.jpg", "caption" to "hello"),
            mapOf("type" to "photo", "file_path" to "b.jpg"),
        )))
        assertFalse(result.isError)
    }

    @Test fun `send_venue rejects blocked text in every text field`() {
        val base = mapOf<String, Any>("chat_id" to 42, "latitude" to 1, "longitude" to 2, "title" to "Cafe", "address" to "Main St")
        listOf("title", "address", "provider", "provider_venue_id", "provider_venue_type").forEach { field ->
            assertRejected(venue().execute(exchange, base + (field to "SECRET_TOKEN")))
        }
        verify(exactly = 0) { client.sendVenue(any(), any()) }
        assertFalse(venue().execute(exchange, base).isError)
    }

    @Test fun `send_contact rejects blocked text in every text field`() {
        val base = mapOf<String, Any>("chat_id" to 42, "phone_number" to "+100", "first_name" to "Ann")
        listOf("first_name", "last_name", "vcard", "phone_number").forEach { field ->
            assertRejected(contact().execute(exchange, base + (field to "SECRET_TOKEN")))
        }
        verify(exactly = 0) { client.sendContact(any(), any()) }
        assertFalse(contact().execute(exchange, base).isError)
    }

    @Test fun `list_join_requests filters its search query like the other search tools`() {
        val tool = ListJoinRequestsTool(client, resolver, guard, audit, mapper, meter)
        assertRejected(tool.execute(exchange, mapOf("chat_id" to 42, "query" to "SECRET_TOKEN")))
        verify(exactly = 0) { client.getChatJoinRequests(any(), any(), any(), any()) }
    }
}
