package dev.telegrammcp.server.cli

import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import dev.telegrammcp.server.TelegramMcpApplication
import dev.telegrammcp.server.auth.AuthState
import dev.telegrammcp.server.auth.TelegramAuthStateHolder
import dev.telegrammcp.server.auth.TelegramAuthOrchestrator
import dev.telegrammcp.server.client.TelegramAccountRegistry
import org.springframework.boot.WebApplicationType
import org.springframework.boot.builder.SpringApplicationBuilder
import org.springframework.context.ConfigurableApplicationContext
import java.awt.Desktop
import java.net.ServerSocket
import java.net.URI
import java.security.SecureRandom
import java.util.Base64
import java.util.Properties

/** Stable command line shared by the JAR and runtime-inclusive launchers. */
object TelegramMcpCli {

    fun run(args: Array<String>): Boolean {
        val command = args.firstOrNull()
        return when (command) {
            null -> {
                if (System.getenv("MCP_TRANSPORT").isNullOrBlank()) {
                    false
                } else {
                    runServer(emptyList())
                    true
                }
            }
            "serve" -> {
                runServer(args.drop(1))
                true
            }
            "auth" -> {
                runAuthWizard(args.drop(1))
                true
            }
            "version", "--version", "-V" -> {
                println(version())
                true
            }
            "help", "--help", "-h" -> {
                printUsage()
                true
            }
            else -> false
        }
    }

    private fun runServer(arguments: List<String>) {
        val invocation = resolveServerInvocation(arguments, System.getenv("MCP_TRANSPORT"))
        val builder = SpringApplicationBuilder(TelegramMcpApplication::class.java)
        if (invocation.transport == Transport.STDIO) {
            builder
                .web(WebApplicationType.NONE)
                .profiles("stdio")
                .properties(
                    "spring.ai.mcp.server.stdio=true",
                    "spring.main.banner-mode=off",
                )
        }
        builder.run(*invocation.remaining.toTypedArray())
    }

    internal fun resolveServerInvocation(
        arguments: List<String>,
        environmentTransport: String?,
    ): ServerInvocation {
        val parsed = extractOption(arguments, "--transport")
        return ServerInvocation(
            transport = normalizeTransport(parsed.value ?: environmentTransport ?: "streamable-http"),
            remaining = parsed.remaining,
        )
    }

    private fun runAuthWizard(arguments: List<String>) {
        val accountOption = extractOption(arguments, "--account")
        val methodOption = extractOption(accountOption.remaining, "--method")
        val noBrowser = "--no-browser" in methodOption.remaining
        val remaining = methodOption.remaining.filterNot { it == "--no-browser" }
        require(remaining.isEmpty()) {
            "Unknown auth argument(s): ${remaining.joinToString(" ")}"
        }

        val account = TelegramAccountRegistry.normalizeLabel(accountOption.value ?: "default")
        val method = (methodOption.value ?: "qr").lowercase()
        require(method in setOf("qr", "phone")) { "--method must be qr or phone" }

        val port = ServerSocket(0, 1, java.net.InetAddress.getLoopbackAddress()).use { it.localPort }
        val nonce = randomNonce()
        val context = SpringApplicationBuilder(TelegramMcpApplication::class.java)
            .web(WebApplicationType.SERVLET)
            .profiles("auth-wizard")
            .properties(
                "server.address=127.0.0.1",
                "server.port=$port",
                "spring.ai.mcp.server.enabled=false",
                "management.endpoints.enabled-by-default=false",
                "auth-wizard.enabled=true",
                "auth-wizard.nonce=$nonce",
                "auth-wizard.account-label=$account",
                "auth-wizard.method=$method",
            )
            .run(
                "--mcp.security.mode=api-key",
                "--mcp.security.oauth.issuer-uri=",
                "--mcp.security.oauth.jwk-set-uri=",
                "--mcp.security.oauth.resource-uri=",
            )

        val url = URI("http://127.0.0.1:$port/setup?nonce=$nonce")
        println("Account: $account; method: $method")
        if (noBrowser) {
            runConsoleAuth(context, method)
        } else {
            println("Telegram authentication wizard: $url")
            openBrowser(url)
            waitForCompletion(context)
        }
    }

    private fun runConsoleAuth(context: ConfigurableApplicationContext, method: String) {
        val orchestrator = context.getBean(TelegramAuthOrchestrator::class.java)
        val stateHolder = context.getBean(TelegramAuthStateHolder::class.java)
        val apiId = prompt("Telegram API ID: ").toIntOrNull()
            ?: error("Telegram API ID must be an integer")
        val apiHash = promptSecret("Telegram API hash: ")
        require(apiHash.isNotBlank()) { "Telegram API hash is required" }
        val phone = if (method == "phone") {
            prompt("Phone number (international format): ").also {
                require(it.isNotBlank()) { "Phone number is required for phone authentication" }
            }
        } else {
            null
        }

        orchestrator.initAuth(apiId, apiHash, phone)
        if (method == "qr") orchestrator.requestQr()

        var handledState: AuthState? = null
        try {
            while (context.isActive) {
                val state = stateHolder.getState()
                if (state !== handledState) {
                    when (state) {
                        is AuthState.WaitingQr -> {
                            println("Scan this QR code in Telegram (Settings > Devices > Link Desktop Device):")
                            printTerminalQr(state.qrLink)
                            handledState = state
                        }
                        is AuthState.WaitingCode -> {
                            val code = promptSecret("Telegram login code: ")
                            require(stateHolder.submitCode(code)) { "Telegram no longer expects a login code" }
                            handledState = state
                        }
                        is AuthState.WaitingPassword -> {
                            val password = promptSecret(
                                if (state.passwordHint.isBlank()) {
                                    "Telegram 2FA password: "
                                } else {
                                    "Telegram 2FA password (hint: ${state.passwordHint}): "
                                },
                            )
                            require(stateHolder.submitPassword(password)) {
                                "Telegram no longer expects a 2FA password"
                            }
                            handledState = state
                        }
                        is AuthState.Ready -> {
                            println("Telegram authentication completed. Session state was saved locally.")
                            return
                        }
                        is AuthState.Error -> error("Telegram authentication failed: ${state.errorMessage}")
                        is AuthState.LoggedOut -> error("Telegram authentication was cancelled")
                        else -> handledState = state
                    }
                }
                Thread.sleep(250)
            }
        } finally {
            context.close()
        }
    }

    private fun prompt(label: String): String {
        val console = System.console()
        if (console != null) return console.readLine("%s", label)?.trim().orEmpty()
        print(label)
        System.out.flush()
        return readlnOrNull()?.trim().orEmpty()
    }

    private fun promptSecret(label: String): String {
        val console = System.console()
        if (console != null) return console.readPassword("%s", label)?.concatToString().orEmpty()
        print(label)
        System.out.flush()
        return readlnOrNull().orEmpty()
    }

    private fun printTerminalQr(value: String) {
        val matrix = QRCodeWriter().encode(value, BarcodeFormat.QR_CODE, 0, 0)
        val quietZone = 2
        val whiteRow = "  ".repeat(matrix.width + quietZone * 2)
        repeat(quietZone) { println(whiteRow) }
        for (y in 0 until matrix.height) {
            print("  ".repeat(quietZone))
            for (x in 0 until matrix.width) {
                print(if (matrix[x, y]) "██" else "  ")
            }
            println("  ".repeat(quietZone))
        }
        repeat(quietZone) { println(whiteRow) }
    }

    private fun waitForCompletion(context: ConfigurableApplicationContext) {
        val stateHolder = context.getBean(TelegramAuthStateHolder::class.java)
        while (context.isActive) {
            when (stateHolder.getState()) {
                is AuthState.Ready -> {
                    println("Telegram authentication completed. Session state was saved locally.")
                    Thread.sleep(750)
                    context.close()
                    return
                }
                else -> Thread.sleep(250)
            }
        }
    }

    private fun openBrowser(uri: URI) {
        runCatching {
            require(Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE))
            Desktop.getDesktop().browse(uri)
        }.onFailure {
            println("Could not open a browser automatically. Open the printed loopback URL manually.")
        }
    }

    private fun version(): String {
        val properties = Properties()
        TelegramMcpCli::class.java.classLoader.getResourceAsStream("META-INF/build-info.properties")?.use {
            properties.load(it)
        }
        return properties.getProperty("build.version")
            ?: System.getenv("MCP_SERVER_VERSION")
            ?: "0.0.0-dev"
    }

    private fun normalizeTransport(value: String): Transport = when (value.trim().lowercase()) {
        "http", "streamable", "streamable-http" -> Transport.HTTP
        "stdio" -> Transport.STDIO
        else -> error("Unsupported MCP transport '$value'; use streamable-http or stdio")
    }

    private fun extractOption(
        arguments: List<String>,
        name: String,
    ): ParsedOption {
        var value: String? = null
        val remaining = mutableListOf<String>()
        var index = 0
        while (index < arguments.size) {
            val argument = arguments[index]
            when {
                argument == name -> {
                    require(value == null) { "$name may be specified only once" }
                    value = arguments.getOrNull(index + 1)
                        ?: error("$name requires a value")
                    index += 2
                }
                argument.startsWith("$name=") -> {
                    require(value == null) { "$name may be specified only once" }
                    value = argument.substringAfter('=').takeIf(String::isNotBlank)
                        ?: error("$name requires a value")
                    index++
                }
                else -> {
                    remaining += argument
                    index++
                }
            }
        }
        return ParsedOption(value, remaining)
    }

    private fun randomNonce(): String {
        val bytes = ByteArray(32)
        SecureRandom().nextBytes(bytes)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }

    private fun printUsage() {
        println("Telegram MCP Server ${version()}")
        println("Usage:")
        println("  telegram-mcp serve [--transport streamable-http|stdio] [Spring options]")
        println("  telegram-mcp auth [--account <label>] [--method qr|phone] [--no-browser]")
        println("  telegram-mcp session <doctor|logout|clear> [options]")
        println("  telegram-mcp version")
        println()
        println("Running without a command preserves the legacy Streamable HTTP startup.")
    }

    internal data class ServerInvocation(
        val transport: Transport,
        val remaining: List<String>,
    )

    private data class ParsedOption(
        val value: String?,
        val remaining: List<String>,
    )

    internal enum class Transport { HTTP, STDIO }
}
