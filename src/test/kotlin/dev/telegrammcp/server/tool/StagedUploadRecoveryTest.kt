package dev.telegrammcp.server.tool

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import dev.telegrammcp.server.client.TelegramClientService
import dev.telegrammcp.server.config.ServerModeProperties
import dev.telegrammcp.server.exception.SendOutcomeUnknownException
import dev.telegrammcp.server.model.TelegramMessage
import dev.telegrammcp.server.service.AuditService
import dev.telegrammcp.server.service.EntityResolverService
import dev.telegrammcp.server.service.FileSecurityService
import dev.telegrammcp.server.service.GuardrailService
import dev.telegrammcp.server.service.OperationGuardService
import dev.telegrammcp.server.service.PlatformPaths
import dev.telegrammcp.server.service.SendOperationService
import dev.telegrammcp.server.tool.media.SendAlbumTool
import dev.telegrammcp.server.tool.media.SendFileTool
import dev.telegrammcp.server.tool.media.SendStickerTool
import dev.telegrammcp.server.tool.media.SendVoiceTool
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.modelcontextprotocol.server.McpSyncServerExchange
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Exercises the real staging/journal boundary; stable-path staging mocks cannot catch these regressions. */
class StagedUploadRecoveryTest {
    @TempDir lateinit var directory: Path
    private val mapper = jacksonObjectMapper().findAndRegisterModules()
    private val client = mockk<TelegramClientService>()
    private val resolver = mockk<EntityResolverService> { every { resolve(any<Any>()) } returns 42L }
    private val guardrails = mockk<GuardrailService>(relaxed = true)
    private val guard = mockk<OperationGuardService>(relaxed = true)
    private val audit = mockk<AuditService>(relaxed = true)
    private val exchange = mockk<McpSyncServerExchange>(relaxed = true)
    private val metrics = SimpleMeterRegistry()

    private fun paths(): PlatformPaths = mockk {
        every { applicationDataDirectory } returns directory.resolve("state")
        every { resolveApplicationPath(any(), any()) } answers { Path.of(firstArg<String>()).toAbsolutePath().normalize() }
    }

    private fun files(): FileSecurityService = FileSecurityService(
        ServerModeProperties(fileSecurity = ServerModeProperties.FileSecurityProps(
            allowedRoots = listOf(directory.resolve("uploads").toString()), maxFileSizeBytes = 1024)), paths())

    private fun source(name: String): Path {
        Files.createDirectories(directory.resolve("uploads"))
        return Files.writeString(directory.resolve("uploads").resolve(name), "original bytes for $name")
    }

    private fun message(id: Long) = TelegramMessage(id, 42, "Chat", "Sender", text = "sent", date = Instant.EPOCH)

    private fun receipt(result: io.modelcontextprotocol.spec.McpSchema.CallToolResult): Map<*, *> =
        (result.structuredContent() as Map<*, *>)["data"] as Map<*, *>

    @Test
    fun `real upload snapshots preserve filenames and replay identical media without another dispatch`() {
        val first = source("invoice.pdf")
        val second = source("second.pdf")
        val files = files()
        val observedPaths = mutableListOf<Path>()
        fun observe(path: String) {
            val staged = Path.of(path)
            assertTrue(staged.startsWith(directory.resolve("state/upload-snapshots")))
            assertTrue(staged.fileName.toString() in setOf("invoice.pdf", "second.pdf"))
            assertTrue(Files.readString(staged).startsWith("original bytes"))
            observedPaths.add(staged)
        }
        every { client.sendFile(any(), any(), any()) } answers { observe(secondArg()); message(100) }
        every { client.sendVoice(any(), any(), any(), any()) } answers { observe(secondArg()); message(100) }
        every { client.sendSticker(any(), any(), any()) } answers { observe(secondArg()); message(100) }
        every { client.sendAlbum(any(), any()) } answers {
            secondArg<List<dev.telegrammcp.server.model.AlbumItem>>().forEach { observe(it.filePath) }
            listOf(message(100), message(101))
        }
        SendOperationService(mockk { every { currentAccount() } returns "work" }, paths(), mapper).use { operations ->
            val cases: List<Pair<McpToolHandler, Map<String, Any>>> = listOf(
                SendFileTool(client, resolver, guardrails, files, guard, audit, mapper, metrics, operations) to
                    mapOf("chat_id" to 42, "file_path" to first.toString()),
                SendVoiceTool(client, resolver, guardrails, files, guard, audit, mapper, metrics, operations) to
                    mapOf("chat_id" to 42, "file_path" to first.toString()),
                SendStickerTool(client, resolver, guardrails, files, guard, audit, mapper, metrics, operations) to
                    mapOf("chat_id" to 42, "file_path" to first.toString()),
                SendAlbumTool(client, resolver, guardrails, files, guard, audit, mapper, metrics, operations) to
                    mapOf("chat_id" to 42, "items" to listOf(
                        mapOf("file_path" to first.toString(), "type" to "document"),
                        mapOf("file_path" to second.toString(), "type" to "document"))),
            )
            for ((tool, input) in cases) {
                val arguments = input + ("idempotency_key" to tool.definition().name())
                assertFalse(tool.executeChecked(exchange, arguments).isError, tool.definition().name())
                val replay = tool.executeChecked(exchange, arguments)
                assertFalse(replay.isError, "A fresh staging UUID must not conflict with ${tool.definition().name()} replay")
                assertEquals("SENT", receipt(replay)["status"])
                assertEquals(true, receipt(replay)["replayed"])
                verify(exactly = 1) { guard.checkPermission(tool.definition().name(), arguments) }
            }
        }
        verify(exactly = 1) { client.sendFile(any(), any(), any()) }
        verify(exactly = 1) { client.sendVoice(any(), any(), any(), any()) }
        verify(exactly = 1) { client.sendSticker(any(), any(), any()) }
        verify(exactly = 1) { client.sendAlbum(any(), any()) }
        assertEquals(5, observedPaths.size)
        assertTrue(observedPaths.none { Files.exists(it) }, "Confirmed/replayed uploads must release their transient copies")
    }

    @Test
    fun `unknown file delivery replays UNKNOWN while retaining the original snapshot`() {
        val source = source("recording.ogg")
        val files = files()
        lateinit var dispatched: Path
        every { client.sendFile(any(), any(), any()) } answers {
            dispatched = Path.of(secondArg<String>())
            throw SendOutcomeUnknownException(42, cause = java.util.concurrent.TimeoutException())
        }
        SendOperationService(mockk { every { currentAccount() } returns "work" }, paths(), mapper).use { operations ->
            val tool = SendFileTool(client, resolver, guardrails, files, guard, audit, mapper, metrics, operations)
            val arguments = mapOf("chat_id" to 42, "file_path" to source.toString(), "idempotency_key" to "one-file")
            assertTrue(tool.executeChecked(exchange, arguments).isError)
            assertTrue(Files.exists(dispatched))
            val replay = tool.executeChecked(exchange, arguments)
            assertFalse(replay.isError)
            assertEquals("UNKNOWN", receipt(replay)["status"])
            assertEquals(true, receipt(replay)["replayed"])
            assertTrue(Files.exists(dispatched), "An unresolved send still owns its original bytes")
            verify(exactly = 1) { client.sendFile(any(), any(), any()) }
            verify(exactly = 1) { guard.checkPermission("send_file", arguments) }
        }
    }
}
