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

