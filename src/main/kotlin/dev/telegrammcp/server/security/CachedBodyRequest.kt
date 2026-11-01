package dev.telegrammcp.server.security

import jakarta.servlet.ReadListener
import jakarta.servlet.ServletInputStream
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletRequestWrapper
import java.io.BufferedReader
import java.io.InputStreamReader

/** Replays a request body that a filter already read. */
internal class CachedBodyRequest(
    request: HttpServletRequest,
    val cachedBody: ByteArray,
) : HttpServletRequestWrapper(request) {
    override fun getInputStream(): ServletInputStream = ByteArrayServletInputStream(cachedBody)

    override fun getReader(): BufferedReader =
        BufferedReader(InputStreamReader(inputStream, Charsets.UTF_8))

    override fun getContentLength(): Int = cachedBody.size

    override fun getContentLengthLong(): Long = cachedBody.size.toLong()
}

/**
 * Puts back the bytes a filter peeked at, then the unread remainder.
 * [getInputStream] can be called once.
 */
internal class PrefixedBodyRequest(
    request: HttpServletRequest,
    prefix: ByteArray,
    remainder: ServletInputStream,
) : HttpServletRequestWrapper(request) {
    private val stream = PrefixedServletInputStream(prefix, remainder)
    private var opened = false

    override fun getInputStream(): ServletInputStream {
        check(!opened) { "Request body was already read" }
        opened = true
        return stream
    }

    override fun getReader(): BufferedReader =
        BufferedReader(InputStreamReader(inputStream, Charsets.UTF_8))
}

private class ByteArrayServletInputStream(private val body: ByteArray) : ServletInputStream() {
    private var index = 0

    override fun read(): Int = if (index >= body.size) -1 else body[index++].toInt() and 0xff

    override fun isFinished(): Boolean = index >= body.size

    override fun isReady(): Boolean = true

    override fun setReadListener(listener: ReadListener?) = Unit
}

private class PrefixedServletInputStream(
    prefix: ByteArray,
    private val remainder: ServletInputStream,
) : ServletInputStream() {
    private var index = 0
    private val prefix = prefix

    override fun read(): Int {
        if (index < prefix.size) return prefix[index++].toInt() and 0xff
        return remainder.read()
    }

    override fun isFinished(): Boolean = index >= prefix.size && remainder.isFinished

    override fun isReady(): Boolean = true

    override fun setReadListener(listener: ReadListener?) = Unit
}
