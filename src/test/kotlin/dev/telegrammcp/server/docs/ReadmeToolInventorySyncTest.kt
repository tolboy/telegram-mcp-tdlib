package dev.telegrammcp.server.docs

import dev.telegrammcp.server.tool.McpToolHandler
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.ActiveProfiles
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Guards against documentation drift: every tool advertised by the server
 * must appear in the README "Available MCP Tools" tables and vice versa.
 *
 * The README is the public contract; a tool added or removed in code without
 * a matching table row fails the build instead of shipping stale docs.
 */
@SpringBootTest
@ActiveProfiles("test")
class ReadmeToolInventorySyncTest {

    @Autowired
    private lateinit var handlers: List<McpToolHandler>

    @Test
    fun `README tool tables match the registered tool inventory`() {
        val registered = handlers.map { it.definition().name() }.toSet()
        val section = Files.readString(locateReadme())
            .substringAfter("## Available MCP Tools")
            .substringBefore("\n## ")
        val documented = toolRows(section).toSet()

        val undocumented = registered - documented
        val stale = documented - registered

        assertTrue(
            undocumented.isEmpty(),
            "Tools registered in code but missing from README tables: ${undocumented.sorted()}",
        )
        assertTrue(
            stale.isEmpty(),
            "Tools documented in README but not registered in code: ${stale.sorted()}",
        )
        val inventory = section.substringAfter("<details>").substringBefore("</details>")
        val summary = requireNotNull(Regex("""Show all (\d+) tools""").find(inventory))
        assertEquals(registered.size, summary.groupValues[1].toInt(), "Full inventory summary count")
        assertEquals(registered.size, toolRows(inventory).size, "Full inventory table row count")
        assertEquals(registered, toolRows(inventory).toSet(), "Full inventory tables")
        val heading = Regex("""^### .* \((\d+) tools?\)\s*$""")
        for (group in inventory.split("\n### ").drop(1)) {
            val title = group.lineSequence().first()
            val expected = requireNotNull(heading.matchEntire("### $title")) { "Missing count in $title" }
            assertEquals(expected.groupValues[1].toInt(), toolRows(group).size, title)
        }
    }

    private fun toolRows(section: String): List<String> {
        val rowPattern = Regex("""^\|\s*`([a-z_][a-z0-9_]*)`\s*\|""", RegexOption.MULTILINE)
        return rowPattern.findAll(section).map { it.groupValues[1] }.toList()
    }

    /**
     * Tests normally run with the module directory as the working directory,
     * but walk upwards defensively so an IDE launch from a subdirectory works.
     */
    private fun locateReadme(): Path {
        var dir: Path? = Paths.get(System.getProperty("user.dir")).toAbsolutePath()
        while (dir != null) {
            val candidate = dir.resolve("README.md")
            if (Files.exists(candidate) && Files.exists(dir.resolve("settings.gradle.kts"))) {
                return candidate
            }
            dir = dir.parent
        }
        error("README.md not found upwards from ${System.getProperty("user.dir")}")
    }
}
