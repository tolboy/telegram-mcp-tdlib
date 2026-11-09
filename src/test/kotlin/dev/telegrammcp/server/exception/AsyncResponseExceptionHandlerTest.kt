package dev.telegrammcp.server.exception

import jakarta.servlet.http.HttpServletResponse
import org.junit.jupiter.api.Test
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.context.request.async.AsyncRequestNotUsableException
import org.springframework.web.context.request.async.AsyncRequestTimeoutException

class AsyncResponseExceptionHandlerTest {
    private val mvc = MockMvcBuilders.standaloneSetup(AsyncFailureController())
        .setControllerAdvice(GlobalExceptionHandler()).build()

    @Test
    fun `timeout after an SSE event preserves the committed stream`() {
        mvc.get("/test/committed-sse").andExpect {
            status { isOk() }
            // DispatcherServlet clears headers before invoking advice, even
            // when MockHttpServletResponse is committed. The invariant here
            // is preserving the event/status without appending a JSON error.
            content { string("data: ready\n\n") }
        }
    }

    @Test
    fun `timeout before SSE is committed ends with 503 and no incompatible JSON body`() {
        mvc.get("/test/uncommitted-sse").andExpect {
            status { isServiceUnavailable() }
            content { string("") }
        }
    }

    @Test
    fun `an unusable response is handled without another write`() {
        mvc.get("/test/disconnected").andExpect { content { string("") } }
    }

    @Test
    fun `ordinary unexpected REST failures keep the problem detail contract`() {
        mvc.get("/test/unexpected").andExpect {
            status { isInternalServerError() }
            content { contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON) }
            jsonPath("$.status") { value(500) }
            jsonPath("$.detail") { value("An unexpected error occurred") }
        }
    }

    @RestController
    class AsyncFailureController {
        @GetMapping("/test/committed-sse")
        fun committed(response: HttpServletResponse) {
            response.contentType = MediaType.TEXT_EVENT_STREAM_VALUE
            response.writer.write("data: ready\n\n")
            response.flushBuffer()
            throw AsyncRequestTimeoutException()
        }

        @GetMapping("/test/uncommitted-sse")
        fun uncommitted(response: HttpServletResponse) {
            response.contentType = MediaType.TEXT_EVENT_STREAM_VALUE
            throw AsyncRequestTimeoutException()
        }

        @GetMapping("/test/disconnected")
        fun disconnected(): Unit = throw AsyncRequestNotUsableException("Client connection closed")

        @GetMapping("/test/unexpected")
        fun unexpected(): Unit = error("unexpected error")
    }
}
