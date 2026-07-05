package dev.telegrammcp.server.tool

import com.fasterxml.jackson.databind.ObjectMapper
import dev.telegrammcp.server.exception.AntiSpamException
import dev.telegrammcp.server.exception.ChatNotAllowedException
import dev.telegrammcp.server.exception.ConfirmationRequiredException
import dev.telegrammcp.server.exception.FileSecurityException
import dev.telegrammcp.server.exception.GuardrailViolationException
import dev.telegrammcp.server.exception.ReadOnlyModeException
import dev.telegrammcp.server.model.AuditOutcome
import dev.telegrammcp.server.service.AuditService
import dev.telegrammcp.server.util.StructuredLogger
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Timer
import io.modelcontextprotocol.spec.McpSchema

/**
 * Shared helpers for MCP tool definitions and execution.
 *
 * The tool layer is intentionally thin and repetitive; these helpers centralize
 * the mechanics (schema parsing, metrics, audit, JSON/text wrapping) while
 * keeping each tool's domain validation explicit.
 */
object ToolSupport {

    private val fallbackObjectMapper = ObjectMapper()

    private val outputSchema: Map<String, Any> = mapOf(
        "type" to "object",
        "properties" to mapOf(
            "data" to emptyMap<String, Any>(),
            "meta" to mapOf(
                "type" to "object",
                "properties" to mapOf(
                    "untrustedTelegramContent" to mapOf("type" to "boolean"),
                    "escapedCharacterCount" to mapOf("type" to "integer", "minimum" to 0),
                ),
                "required" to listOf("untrustedTelegramContent", "escapedCharacterCount"),
                "additionalProperties" to false,
            ),
        ),
        "required" to listOf("data", "meta"),
        "additionalProperties" to false,
    )

    private val userContentAnnotations = McpSchema.Annotations.builder()
        .audience(listOf(McpSchema.Role.USER))
        .priority(0.8)
        .build()

    @Suppress("UNCHECKED_CAST")
    fun definition(
        name: String,
        description: String,
        inputSchema: String,
        objectMapper: ObjectMapper,
    ): McpSchema.Tool {
        val schemaMap = objectMapper.readValue(inputSchema, Map::class.java) as Map<String, Any>
        return definition(name, description, schemaMap)
    }

    fun definition(
        name: String,
        description: String,
        inputSchema: Map<String, Any>,
    ): McpSchema.Tool = McpSchema.Tool(
        name,
        null,
        description,
        inputSchema,
        outputSchema,
        null,
        emptyMap(),
        emptyList(),
    )

    fun execute(
        toolName: String,
        arguments: Map<String, Any>,
        objectMapper: ObjectMapper,
        meterRegistry: MeterRegistry,
        log: StructuredLogger,
        failureMessage: String,
        auditService: AuditService? = null,
        block: () -> Any,
    ): McpSchema.CallToolResult {
        val sample = Timer.start(meterRegistry)
        val startNanos = System.nanoTime()

        return try {
            val payload = block()
            val durationMs = elapsedMillis(startNanos)
            auditService?.record(toolName, arguments, AuditOutcome.SUCCESS, durationMs = durationMs)
            toResult(payload, objectMapper)
        } catch (ex: Exception) {
            val durationMs = elapsedMillis(startNanos)
            log.withTool(toolName).error("{}: {}", failureMessage, ex.message, ex)
            auditService?.record(
                toolName,
                arguments,
                outcome = auditOutcomeFor(ex),
                error = ex.message,
                durationMs = durationMs,
            )
            errorResult(ex)
        } finally {
            sample.stop(Timer.builder("mcp.tool.execution").tag("tool", toolName).register(meterRegistry))
        }
    }

    fun jsonResult(
        payload: Any,
        objectMapper: ObjectMapper,
    ): McpSchema.CallToolResult {
        val normalized = UntrustedContentNormalizer.normalize(payload, objectMapper)
        return successfulResult(
            text = objectMapper.writeValueAsString(normalized.value),
            data = normalized.value,
            escapedCharacterCount = normalized.escapedCharacterCount,
        )
    }

    fun textResult(text: String): McpSchema.CallToolResult {
        val parsed = runCatching { fallbackObjectMapper.readValue(text, Any::class.java) }.getOrNull()
        return if (parsed != null) {
            jsonResult(parsed, fallbackObjectMapper)
        } else {
            val (normalized, count) = UntrustedContentNormalizer.normalizeText(text)
            successfulResult(normalized, normalized, count)
        }
    }

    private fun toResult(
        payload: Any,
        objectMapper: ObjectMapper,
    ): McpSchema.CallToolResult = when (payload) {
        is McpSchema.CallToolResult -> payload
        is CharSequence -> textResult(payload.toString())
        else -> jsonResult(payload, objectMapper)
    }

    fun errorResult(ex: Exception): McpSchema.CallToolResult =
        errorText("Error: ${ex.message}")

    fun errorText(text: String): McpSchema.CallToolResult =
        McpSchema.CallToolResult.builder()
            .addContent(annotatedText(UntrustedContentNormalizer.normalizeText(text).first))
            .isError(true)
            .build()

    private fun successfulResult(
        text: String,
        data: Any?,
        escapedCharacterCount: Int,
    ): McpSchema.CallToolResult {
        val resultMeta = mapOf(
            "untrustedTelegramContent" to true,
            "escapedCharacterCount" to escapedCharacterCount,
        )
        val envelope = mapOf(
            "data" to data,
            "meta" to resultMeta,
        )
        return McpSchema.CallToolResult.builder()
            .addContent(annotatedText(text))
            .structuredContent(envelope)
            .meta(mapOf("io.github.tolboy/untrusted-content" to true))
            .isError(false)
            .build()
    }

    private fun annotatedText(text: String): McpSchema.TextContent =
        McpSchema.TextContent(
            userContentAnnotations,
            text,
            mapOf("io.github.tolboy/untrusted-content" to true),
        )

    private fun auditOutcomeFor(ex: Exception): AuditOutcome = when (ex) {
        is ReadOnlyModeException -> AuditOutcome.BLOCKED_READONLY
        is ConfirmationRequiredException -> AuditOutcome.BLOCKED_CONFIRMATION
        is AntiSpamException -> AuditOutcome.BLOCKED_ANTISPAM
        is GuardrailViolationException,
        is ChatNotAllowedException,
        is FileSecurityException,
        -> AuditOutcome.BLOCKED_GUARDRAIL
        else -> AuditOutcome.ERROR
    }

    private fun elapsedMillis(startNanos: Long): Long = (System.nanoTime() - startNanos) / 1_000_000
}
