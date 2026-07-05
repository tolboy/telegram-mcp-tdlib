package dev.telegrammcp.server.model

/**
 * Metadata for a media file attached to a Telegram message.
 */
data class MediaInfo(
    val messageId: Long,
    val chatId: Long,
    val mediaType: String,
    val fileName: String? = null,
    val mimeType: String? = null,
    val fileSize: Long? = null,
    val width: Int? = null,
    val height: Int? = null,
    val duration: Int? = null,
    val caption: String? = null,
)

/**
 * Result of a file download operation.
 */
data class DownloadResult(
    val localPath: String,
    val fileName: String,
    val mimeType: String?,
    val fileSize: Long,
)

/**
 * Telegram's native speech-recognition state for a voice note.
 *
 * Recognition is performed by Telegram itself. It can require Premium or use
 * Telegram's limited trial allowance; no audio is sent to a third-party STT
 * provider and this server never posts a separate transcript message.
 */
data class VoiceTranscription(
    val chatId: Long,
    val messageId: Long,
    val status: VoiceTranscriptionStatus,
    val text: String? = null,
    val partialText: String? = null,
)

enum class VoiceTranscriptionStatus {
    COMPLETED,
    PENDING,
}
