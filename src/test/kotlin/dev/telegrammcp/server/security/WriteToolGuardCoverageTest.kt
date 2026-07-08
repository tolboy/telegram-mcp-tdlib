package dev.telegrammcp.server.security

import dev.telegrammcp.server.service.OperationGuardService
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.io.path.readText
import kotlin.streams.asSequence
import kotlin.test.assertTrue

/**
 * Guards the guard: every tool listed in [OperationGuardService.WRITE_TOOLS]
 * must invoke `operationGuardService.checkPermission(...)` in its handler
 * source file. The operation guard is the single entry point for read-only
 * enforcement, destructive-tool confirmation, and the anti-spam rate limiter;
 * a write tool that skips it silently loses all three protections.
 *
 * This is a source-level check because the guard call is a per-tool
 * convention rather than a framework hook. Files hosting several tool classes
 * must contain at least as many guard calls as write tools, which keeps the
 * check honest for grouped handlers (ChatFolderTools, P3SettingsTools, ...).
 */
class WriteToolGuardCoverageTest {

    @Test
    fun `every write tool source calls the operation guard`() {
        val toolNamePattern = Regex("""const val TOOL_NAME = "([a-z0-9_]+)"""")
        val toolFiles = toolSourceFiles()

        val discoveredWriteTools = mutableSetOf<String>()
        val violations = mutableListOf<String>()

        toolFiles.forEach { file ->
            val source = file.readText()
            val toolNames = toolNamePattern.findAll(source).map { it.groupValues[1] }.toList()
            val writeTools = toolNames.filter { it in OperationGuardService.WRITE_TOOLS }
            discoveredWriteTools += writeTools

            if (writeTools.isEmpty()) return@forEach

            val guardCalls = Regex("""checkPermission\(""").findAll(source).count()
            if (guardCalls < writeTools.size) {
                violations += "${file.fileName}: declares write tool(s) ${writeTools.sorted()} " +
                    "but contains only $guardCalls checkPermission call(s)"
            }
        }

        assertTrue(
            violations.isEmpty(),
            "Write tools missing an OperationGuardService.checkPermission call " +
                "(read-only/confirmation/anti-spam bypass):\n" + violations.joinToString("\n"),
        )

        val stalePolicyEntries = OperationGuardService.WRITE_TOOLS - discoveredWriteTools
        assertTrue(
            stalePolicyEntries.isEmpty(),
            "WRITE_TOOLS entries without a matching TOOL_NAME constant " +
                "(renamed or removed tool leaves policy unenforced): ${stalePolicyEntries.sorted()}",
        )
    }

    private fun toolSourceFiles(): List<Path> {
        val toolRoot = locateProjectRoot()
            .resolve(Paths.get("src", "main", "kotlin", "dev", "telegrammcp", "server", "tool"))
        check(Files.isDirectory(toolRoot)) { "Tool source directory not found: $toolRoot" }
        Files.walk(toolRoot).use { stream ->
            return stream.asSequence()
                .filter { it.toString().endsWith(".kt") }
                .toList()
        }
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
