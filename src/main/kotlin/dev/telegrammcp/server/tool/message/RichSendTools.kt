package dev.telegrammcp.server.tool.message

import com.fasterxml.jackson.databind.ObjectMapper
import dev.telegrammcp.server.client.TelegramClientService
import dev.telegrammcp.server.exception.InvalidToolInputException
import dev.telegrammcp.server.model.ChatActionType
import dev.telegrammcp.server.model.ContactSpec
import dev.telegrammcp.server.model.VenueSpec
import dev.telegrammcp.server.service.AuditService
import dev.telegrammcp.server.service.EntityResolverService
import dev.telegrammcp.server.service.GuardrailService
import dev.telegrammcp.server.service.OperationGuardService
import dev.telegrammcp.server.service.SendOperationService
import dev.telegrammcp.server.tool.McpToolHandler
import dev.telegrammcp.server.tool.RecoverableSend
import dev.telegrammcp.server.tool.ToolSupport
import dev.telegrammcp.server.util.StructuredLogger
import io.micrometer.core.instrument.MeterRegistry
import io.modelcontextprotocol.server.McpSyncServerExchange
import io.modelcontextprotocol.spec.McpSchema
import org.springframework.stereotype.Component

/** Argument parsing shared by the location, venue, contact and chat-action tools. */
internal object RichSendArguments {
    const val CHAT_ID_SCHEMA =
        """{ "type": ["string", "number"], "description": "Chat identifier: numeric ID, @username, +phone, or the canonical value self" }"""

    fun chatId(entityResolver: EntityResolverService, arguments: Map<String, Any>): Long =
        entityResolver.resolve(arguments["chat_id"] ?: throw InvalidToolInputException("chat_id is required"))

    fun number(arguments: Map<String, Any>, key: String, min: Double, max: Double, default: Double? = null): Double {
        val raw = arguments[key] ?: return default ?: throw InvalidToolInputException("$key is required")
        val value = (raw as? Number)?.toDouble() ?: raw.toString().toDoubleOrNull()
            ?: throw InvalidToolInputException("$key must be a number")
        if (!value.isFinite() || value < min || value > max) throw InvalidToolInputException("$key must be between $min and $max")
        return value
    }

    fun text(arguments: Map<String, Any>, key: String, maxLength: Int, required: Boolean = false): String {
        val value = arguments[key]?.toString()?.trim().orEmpty()
        if (required && value.isEmpty()) throw InvalidToolInputException("$key is required")
        if (value.length > maxLength) throw InvalidToolInputException("$key must be at most $maxLength characters")
        return value
    }

    /** Runs every non-empty outgoing text field through the configured input filter. */
    fun validateText(guardrailService: GuardrailService, vararg values: String?) {
        values.filterNot { it.isNullOrEmpty() }.forEach { guardrailService.validateInput(it!!) }
    }
}

/**
 * MCP tool: **send_location** — sends a static map point.
 */
@Component
class SendLocationTool(
    private val telegramClient: TelegramClientService,
    private val entityResolver: EntityResolverService,
    private val guardrailService: GuardrailService,
    private val operationGuardService: OperationGuardService,
    private val auditService: AuditService,
    private val objectMapper: ObjectMapper,
    private val meterRegistry: MeterRegistry,
    private val sendOperations: SendOperationService? = null,
) : McpToolHandler {

    private val log = StructuredLogger.forClass<SendLocationTool>()

    companion object {
        const val TOOL_NAME = "send_location"

        @Suppress("JsonStandardCompliance")
        private val INPUT_SCHEMA = """
        {
          "type": "object",
          "properties": {
            "chat_id": ${RichSendArguments.CHAT_ID_SCHEMA},
            "latitude": { "type": "number", "minimum": -90, "maximum": 90, "description": "Latitude in degrees" },
            "longitude": { "type": "number", "minimum": -180, "maximum": 180, "description": "Longitude in degrees" },
            "horizontal_accuracy": { "type": "number", "minimum": 0, "maximum": 1500, "description": "Optional accuracy radius in meters (0-1500)" }
          },
          "required": ["chat_id", "latitude", "longitude"]
        }
        """.trimIndent()
    }

    override fun definition(): McpSchema.Tool =
        ToolSupport.definition(TOOL_NAME, "Send a static location (map point) to a Telegram chat", INPUT_SCHEMA, objectMapper)

    override fun execute(exchange: McpSyncServerExchange, arguments: Map<String, Any>): McpSchema.CallToolResult =
        ToolSupport.execute(TOOL_NAME, arguments, objectMapper, meterRegistry, log, "Failed to send location", auditService) {
            RecoverableSend.check(operationGuardService, TOOL_NAME, arguments)
            val chatId = RichSendArguments.chatId(entityResolver, arguments)
            val latitude = RichSendArguments.number(arguments, "latitude", -90.0, 90.0)
            val longitude = RichSendArguments.number(arguments, "longitude", -180.0, 180.0)
            val accuracy = RichSendArguments.number(arguments, "horizontal_accuracy", 0.0, 1500.0, default = 0.0)
            guardrailService.validateChatAccess(chatId)
            log.withTool(TOOL_NAME).info("Sending location to chat {}", chatId)
            RecoverableSend.execute(sendOperations, operationGuardService, TOOL_NAME, arguments, chatId,
                listOf(latitude, longitude, accuracy)) {
                telegramClient.sendLocation(chatId, latitude, longitude, accuracy)
            }
        }
}

/**
 * MCP tool: **send_venue** — sends a named place with an address.
 */
@Component
class SendVenueTool(
    private val telegramClient: TelegramClientService,
    private val entityResolver: EntityResolverService,
    private val guardrailService: GuardrailService,
    private val operationGuardService: OperationGuardService,
    private val auditService: AuditService,
    private val objectMapper: ObjectMapper,
    private val meterRegistry: MeterRegistry,
    private val sendOperations: SendOperationService? = null,
) : McpToolHandler {

    private val log = StructuredLogger.forClass<SendVenueTool>()

    companion object {
        const val TOOL_NAME = "send_venue"

        @Suppress("JsonStandardCompliance")
        private val INPUT_SCHEMA = """
        {
          "type": "object",
          "properties": {
            "chat_id": ${RichSendArguments.CHAT_ID_SCHEMA},
            "latitude": { "type": "number", "minimum": -90, "maximum": 90, "description": "Latitude in degrees" },
            "longitude": { "type": "number", "minimum": -180, "maximum": 180, "description": "Longitude in degrees" },
            "title": { "type": "string", "description": "Venue name (1-64 characters)" },
            "address": { "type": "string", "description": "Venue address (1-256 characters)" },
            "provider": { "type": "string", "description": "Optional venue provider; Telegram supports foursquare and gplaces" },
            "provider_venue_id": { "type": "string", "description": "Optional venue ID in the provider's database" },
            "provider_venue_type": { "type": "string", "description": "Optional venue type in the provider's taxonomy" }
          },
          "required": ["chat_id", "latitude", "longitude", "title", "address"]
        }
        """.trimIndent()
    }

    override fun definition(): McpSchema.Tool =
        ToolSupport.definition(TOOL_NAME, "Send a venue (named place with an address) to a Telegram chat", INPUT_SCHEMA, objectMapper)

    override fun execute(exchange: McpSyncServerExchange, arguments: Map<String, Any>): McpSchema.CallToolResult =
        ToolSupport.execute(TOOL_NAME, arguments, objectMapper, meterRegistry, log, "Failed to send venue", auditService) {
            RecoverableSend.check(operationGuardService, TOOL_NAME, arguments)
            val chatId = RichSendArguments.chatId(entityResolver, arguments)
            val venue = VenueSpec(
                latitude = RichSendArguments.number(arguments, "latitude", -90.0, 90.0),
                longitude = RichSendArguments.number(arguments, "longitude", -180.0, 180.0),
                title = RichSendArguments.text(arguments, "title", 64, required = true),
                address = RichSendArguments.text(arguments, "address", 256, required = true),
                provider = RichSendArguments.text(arguments, "provider", 64),
                providerVenueId = RichSendArguments.text(arguments, "provider_venue_id", 256),
                providerVenueType = RichSendArguments.text(arguments, "provider_venue_type", 256),
            )
            guardrailService.validateChatAccess(chatId)
            RichSendArguments.validateText(guardrailService, venue.title, venue.address, venue.provider,
                venue.providerVenueId, venue.providerVenueType)
            log.withTool(TOOL_NAME).info("Sending venue to chat {}", chatId)
            RecoverableSend.execute(sendOperations, operationGuardService, TOOL_NAME, arguments, chatId, listOf(venue)) {
                telegramClient.sendVenue(chatId, venue)
            }
        }
}

/**
 * MCP tool: **send_contact** — sends a contact card.
 */
@Component
class SendContactTool(
    private val telegramClient: TelegramClientService,
    private val entityResolver: EntityResolverService,
    private val guardrailService: GuardrailService,
    private val operationGuardService: OperationGuardService,
    private val auditService: AuditService,
    private val objectMapper: ObjectMapper,
    private val meterRegistry: MeterRegistry,
    private val sendOperations: SendOperationService? = null,
) : McpToolHandler {

    private val log = StructuredLogger.forClass<SendContactTool>()

    companion object {
        const val TOOL_NAME = "send_contact"

        @Suppress("JsonStandardCompliance")
        private val INPUT_SCHEMA = """
        {
          "type": "object",
          "properties": {
            "chat_id": ${RichSendArguments.CHAT_ID_SCHEMA},
            "phone_number": { "type": "string", "description": "Contact phone number" },
            "first_name": { "type": "string", "description": "Contact first name (1-64 characters)" },
            "last_name": { "type": "string", "description": "Optional contact last name" },
            "vcard": { "type": "string", "description": "Optional vCard data (up to 2048 characters)" },
            "user_id": { "type": "number", "description": "Optional Telegram user ID of the contact" }
          },
          "required": ["chat_id", "phone_number", "first_name"]
        }
        """.trimIndent()
    }

    override fun definition(): McpSchema.Tool =
        ToolSupport.definition(TOOL_NAME, "Send a contact card to a Telegram chat", INPUT_SCHEMA, objectMapper)

    override fun execute(exchange: McpSyncServerExchange, arguments: Map<String, Any>): McpSchema.CallToolResult =
        ToolSupport.execute(TOOL_NAME, arguments, objectMapper, meterRegistry, log, "Failed to send contact", auditService) {
            RecoverableSend.check(operationGuardService, TOOL_NAME, arguments)
            val chatId = RichSendArguments.chatId(entityResolver, arguments)
            val userId = arguments["user_id"]?.let {
                (it as? Number)?.toLong() ?: throw InvalidToolInputException("user_id must be a number")
            } ?: 0L
            if (userId < 0) throw InvalidToolInputException("user_id must be a positive Telegram user ID")
            val contact = ContactSpec(
                phoneNumber = RichSendArguments.text(arguments, "phone_number", 32, required = true),
                firstName = RichSendArguments.text(arguments, "first_name", 64, required = true),
                lastName = RichSendArguments.text(arguments, "last_name", 64),
                vcard = RichSendArguments.text(arguments, "vcard", 2048),
                userId = userId,
            )
            guardrailService.validateChatAccess(chatId)
            RichSendArguments.validateText(guardrailService, contact.phoneNumber, contact.firstName,
                contact.lastName, contact.vcard)
            log.withTool(TOOL_NAME).info("Sending contact card to chat {}", chatId)
            RecoverableSend.execute(sendOperations, operationGuardService, TOOL_NAME, arguments, chatId, listOf(contact)) {
                telegramClient.sendContact(chatId, contact)
            }
        }
}

/**
 * MCP tool: **send_chat_action** — shows a short-lived status such as
 * "typing…" to the other members of a chat. No message is sent.
 */
@Component
class SendChatActionTool(
    private val telegramClient: TelegramClientService,
    private val entityResolver: EntityResolverService,
    private val guardrailService: GuardrailService,
    private val operationGuardService: OperationGuardService,
    private val auditService: AuditService,
    private val objectMapper: ObjectMapper,
    private val meterRegistry: MeterRegistry,
) : McpToolHandler {

    private val log = StructuredLogger.forClass<SendChatActionTool>()

    companion object {
        const val TOOL_NAME = "send_chat_action"
        private val ACTIONS = ChatActionType.entries.map { it.name.lowercase() }

        @Suppress("JsonStandardCompliance")
        private val INPUT_SCHEMA = """
        {
          "type": "object",
          "properties": {
            "chat_id": ${RichSendArguments.CHAT_ID_SCHEMA},
            "action": {
              "type": "string",
              "enum": ${ACTIONS.joinToString(", ", "[", "]") { "\"$it\"" }},
              "description": "Status to show (default typing). Telegram clears it after about 5 seconds or when a message is sent; cancel clears it at once"
            }
          },
          "required": ["chat_id"]
        }
        """.trimIndent()
    }

    override fun definition(): McpSchema.Tool = ToolSupport.definition(
        TOOL_NAME,
        "Show a short-lived chat action such as typing to the other members of a Telegram chat (no message is sent)",
        INPUT_SCHEMA,
        objectMapper,
    )

    override fun execute(exchange: McpSyncServerExchange, arguments: Map<String, Any>): McpSchema.CallToolResult =
        ToolSupport.execute(TOOL_NAME, arguments, objectMapper, meterRegistry, log, "Failed to send chat action", auditService) {
            operationGuardService.checkPermission(TOOL_NAME, arguments)
            val chatId = RichSendArguments.chatId(entityResolver, arguments)
            val raw = arguments["action"]?.toString()?.trim()?.lowercase()?.takeIf { it.isNotEmpty() } ?: "typing"
            val action = ChatActionType.entries.firstOrNull { it.name.lowercase() == raw }
                ?: throw InvalidToolInputException("action must be one of ${ACTIONS.joinToString()}")
            guardrailService.validateChatAccess(chatId)
            log.withTool(TOOL_NAME).info("Sending chat action {} to chat {}", raw, chatId)
            telegramClient.sendChatAction(chatId, action)
            mapOf("chat_id" to chatId, "action" to raw, "sent" to true)
        }
}
