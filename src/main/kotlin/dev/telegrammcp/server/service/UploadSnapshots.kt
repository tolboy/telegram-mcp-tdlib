package dev.telegrammcp.server.service

import dev.telegrammcp.server.exception.FileSecurityException
import dev.telegrammcp.server.exception.SendOutcomeUnknownException
import dev.telegrammcp.server.security.LocalAccessChecks
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption.CREATE_NEW
import java.nio.file.StandardOpenOption.READ
import java.nio.file.StandardOpenOption.WRITE
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Byte copy of an upload, taken from an already opened file.
 *
 * TDLib is then given the copy. Replacing, truncating, or hard-linking the
 * caller's path after the copy starts does not change the bytes being sent.
 * A definite result deletes the copy. An unknown send outcome keeps it until
 * the retention window, because the caller must not infer that the send can
 * be repeated from the copy's absence.
 */
internal class UploadSnapshots(
    private val root: Path,
    private val maxFileBytes: Long,
    private val maxTotalBytes: Long,
    private val retention: Duration = Duration.ofHours(24),
    private val now: () -> Instant = Instant::now,
) {
    private val lock = Any()
    private var prepared = false

    fun <T> use(source: Path, block: (Path) -> T): T {
        val staged = stage(source)
        return try {
            block(staged.path)
        } catch (error: Exception) {
            if (unknownOutcome(error)) staged.retain()
            throw error
        } finally {
            staged.close()
        }
    }

    fun <T> useAll(sources: List<Path>, block: (List<Path>) -> T): T {
        val staged = mutableListOf<Staged>()
        return try {
            sources.forEach { staged += stage(it) }
            block(staged.map { it.path })
        } catch (error: Exception) {
            if (unknownOutcome(error)) staged.forEach { it.retain() }
            throw error
        } finally {
            staged.forEach { it.close() }
        }
    }

    private fun stage(source: Path): Staged {
        synchronized(lock) {
            prepare()
            FileChannel.open(source, READ).use { input ->
                val size = input.size()
                if (size > maxFileBytes) {
                    throw FileSecurityException("File size exceeds the upload snapshot limit of $maxFileBytes bytes")
                }
                val used = Files.list(root).use { paths -> paths.filter { Files.isRegularFile(it) }.mapToLong { Files.size(it) }.sum() }
                if (used > maxTotalBytes - size) {
                    throw FileSecurityException("Upload snapshot storage is full")
                }
                val destination = root.resolve(UUID.randomUUID().toString())
                FileChannel.open(destination, CREATE_NEW, WRITE).use { output ->
                    var position = 0L
                    while (position < size) {
                        val copied = input.transferTo(position, size - position, output)
                        if (copied <= 0) break
                        position += copied
                    }
                    if (position != size) {
                        Files.deleteIfExists(destination)
                        throw FileSecurityException("The file changed while its upload snapshot was being taken")
                    }
                }
                return Staged(destination)
            }
        }
    }

    private fun prepare() {
        if (prepared) return
        LocalAccessChecks.prepareOwnerDirectory(root)
        val cutoff = now().minus(retention)
        Files.list(root).use { paths ->
            paths.filter { Files.isRegularFile(it) }.forEach { path ->
                val modified = Files.getLastModifiedTime(path).toInstant()
                if (modified.isBefore(cutoff)) Files.deleteIfExists(path)
            }
        }
        prepared = true
    }

    private fun unknownOutcome(error: Throwable): Boolean {
        var current: Throwable? = error
        while (current != null) {
            if (current is SendOutcomeUnknownException) return true
            current = current.cause
        }
        return false
    }

    private class Staged(val path: Path) : AutoCloseable {
        private val deleteOnClose = AtomicBoolean(true)

        fun retain() {
            deleteOnClose.set(false)
        }

        override fun close() {
            if (deleteOnClose.compareAndSet(true, false)) Files.deleteIfExists(path)
        }
    }
}
