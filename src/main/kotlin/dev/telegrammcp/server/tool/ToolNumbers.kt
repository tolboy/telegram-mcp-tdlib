package dev.telegrammcp.server.tool

import dev.telegrammcp.server.exception.InvalidToolInputException
import dev.telegrammcp.server.util.ExactNumbers

/** Server-side parsing for integer tool arguments; advertised JSON Schema is not a validator. */
object ToolNumbers {
    /** Accepts an exact int64 JSON number or a decimal integer string. */
    fun long(value: Any?, field: String): Long = try {
        when (value) {
            is Number -> ExactNumbers.long(value)
            is String -> value.trim().toLongOrNull() ?: invalid(field, 64)
            else -> invalid(field, 64)
        }
    } catch (_: IllegalArgumentException) {
        invalid(field, 64)
    } catch (_: ArithmeticException) {
        invalid(field, 64)
    }

    /** Checks the range before narrowing, so oversized limits cannot wrap to a valid value. */
    fun int(value: Any?, field: String): Int {
        val number = long(value, field)
        if (number !in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong()) invalid(field, 32)
        return number.toInt()
    }

    /** Defaults apply only to absent arguments; explicit null is malformed integer input. */
    fun optionalLong(arguments: Map<String, Any>, field: String): Long? =
        if (arguments.containsKey(field)) long(arguments[field], field) else null

    /** Defaults apply only to absent arguments; explicit null is malformed integer input. */
    fun optionalInt(arguments: Map<String, Any>, field: String): Int? =
        if (arguments.containsKey(field)) int(arguments[field], field) else null

    private fun invalid(field: String, bits: Int): Nothing =
        throw InvalidToolInputException("$field must be an exact $bits-bit integer")
}
