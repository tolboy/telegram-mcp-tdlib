package dev.telegrammcp.server.docs

import dev.telegrammcp.server.tool.McpToolHandler
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.ActiveProfiles
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
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
        val documented = readmeDocumentedTools()

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
    }

    private fun readmeDocumentedTools(): Set<String> {
        val readme = Files.readString(locateReadme())
        val toolsSection = readme
            .substringAfter("## Available MCP Tools")
            .substringBefore("\n## ")
        val rowPattern = Regex("""^\|\s*`([a-z_][a-z0-9_]*)`\s*\|""", RegexOption.MULTILINE)
        return rowPattern.findAll(toolsSection).map { it.groupValues[1] }.toSet()
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
