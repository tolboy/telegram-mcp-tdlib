package dev.telegrammcp.server.service

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import dev.telegrammcp.server.client.TelegramAccountContext
import dev.telegrammcp.server.client.TelegramAccountRegistry
import dev.telegrammcp.server.config.McpSecurityProperties
import dev.telegrammcp.server.config.TelegramProperties
import dev.telegrammcp.server.exception.TdLibAuthException
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

class AccountArtifactOwnershipTest {
    @TempDir
    lateinit var root: Path

    private val paths = mockk<PlatformPaths> { every { applicationDataDirectory } answers { root } }
    private val ownership = AccountArtifactOwnership(paths)

    @Test
    fun `a fresh scope binds once and rejects a different user or Telegram network`() {
        ownership.requireUnbound("work")
        ownership.bind("work", 42, testDc = false, legacyOwnerUserId = null)
        val reopened = AccountArtifactOwnership(paths)
        reopened.bind("WORK", 42, testDc = false, legacyOwnerUserId = null)
        assertFailsWith<TdLibAuthException> { reopened.bind("work", 43, false, 43) }
        assertFailsWith<TdLibAuthException> { reopened.bind("work", 42, true, 42) }
        assertFailsWith<TdLibAuthException> { reopened.requireUnbound("work") }
        reopened.requireUnbound("personal")
    }

    @Test
    fun `adopting existing artifacts retains the cursor export ID and idempotency key`() {
        val mapper = jacksonObjectMapper().findAndRegisterModules()
        val client = mockk<dev.telegrammcp.server.client.TelegramClientService>()
        val registry = TelegramAccountRegistry(ownership)
        registry.register(TelegramAccountRegistry.AccountHandle("work", client))
        val accounts = TelegramAccountContext(registry)
        val guard = GuardrailService(McpSecurityProperties(), TelegramProperties())
        val exports = ExportJobService(paths, mapper, accounts, client, guard)
        val receipts = SendOperationJournal(root.resolve("send-operations"), mapper)
        val job = accounts.withAccount("work") { exports.create(17).job }
        receipts.send("work", "delivery-key", 17, "request", {}) { 4 }
        ChangeJournal(paths, mapper).use { journal ->
            val cursor = journal.tailCursor("work")
            journal.append("work", "new", 17, 4)
            journal.tailCursor("work")

            assertContains(assertFailsWith<TdLibAuthException> { ownership.bind("work", 42, false, null) }.message, "no verified owner")
            assertFailsWith<TdLibAuthException> { ownership.bind("work", 42, false, 41) }
            assertFalse(Files.exists(root.resolve("account-artifact-owners/work.owner")))
            ownership.bind("work", 42, false, 42)

            assertFailsWith<TdLibAuthException> { registry.expectedAccountGate("work") }
            assertEquals(4L, journal.page("work", cursor, 5) { true }.entries.single().messageId)
            assertEquals(job, accounts.withAccount("work") { exports.operate(job.jobId, "status").job })
            val receipt = receipts.send("work", "delivery-key", 17, "request", {}) { error("must replay the existing receipt") }
            assertEquals(true, receipt["replayed"])
            assertEquals(4L, receipt["message_id"])
        }
    }

    @Test
    fun `a legacy export on its own requires an owner assertion`() {
        val legacyScope = Files.createDirectories(root.resolve("exports/opaque-client-scope"))
        val job = legacyScope.resolve("existing-job.json")
        Files.writeString(job, "{}")

        assertFailsWith<TdLibAuthException> { ownership.bind("work", 42, false, null) }
        ownership.bind("work", 42, false, 42)
        assertEquals("{}", Files.readString(job))
    }

    @Test
    fun `concurrent first claims cannot assign the same label to two identities`() {
        val start = CountDownLatch(1)
        Executors.newFixedThreadPool(2).use { pool ->
            val claims = listOf(42L, 43L).map { id ->
                pool.submit<Boolean> {
                    check(start.await(5, TimeUnit.SECONDS))
                    runCatching { AccountArtifactOwnership(paths).bind("work", id, false, null) }.isSuccess
                }
            }
            start.countDown()
            assertEquals(1, claims.count { it.get(5, TimeUnit.SECONDS) })
        }
    }
}
