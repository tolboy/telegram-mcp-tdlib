package dev.telegrammcp.server.model

/**
 * Domain representation of a Telegram user/contact.
 */
data class UserInfo(
    val userId: Long,
    val firstName: String,
    val lastName: String? = null,
    val username: String? = null,
    val phoneNumber: String? = null,
    val isBot: Boolean = false,
    val isContact: Boolean = false,
    val isPremium: Boolean = false,
    val bio: String? = null,
    val lastSeenStatus: String? = null,
)

/**
 * Represents a Telegram contact entry (from the address book).
 */
data class ContactInfo(
    val userId: Long,
    val firstName: String,
    val lastName: String? = null,
    val username: String? = null,
    val phoneNumber: String? = null,
)
