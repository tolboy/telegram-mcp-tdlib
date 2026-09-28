package dev.telegrammcp.server.cli

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import dev.telegrammcp.server.service.DurableFiles
import dev.telegrammcp.server.service.PlatformPaths
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption.*
import java.security.SecureRandom
import java.time.Duration
import java.util.Base64
import java.util.UUID
import java.util.concurrent.TimeUnit

internal data class DaemonRecord(val pid: Long, val started: String, val port: Int, val instance: String)

/** One managed JVM per data directory. No shell, system service installation, or session copying. */
internal object DaemonCli {
    private val mapper = jacksonObjectMapper()
    private val root get() = PlatformPaths().applicationDataDirectory.resolve("daemon")
    private val http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build()
    // The child owns this OS lock until process exit, including Spring startup and shutdown.
    private var lease: FileChannel? = null

    fun run(args: List<String>) {
        val action = args.firstOrNull() ?: "status"
        if (action == "run") { child(args.drop(1)); return }
        DurableFiles.privateDirectory(root)
        FileChannel.open(root.resolve("control.lock"), CREATE, WRITE).use { channel ->
            channel.lock().use {
                when (action) {
                    "start" -> {
                        require(args.size == 1 || (args.size == 3 && args[1] == "--port")) { "daemon start [--port 8080]" }
                        val port = args.getOrNull(2)?.toIntOrNull() ?: if (args.size == 1) 8080 else error("Invalid port")
                        require(port in 1024..65535) { "Port must be in 1024..65535" }
                        start(port)
                    }
                    "status" -> { require(args.size <= 1); println(status()) }
                    "stop" -> { require(args.size == 1); stop() }
                    "attach" -> {
                        require(args.size == 3 && args[1] == "--client") { "daemon attach --client claude-code|cursor|vscode|codex" }
                        val record = requireNotNull(record()?.takeIf(::alive)) { "Daemon is not running; run daemon start" }
                        require(ready(record)) { "Daemon is not ready" }
                        val options = TelegramMcpCli.resolveConfigOptions(listOf("--client", args[2], "--http", "http://127.0.0.1:${record.port}/mcp"))
                        println(ClientConfigPrinter.render(options).replace("<your-MCP_API_KEY>", key()))
                    }
                    else -> error("daemon supports start, status, stop and attach")
                }
            }
        }
    }

    private fun start(port: Int) {
        record()?.takeIf(::alive)?.let { println(status()); return }
        FileChannel.open(root.resolve("instance.lock"), CREATE, WRITE).use { channel ->
            val lock = channel.tryLock() ?: error("Daemon is starting or stopping; check status again")
            lock.release()
        }
        if (!Files.exists(root.resolve("api-key"))) {
            val bytes = ByteArray(32).also(SecureRandom()::nextBytes)
            DurableFiles.write(root.resolve("api-key"), Base64.getUrlEncoder().withoutPadding().encode(bytes))
        }
        val javaExecutable = Path.of(System.getProperty("java.home"), "bin", if (isWindows()) "javaw.exe" else "java").toString()
        val classpath = System.getProperty("java.class.path")
        require(classpath.isNotBlank()) { "Cannot determine runtime classpath" }
        val command = mutableListOf(javaExecutable, "-cp", classpath)
        // The runtime bundle and java -jar both expose the executable Boot JAR as classpath.
        if (classpath.endsWith(".jar") && !classpath.contains(java.io.File.pathSeparator)) {
            command += "org.springframework.boot.loader.launch.JarLauncher"
        } else command += "dev.telegrammcp.server.TelegramMcpApplicationKt"
        command += listOf("daemon", "run", port.toString())
        val process = ProcessBuilder(command).redirectInput(ProcessBuilder.Redirect.PIPE)
            .redirectOutput(ProcessBuilder.Redirect.appendTo(root.resolve("daemon.log").toFile()))
            .redirectErrorStream(true).start()
        process.outputStream.close()
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(45)
        while (System.nanoTime() < deadline && process.isAlive) {
            val record = record()
            if (record != null && record.pid == process.pid() && ready(record)) { println(status()); return }
            Thread.sleep(250)
        }
        check(process.isAlive) { "Daemon startup failed; see ${root.resolve("daemon.log")}" }
        println("STARTING pid=${process.pid()}; use daemon status. Log: ${root.resolve("daemon.log")}")
    }

    private fun child(args: List<String>) {
        require(args.size == 1)
        val port = args.single().toInt()
        require(port in 1024..65535)
        DurableFiles.privateDirectory(root)
        val channel = FileChannel.open(root.resolve("instance.lock"), CREATE, WRITE)
        check(channel.tryLock() != null) { "Another managed daemon owns this data directory" }
        lease = channel
        val process = ProcessHandle.current()
        val record = DaemonRecord(process.pid(), process.info().startInstant().orElseThrow().toString(), port, UUID.randomUUID().toString())
        DurableFiles.write(root.resolve("state.json"), mapper.writeValueAsBytes(record))
        TelegramMcpCli.run(arrayOf("serve", "--transport", "streamable-http",
            "--server.address=127.0.0.1", "--server.port=$port", "--spring.ai.mcp.server.stdio=false",
            "--spring.profiles.active=daemon",
            "--mcp.security.mode=api-key", "--mcp.security.api-key=", "--mcp.security.api-key-file=${root.resolve("api-key")}",
            "--daemon.instance=${record.instance}"))
    }

    private fun record(): DaemonRecord? = root.resolve("state.json").let {
        if (Files.exists(it)) mapper.readValue(Files.readAllBytes(it), DaemonRecord::class.java) else null
    }

    internal fun alive(record: DaemonRecord): Boolean = ProcessHandle.of(record.pid).orElse(null)?.let {
        it.isAlive && it.info().startInstant().orElse(null)?.toString() == record.started
    } ?: false

    private fun key() = Files.readString(root.resolve("api-key")).trim()
    private fun request(record: DaemonRecord, stop: Boolean = false): HttpRequest = HttpRequest.newBuilder()
        .uri(URI("http://127.0.0.1:${record.port}/mcp/daemon/${if (stop) "stop" else "status"}"))
        .timeout(Duration.ofSeconds(2)).header("Authorization", "Bearer ${key()}")
        .header("X-Daemon-Instance", record.instance).let { if (stop) it.POST(HttpRequest.BodyPublishers.noBody()) else it.GET() }.build()

    private fun ready(record: DaemonRecord): Boolean = runCatching {
        val response = http.send(request(record), HttpResponse.BodyHandlers.ofString())
        response.statusCode() == 200 && mapper.readTree(response.body()).path("instance").asText() == record.instance
    }.getOrDefault(false)

    private fun status(): String {
        val record = record() ?: return "STOPPED"
        if (!alive(record)) return "STOPPED (stale process record)"
        return "${if (ready(record)) "READY" else "STARTING_OR_UNHEALTHY"} pid=${record.pid} url=http://127.0.0.1:${record.port}/mcp"
    }

    private fun stop() {
        val record = record()?.takeIf(::alive) ?: run { println("STOPPED"); return }
        check(ready(record)) { "Daemon is not ready; refusing to signal an unverified process. Inspect ${root.resolve("daemon.log")}" }
        // A graceful shutdown can close the connection before the response is flushed.
        runCatching { http.send(request(record, true), HttpResponse.BodyHandlers.discarding()) }
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15)
        while (alive(record) && System.nanoTime() < deadline) Thread.sleep(100)
        check(!alive(record)) { "Daemon did not stop; inspect daemon.log" }
        println("STOPPED")
    }

    private fun isWindows() = System.getProperty("os.name").startsWith("Windows", true)

}
