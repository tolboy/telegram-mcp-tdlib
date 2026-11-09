package dev.telegrammcp.server.util

/** Exact integer conversion for untrusted JSON numbers, shared by entities and tools. */
object ExactNumbers {
    /** Rejects fractions, overflow and floating-point values with an ambiguous integer identity. */
    fun long(value: Number): Long {
        require(value !is Double || (value.isFinite() && kotlin.math.abs(value) <= 9_007_199_254_740_991.0))
        require(value !is Float || (value.isFinite() && kotlin.math.abs(value) <= 16_777_215f))
        return value.toString().toBigDecimal().longValueExact()
    }
}
