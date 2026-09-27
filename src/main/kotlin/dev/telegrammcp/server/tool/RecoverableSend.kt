package dev.telegrammcp.server.tool

import dev.telegrammcp.server.service.OperationGuardService
import dev.telegrammcp.server.service.SendOperationService
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest

/** Common preflight and replay boundary for media, forwarding and scheduled sends. */
object RecoverableSend {
    val toolNames = setOf("send_file", "send_voice", "send_sticker", "forward_message", "schedule_message")

    fun check(guard: OperationGuardService, tool: String, arguments: Map<String, Any>) {
        if (SendOperationService.key(arguments) == null) guard.checkPermission(tool, arguments)
        else guard.checkPolicy(tool, arguments)
    }

    fun execute(service: SendOperationService?, guard: OperationGuardService, tool: String,
        arguments: Map<String, Any>, chatId: Long, parameters: List<Any?>,
        expectedCount: Int = 1, scheduled: Boolean = false, send: () -> Any): Any {
        if (SendOperationService.key(arguments) == null) return send()
        return requireNotNull(service) { "Send operation storage unavailable" }.execute(
            arguments, chatId, listOf(tool, chatId) + parameters,
            { guard.checkPermission(tool, arguments) }, expectedCount, scheduled, send,
        )
    }

    /** Bind a keyed upload to file content as well as its validated path. */
    fun fileFingerprint(arguments: Map<String, Any>, path: Path): String? {
        if (SendOperationService.key(arguments) == null) return null
        val digest = MessageDigest.getInstance("SHA-256")
        Files.newInputStream(path).use { input ->
            val buffer = ByteArray(65536)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
