package dev.telegrammcp.server.service

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
        return FileChannel.open(directory.resolve("store.lock"), CREATE, WRITE).use { channel ->
            channel.lock().use { action() }
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
