package dev.telegrammcp.server.tool

import io.modelcontextprotocol.json.schema.jackson3.DefaultJsonSchemaValidator
import org.junit.jupiter.api.Test
import kotlin.test.*

class ToolPaginationTest {
    @Test
    fun `empty filtered lists never imply exhaustion and retain legacy text`() {
        val tool = ToolSupport.definition("get_history", "History", mapOf("type" to "object",
            "properties" to mapOf("from_message_id" to mapOf("type" to "number"))))
        val original = ToolSupport.textResult("[]")
        val result = ToolPagination.decorate(tool, original)
        assertEquals(original.content(), result.content())
        val page = ((result.structuredContent() as Map<*, *>)["meta"] as Map<*, *>)["page"] as Map<*, *>
        assertEquals(0, page["returned_count"])
        assertNull(page["complete"])
        assertEquals(listOf("from_message_id"), page["continuation_parameters"])
        assertTrue(DefaultJsonSchemaValidator().validate(tool.outputSchema(), result.structuredContent()).valid())
    }

    @Test
    fun `all catalog contracts reject an arbitrary object and accept structured errors`() {
        val validator = DefaultJsonSchemaValidator()
        for ((name, _) in ToolContractCatalog.schemas) {
            val tool = ToolSupport.definition(name, name, mapOf("type" to "object"))
            assertFalse(validator.validate(tool.outputSchema(), ToolSupport.textResult("{\"unexpected\":true}").structuredContent()).valid(), name)
            assertTrue(validator.validate(tool.outputSchema(), ToolSupport.errorResult(IllegalArgumentException("bad input")).structuredContent()).valid(), name)
        }
    }
}
