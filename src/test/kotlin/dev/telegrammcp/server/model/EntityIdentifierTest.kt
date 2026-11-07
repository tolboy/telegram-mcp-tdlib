package dev.telegrammcp.server.model

import dev.telegrammcp.server.exception.InvalidToolInputException
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.test.assertEquals
import kotlin.test.assertIs

class EntityIdentifierTest {

    @Nested
    inner class ParseNumericId {

        @Test
        fun `parses positive integer`() {
            val id = EntityIdentifier.parse(123456789L)
            assertIs<EntityIdentifier.NumericId>(id)
            assertEquals(123456789L, id.id)
        }

        @Test
        fun `parses negative integer`() {
            val id = EntityIdentifier.parse(-1001234567890L)
            assertIs<EntityIdentifier.NumericId>(id)
            assertEquals(-1001234567890L, id.id)
        }

        @Test
        fun `parses string-encoded integer`() {
            val id = EntityIdentifier.parse("-1001234567890")
            assertIs<EntityIdentifier.NumericId>(id)
            assertEquals(-1001234567890L, id.id)
        }

        @Test
        fun `parses Int as number`() {
            val id = EntityIdentifier.parse(42)
            assertIs<EntityIdentifier.NumericId>(id)
            assertEquals(42L, id.id)
        }

        @Test
        fun `preserves exact integer values across JSON number representations`() {
            for (value in listOf<Number>(42.0, java.math.BigDecimal("42.000"), java.math.BigInteger("42"))) {
                assertEquals(EntityIdentifier.NumericId(42), EntityIdentifier.parse(value))
            }
            for (value in listOf(Long.MIN_VALUE, Long.MAX_VALUE)) {
                assertEquals(EntityIdentifier.NumericId(value), EntityIdentifier.parse(value))
            }
        }

        @Test
        fun `rejects fractional nonfinite and overflowing identifiers without changing destination`() {
            val invalid = listOf<Number>(
                42.9, Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY,
                Long.MAX_VALUE.toDouble(), 1.0000000000000001E18, 16_777_216f,
                java.math.BigInteger("9223372036854775808"),
                java.math.BigInteger("-9223372036854775809"), java.math.BigDecimal("42.000000000000000001"),
            )
            for (value in invalid) {
                assertThrows<InvalidToolInputException> { EntityIdentifier.parse(value) }
            }
        }
    }

    @Nested
    inner class ParseUsername {

        @Test
        fun `parses username with @`() {
            val id = EntityIdentifier.parse("@channel_name")
            assertIs<EntityIdentifier.Username>(id)
            assertEquals("channel_name", id.username)
        }

        @Test
        fun `parses username without @`() {
            val id = EntityIdentifier.parse("channel_name")
            assertIs<EntityIdentifier.Username>(id)
            assertEquals("channel_name", id.username)
        }

        @Test
        fun `rejects too short username`() {
            assertThrows<InvalidToolInputException> {
                EntityIdentifier.parse("@ab")
            }
        }
    }

    @Nested
    inner class ParsePhoneNumber {

        @Test
        fun `parses valid phone number`() {
            val id = EntityIdentifier.parse("+1234567890")
            assertIs<EntityIdentifier.PhoneNumber>(id)
            assertEquals("+1234567890", id.phone)
        }

        @Test
        fun `rejects invalid phone format`() {
            assertThrows<InvalidToolInputException> {
                EntityIdentifier.parse("+123") // too short
            }
        }
    }

    @Nested
    inner class ParseSelfChat {

        @Test
        fun `parses self alias`() {
            val id = EntityIdentifier.parse("self")
            assertIs<EntityIdentifier.SelfChat>(id)
            assertEquals("self", id.identifier)
        }

        @Test
        fun `rejects natural language self aliases`() {
            assertThrows<InvalidToolInputException> {
                EntityIdentifier.parse("Saved Messages")
            }
            assertThrows<InvalidToolInputException> {
                EntityIdentifier.parse("сохранёнки")
            }
        }
    }

    @Nested
    inner class ParseErrors {

        @Test
        fun `rejects blank input`() {
            assertThrows<InvalidToolInputException> {
                EntityIdentifier.parse("  ")
            }
        }

        @Test
        fun `rejects unsupported type`() {
            assertThrows<InvalidToolInputException> {
                EntityIdentifier.parse(listOf(1, 2, 3))
            }
        }
    }
}

