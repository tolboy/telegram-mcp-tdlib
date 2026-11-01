package dev.telegrammcp.server.service

import dev.telegrammcp.server.config.AntiSpamProperties
import dev.telegrammcp.server.config.AntiSpamProperties.RuleProps
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermission
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class AntiSpamPolicyServiceTest {

    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `runtime override cannot disable the guard or exceed the default ceiling`() {
        val service = AntiSpamPolicyService(AntiSpamProperties(overridesFile = ""))
        val applied = service.setOverride(
            "send_file",
            RuleProps(maxOps = 10_000, dailyMax = -1, windowMs = 1, exempt = true),
        )

        assertEquals(60, applied.maxOps)
        assertEquals(500, applied.dailyMax)
        assertEquals(1_000, applied.windowMs)
        assertFalse(applied.exempt)
    }

    @Test
    fun `explicit ceiling stays tighter than the default runtime ceiling`() {
        val service = AntiSpamPolicyService(
            AntiSpamProperties(
                overridesFile = "",
                ceilings = mapOf(
                    "send_message" to AntiSpamProperties.RuleCeilings(maxOpsCeiling = 30, dailyMaxCeiling = 100),
                ),
            ),
        )
        val applied = service.setOverride("send_message", RuleProps(maxOps = 10_000, dailyMax = 10_000))

        assertEquals(30, applied.maxOps)
        assertEquals(100, applied.dailyMax)
    }

    @Test
    fun `yaml rules are kept when the tool has no explicit ceiling`() {
        val service = AntiSpamPolicyService(
            AntiSpamProperties(
                overridesFile = "",
                rules = mapOf("search_messages" to RuleProps(maxOps = 120)),
            ),
        )

        assertEquals(120, service.effectiveRule("search_messages", RuleProps(maxOps = 10)).maxOps)
    }

    @Test
    fun `a partial explicit ceiling preserves safety floors and the daily ceiling`() {
        val service = AntiSpamPolicyService(
            AntiSpamProperties(
                overridesFile = "",
                ceilings = mapOf("send_message" to AntiSpamProperties.RuleCeilings(maxOpsCeiling = 30)),
            ),
        )
        service.setOverride(
            "send_message",
            RuleProps(maxOps = 10_000, windowMs = -1, windowMsExternal = -1, dailyMax = -1, dedupWindowMs = -1),
        )
        val effective = service.effectiveRule("send_message", RuleProps())
        assertEquals(30, effective.maxOps)
        assertEquals(500, effective.dailyMax)
        assertEquals(1_000, effective.windowMs)
        assertEquals(1_000, effective.windowMsExternal)
        assertEquals(1_000, effective.dedupWindowMs)
    }

    @Test
    fun `runtime policy cannot redirect the rate counter or disable duplicate detection`() {
        val service = AntiSpamPolicyService(AntiSpamProperties(overridesFile = ""))
        service.setOverride("send_message", RuleProps(keyField = "text", dedupTextField = null))
        val effective = service.effectiveRule("send_message", RuleProps(keyField = "chat_id", dedupTextField = "text"))
        assertEquals("chat_id", effective.keyField)
        assertEquals("text", effective.dedupTextField)
    }

    @Test
    fun `blank overrides are invalid JSON rather than an empty policy`() {
        val file = Files.writeString(tempDir.resolve("empty.json"), " ")
        val service = AntiSpamPolicyService(AntiSpamProperties(overridesFile = file.toString()))
        assertThrows<IllegalStateException> { service.loadOverrides() }
    }

    @Test
    fun `persist does not overwrite a preexisting fixed temporary path`() {
        val file = tempDir.resolve("overrides.json")
        val planted = Files.writeString(tempDir.resolve("overrides.json.tmp"), "sentinel")
        val service = AntiSpamPolicyService(AntiSpamProperties(overridesFile = file.toString()))
        service.setOverride("send_message", RuleProps(maxOps = 10))
        assertEquals("sentinel", Files.readString(planted))
        val reloaded = AntiSpamPolicyService(AntiSpamProperties(overridesFile = file.toString()))
        reloaded.loadOverrides()
        assertEquals(10, reloaded.listOverrides().getValue("send_message").maxOps)
    }

    @Test
    fun `a corrupt overrides file refuses startup`() {
        val file = tempDir.resolve("anti-spam-overrides.json")
        Files.writeString(file, "{")
        runCatching {
            Files.setPosixFilePermissions(
                file,
                setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE),
            )
        }
        val service = AntiSpamPolicyService(AntiSpamProperties(overridesFile = file.toString()))

        assertThrows<IllegalStateException> { service.loadOverrides() }
    }
}
