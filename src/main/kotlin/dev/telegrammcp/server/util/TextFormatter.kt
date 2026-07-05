package dev.telegrammcp.server.util

import dev.telegrammcp.server.client.TdLibClientService
import dev.telegrammcp.server.model.ParseMode
import it.tdlight.jni.TdApi

/**
 * Converts [ParseMode] to TDLib formatted text objects.
 *
 * Used by the [TdLibClientService] to build [TdApi.FormattedText] from
 * user-supplied text and parse mode.
 */
object TextFormatter {

    /**
     * Converts [parseMode] into TDLib parse mode.
     *
     * For [ParseMode.PLAIN], returns `null` (no entity parsing).
     */
    fun toTdLibParseMode(parseMode: ParseMode): TdApi.TextParseMode? {
        return when (parseMode) {
            ParseMode.PLAIN -> null
            ParseMode.HTML -> TdApi.TextParseModeHTML()
            ParseMode.MARKDOWN -> TdApi.TextParseModeMarkdown(2)
        }
    }

    /**
     * Creates a plain [TdApi.FormattedText] without any entities.
     */
    fun plainText(text: String): TdApi.FormattedText {
        return TdApi.FormattedText(text, emptyArray())
    }
}
