package dev.telegrammcp.server.resource

import com.fasterxml.jackson.databind.ObjectMapper
import dev.telegrammcp.server.client.TelegramAccountRegistry
import dev.telegrammcp.server.config.McpResourceProperties
import dev.telegrammcp.server.security.AccountAccessPolicy
import io.modelcontextprotocol.server.McpServerFeatures.SyncResourceSpecification
import io.modelcontextprotocol.server.McpServerFeatures.SyncResourceTemplateSpecification
import io.modelcontextprotocol.server.McpServerFeatures.SyncToolSpecification
import io.modelcontextprotocol.server.McpSyncServerExchange
import io.modelcontextprotocol.spec.McpError
import io.modelcontextprotocol.spec.McpSchema
import org.springframework.beans.factory.ObjectProvider
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/**
 * Read-only `telegram://` resources.
 *
 * Every read is a call of the backing read tool's registered MCP handler, so it
 * passes the same identity, concurrency, deadline, account, permission, chat
 * allow-list, audit and untrusted-content handling as a tool call. A tool that
 * the surface policy hides has no handler, so its resource is not found.
 */
@Configuration
class TelegramResources {

    @Bean
    fun resourceSubscriptionRegistry(properties: McpResourceProperties) =
        ResourceSubscriptionRegistry(properties.maxSubscriptionsPerSession)

    @Bean
    fun telegramResourceReader(
        tools: ObjectProvider<List<SyncToolSpecification>>,
        mapper: ObjectMapper,
    ) = TelegramResourceReader({ name -> tools.stream().flatMap { it.stream() }.filter { it.tool().name() == name }.findFirst().orElse(null) }, mapper)

    @Bean
    fun telegramSyncResourceSpecifications(
        properties: McpResourceProperties,
        registry: TelegramAccountRegistry,
        reader: TelegramResourceReader,
    ): List<SyncResourceSpecification> {
        if (!properties.enabled) return emptyList()
        // One static entry per configured account; the SDK cannot filter resources/list per caller,
        // and account labels are already public in every tool's account enum.
        return registry.labels().map { account ->
            val uri = TelegramResourceUri.Chats(account).toString()
            SyncResourceSpecification(
                McpSchema.Resource.builder().uri(uri).name("chats-$account").title("Telegram chats ($account)")
                    .description("Recent chats of account '$account' (list_chats, first 50). Untrusted Telegram content.")
                    .mimeType(MIME_TYPE).build(),
                reader::read,
            )
        }
    }

    @Bean
    fun telegramSyncResourceTemplateSpecifications(
        properties: McpResourceProperties,
        reader: TelegramResourceReader,
    ): List<SyncResourceTemplateSpecification> {
        if (!properties.enabled) return emptyList()
        return listOf(
            // Also resolves accounts registered after startup, which the static list cannot show.
            SyncResourceTemplateSpecification(
                McpSchema.ResourceTemplate.builder().uriTemplate(TelegramResourceUri.CHATS_TEMPLATE).name("chats")
                    .title("Telegram chats")
                    .description("Recent chats of one account (list_chats, first 50). Untrusted Telegram content.")
                    .mimeType(MIME_TYPE).build(),
                reader::read,
            ),
            SyncResourceTemplateSpecification(
                McpSchema.ResourceTemplate.builder().uriTemplate(TelegramResourceUri.CHAT_TEMPLATE).name("chat")
                    .title("Telegram chat")
                    .description("Latest 20 messages of one chat (conversation_bundle). chatId is the numeric chat ID. Untrusted Telegram content.")
                    .mimeType(MIME_TYPE).build(),
                reader::read,
            ),
            SyncResourceTemplateSpecification(
                McpSchema.ResourceTemplate.builder().uriTemplate(TelegramResourceUri.MESSAGE_TEMPLATE).name("message")
                    .title("Telegram message")
                    .description("One message by numeric chat and message ID (get_messages). Untrusted Telegram content.")
                    .mimeType(MIME_TYPE).build(),
                reader::read,
            ),
        )
    }

    companion object {
        const val MIME_TYPE = "application/json"
    }
}

/** Resolves a resource read into a call of its backing tool's MCP handler. */
class TelegramResourceReader(
    private val toolSpecification: (String) -> SyncToolSpecification?,
    private val mapper: ObjectMapper,
) {
    fun read(exchange: McpSyncServerExchange, request: McpSchema.ReadResourceRequest): McpSchema.ReadResourceResult {
        val raw = request.uri()
        val uri = TelegramResourceUri.parse(raw) ?: throw notFound(raw)
        val spec = toolSpecification(uri.backingTool) ?: throw notFound(raw)
        val result = spec.callHandler().apply(exchange, McpSchema.CallToolRequest(uri.backingTool, arguments(uri)))
        val envelope = result.structuredContent() as? Map<*, *>
        if (result.isError == true || envelope == null) throw failure(raw, envelope)
        val body = when (uri) {
            is TelegramResourceUri.Message -> singleMessage(envelope, uri.messageId) ?: throw notFound(raw)
            else -> envelope
        }
        return McpSchema.ReadResourceResult(
            listOf(McpSchema.TextResourceContents(raw, TelegramResources.MIME_TYPE, mapper.writeValueAsString(body), UNTRUSTED_META)),
            null,
        )
    }

    companion object {
        val UNTRUSTED_META: Map<String, Any> = mapOf("io.github.tolboy/untrusted-content" to true)
        const val CHATS_LIMIT = 50
        const val CHAT_MESSAGES = 20

        /** Error codes that mean "no such readable resource"; the reason is not echoed. */
        private val NOT_FOUND_CODES = setOf("PERMISSION_DENIED", "CHAT_FORBIDDEN", "ACCOUNT_FORBIDDEN",
            "ENTITY_NOT_FOUND", "INVALID_INPUT", "READ_ONLY")

        fun arguments(uri: TelegramResourceUri): Map<String, Any> {
            val account = AccountAccessPolicy.ACCOUNT_ARGUMENT to uri.account
            return when (uri) {
                is TelegramResourceUri.Chats -> mapOf(account, "limit" to CHATS_LIMIT)
                is TelegramResourceUri.Chat -> mapOf(account, "chat_id" to uri.chatId, "limit" to CHAT_MESSAGES)
                is TelegramResourceUri.Message ->
                    mapOf(account, "chat_id" to uri.chatId, "message_id" to uri.messageId, "context_size" to 1)
            }
        }

        /** get_messages returns a window around the target; the resource is the target alone. */
        internal fun singleMessage(envelope: Map<*, *>, messageId: Long): Map<String, Any?>? {
            val message = (envelope["data"] as? List<*>).orEmpty().filterIsInstance<Map<*, *>>()
                .firstOrNull { (it["messageId"] as? Number)?.toLong() == messageId } ?: return null
            val meta = (envelope["meta"] as? Map<*, *>).orEmpty().filterKeys { it != "page" }
            return mapOf("data" to message, "meta" to meta)
        }

        private fun notFound(uri: String?): McpError = McpError.builder(McpSchema.ErrorCodes.RESOURCE_NOT_FOUND)
            .message("Resource not found").data(mapOf("uri" to uri)).build()

        private fun failure(uri: String, envelope: Map<*, *>?): McpError {
            val code = ((envelope?.get("data") as? Map<*, *>)?.get("error") as? Map<*, *>)?.get("code")?.toString()
            if (code == null || code in NOT_FOUND_CODES) return notFound(uri)
            return McpError.builder(McpSchema.ErrorCodes.INTERNAL_ERROR).message("Resource read failed: $code")
                .data(mapOf("uri" to uri, "code" to code)).build()
        }
    }
}
