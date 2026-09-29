package dev.telegrammcp.server.service

import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption.*
import java.nio.file.StandardOpenOption.*
import java.nio.file.attribute.*

/** Never publish a cursor before its data. Fail closed if atomic replacement is unavailable. */
internal object DurableFiles {
    fun privateDirectory(path: Path) {
        Files.createDirectories(path)
        val posix = Files.getFileAttributeView(path, PosixFileAttributeView::class.java)
        if (posix != null) posix.setPermissions(PosixFilePermissions.fromString("rwx------"))
        else {
            val acl = requireNotNull(Files.getFileAttributeView(path, AclFileAttributeView::class.java)) { "Private state requires filesystem ACL support" }
            acl.acl = listOf(AclEntry.newBuilder().setType(AclEntryType.ALLOW).setPrincipal(acl.owner)
                .setPermissions(*AclEntryPermission.entries.toTypedArray())
                .setFlags(AclEntryFlag.DIRECTORY_INHERIT, AclEntryFlag.FILE_INHERIT).build())
        }
    }

    fun <T> locked(directory: Path, action: () -> T): T {
        privateDirectory(directory)
        return lockedPrepared(directory, action)
    }

    /** Cross-process lock for a directory already secured by [privateDirectory]. */
    fun <T> lockedPrepared(directory: Path, action: () -> T): T =
        FileChannel.open(directory.resolve("store.lock"), CREATE, WRITE).use { channel ->
            channel.lock().use { action() }
        }

    /** Append and flush. A failed write is cut back so no torn record precedes later appends. */
    fun append(path: Path, bytes: ByteArray) {
        FileChannel.open(path, WRITE, APPEND).use { channel ->
            val start = channel.size()
            try {
                val buffer = ByteBuffer.wrap(bytes)
                while (buffer.hasRemaining()) channel.write(buffer)
                channel.force(true)
            } catch (failure: IOException) {
                runCatching { channel.truncate(start) }.exceptionOrNull()?.let(failure::addSuppressed)
                throw failure
            }
        }
    }

    fun write(path: Path, bytes: ByteArray) {
        Files.createDirectories(path.parent)
        val temporary = Files.createTempFile(path.parent, ".pending-", ".tmp")
        try {
            FileChannel.open(temporary, WRITE).use { channel ->
                val buffer = ByteBuffer.wrap(bytes)
                while (buffer.hasRemaining()) channel.write(buffer)
                channel.force(true)
            }
            Files.move(temporary, path, ATOMIC_MOVE, REPLACE_EXISTING)
        } finally { Files.deleteIfExists(temporary) }
    }
}
