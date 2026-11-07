package dev.telegrammcp.server.config

import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * TDLib, its native ABI, and the MCP Java SDK move only when this test is
 * edited on purpose. A bump also needs an Unreleased changelog line
 * `Dependency upgrade: tdlight=<version> mcp-sdk=<version>` describing the
 * compatibility check. Refactors must not change these pins.
 */
class DependencyUpgradeGateTest {
    @Test
    fun `reviewed tdlight and mcp sdk pins are unchanged`() {
        val gradle = Files.readString(Path.of("build.gradle.kts"))
        val tdlight = Regex("""val tdlightVersion = "([^"]+)"""").find(gradle)?.groupValues?.get(1)
        val mcp = Regex("""val mcpSdkVersion = "([^"]+)"""").find(gradle)?.groupValues?.get(1)
        if (tdlight != TDLIGHT || mcp != MCP_SDK) {
            val changelog = Files.readString(Path.of("CHANGELOG.md"))
            assertTrue(
                changelog.contains("Dependency upgrade: tdlight=$tdlight mcp-sdk=$mcp"),
                "Changing TDLib or the MCP SDK requires the dependency-upgrade changelog line and a review of this test's pins",
            )
        }
        assertEquals(TDLIGHT, tdlight)
        assertEquals(MCP_SDK, mcp)
    }

    private companion object {
        const val TDLIGHT = "3.5.9+td.1.8.68"
        const val MCP_SDK = "2.1.0"
    }
}
