package dev.telegrammcp.server.tool

import dev.telegrammcp.server.exception.InvalidToolInputException
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.math.BigInteger
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class ToolNumbersTest {
    @Test
    fun `preserves exact integers including int64 endpoints and supported decimal strings`() {
        for (value in listOf<Any>(42, 42L, 42.0, 42f, BigDecimal("42.000"), BigInteger("42"), " 42 ")) {
            assertEquals(42L, ToolNumbers.long(value, "message_id"))
            assertEquals(42, ToolNumbers.int(value, "limit"))
        }
        for (value in listOf(Long.MIN_VALUE, Long.MAX_VALUE)) {
            assertEquals(value, ToolNumbers.long(value, "message_id"))
            assertEquals(value, ToolNumbers.long(value.toString(), "message_id"))
        }
        assertEquals(Int.MIN_VALUE, ToolNumbers.int(Int.MIN_VALUE, "offset"))
        assertEquals(Int.MAX_VALUE, ToolNumbers.int(Int.MAX_VALUE, "limit"))
    }

    @Test
    fun `rejects fractional overflowing ambiguous and malformed IDs without silently changing them`() {
        val invalid = listOf<Any?>(null, true, "", "42.5", "1e3", "9223372036854775808",
            42.5, Double.NaN, Double.POSITIVE_INFINITY, 9_007_199_254_740_992.0, 16_777_216f,
            BigDecimal("42.000000000000000001"), BigInteger("9223372036854775808"))
        for (value in invalid) {
            val failure = assertFailsWith<InvalidToolInputException> { ToolNumbers.long(value, "message_id") }
            assertContains(failure.message, "message_id")
        }
    }

    @Test
    fun `rejects limits before narrowing or clamping can hide an overflow`() {
        for (value in listOf<Any>(2_147_483_648L, -2_147_483_649L, "4294967297", BigInteger("4294967297"))) {
            assertFailsWith<InvalidToolInputException> { ToolNumbers.int(value, "limit") }
        }
    }

    @Test
    fun `defaults apply to absent arguments and never mask an explicit null`() {
        assertNull(ToolNumbers.optionalLong(emptyMap(), "message_id"))
        assertNull(ToolNumbers.optionalInt(emptyMap(), "limit"))
        @Suppress("UNCHECKED_CAST")
        val invalid = mapOf("message_id" to null, "limit" to null) as Map<String, Any>
        assertFailsWith<InvalidToolInputException> { ToolNumbers.optionalLong(invalid, "message_id") }
        assertFailsWith<InvalidToolInputException> { ToolNumbers.optionalInt(invalid, "limit") }
    }
}
