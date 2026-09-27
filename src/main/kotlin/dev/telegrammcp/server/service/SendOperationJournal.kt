package dev.telegrammcp.server.service

import com.fasterxml.jackson.databind.ObjectMapper
import dev.telegrammcp.server.exception.InvalidToolInputException
import dev.telegrammcp.server.exception.SendJournalException
import dev.telegrammcp.server.exception.SendOperationBusyException
import dev.telegrammcp.server.exception.SendOperationConflictException
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

    /** beforeSend runs only for a new operation, before the durable reservation. */
    fun send(
        account: String,
        key: String,
        chatId: Long,
        fingerprint: String,
        beforeSend: () -> Unit,
        send: () -> Long,
    ): Map<String, Any?> = sendCompletion(account, key, chatId, fingerprint, beforeSend) { Completion(send()) }

    fun sendCompletion(account: String, key: String, chatId: Long, fingerprint: String,
        beforeSend: () -> Unit, send: () -> Completion): Map<String, Any?> = locked(account, key) { path, operationId ->
        if (Files.exists(path, NOFOLLOW_LINKS)) {
            val (reservation, receipt) = read(path)
            if (reservation.chatId != chatId || reservation.fingerprint != fingerprint) {
                throw SendOperationConflictException()
            }
            return@locked receipt.payload(replayed = true)
        }
        beforeSend()
        val reservation = Reservation(operationId = operationId, chatId = chatId, fingerprint = fingerprint)
        persist(path, mapper.writeValueAsBytes(reservation), create = true)
        // Any exception after reservation leaves UNKNOWN. Even a seemingly
        // definitive local failure can follow a successful Telegram send.
        val completion = send()
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
        val completion = lines.getOrNull(1)?.let { mapper.readValue(it, Completion::class.java) }
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
        fun validateKey(key: String) {
            if (!Regex("[A-Za-z0-9._:-]{1,128}").matches(key)) {
                throw InvalidToolInputException("idempotency_key must be 1-128 ASCII letters, digits, '.', '_', ':', or '-'")
            }
        }
        fun digest(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it) }
    }
}
