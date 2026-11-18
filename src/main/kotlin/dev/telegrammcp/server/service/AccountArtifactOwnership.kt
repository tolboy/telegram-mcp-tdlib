package dev.telegrammcp.server.service

import dev.telegrammcp.server.client.TelegramAccountRegistry
import dev.telegrammcp.server.exception.TdLibAuthException
import dev.telegrammcp.server.security.LocalAccessChecks
import org.springframework.stereotype.Service
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes
import java.util.concurrent.TimeUnit

/**
 * Pins the owner of the existing label-keyed change journal, exports, and send
 * receipts without changing old cursor, export, or idempotency keys.
 */
@Service
class AccountArtifactOwnership(private val paths: PlatformPaths) {
    private val directory: Path get() = paths.applicationDataDirectory.resolve("account-artifact-owners")

    /** Call with the actual user ID returned by the accepted TDLib GetMe. */
    internal fun bind(account: String, userId: Long, testDc: Boolean, legacyOwnerUserId: Long?) {
        val label = TelegramAccountRegistry.normalizeLabel(account)
        if (userId <= 0) throw TdLibAuthException("Telegram account '$label' returned an invalid user ID")
        if (legacyOwnerUserId != null && legacyOwnerUserId != userId) {
            throw TdLibAuthException("The declared legacy artifact owner for '$label' differs from the verified Telegram user ID")
        }

        val mode = if (testDc) "test" else "production"
        val expected = "v1 $mode $userId\n"
        storageAccess(label) {
            val root = directory
            Files.createDirectories(root)
            require(attributes(root)?.isDirectory == true && root.toRealPath() == root.parent.toRealPath().resolve(root.fileName))
            DurableFiles.privateDirectory(root)
            LocalAccessChecks.auditSessionAncestors(root)
            LocalAccessChecks.enforceWindowsSessionDirectory(root)
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
            val bound = DurableFiles.tryLockedPrepared(root, { deadline - System.nanoTime() }) {
                val file = ownerFile(label)
                val existing = attributes(file)
                if (existing != null) {
                    if (!existing.isRegularFile || existing.size() > 128 || Files.readString(file) != expected) {
                        throw TdLibAuthException(
                            "Local MCP artifacts for '$label' belong to a different identity or have an invalid ownership record. " +
                                "Use the original account and network, or a separate TELEGRAM_MCP_DATA_DIR.",
                        )
                    }
                } else {
                    if (legacyOwnerUserId == null && hasLegacyArtifacts(label)) {
                        throw TdLibAuthException(
                            "Local MCP artifacts for '$label' have no verified owner. " +
                                "Confirm their owner with auth.legacy-artifact-owner-user-id, or use a separate TELEGRAM_MCP_DATA_DIR.",
                        )
                    }
                    DurableFiles.write(file, expected.toByteArray(Charsets.UTF_8))
                }
                true
            }
            if (bound == null) throw TdLibAuthException("Local MCP artifact ownership for '$label' is busy; restart to retry")
        }
    }

    internal fun requireUnbound(account: String) {
        val label = TelegramAccountRegistry.normalizeLabel(account)
        storageAccess(label) {
            if (attributes(ownerFile(label)) != null) {
                throw TdLibAuthException(
                    "Local MCP artifacts for '$label' require the expected-account check. Enable it for the bound Telegram identity.",
                )
            }
        }
    }

    private fun ownerFile(label: String): Path = directory.resolve("$label.owner")

    private fun hasLegacyArtifacts(label: String): Boolean {
        val root = paths.applicationDataDirectory
        val receiptScope = SendOperationJournal.digest(label.toByteArray(Charsets.UTF_8))
        if (hasJournalEntries(root.resolve("changes").resolve("$label.jsonl")) ||
            attributes(root.resolve("send-operations").resolve(receiptScope)) != null ||
            attributes(root.resolve("send-operations").resolve("late").resolve(receiptScope)) != null) return true

        // Legacy export scopes are opaque hashes. A saved export may be for
        // any label, so its provenance needs an operator decision.
        val exports = root.resolve("exports")
        val exportDirectory = attributes(exports) ?: return false
        require(exportDirectory.isDirectory)
        return Files.find(exports, 2, { path, info ->
            info.isSymbolicLink || (info.isRegularFile && path.fileName.toString().endsWith(".json"))
        }).use { it.findAny().isPresent }
    }

    private fun hasJournalEntries(path: Path): Boolean {
        val info = attributes(path) ?: return false
        require(info.isRegularFile)
        // A read may have initialized only the header, without recording an event.
        return Files.newBufferedReader(path).use { journal ->
            journal.readLine() != null && journal.read() != -1
        }
    }

    private fun attributes(path: Path): BasicFileAttributes? = try {
        Files.readAttributes(path, BasicFileAttributes::class.java, NOFOLLOW_LINKS)
    } catch (_: NoSuchFileException) {
        null
    }

    private fun storageAccess(label: String, action: () -> Unit) {
        try {
            action()
        } catch (error: TdLibAuthException) {
            throw error
        } catch (error: Exception) {
            throw TdLibAuthException("Local MCP artifact ownership for '$label' could not be checked (${error.javaClass.simpleName})")
        }
    }
}
