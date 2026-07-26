package dev.telegrammcp.server.service

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import dev.telegrammcp.server.config.ServerModeProperties
import dev.telegrammcp.server.exception.AccountAccessDeniedException
import dev.telegrammcp.server.exception.AntiSpamException
import dev.telegrammcp.server.exception.ChatNotAllowedException
import dev.telegrammcp.server.exception.ConfirmationRequiredException
import dev.telegrammcp.server.exception.FileSecurityException
import dev.telegrammcp.server.exception.GuardrailViolationException
import dev.telegrammcp.server.exception.ReadOnlyModeException
import dev.telegrammcp.server.model.AuditCategory
import dev.telegrammcp.server.model.AuditOutcome
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import io.modelcontextprotocol.spec.McpSchema
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.io.path.readText
import kotlin.streams.asSequence
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AuditServiceTest {

    private lateinit var meterRegistry: SimpleMeterRegistry
    private val objectMapper = jacksonObjectMapper().findAndRegisterModules()

    @TempDir
    lateinit var tempDir: Path

    @BeforeEach
    fun setUp() {
        meterRegistry = SimpleMeterRegistry()
    }

    private fun auditService(
        enabled: Boolean = true,
        logArguments: Boolean = false,
        auditFile: String = "",
    ): AuditService {
        return AuditService(
            props = ServerModeProperties(
                audit = ServerModeProperties.AuditProps(
                    enabled = enabled,
                    logArguments = logArguments,
                    file = auditFile,
                ),
            ),
            meterRegistry = meterRegistry,
            objectMapper = objectMapper,
        )
    }

    @Test
    fun `records audit entry on success`() {
        val service = auditService()

        service.record(
            toolName = "send_message",
            arguments = mapOf("chat_id" to 42, "text" to "hello"),
            outcome = AuditOutcome.SUCCESS,
            durationMs = 100,
        )

        val entries = service.getRecentEntries()
        assertEquals(1, entries.size)
        assertEquals("send_message", entries[0].toolName)
        assertEquals(AuditCategory.SEND_MESSAGE, entries[0].category)
        assertEquals(AuditOutcome.SUCCESS, entries[0].outcome)
        assertEquals(100L, entries[0].durationMs)
    }

    @Test
    fun `records audit entry on error`() {
        val service = auditService()

        service.record(
            toolName = "get_history",
            outcome = AuditOutcome.ERROR,
            error = "Chat not found",
        )

        val entries = service.getRecentEntries()
        assertEquals(1, entries.size)
        assertEquals(AuditOutcome.ERROR, entries[0].outcome)
        assertEquals("Tool execution failed; see application logs", entries[0].errorMessage)
    }

    @Test
    fun `classifies policy exceptions consistently`() {
        assertEquals(
            AuditOutcome.BLOCKED_READONLY,
            AuditService.outcomeFor(ReadOnlyModeException("send_message")),
        )
        assertEquals(
            AuditOutcome.BLOCKED_CONFIRMATION,
            AuditService.outcomeFor(ConfirmationRequiredException("delete_message", "destructive")),
        )
        assertEquals(
            AuditOutcome.BLOCKED_ANTISPAM,
            AuditService.outcomeFor(AntiSpamException("send_message", "rate limit")),
        )
        listOf(
            GuardrailViolationException("unsafe input"),
            AccountAccessDeniedException("private"),
            ChatNotAllowedException(42),
            FileSecurityException("outside allowed roots"),
        ).forEach { error ->
            assertEquals(AuditOutcome.BLOCKED_GUARDRAIL, AuditService.outcomeFor(error))
        }
        assertEquals(AuditOutcome.ERROR, AuditService.outcomeFor(IllegalStateException("boom")))
    }

    @Test
    fun `does not record when disabled`() {
        val service = auditService(enabled = false)

        service.record("send_message")

        val entries = service.getRecentEntries()
        assertTrue(entries.isEmpty())
    }

    @Test
    fun `omits every argument value when logArguments is false`() {
        val service = auditService(logArguments = false)

        service.record(
            toolName = "send_message",
            arguments = mapOf(
                "chat_id" to "@private_contact",
                "query" to "private message fragment",
                "text" to "secret stuff",
                "token" to "abc123",
            ),
        )

        assertTrue(service.getRecentEntries().single().arguments.isEmpty())
    }

    @Test
    fun `redacts password fields when logArguments is true`() {
        val service = auditService(logArguments = true)

        service.record(
            toolName = "send_message",
            arguments = mapOf("chat_id" to 42, "password" to "secret123"),
        )

        val entries = service.getRecentEntries()
        assertEquals("***REDACTED***", entries[0].arguments["password"])
    }

    @Test
    fun `redacts sensitive keys nested in object and array arguments`() {
        val service = auditService(logArguments = true)

        service.record(
            toolName = "send_message",
            arguments = mapOf(
                "chat_id" to 42,
                "options" to mapOf(
                    "bot_token" to "123:abc",
                    "proxyPassword" to "hunter2",
                    "label" to "kept",
                ),
                "recipients" to listOf(
                    mapOf("phone_number" to "+15551234567", "name" to "kept-too"),
                ),
            ),
        )

        val entry = service.getRecentEntries().first()
        @Suppress("UNCHECKED_CAST")
        val options = entry.arguments["options"] as Map<String, Any>
        assertEquals("***REDACTED***", options["bot_token"])
        assertEquals("***REDACTED***", options["proxyPassword"])
        assertEquals("kept", options["label"])

        @Suppress("UNCHECKED_CAST")
        val recipient = (entry.arguments["recipients"] as List<Map<String, Any>>).first()
        assertEquals("***REDACTED***", recipient["phone_number"])
        assertEquals("kept-too", recipient["name"])
    }

    @Test
    fun `increments metrics counter`() {
        val service = auditService()

        service.record("send_message", outcome = AuditOutcome.SUCCESS)
        service.record("send_message", outcome = AuditOutcome.SUCCESS)
        service.record("get_history", outcome = AuditOutcome.ERROR, error = "fail")

        val sendCounter = meterRegistry.counter(
            "mcp.audit.operations",
            "tool", "send_message",
            "category", AuditCategory.SEND_MESSAGE.name,
            "outcome", AuditOutcome.SUCCESS.name,
        )
        assertEquals(2.0, sendCounter.count())
    }

    @Test
    fun `ring buffer limits entries to 1000`() {
        val service = auditService()

        repeat(1050) { i ->
            service.record("get_history", arguments = mapOf("i" to i))
        }

        val entries = service.getRecentEntries(2000)
        assertEquals(1000, entries.size)
    }

    @Test
    fun `newest entries come first`() {
        val service = auditService(logArguments = true)

        service.record("get_history", arguments = mapOf("chat_id" to 1))
        service.record("send_message", arguments = mapOf("chat_id" to 2))

        val entries = service.getRecentEntries()
        assertEquals("send_message", entries[0].toolName)
        assertEquals("get_history", entries[1].toolName)
    }

    @Test
    fun `appends redacted entries to the configured durable JSONL file`() {
        val target = tempDir.resolve("nested").resolve("telegram-audit.jsonl")
        val service = auditService(logArguments = true, auditFile = target.toString())

        service.record(
            "send_message",
            arguments = mapOf("chat_id" to 42, "text" to "test", "api_token" to "secret"),
        )

        val lines = Files.readAllLines(target)
        assertEquals(1, lines.size)
        val payload = objectMapper.readTree(lines.single())
        assertEquals("send_message", payload["toolName"].asText())
        assertEquals("***REDACTED***", payload["arguments"]["api_token"].asText())
        assertEquals("test", payload["arguments"]["text"].asText())
    }

    @Test
    fun `durable JSONL omits identifiers and queries unless arguments are enabled`() {
        val target = tempDir.resolve("audit-no-arguments.jsonl")
        val service = auditService(logArguments = false, auditFile = target.toString())

        service.record(
            "search_global",
            arguments = mapOf(
                "chat_id" to "@private_contact",
                "query" to "private message fragment",
            ),
            outcome = AuditOutcome.ERROR,
            error = "Could not read @private_contact while searching private message fragment",
        )

        val durableText = Files.readString(target)
        val payload = objectMapper.readTree(durableText)
        assertEquals(0, payload["arguments"].size())
        assertTrue("@private_contact" !in durableText)
        assertTrue("private message fragment" !in durableText)
    }

    @Test
    fun `durable errors redact recognized secret argument values when argument logging is enabled`() {
        val target = tempDir.resolve("audit-redacted-error.jsonl")
        val service = auditService(logArguments = true, auditFile = target.toString())

        service.record(
            "send_message",
            arguments = mapOf("api_token" to "super-secret-value"),
            outcome = AuditOutcome.ERROR,
            error = "Remote rejected super-secret-value",
        )

        val durableText = Files.readString(target)
        assertTrue("super-secret-value" !in durableText)
        assertTrue("***REDACTED***" in durableText)
    }

    @Test
    fun `durable audit refuses a symbolic-link target`() {
        val realTarget = tempDir.resolve("real-audit.jsonl")
        val linkTarget = tempDir.resolve("linked-audit.jsonl")
        Files.writeString(realTarget, "")
        val linkCreated = runCatching {
            Files.createSymbolicLink(linkTarget, realTarget)
        }.isSuccess
        assumeTrue(linkCreated, "Symbolic links are unavailable for this test user")
        val service = auditService(auditFile = linkTarget.toString())

        service.record("get_history")

        assertEquals("", Files.readString(realTarget))
        assertEquals(
            1.0,
            meterRegistry.counter("mcp.audit.durable_write_failures").count(),
        )
    }

    @Test
    fun `dispatch fallback records handlers that do not audit themselves`() {
        val service = auditService()

        service.executeWithFallbackAudit("get_history", mapOf("chat_id" to 42)) {
            McpSchema.CallToolResult.builder().addTextContent("ok").isError(false).build()
        }

        assertEquals(listOf("get_history"), service.getRecentEntries().map { it.toolName })
    }

    @Test
    fun `dispatch fallback does not duplicate a handler audit entry`() {
        val service = auditService()

        service.executeWithFallbackAudit("send_message", mapOf("chat_id" to 42)) {
            service.record("send_message", outcome = AuditOutcome.SUCCESS)
            McpSchema.CallToolResult.builder().addTextContent("ok").isError(false).build()
        }

        assertEquals(1, service.getRecentEntries().size)
    }

    @Test
    fun `every registered tool has an explicit audit category`() {
        val toolRoot = locateProjectRoot()
            .resolve(Paths.get("src", "main", "kotlin", "dev", "telegrammcp", "server", "tool"))
        val pattern = Regex("""const val TOOL_NAME = "([a-z0-9_]+)"""")
        val toolNames = Files.walk(toolRoot).use { stream ->
            stream.asSequence()
                .filter { it.toString().endsWith(".kt") }
                .flatMap { file -> pattern.findAll(file.readText()).map { it.groupValues[1] } }
                .toMutableSet()
        }.also {
            // This account-agnostic handler intentionally uses a literal name.
            it += "list_accounts"
        }

        val unclassified = toolNames.filter { AuditService.categoryFor(it) == AuditCategory.UNCLASSIFIED }
        assertTrue(unclassified.isEmpty(), "Tools without explicit audit categories: ${unclassified.sorted()}")
    }

    private fun locateProjectRoot(): Path {
        var dir: Path? = Paths.get(System.getProperty("user.dir")).toAbsolutePath()
        while (dir != null) {
            if (Files.exists(dir.resolve("settings.gradle.kts"))) return dir
            dir = dir.parent
        }
        error("Project root not found upwards from ${System.getProperty("user.dir")}")
    }
}
