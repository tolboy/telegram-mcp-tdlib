package dev.telegrammcp.server.controller

import org.junit.jupiter.api.Test
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.web.server.ResponseStatusException
import kotlin.test.*

class DaemonControllerTest {
    @Test fun `status requires direct loopback and matching instance`() {
        val controller = DaemonController("instance")
        val request = MockHttpServletRequest().apply { remoteAddr = "127.0.0.1"; addHeader("X-Daemon-Instance", "instance") }
        assertEquals("instance", controller.status(request)["instance"])
        request.addHeader("X-Forwarded-For", "127.0.0.1")
        assertFailsWith<ResponseStatusException> { controller.status(request) }
        request.removeHeader("X-Forwarded-For")
        request.removeHeader("X-Daemon-Instance")
        assertFailsWith<ResponseStatusException> { controller.status(request) }
        request.addHeader("X-Daemon-Instance", "instance")
        request.remoteAddr = "192.0.2.1"
        assertFailsWith<ResponseStatusException> { controller.status(request) }
    }
}
