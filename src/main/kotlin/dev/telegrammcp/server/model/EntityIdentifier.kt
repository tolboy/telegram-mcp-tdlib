package dev.telegrammcp.server.model

import dev.telegrammcp.server.exception.InvalidToolInputException

/**
 * Sealed hierarchy representing the supported Telegram entity identifier formats.
 *
 * All tool inputs accepting a "chat_id" / "identifier" pass through [parse] to
 * normalize the raw value into one of:
 * - [NumericId] — an immutable Telegram numeric ID
 * - [Username] — a Telegram @username (5–32 alphanumeric + underscore)
 * - [PhoneNumber] — an international phone number (+digits)
 * - [SelfChat] — the current account's self chat, addressed canonically as `self`
 */
sealed interface EntityIdentifier {

    /** Raw string representation as received from the tool input. */
    val raw: String

    /** Telegram numeric ID (e.g. `123456789`, `-1001234567890`). */
    data class NumericId(val id: Long) : EntityIdentifier {
        override val raw: String get() = id.toString()
    }

    /** Telegram username (e.g. `@channel_name`). Stored without leading `@`. */
    data class Username(val username: String) : EntityIdentifier {
        override val raw: String get() = "@$username"
    }

    /** International phone number (e.g. `+1234567890`). */
    data class PhoneNumber(val phone: String) : EntityIdentifier {
        override val raw: String get() = phone
    }

    /** The current account's Saved Messages / self chat. */
    data class SelfChat(val identifier: String) : EntityIdentifier {
        override val raw: String get() = identifier
    }

    companion object {

        private val USERNAME_PATTERN = Regex("^@?([a-zA-Z][a-zA-Z0-9_]{4,31})$")
        private val PHONE_PATTERN = Regex("^\\+[1-9]\\d{6,14}$")
        private const val SELF_CHAT_IDENTIFIER = "self"

        /**
         * Auto-detects the identifier format and returns the appropriate subtype.
         *
         * @param raw the input value — may be a number, a string-encoded number,
         *            a @username, a phone number, or a self chat alias
         * @throws InvalidToolInputException if the value matches none of the formats
         */
        fun parse(raw: Any): EntityIdentifier {
            return when (raw) {
                is Number -> NumericId(raw.toLong())
                is String -> parseString(raw)
                else -> throw InvalidToolInputException(
                    "Identifier must be a number, username, phone number, or self chat alias, got: ${raw::class.simpleName}",
                )
            }
        }

        private fun parseString(value: String): EntityIdentifier {
            val trimmed = value.trim()
            if (trimmed.isBlank()) {
                throw InvalidToolInputException("Identifier must not be blank")
            }

            val normalizedAlias = normalizeAlias(trimmed)
            if (normalizedAlias == SELF_CHAT_IDENTIFIER) {
                return SelfChat(normalizedAlias)
            }

            // Try as phone number first (+ prefix is exclusive to phone numbers)
            if (trimmed.startsWith("+")) {
                if (PHONE_PATTERN.matches(trimmed)) {
                    return PhoneNumber(trimmed)
                }
                throw InvalidToolInputException(
                    "Invalid phone number format: $trimmed (expected +<country><number>, 7–15 digits)",
                )
            }

            // Try as numeric ID
            trimmed.toLongOrNull()?.let { return NumericId(it) }

            // Try as username
            val usernameMatch = USERNAME_PATTERN.matchEntire(trimmed)
            if (usernameMatch != null) {
                return Username(usernameMatch.groupValues[1])
            }

            throw InvalidToolInputException(
                "Cannot parse identifier '$trimmed' — expected a numeric ID, @username, +phone, or the self chat identifier 'self'",
            )
        }

        private fun normalizeAlias(value: String): String =
            value.lowercase().trim().replace(Regex("\\s+"), " ")
    }
}


