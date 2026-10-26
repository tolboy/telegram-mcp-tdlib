package dev.telegrammcp.server.service

import com.fasterxml.jackson.databind.ObjectMapper
import dev.telegrammcp.server.exception.InvalidToolInputException
import dev.telegrammcp.server.exception.SendJournalException
import dev.telegrammcp.server.exception.SendOperationBusyException
import dev.telegrammcp.server.exception.SendOperationConflictException
import dev.telegrammcp.server.exception.TelegramApiException
import dev.telegrammcp.server.exception.TelegramFloodGateException
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.channels.OverlappingFileLockException
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.StandardOpenOption.*
import java.nio.file.attribute.PosixFilePermissions
import java.security.MessageDigest

/** Append-only reservation and receipt. An incomplete record is never replayed. */
class SendOperationJournal(private val root: Path, private val mapper: ObjectMapper) {
    data class Reservation(
        val version: Int = 1,
        val operationId: String,
        val chatId: Long,
        val fingerprint: String,
    )

    /** Second journal line for a send Telegram definitely did not execute. */
    data class Rejection(val rejected: Boolean = true)

    data class Completion(val messageId: Long, val messageIds: List<Long>? = null, val scheduled: Boolean = false)

    data class Receipt(val operationId: String, val chatId: Long, val status: String, val messageId: Long? = null,
        val messageIds: List<Long>? = null) {
        fun payload(replayed: Boolean? = null): Map<String, Any?> = linkedMapOf<String, Any?>(
            "operation_id" to operationId, "chat_id" to chatId,
            "status" to status, "message_id" to messageId,
        ).also {
            if (replayed != null) it["replayed"] = replayed
            if (messageIds != null) it["message_ids"] = messageIds
        }
    }

    /**
     * Single-message send. beforeSend runs only for a new operation, before
     * the durable reservation. A flood-wait rejection, or a retryable local rejection
     * before the request reached TDLib (bulkhead full, deadline ran out), is recorded
     * as REJECTED (see [wasNotExecuted]).
     */
    fun send(
        account: String,
        key: String,
        chatId: Long,
        fingerprint: String,
        beforeSend: () -> Unit,
        send: () -> Long,
    ): Map<String, Any?> = sendCompletion(account, key, chatId, fingerprint, beforeSend, rejectable = true) { Completion(send()) }

    /**
     * [rejectable] allows recording REJECTED when Telegram definitely did not
     * execute the send (a flood wait, or a local rejection before TDLib). Only single-message sends qualify: a
     * multi-message send may fail after earlier messages were delivered.
     */
    fun sendCompletion(account: String, key: String, chatId: Long, fingerprint: String,
        beforeSend: () -> Unit, rejectable: Boolean = false, send: () -> Completion): Map<String, Any?> = locked(account, key) { path, operationId ->
        if (Files.exists(path, NOFOLLOW_LINKS)) {
            val (reservation, receipt) = read(path)
            if (reservation.chatId != chatId || reservation.fingerprint != fingerprint) {
                throw SendOperationConflictException()
            }
            if (receipt.status != REJECTED) return@locked receipt.payload(replayed = true)
            // Nothing was sent last time: an unchanged retry starts a fresh attempt.
            io { Files.delete(path) }
        }
        beforeSend()
        val reservation = Reservation(operationId = operationId, chatId = chatId, fingerprint = fingerprint)
        persist(path, mapper.writeValueAsBytes(reservation), create = true)
        // Any other exception after reservation leaves UNKNOWN. Even a seemingly
        // definitive local failure can follow a successful Telegram send.
        val completion = try {
            send()
        } catch (error: Exception) {
            if (rejectable && wasNotExecuted(error)) {
                // If this write fails the record stays UNKNOWN, which is still safe.
                runCatching { persist(path, mapper.writeValueAsBytes(Rejection()), create = false) }
            }
            throw error
        }
        validateCompletion(completion)
        persist(path, mapper.writeValueAsBytes(completion), create = false)
        receipt(reservation, completion).payload(replayed = false)
    }

    fun status(account: String, key: String, chatId: Long): Map<String, Any?> = locked(account, key) { path, operationId ->
        if (!Files.exists(path, NOFOLLOW_LINKS)) {
            return@locked Receipt(operationId, chatId, "NOT_FOUND").payload()
        }
        val (reservation, receipt) = read(path)
        // Never return another chat's receipt even if its key is known.
        if (reservation.chatId != chatId) throw SendOperationConflictException()
        receipt.payload()
    }

    /** A separate lock lets TDLib persist a late receipt while the original caller still waits. */
    fun recordLate(account: String, key: String, chatId: Long, messageId: Long) {
        recordLateCompletion(account, key, chatId, Completion(messageId))
    }

    fun recordLateCompletion(account: String, key: String, chatId: Long, completion: Completion) {
        SendOperationJournal(root.resolve("late"), mapper).sendCompletion(account, key, chatId, "late-delivery", {}) { completion }
    }

    fun recoverLate(account: String, key: String, chatId: Long, receipt: Map<String, Any?>): Map<String, Any?> {
        if (receipt["status"] != "UNKNOWN") return receipt
        val late = SendOperationJournal(root.resolve("late"), mapper).status(account, key, chatId)
        return if (late["status"] in setOf("SENT", "SCHEDULED")) receipt + late.filterKeys { it in setOf("status", "message_id", "message_ids") }
        else receipt
    }

    private fun read(path: Path): Pair<Reservation, Receipt> = io {
        require(!Files.isSymbolicLink(path) && Files.size(path) <= 8192)
        val lines = Files.readAllLines(path)
        require(lines.size in 1..2)
        val reservation = mapper.readValue(lines[0], Reservation::class.java)
        require(reservation.version == 1)
        val second = lines.getOrNull(1)?.let { mapper.readTree(it) }
        if (second != null && second.path("rejected").asBoolean(false)) {
            return@io reservation to Receipt(reservation.operationId, reservation.chatId, REJECTED)
        }
        val completion = second?.let { mapper.treeToValue(it, Completion::class.java) }
        if (completion != null) validateCompletion(completion)
        reservation to receipt(reservation, completion)
    }

    private fun validateCompletion(completion: Completion) {
        if (completion.messageId <= 0 || completion.messageIds?.let {
                it.isEmpty() || it.any { id -> id <= 0 } || it.first() != completion.messageId
            } == true) throw SendJournalException()
    }

    private fun receipt(reservation: Reservation, completion: Completion?) = Receipt(
        reservation.operationId, reservation.chatId,
        when { completion == null -> "UNKNOWN"; completion.scheduled -> "SCHEDULED"; else -> "SENT" },
        completion?.messageId, completion?.messageIds,
    )

    private fun persist(path: Path, json: ByteArray, create: Boolean) = io {
        val options = if (create) setOf(CREATE_NEW, WRITE, NOFOLLOW_LINKS) else setOf(WRITE, APPEND, NOFOLLOW_LINKS)
        FileChannel.open(path, options, *filePermissions()).use { channel ->
            val bytes = ByteBuffer.wrap(json + byteArrayOf(10))
            while (bytes.hasRemaining()) channel.write(bytes)
            channel.force(true)
        }
    }

    private fun <T> locked(account: String, key: String, action: (Path, String) -> T): T {
        validateKey(key)
        val operationId = digest("$account\u0000$key".toByteArray(Charsets.UTF_8))
        val directory = io {
            Files.createDirectories(root)
            require(!Files.isSymbolicLink(root))
            val dir = root.resolve(digest(account.toByteArray(Charsets.UTF_8)))
            if (!Files.exists(dir, NOFOLLOW_LINKS)) {
                try {
                    Files.createDirectory(dir, *directoryPermissions())
                } catch (_: java.nio.file.FileAlreadyExistsException) { /* Another key created the account directory. */ }
            }
            require(Files.isDirectory(dir, NOFOLLOW_LINKS))
            dir
        }
        val channel = io { FileChannel.open(directory.resolve("$operationId.lock"), setOf(CREATE, WRITE, NOFOLLOW_LINKS), *filePermissions()) }
        channel.use {
            val lock = try { channel.tryLock() } catch (_: OverlappingFileLockException) { null }
                ?: throw SendOperationBusyException()
            lock.use { return action(directory.resolve("$operationId.jsonl"), operationId) }
        }
    }

    private fun filePermissions() = if (root.fileSystem.supportedFileAttributeViews().contains("posix"))
        arrayOf(PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------"))) else emptyArray()

    private fun directoryPermissions() = if (root.fileSystem.supportedFileAttributeViews().contains("posix"))
        arrayOf(PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------"))) else emptyArray()

    private fun <T> io(action: () -> T): T = try { action() } catch (error: Exception) { throw SendJournalException(error) }

    companion object {
        const val REJECTED = "REJECTED"

        /**
         * Telegram (or a local check) definitely rejected the send before
         * executing it. A local timeout or a full bulkhead qualifies only when
         * it is retryable, i.e. no write of the call was handed to TDLib; after
         * an earlier write the outcome stays UNKNOWN.
         */
        fun wasNotExecuted(error: Throwable): Boolean =
            (error is TelegramFloodGateException && error.isRetryable) ||
                (error is TelegramApiException && error.wasNotExecuted && error.isRetryable) ||
                (error is TelegramApiException && error.preWriteValidationFailed && !error.priorWriteDispatched) ||
                (error is dev.telegrammcp.server.exception.TelegramTimeoutException && !error.requestSent && error.retryable) ||
                (error is dev.telegrammcp.server.exception.TelegramBusyException && error.retryable)

        fun validateKey(key: String) {
            if (!Regex("[A-Za-z0-9._:-]{1,128}").matches(key)) {
                throw InvalidToolInputException("idempotency_key must be 1-128 ASCII letters, digits, '.', '_', ':', or '-'")
            }
        }
        fun digest(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it) }
    }
}
