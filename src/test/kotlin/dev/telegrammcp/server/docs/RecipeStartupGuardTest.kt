package dev.telegrammcp.server.docs

import com.fasterxml.jackson.databind.ObjectMapper
import dev.telegrammcp.server.config.McpSecurityProperties
import dev.telegrammcp.server.config.ServerModeProperties
import dev.telegrammcp.server.security.WriteSafetyConfigurationValidator
import dev.telegrammcp.server.service.ToolSurfacePolicy
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.boot.context.properties.bind.Binder
import org.springframework.boot.env.YamlPropertySourceLoader
import org.springframework.core.env.MapPropertySource
import org.springframework.core.env.StandardEnvironment
import org.springframework.core.io.ClassPathResource
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.streams.asSequence
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Runs the configuration every recipe prints through the startup guards.
 *
 * [RecipeProfileInventoryTest] proves a recipe's tools are visible; this proves
 * the server would start at all. 1.18.1 began refusing writes without a human
 * approval route, and the shipped `safe-draft-reply` recipe kept the old env
 * block, so copying it produced a server that exited at startup.
 */
class RecipeStartupGuardTest {

    @Test
    fun `every recipe env block passes the startup guards`() {
        val recipes = recipeEnvironments()
        assertTrue(recipes.isNotEmpty(), "No recipe env blocks found to verify")

        val failures = recipes.mapNotNull { (name, env) ->
            runCatching { startupGuards(env) }.exceptionOrNull()?.let { "$name: ${rootMessage(it)}" }
        }
        if (failures.isNotEmpty()) {
            fail("Recipes print configurations the server refuses at startup:\n" + failures.joinToString("\n") { "  - $it" })
        }
    }

    @Test
    fun `the harness rejects a write recipe without an approval route`() {
        // Guards the guard: if binding silently ignored the env block, the test
        // above would pass for every recipe regardless of its content.
        assertThrows<IllegalArgumentException> {
            startupGuards(
                mapOf(
                    "MCP_TOOL_PROFILE" to "inbox",
                    "MCP_READ_ONLY" to "false",
                    "MCP_CONFIRMATION_REQUIRED" to "true",
                ),
            )
        }
    }

    @Test
    fun `every write-enabled recipe names an approval route`() {
        recipeEnvironments()
            .filter { (_, env) -> env["MCP_READ_ONLY"].equals("false", ignoreCase = true) }
            .forEach { (name, env) ->
                assertTrue(
                    env["MCP_DESTRUCTIVE_APPROVAL"].orEmpty().lowercase() in setOf("auto", "elicitation", "loopback"),
                    "$name enables writes without MCP_DESTRUCTIVE_APPROVAL",
                )
            }
    }

    /** Binds application.yml with [env] taking the place of process environment variables. */
    private fun startupGuards(env: Map<String, String>) {
        val environment = StandardEnvironment()
        environment.propertySources.remove(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME)
        environment.propertySources.remove(StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME)
        environment.propertySources.addFirst(MapPropertySource("recipe-env", env))
        YamlPropertySourceLoader()
            .load("application.yml", ClassPathResource("application.yml"))
            .forEach(environment.propertySources::addLast)
        val binder = Binder.get(environment)
        val serverMode = binder.bindOrCreate("server-mode", ServerModeProperties::class.java)
        val mcp = binder.bindOrCreate("mcp", McpSecurityProperties::class.java)

        WriteSafetyConfigurationValidator(serverMode).validate()
        ToolSurfacePolicy(mcp)
    }

    private fun recipeEnvironments(): List<Pair<String, Map<String, String>>> {
        val mapper = ObjectMapper()
        return Files.list(recipeDirectory()).use { stream ->
            stream.asSequence()
                .filter { it.fileName.toString().endsWith(".md") && it.fileName.toString() != "README.md" }
                .sortedBy { it.fileName.toString() }
                .flatMap { recipe ->
                    ENV_BLOCK.findAll(Files.readString(recipe)).map { match ->
                        val env = mapper.readTree("{${match.groupValues[1]}}").path("env")
                        val values = env.properties().associate { (key, value) -> key to value.asText() }
                        recipe.fileName.toString() to values
                    }
                }
                .toList()
        }
    }

    private fun rootMessage(error: Throwable): String {
        var current = error
        while (current.cause != null && current.cause !== current) current = current.cause!!
        return current.message ?: current.javaClass.simpleName
    }

    private fun recipeDirectory(): Path {
        var current: Path? = Paths.get("").toAbsolutePath()
        while (current != null) {
            val candidate = current.resolve("docs/recipes")
            if (Files.isDirectory(candidate)) return candidate
            current = current.parent
        }
        error("docs/recipes not found from ${Paths.get("").toAbsolutePath()}")
    }

    private companion object {
        private val ENV_BLOCK = Regex("```json\\s*(\"env\"\\s*:\\s*\\{.*?})\\s*```", RegexOption.DOT_MATCHES_ALL)
    }
}
