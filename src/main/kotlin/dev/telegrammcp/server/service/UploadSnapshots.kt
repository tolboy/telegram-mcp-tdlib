package dev.telegrammcp.server.service

import dev.telegrammcp.server.exception.FileSecurityException
import dev.telegrammcp.server.exception.InvalidToolInputException
import dev.telegrammcp.server.exception.SendOperationBusyException
import dev.telegrammcp.server.exception.SendOperationConflictException
import dev.telegrammcp.server.security.LocalAccessChecks
import org.slf4j.LoggerFactory
import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.nio.channels.OverlappingFileLockException
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.StandardOpenOption.CREATE
import java.nio.file.StandardOpenOption.CREATE_NEW
import java.nio.file.StandardOpenOption.READ
import java.nio.file.StandardOpenOption.WRITE
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.attribute.FileTime
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Gives TDLib a private byte copy rather than the caller's mutable path.
 * UUID directories isolate uploads while their original filenames are preserved.
 * An ambiguous callback failure retains the copy; it never authorizes a resend.
 */
internal class UploadSnapshots(
    root: Path,
    private val maxFileBytes: Long,
    private val maxTotalBytes: Long,
    private val retention: Duration = Duration.ofHours(24),
    private val now: () -> Instant = Instant::now,
    private val maxSnapshots: Int = 256,
) {
    private val root = root.toAbsolutePath().normalize()
    private val log = LoggerFactory.getLogger(UploadSnapshots::class.java)
    private val lock = locks.computeIfAbsent(this.root) { Any() }

    init {
        require(maxFileBytes >= 0 && maxTotalBytes >= 0 && maxSnapshots > 0 && !retention.isNegative)
    }

    fun <T> use(source: Path, validateSource: (Path) -> Unit = {}, block: (Path) -> T): T {
        val staged = stage(source, validateSource)
        return try {
            block(staged.path)
        } catch (error: Throwable) {
            if (!definitelyBeforeDispatch(error)) staged.retain()
            throw error
        } finally {
            staged.close()
        }
    }

    fun <T> useAll(sources: List<Path>, validateSource: (Path) -> Unit = {}, block: (List<Path>) -> T): T {
        val staged = mutableListOf<Staged>()
        var callbackStarted = false
        return try {
            sources.forEach { staged += stage(it, validateSource) }
            callbackStarted = true
            block(staged.map { it.path })
        } catch (error: Throwable) {
            // A failed copy has not dispatched an album. Callback failures may
            // follow partial delivery, a local timeout, or cancellation.
            if (callbackStarted && !definitelyBeforeDispatch(error)) staged.forEach { it.retain() }
            throw error
        } finally {
            staged.forEach { it.close() }
        }
    }

    private fun stage(source: Path, validateSource: (Path) -> Unit): Staged = locked {
        expireIdleSnapshots()
        validateSource(source)
        val before = Files.readAttributes(source, BasicFileAttributes::class.java, NOFOLLOW_LINKS)
        if (!before.isRegularFile) throw FileSecurityException("Upload source must be a regular file")
        FileChannel.open(source, READ, NOFOLLOW_LINKS).use { input ->
            validateSource(source)
            val size = input.size()
            if (size > maxFileBytes) throw FileSecurityException("File size exceeds the upload snapshot limit of $maxFileBytes bytes")
            val entries = snapshots()
            if (entries.size >= maxSnapshots || usedBytes(entries) > maxTotalBytes - size) {
                throw FileSecurityException("Upload snapshot storage is full")
            }
            val directory = root.resolve(UUID.randomUUID().toString())
            Files.createDirectory(directory)
            var leaseChannel: FileChannel? = null
            var lease: FileLock? = null
            val payloadDirectory = directory.resolve("content")
            val destination = payloadDirectory.resolve(source.fileName.toString())
            try {
                Files.createDirectory(payloadDirectory)
                leaseChannel = FileChannel.open(directory.resolve(LEASE_FILE), CREATE_NEW, WRITE, NOFOLLOW_LINKS)
                lease = leaseChannel.lock()
                FileChannel.open(destination, CREATE_NEW, WRITE, NOFOLLOW_LINKS).use { output ->
                    var position = 0L
                    while (position < size) {
                        val copied = input.transferTo(position, size - position, output)
                        if (copied <= 0) throw FileSecurityException("The file changed while its upload snapshot was being taken")
                        position += copied
                    }
                }
                validateSource(source)
                val after = Files.readAttributes(source, BasicFileAttributes::class.java, NOFOLLOW_LINKS)
                if (!after.isRegularFile || input.size() != size || after.size() != before.size() ||
                    after.lastModifiedTime() != before.lastModifiedTime() || after.fileKey() != before.fileKey()) {
                    throw FileSecurityException("The file changed while its upload snapshot was being taken")
                }
                Staged(destination, directory, leaseChannel, lease)
            } catch (error: Throwable) {
                runCatching { lease?.release() }.exceptionOrNull()?.let(error::addSuppressed)
                runCatching { leaseChannel?.close() }.exceptionOrNull()?.let(error::addSuppressed)
                // Close all channels first: Windows cannot delete an open copy.
                runCatching { deleteSnapshot(directory) }.exceptionOrNull()?.let(error::addSuppressed)
                throw error
            }
        }
    }

    /** Serializes accounting across instances and processes using this same cache. */
    private fun <T> locked(block: () -> T): T = synchronized(lock) {
        if (Files.isSymbolicLink(root)) throw FileSecurityException("Upload snapshot directory must not be a symbolic link")
        DurableFiles.privateDirectory(root)
        LocalAccessChecks.auditSessionAncestors(root)
        FileChannel.open(root.resolve(STORE_LOCK), CREATE, WRITE, NOFOLLOW_LINKS).use { channel ->
            val acquired = try { channel.tryLock() } catch (_: OverlappingFileLockException) { null }
                ?: throw FileSecurityException("Upload snapshot storage is busy")
            acquired.use { block() }
        }
    }

    /** Recognize the old flat layout too, without treating unrelated files as disposable snapshots. */
    private fun snapshots(): List<Path> = Files.list(root).use { entries ->
        entries.filter { it.fileName.toString().matches(SNAPSHOT_NAME) && !Files.isSymbolicLink(it) }.toList()
    }

    private fun usedBytes(entries: List<Path>): Long = entries.fold(0L) { total, entry ->
        val bytes = if (Files.isDirectory(entry, NOFOLLOW_LINKS)) Files.walk(entry, 2).use { files ->
            files.filter { it != entry.resolve(LEASE_FILE) && Files.isRegularFile(it, NOFOLLOW_LINKS) }
                .mapToLong { Files.size(it) }.sum()
        } else if (Files.isRegularFile(entry, NOFOLLOW_LINKS)) Files.size(entry) else 0L
        if (bytes > Long.MAX_VALUE - total) Long.MAX_VALUE else total + bytes
    }

    /** Every upload reclaims expired copies; a held lease excludes even very old active uploads. */
    private fun expireIdleSnapshots() {
        val cutoff = now().minus(retention)
        snapshots().filter { Files.getLastModifiedTime(it, NOFOLLOW_LINKS).toInstant().isBefore(cutoff) }.forEach { entry ->
            if (Files.isRegularFile(entry, NOFOLLOW_LINKS)) {
                Files.deleteIfExists(entry) // legacy copies were flat; all new active copies have leased directories
            } else if (Files.isDirectory(entry, NOFOLLOW_LINKS)) {
                var removable = false
                FileChannel.open(entry.resolve(LEASE_FILE), CREATE, WRITE, NOFOLLOW_LINKS).use { channel ->
                    val lease = try { channel.tryLock() } catch (_: OverlappingFileLockException) { null }
                    if (lease != null) lease.use { removable = true }
                }
                if (removable) deleteSnapshot(entry)
            }
        }
    }

    private fun deleteSnapshot(directory: Path) {
        check(directory.parent == root && directory.fileName.toString().matches(SNAPSHOT_NAME))
        if (Files.isDirectory(directory, NOFOLLOW_LINKS)) {
            // Only our generated UUID subtree, two levels deep; walk does not follow links.
            Files.walk(directory, 2).use { files ->
                files.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
            }
        } else {
            Files.deleteIfExists(directory)
        }
    }

    private fun definitelyBeforeDispatch(error: Throwable): Boolean =
        error is InvalidToolInputException || error is SendOperationConflictException || error is SendOperationBusyException ||
            SendOperationJournal.wasNotExecuted(error)

    private inner class Staged(val path: Path, private val directory: Path,
        private val channel: FileChannel, private val lease: FileLock) : AutoCloseable {
        private val closed = AtomicBoolean()
        private var retained = false

        fun retain() { retained = true }

        override fun close() {
            if (!closed.compareAndSet(false, true)) return
            try {
                locked {
                    try {
                        lease.release()
                    } finally {
                        channel.close()
                    }
                    if (retained) Files.setLastModifiedTime(directory, FileTime.from(now()))
                    else deleteSnapshot(directory)
                }
            } catch (error: Exception) {
                // Cleanup must not turn a confirmed send into an error, or hide
                // the original UNKNOWN/partial-delivery/cancellation outcome.
                runCatching { lease.release() }
                runCatching { channel.close() }
                log.warn("Upload snapshot cleanup deferred ({})", error.javaClass.simpleName)
            }
        }
    }

    private companion object {
        val locks = ConcurrentHashMap<Path, Any>()
        val SNAPSHOT_NAME = Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")
        const val STORE_LOCK = ".store.lock"
        const val LEASE_FILE = ".lease"
    }
}
