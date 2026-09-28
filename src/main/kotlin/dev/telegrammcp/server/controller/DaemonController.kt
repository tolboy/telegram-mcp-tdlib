package dev.telegrammcp.server.controller

import dev.telegrammcp.server.runtime.ServerShutdown
import dev.telegrammcp.server.security.SecurityConfig
import jakarta.servlet.http.HttpServletRequest
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.*
import org.springframework.web.server.ResponseStatusException

/** Managed mode only; API-key authentication plus direct loopback and instance binding. */
@RestController
@ConditionalOnProperty("daemon.instance")
class DaemonController(@Value("\${daemon.instance}") private val instance: String) {
    private fun validate(request: HttpServletRequest) {
        if (!SecurityConfig.isDirectLoopbackRequest(request) || request.getHeader("X-Daemon-Instance") != instance)
            throw ResponseStatusException(HttpStatus.FORBIDDEN)
    }

    @GetMapping("/mcp/daemon/status")
    fun status(request: HttpServletRequest): Map<String, String> {
        validate(request)
        return mapOf("instance" to instance)
    }

    @PostMapping("/mcp/daemon/stop")
    fun stop(request: HttpServletRequest): Map<String, String> {
        validate(request)
        ServerShutdown.INSTANCE.requestShutdown("managed daemon stop")
        return mapOf("status" to "STOPPING")
    }
}
