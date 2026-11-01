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
