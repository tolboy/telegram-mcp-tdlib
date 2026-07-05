package dev.telegrammcp.server.controller

import dev.telegrammcp.server.model.McpServerDescriptor
import dev.telegrammcp.server.service.McpServerDescriptorService
import org.springframework.http.MediaType
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.servlet.support.ServletUriComponentsBuilder

@RestController
class WellKnownMcpDescriptorController(
    private val descriptorService: McpServerDescriptorService,
) {

    @GetMapping(path = ["/.well-known/mcp-server.json"], produces = [MediaType.APPLICATION_JSON_VALUE])
    fun descriptor(): McpServerDescriptor {
        val baseUrl = ServletUriComponentsBuilder.fromCurrentContextPath()
            .build()
            .toUriString()
            .trimEnd('/')
        return descriptorService.build(baseUrl)
    }
}