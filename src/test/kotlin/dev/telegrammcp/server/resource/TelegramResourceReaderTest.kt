package dev.telegrammcp.server.resource

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import io.mockk.mockk
import io.modelcontextprotocol.server.McpServerFeatures.SyncToolSpecification
import io.modelcontextprotocol.server.McpSyncServerExchange
import io.modelcontextprotocol.spec.McpError
import io.modelcontextprotocol.spec.McpSchema
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TelegramResourceReaderTest {
    private val mapper = jacksonObjectMapper()
    private val calls = mutableListOf<McpSchema.CallToolRequest>()
    private var result: McpSchema.CallToolResult = success(emptyList<Any>())

    private fun success(data: Any) = McpSchema.CallToolResult.builder().isError(false)
        .structuredContent(mapOf("data" to data, "meta" to mapOf("untrustedTelegramContent" to true, "page" to mapOf("returned_count" to 3))))
        .build()

    private fun failure(code: String) = McpSchema.CallToolResult.builder().isError(true)
        .structuredContent(mapOf("data" to mapOf("error" to mapOf("code" to code, "message" to "chat 43 is secret"))))
        .build()

    private fun reader(vararg tools: String) = TelegramResourceReader({ name ->
        if (name !in tools) null else SyncToolSpecification.builder()
            .tool(McpSchema.Tool.builder().name(name).inputSchema(McpSchema.JsonSchema("object", emptyMap(), emptyList(), false, null, null)).build())
            .callHandler { _, request -> calls += request; result }.build()
    }, mapper)

    private fun read(reader: TelegramResourceReader, uri: String) =
        reader.read(mockk<McpSyncServerExchange>(), McpSchema.ReadResourceRequest(uri))

    @Test fun `chat resource calls the backing tool with the account and returns its envelope`() {
        result = success(mapOf("chatId" to 42, "messages" to listOf(mapOf("messageId" to 7, "text" to "hi"))))
        val contents = read(reader("conversation_bundle"), "telegram://work/chat/42").contents().single() as McpSchema.TextResourceContents
        assertEquals(mapOf("account" to "work", "chat_id" to 42L, "limit" to 20), calls.single().arguments())
        assertEquals("telegram://work/chat/42", contents.uri())
        assertEquals("application/json", contents.mimeType())
        assertEquals(true, contents.meta()["io.github.tolboy/untrusted-content"])
        assertEquals("hi", mapper.readTree(contents.text()).path("data").path("messages")[0].path("text").asText())
    }

    @Test fun `message resource is the target message alone`() {
        result = success(listOf(6, 7, 8).map { mapOf("messageId" to it, "text" to "m$it") })
        val text = (read(reader("get_messages"), "telegram://work/chat/42/message/7").contents().single() as McpSchema.TextResourceContents).text()
        assertEquals(mapOf("account" to "work", "chat_id" to 42L, "message_id" to 7L, "context_size" to 1), calls.single().arguments())
        val json = mapper.readTree(text)
        assertEquals("m7", json.path("data").path("text").asText())
        assertFalse(json.path("meta").has("page"))
        assertTrue(json.path("meta").path("untrustedTelegramContent").asBoolean())
    }

    @Test fun `missing target message is not found`() {
        result = success(listOf(mapOf("messageId" to 6)))
        val error = assertThrows<McpError> { read(reader("get_messages"), "telegram://work/chat/42/message/7") }
        assertEquals(McpSchema.ErrorCodes.RESOURCE_NOT_FOUND, error.jsonRpcError.code())
    }

    @Test fun `chats resource uses list_chats`() {
        read(reader("list_chats"), "telegram://work/chats")
        assertEquals(mapOf("account" to "work", "limit" to 50), calls.single().arguments())
    }

    @Test fun `denials, hidden tools and malformed URIs are indistinguishable`() {
        val messages = mutableSetOf<String>()
        listOf("PERMISSION_DENIED", "CHAT_FORBIDDEN", "ACCOUNT_FORBIDDEN", "ENTITY_NOT_FOUND", "INVALID_INPUT").forEach {
            result = failure(it)
            val error = assertThrows<McpError> { read(reader("conversation_bundle"), "telegram://work/chat/43") }
            assertEquals(McpSchema.ErrorCodes.RESOURCE_NOT_FOUND, error.jsonRpcError.code())
            messages += error.jsonRpcError.message()
        }
        listOf("telegram://work/chat/43" to reader(), "telegram://work/chat/043" to reader("conversation_bundle")).forEach { (uri, r) ->
            val error = assertThrows<McpError> { read(r, uri) }
            assertEquals(McpSchema.ErrorCodes.RESOURCE_NOT_FOUND, error.jsonRpcError.code())
            messages += error.jsonRpcError.message()
        }
        assertEquals(setOf("Resource not found"), messages)
    }

    @Test fun `operational failures are internal errors without the tool's message`() {
        result = failure("TELEGRAM_UNAVAILABLE")
        val error = assertThrows<McpError> { read(reader("conversation_bundle"), "telegram://work/chat/42") }
        assertEquals(McpSchema.ErrorCodes.INTERNAL_ERROR, error.jsonRpcError.code())
        assertFalse("secret" in error.jsonRpcError.message())
    }
}
