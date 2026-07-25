package dev.telegrammcp.server.runtime

import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Passes stdin through untouched and reports the one moment it ends.
 *
 * Exiting when the client closes stdin is part of the stdio contract, but the
 * server must not read stdin to notice: the MCP transport owns that stream and
 * a single stolen byte corrupts JSON-RPC framing. This wrapper therefore only
 * observes the result of the transport's own reads — `-1` or an [IOException]
 * means the client is gone — and never consumes anything itself.
 */
class EofSignalingInputStream(
    delegate: InputStream,
    private val onEndOfStream: () -> Unit,
) : FilterInputStream(delegate) {

    private val signalled = AtomicBoolean(false)

    override fun read(): Int = observing { super.read() }

    override fun read(b: ByteArray, off: Int, len: Int): Int = observing { super.read(b, off, len) }

    private fun observing(read: () -> Int): Int {
        val result = try {
            read()
        } catch (e: IOException) {
            signal()
            throw e
        }
        if (result == -1) signal()
        return result
    }

    private fun signal() {
        if (signalled.compareAndSet(false, true)) onEndOfStream()
    }
}

/**
 * Replaces `System.in` so [shutdown] runs once the MCP client closes stdin.
 *
 * Must be installed before the stdio transport captures the stream, and only
 * for that transport: an HTTP deployment keeps a stdin its lifecycle does not
 * depend on.
 */
fun installStdinCloseWatcher(shutdown: ServerShutdown) {
    System.setIn(
        EofSignalingInputStream(System.`in`) {
            shutdown.requestShutdown("stdin reached EOF — the MCP client closed the stdio transport")
        },
    )
}
