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

private class ByteArrayServletInputStream(private val body: ByteArray) : ServletInputStream() {
    private var index = 0

    override fun read(): Int = if (index >= body.size) -1 else body[index++].toInt() and 0xff

    override fun isFinished(): Boolean = index >= body.size

    override fun isReady(): Boolean = true

    override fun setReadListener(listener: ReadListener?) = Unit
}
