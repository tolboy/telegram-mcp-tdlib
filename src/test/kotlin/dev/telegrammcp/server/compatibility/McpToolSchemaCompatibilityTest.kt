package dev.telegrammcp.server.compatibility

import dev.telegrammcp.server.tool.McpToolHandler
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.ActiveProfiles
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Keeps tool schemas within the conservative JSON-Schema subset accepted by
 * the desktop MCP clients tracked in docs/MCP_CLIENT_COMPATIBILITY.md.
 */
@SpringBootTest
@ActiveProfiles("test")
class McpToolSchemaCompatibilityTest {

    @Autowired
    private lateinit var handlers: List<McpToolHandler>

    @Test
    fun `every tool uses the portable object-input JSON Schema profile`() {
        handlers.forEach { handler ->
            val tool = handler.definition()
            val schema = tool.inputSchema()
            assertEquals("object", schema["type"], "${tool.name()} must have an object input schema")
            assertTrue(schema["properties"] is Map<*, *>, "${tool.name()} must declare properties")

            val unsupported = collectUnsupportedKeywords(schema)
            assertTrue(
                unsupported.isEmpty(),
                "${tool.name()} uses JSON-Schema keywords outside the portable profile: ${unsupported.sorted()}",
            )
        }
    }

    @Test
    fun `identifiers counts and durations advertise integers while geographic values retain decimals`() {
        val realValuedInputs = mapOf(
            "send_location" to setOf("$.properties.latitude", "$.properties.longitude", "$.properties.horizontal_accuracy"),
            "send_venue" to setOf("$.properties.latitude", "$.properties.longitude"),
        )
        val definitions = handlers.map { it.definition() }
        assertTrue(definitions.map { it.name() }.containsAll(realValuedInputs.keys))
        definitions.forEach { tool ->
            assertEquals(
                realValuedInputs[tool.name()].orEmpty(),
                collectNumberTypes(tool.inputSchema()),
                "${tool.name()} must use integer for exact identifiers, counts and durations; only geographic inputs accept fractional values",
            )
        }
    }

    /** Includes array items, nested objects and union type arrays, not just top-level properties. */
    private fun collectNumberTypes(value: Any?, path: String = "$"): Set<String> = when (value) {
        is Map<*, *> -> buildSet {
            val types = value["type"]
            if (types == "number" || types is Iterable<*> && "number" in types) add(path)
            value.forEach { (key, nested) -> addAll(collectNumberTypes(nested, "$path.$key")) }
        }
        is Iterable<*> -> value.withIndex().flatMapTo(mutableSetOf()) { (index, nested) ->
            collectNumberTypes(nested, "$path[$index]")
        }
        is Array<*> -> value.withIndex().flatMapTo(mutableSetOf()) { (index, nested) ->
            collectNumberTypes(nested, "$path[$index]")
        }
        else -> emptySet()
    }

    private fun collectUnsupportedKeywords(value: Any?): Set<String> = when (value) {
        is Map<*, *> -> value.entries.flatMapTo(mutableSetOf()) { (key, nested) ->
            buildSet {
                val keyword = key as? String
                if (keyword in UNSUPPORTED_KEYWORDS) add(keyword!!)
                addAll(collectUnsupportedKeywords(nested))
            }
        }
        is Iterable<*> -> value.flatMapTo(mutableSetOf(), ::collectUnsupportedKeywords)
        is Array<*> -> value.flatMapTo(mutableSetOf(), ::collectUnsupportedKeywords)
        else -> emptySet()
    }

    private companion object {
        val UNSUPPORTED_KEYWORDS = setOf(
            "\$ref", "\$defs", "allOf", "anyOf", "oneOf", "not", "if", "then", "else",
            "dependentRequired", "dependentSchemas", "patternProperties", "propertyNames",
            "unevaluatedItems", "unevaluatedProperties",
        )
    }
}
