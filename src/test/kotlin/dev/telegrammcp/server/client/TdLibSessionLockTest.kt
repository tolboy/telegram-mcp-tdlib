package dev.telegrammcp.server.client

import it.tdlight.client.TelegramError
import it.tdlight.jni.TdApi
import org.junit.jupiter.api.Test
import java.nio.file.Path
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The locked-binlog error never reaches the authorization-state handler, so
 * tdlight preserves the TDLib error type and code, but TDLib uses its generic
 * 400 code for this failure. The detector therefore combines those stable
 * fields with the narrow binlog/ownership wording instead of trusting arbitrary
 * exception text.
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
    fun `the alternate TDLib ownership wording is recognised`() {
        val error = TelegramError(
            TdApi.Error(
                400,
                "Cannot lock file C:/data/td.binlog; check for another program instance running",
            ),
        )

        assertTrue(TdLibSessionLock.isSessionLocked(error))
    }

    @Test
    fun `other TDLib errors are left alone`() {
        assertFalse(TdLibSessionLock.isSessionLocked(TelegramError(TdApi.Error(401, "Unauthorized"))))
        assertFalse(TdLibSessionLock.isSessionLocked(IllegalStateException("Can't lock file")))
        assertFalse(TdLibSessionLock.isSessionLocked(null))
    }

    @Test
    fun `plain exception text cannot impersonate a TDLib session lock`() {
        assertFalse(
            TdLibSessionLock.isSessionLocked(
                IllegalStateException("Can't lock file /data/td.binlog because it is already in use"),
            ),
        )
    }

    @Test
    fun `the generic TDLib code and exact binlog target are both required`() {
        val rightMessage = "Can't lock file /data/td.binlog because it is already in use"

        assertFalse(TdLibSessionLock.isSessionLocked(TelegramError(TdApi.Error(500, rightMessage))))
        assertFalse(
            TdLibSessionLock.isSessionLocked(
                TelegramError(TdApi.Error(400, "Can't lock file /tmp/cache.bin because it is already in use")),
            ),
        )
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
