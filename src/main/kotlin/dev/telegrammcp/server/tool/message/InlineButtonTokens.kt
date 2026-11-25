package dev.telegrammcp.server.tool.message

import dev.telegrammcp.server.client.TelegramAccountContext
import dev.telegrammcp.server.model.InlineButtonInfo
import org.springframework.stereotype.Component
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Signs the button snapshots returned by list_inline_buttons.
 *
 * A token is an HMAC-SHA256 under a random per-process key over the account,
 * chat ID, message ID, the message's `edit_date`, the button's position and
 * its type, text, URL and callback data. A fresh read must match those fields
 * for the token to be valid. Tokens are scoped to one account, chat and
 * message and expire on server restart.
 */
@Component
class InlineButtonTokens(private val accounts: TelegramAccountContext? = null) {
    private val key: SecretKeySpec = SecretKeySpec(ByteArray(KEY_BYTES).also(SecureRandom()::nextBytes), ALGORITHM)

    /** Returns [buttons] with their [InlineButtonInfo.token] set. */
    fun sign(chatId: Long, messageId: Long, buttons: List<InlineButtonInfo>): List<InlineButtonInfo> =
        buttons.map { it.copy(token = tokenFor(chatId, messageId, it)) }

    /**
     * Finds the button in a fresh read ([current]) whose token equals [token],
     * comparing in constant time. Returns null when no current button matches.
     */
    fun find(token: String, chatId: Long, messageId: Long, current: List<InlineButtonInfo>): InlineButtonInfo? {
        val presented = token.trim().toByteArray(Charsets.US_ASCII)
        return current.firstOrNull { MessageDigest.isEqual(tokenFor(chatId, messageId, it).toByteArray(Charsets.US_ASCII), presented) }
    }

    fun tokenFor(chatId: Long, messageId: Long, button: InlineButtonInfo): String {
        val bytes = ByteArrayOutputStream()
        DataOutputStream(bytes).use { out ->
            out.writeField(VERSION)
            out.writeField(currentAccount())
            out.writeLong(chatId)
            out.writeLong(messageId)
            out.writeInt(button.messageEditDate)
            out.writeInt(button.position)
            out.writeInt(button.row)
            out.writeInt(button.index)
            out.writeField(button.type)
            out.writeField(button.text)
            out.writeField(button.url)
            out.writeField(button.callbackPayload())
        }
        val mac = Mac.getInstance(ALGORITHM).apply { init(key) }.doFinal(bytes.toByteArray())
        return PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(mac.copyOf(TOKEN_BYTES))
    }

    private fun currentAccount(): String = runCatching { accounts?.currentAccount() }.getOrNull().orEmpty()

    /** Length-prefixed, with a distinct marker for null, so no two field lists encode alike. */
    private fun DataOutputStream.writeField(value: String?) = writeField(value?.toByteArray(Charsets.UTF_8))

    private fun DataOutputStream.writeField(value: ByteArray?) {
        if (value == null) {
            writeInt(-1)
            return
        }
        writeInt(value.size)
        write(value)
    }

    companion object {
        const val PREFIX = "ibt1."
        private const val VERSION = "inline-button-token/v1"
        private const val ALGORITHM = "HmacSHA256"
        private const val KEY_BYTES = 32
        private const val TOKEN_BYTES = 18
    }
}
