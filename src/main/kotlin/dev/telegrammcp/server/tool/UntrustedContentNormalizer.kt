package dev.telegrammcp.server.tool

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ArrayNode
import com.fasterxml.jackson.databind.node.ObjectNode
import com.fasterxml.jackson.databind.node.TextNode

/**
 * Makes invisible or direction-changing Unicode visible before Telegram data
 * crosses the MCP trust boundary.
 *
 * This deliberately does not filter words or instruction-like phrases.
 * Telegram content remains data, including text that happens to look like a
 * prompt. Only characters that can alter terminal/model presentation without
 * being visible are replaced by a reversible `\\uXXXX` representation.
 */
object UntrustedContentNormalizer {

    data class Result(
        val value: Any?,
        val escapedCharacterCount: Int,
    )

    fun normalize(
        value: Any?,
        objectMapper: ObjectMapper,
    ): Result {
        val tree = objectMapper.valueToTree<JsonNode>(value)
        val counter = Counter()
        val normalized = normalizeNode(tree, counter)
        val converted = objectMapper.convertValue(normalized, Any::class.java)
        return Result(converted, counter.value)
    }

    fun normalizeText(value: String): Pair<String, Int> {
        val counter = Counter()
        return normalizeString(value, counter) to counter.value
    }

    private fun normalizeNode(
        node: JsonNode,
        counter: Counter,
    ): JsonNode = when {
        node.isTextual -> TextNode(normalizeString(node.textValue(), counter))
        node.isObject -> normalizeObject(node as ObjectNode, counter)
        node.isArray -> normalizeArray(node as ArrayNode, counter)
        else -> node.deepCopy()
    }

    private fun normalizeObject(
        source: ObjectNode,
        counter: Counter,
    ): ObjectNode {
        val result = source.objectNode()
        source.properties().forEach { (name, value) ->
            result.set<JsonNode>(normalizeString(name, counter), normalizeNode(value, counter))
        }
        return result
    }

    private fun normalizeArray(
        source: ArrayNode,
        counter: Counter,
    ): ArrayNode {
        val result = source.arrayNode()
        source.forEach { result.add(normalizeNode(it, counter)) }
        return result
    }

    private fun normalizeString(
        source: String,
        counter: Counter,
    ): String = buildString(source.length) {
        source.codePoints().forEach { codePoint ->
            if (shouldEscape(codePoint)) {
                counter.value++
                append(escapeCodePoint(codePoint))
            } else {
                appendCodePoint(codePoint)
            }
        }
    }

    private fun shouldEscape(codePoint: Int): Boolean =
        when {
            codePoint == '\n'.code || codePoint == '\r'.code || codePoint == '\t'.code -> false
            codePoint in 0x00..0x1F || codePoint in 0x7F..0x9F -> true
            codePoint in BIDI_CONTROLS -> true
            codePoint in INVISIBLE_FORMATTING -> true
            else -> false
        }

    private fun escapeCodePoint(codePoint: Int): String =
        if (codePoint <= 0xFFFF) {
            "\\u%04X".format(codePoint)
        } else {
            val chars = Character.toChars(codePoint)
            chars.joinToString(separator = "") { "\\u%04X".format(it.code) }
        }

    private class Counter(var value: Int = 0)

    private val BIDI_CONTROLS = setOf(
        0x061C, // Arabic letter mark
        0x200E, // LRM
        0x200F, // RLM
        0x202A, // LRE
        0x202B, // RLE
        0x202C, // PDF
        0x202D, // LRO
        0x202E, // RLO
        0x2066, // LRI
        0x2067, // RLI
        0x2068, // FSI
        0x2069, // PDI
    )

    private val INVISIBLE_FORMATTING = setOf(
        0x00AD, // soft hyphen
        0x034F, // combining grapheme joiner
        0x180E, // Mongolian vowel separator
        0x200B, // zero-width space
        0x200C, // zero-width non-joiner
        0x200D, // zero-width joiner
        0x2060, // word joiner
        0x2061, // function application
        0x2062, // invisible times
        0x2063, // invisible separator
        0x2064, // invisible plus
        0xFEFF, // zero-width no-break space / BOM
    )
}
