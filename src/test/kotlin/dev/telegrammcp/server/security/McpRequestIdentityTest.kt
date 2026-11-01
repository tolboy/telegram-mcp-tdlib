package dev.telegrammcp.server.security

import io.modelcontextprotocol.common.McpTransportContext
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.springframework.security.core.context.SecurityContextHolder
import java.util.concurrent.Executors
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class McpRequestIdentityTest {
    @AfterEach fun clear() = SecurityContextHolder.clearContext()

    @Test fun `captured server authority follows SDK workers and restores the worker context after errors`() {
        SecurityContextHolder.getContext().authentication = ApiKeyAuthToken("reader", setOf("work"))
        val captured = McpRequestIdentity.capture()
        SecurityContextHolder.clearContext()
        Executors.newSingleThreadExecutor().use { executor ->
            executor.submit {
                SecurityContextHolder.getContext().authentication = ApiKeyAuthToken("previous")
                assertFailsWith<IllegalStateException> {
                    McpRequestIdentity.withIdentity(captured) {
                        assertEquals("reader", AccessPermissionService.clientIdentity())
                        assertEquals(setOf("work"), (SecurityContextHolder.getContext().authentication as ApiKeyAuthToken).allowedAccounts)
                        error("handler failed")
                    }
                }
                assertEquals("previous", AccessPermissionService.clientIdentity())
                SecurityContextHolder.clearContext()
                McpRequestIdentity.withIdentity(McpTransportContext.EMPTY) {
                    assertEquals("stdio", AccessPermissionService.clientIdentity())
                }
            }.get()
        }
        assertEquals("stdio", AccessPermissionService.clientIdentity())
    }
}
