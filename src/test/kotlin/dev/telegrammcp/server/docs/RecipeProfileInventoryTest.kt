package dev.telegrammcp.server.docs

import dev.telegrammcp.server.config.McpSecurityProperties
import dev.telegrammcp.server.config.McpToolProfile
import dev.telegrammcp.server.service.ToolSurfacePolicy
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.streams.asSequence
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Keeps every recipe runnable under the configuration it prints.
 *
 * A recipe is a copy-paste contract: it states a profile and a read-only flag,
 * then names the tools the model needs. Nothing linked those two halves, so the
 * documented `inbox` recipe asked the model to summarize chats with a profile
 * that could not enumerate them. Narrowing a profile now fails here instead of
 * in a user's client.
 */
class RecipeProfileInventoryTest {

    @Test
    fun `every recipe declares tools its own profile exposes`() {
        val recipes = recipeFiles()
        assertTrue(recipes.isNotEmpty(), "No recipes found to verify")

        val failures = buildList {
            recipes.forEach { recipe ->
                val declaration = parse(recipe)
                val policy = ToolSurfacePolicy(McpSecurityProperties(toolProfile = declaration.profile))
                declaration.tools
                    .filterNot { policy.isVisible(it, readOnly = declaration.readOnly) }
                    .forEach { tool ->
                        add(
                            "${declaration.name}: `$tool` is documented but hidden by " +
                                "MCP_TOOL_PROFILE=${declaration.profile.name.lowercase().replace('_', '-')} " +
                                "with MCP_READ_ONLY=${declaration.readOnly}",
                        )
                    }
            }
        }

        if (failures.isNotEmpty()) {
            fail("Recipes promise tools their configuration hides:\n" + failures.joinToString("\n") { "  - $it" })
        }
    }

    /**
     * The check above is only worth as much as its parsing: a restructured
     * recipe that no longer matches these patterns would silently verify
     * nothing at all.
     */
    @Test
    fun `every recipe states a profile and the tools it uses`() {
        recipeFiles().forEach { recipe ->
            val declaration = parse(recipe)
            assertTrue(
                declaration.tools.isNotEmpty(),
                "${declaration.name} lists no tools — expected '- `tool_name` — description' bullets",
            )
        }
    }

    private fun parse(recipe: Path): RecipeDeclaration {
        val name = recipe.fileName.toString()
        val text = Files.readString(recipe)
        val profile = PROFILE.find(text)?.groupValues?.get(1)
            ?: error("$name does not declare MCP_TOOL_PROFILE")
        val readOnly = READ_ONLY.find(text)?.groupValues?.get(1)
            ?: error("$name does not declare MCP_READ_ONLY")
        return RecipeDeclaration(
            name = name,
            profile = McpToolProfile.valueOf(profile.uppercase().replace('-', '_')),
            readOnly = readOnly.toBooleanStrict(),
            tools = TOOL_BULLET.findAll(text).map { it.groupValues[1] }.toList(),
        )
    }

    private fun recipeFiles(): List<Path> {
        val recipeRoot = locateProjectRoot().resolve(Paths.get("docs", "recipes"))
        check(Files.isDirectory(recipeRoot)) { "Recipe directory not found: $recipeRoot" }
        Files.list(recipeRoot).use { stream ->
            return stream.asSequence()
                .filter { it.fileName.toString().endsWith(".md") }
                // The index describes the recipes rather than configuring one.
                .filter { it.fileName.toString() != "README.md" }
                .sorted()
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

    private data class RecipeDeclaration(
        val name: String,
        val profile: McpToolProfile,
        val readOnly: Boolean,
        val tools: List<String>,
    )

    private companion object {
        private val PROFILE = Regex(""""MCP_TOOL_PROFILE":\s*"([a-z-]+)"""")
        private val READ_ONLY = Regex(""""MCP_READ_ONLY":\s*"(true|false)"""")

        /**
         * Only a bullet that opens with a backticked name declares part of the
         * recipe's surface. Prose naming a tool — the write tools a read-only
         * profile hides, or a variation that needs a different profile — is
         * deliberately not a promise about this configuration.
         */
        private val TOOL_BULLET = Regex("""(?m)^- `([a-z_]+)`""")
    }
}
