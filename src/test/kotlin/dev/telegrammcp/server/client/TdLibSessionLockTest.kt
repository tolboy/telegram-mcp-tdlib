package dev.telegrammcp.server.client

import it.tdlight.client.TelegramError
import it.tdlight.jni.TdApi
import org.junit.jupiter.api.Test
import java.nio.file.Path
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The locked-binlog error never reaches the authorization-state handler, so
 * recognising it by message is the only way to tell "another server instance is
 * still running" apart from an authentication that is merely slow.
 */
class TdLibSessionLockTest {

    private fun lockError() = TelegramError(
        TdApi.Error(
            400,
            "Can't lock file \"/data/tdlib-data/td.binlog\", because it is already in use; " +
                "check for another program instance running",
        ),
    )

    @Test
    fun `the binlog lock error is recognised`() {
        assertTrue(TdLibSessionLock.isSessionLocked(lockError()))
    }

    @Test
    fun `a wrapped binlog lock error is recognised`() {
        assertTrue(
            TdLibSessionLock.isSessionLocked(
                IllegalStateException("TDLib startup failed", lockError()),
            ),
        )
    }

    @Test
    fun `other TDLib errors are left alone`() {
        assertFalse(TdLibSessionLock.isSessionLocked(TelegramError(TdApi.Error(401, "Unauthorized"))))
        assertFalse(TdLibSessionLock.isSessionLocked(IllegalStateException("Can't lock file")))
        assertFalse(TdLibSessionLock.isSessionLocked(null))
    }

    @Test
    fun `a circular cause chain terminates`() {
        val outer = IllegalStateException("outer")
        val inner = IllegalStateException("inner", outer)
        outer.initCause(inner)

        assertFalse(TdLibSessionLock.isSessionLocked(outer))
    }

    @Test
    fun `the message names the session and the way out`() {
        val message = TdLibSessionLock.describe("default", Path.of("/data/tdlib-data"))

        assertTrue(message.contains("default"), message)
        assertTrue(message.contains("td.binlog"), message)
        assertTrue(message.contains("docker"), message)
    }
}
