package dev.telegrammcp.server.cli

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import dev.telegrammcp.server.service.DurableFiles
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import java.util.Base64
import java.util.concurrent.TimeUnit

/** Task Scheduler owns the foreground JVM independently of an agent's Job Objects. */
internal object WindowsDaemonTask {
    fun start(command: List<String>, directory: Path) {
        val root = directory.toRealPath()
        val task = "TelegramMcpDaemon-${UUID.randomUUID()}"
        val config = root.resolve("bootstrap-${UUID.randomUUID()}.json")
        val worker = root.resolve("windows-daemon-task.ps1")
        val registration = root.resolve("windows-daemon-register.ps1")
        for (script in listOf(worker, registration)) {
            val resource = requireNotNull(javaClass.getResourceAsStream("/daemon/${script.fileName}"))
            resource.use { DurableFiles.write(script, it.readAllBytes()) }
        }
        DurableFiles.write(config, jacksonObjectMapper().writeValueAsBytes(mapOf(
            "application" to command.first(), "arguments" to command.drop(1).joinToString(" ", transform = ::quote),
            "directory" to Path.of("").toAbsolutePath().toString(), "environment" to System.getenv(),
            "log" to root.resolve("task-bootstrap.log").toString(),
        )))
        val powershell = Path.of(System.getenv("SystemRoot") ?: "C:\\Windows", "System32", "WindowsPowerShell", "v1.0", "powershell.exe")
        val invocation = "& {\n${Files.readString(registration)}\n} -Worker ${literal(worker.toString())} -Config ${literal(config.toString())} -TaskName ${literal(task)}"
        val encoded = Base64.getEncoder().encodeToString(invocation.toByteArray(Charsets.UTF_16LE))
        try {
            val process = ProcessBuilder(powershell.toString(), "-NoProfile", "-NonInteractive", "-WindowStyle", "Hidden",
                "-EncodedCommand", encoded)
                .redirectOutput(root.resolve("task-launch.log").toFile()).redirectErrorStream(true).start()
            process.outputStream.close()
            if (!process.waitFor(30, TimeUnit.SECONDS)) {
                process.destroyForcibly()
                error("Windows task registration timed out; inspect ${root.resolve("task-launch.log")}")
            }
            check(process.exitValue() == 0) {
                "Windows user-task startup failed; inspect ${root.resolve("task-launch.log")}. " +
                    "No attached fallback process was started. Task Scheduler must allow on-demand tasks for this user."
            }
        } catch (failure: Exception) {
            Files.deleteIfExists(config)
            throw failure
        }
    }

    private fun literal(value: String) = "'${value.replace("'", "''")}'"

    /** Windows CRT quoting; the task never evaluates the command as shell code. */
    internal fun quote(argument: String): String {
        require('\u0000' !in argument) { "Process arguments cannot contain NUL" }
        return buildString {
            append('"')
            var slashes = 0
            for (c in argument) {
                when (c) {
                    '\\' -> slashes++
                    '"' -> { repeat(slashes * 2 + 1) { append('\\') }; append(c); slashes = 0 }
                    else -> { repeat(slashes) { append('\\') }; append(c); slashes = 0 }
                }
            }
            repeat(slashes * 2) { append('\\') }
            append('"')
        }
    }
}
