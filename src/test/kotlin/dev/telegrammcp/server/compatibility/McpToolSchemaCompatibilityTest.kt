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
