package dev.telegrammcp.server.cli

import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import dev.telegrammcp.server.TelegramMcpApplication
import dev.telegrammcp.server.auth.AuthState
import dev.telegrammcp.server.auth.TelegramAuthStateHolder
import dev.telegrammcp.server.auth.TelegramAuthOrchestrator
import dev.telegrammcp.server.client.TelegramAccountRegistry
import dev.telegrammcp.server.runtime.ServerShutdown
import dev.telegrammcp.server.runtime.installSignalShutdownHook
import dev.telegrammcp.server.runtime.installStdinCloseWatcher
import dev.telegrammcp.server.runtime.removeSignalShutdownHook
import org.springframework.boot.WebApplicationType
import org.springframework.boot.builder.SpringApplicationBuilder
import org.springframework.context.ConfigurableApplicationContext
import java.awt.Desktop
import java.net.ServerSocket
import java.net.URI
import java.security.SecureRandom
import java.util.Base64
import java.util.Properties
import kotlin.system.exitProcess

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
            "config" -> {
                try {
                    printClientConfig(args.drop(1))
                } catch (invalid: IllegalArgumentException) {
                    failUsage(invalid.message)
                }
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

    /**
     * Reports a rejected argument the way a command line is expected to.
     *
     * The generator refuses a dozen combinations that cannot work; a Kotlin
     * stack trace buries the one line among them that says what to change.
     */
    private fun failUsage(message: String?): Nothing {
        System.err.println("error: ${message ?: "invalid arguments"}")
        System.err.println("Run `telegram-mcp help` for the accepted options.")
        exitProcess(USAGE_EXIT_CODE)
    }

    private fun runServer(arguments: List<String>) {
        val invocation = try {
            resolveServerInvocation(arguments, System.getenv("MCP_TRANSPORT"))
        } catch (invalid: IllegalStateException) {
            failUsage(invalid.message)
        }
        val builder = SpringApplicationBuilder(TelegramMcpApplication::class.java)
        if (invocation.transport == Transport.STDIO) {
            builder
                .web(WebApplicationType.NONE)
                .profiles("stdio")
                .properties(
                    "spring.ai.mcp.server.stdio=true",
                    "spring.main.banner-mode=off",
                )
            // Wrap stdin before the transport claims it. A stdio client ends the
            // session by closing stdin without sending a signal, and `docker run`
            // does not stop the container when its own client dies — without this
            // the process outlives the client and keeps the TDLib session locked.
            installStdinCloseWatcher(ServerShutdown.INSTANCE)
        }
        // Cover signals during the blocking startup as well as after it. If
        // Spring fails normally, remove the hook so its original non-zero exit
        // status is not replaced by the bounded shutdown path.
        val signalHook = installSignalShutdownHook(ServerShutdown.INSTANCE)
        try {
            val context = builder.run(*invocation.remaining.toTypedArray())
            // EOF or a signal may already have arrived while the context was
            // starting; attach hands the completed context to that shutdown.
            ServerShutdown.INSTANCE.attach(context)
        } catch (startupFailure: Throwable) {
            removeSignalShutdownHook(signalHook)
            throw startupFailure
        }
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

    internal fun resolveConfigOptions(
        arguments: List<String>,
        runningVersion: String = version(),
    ): ClientConfigPrinter.Options {
        val clientOption = extractOption(arguments, "--client")
        val profileOption = extractOption(clientOption.remaining, "--profile")
        val apiIdOption = extractOption(profileOption.remaining, "--api-id")
        val dockerOption = extractOption(apiIdOption.remaining, "--docker")
        val httpOption = extractOption(dockerOption.remaining, "--http")
        val writes = "--writes" in httpOption.remaining
        val remaining = httpOption.remaining.filterNot { it == "--writes" }
        require(remaining.isEmpty()) { "Unknown config argument(s): ${remaining.joinToString(" ")}" }
        require(httpOption.value == null || dockerOption.value == null) {
            "--http connects to a running daemon, so it cannot be combined with --docker"
        }

        val client = clientOption.value?.let(ClientConfigPrinter.Client::parse) ?: ClientConfigPrinter.Client.CLAUDE
        require(
            httpOption.value == null ||
                (!writes && profileOption.value == null && apiIdOption.value == null),
        ) {
            "--http cannot be combined with --writes, --profile, or --api-id; configure the tool surface " +
                "and Telegram credentials on the running daemon instead."
        }
        require(httpOption.value == null || client != ClientConfigPrinter.Client.CLAUDE) {
            "--client claude cannot emit an HTTP entry. In Claude Desktop, add the daemon under " +
                "Settings > Connectors using a network-reachable HTTPS URL backed by OAuth " +
                "(MCP_AUTH_MODE=oauth). Claude Connectors cannot carry this generator's static API-key " +
                "Authorization header. Alternatively, omit --http to generate STDIO config."
        }

        val profile = profileOption.value ?: "reader"
        require(profile in VALID_PROFILES) {
            "--profile must be one of ${VALID_PROFILES.joinToString("|")}"
        }
        // The profile filter runs before the read-only one, so a read-only
        // profile hides every write tool no matter what MCP_READ_ONLY says.
        // Emitting both settings would produce an entry that promises writes
        // and lists none, and silently widening the surface instead is worse.
        require(!writes || profile !in READ_ONLY_PROFILES) {
            "--writes has no effect with the '$profile' profile: it hides every write tool before " +
                "read-only mode is consulted. Add --profile inbox|community-admin|all."
        }
        val apiId = apiIdOption.value ?: "123456"
        val parsedApiId = apiId.toIntOrNull()
        require(parsedApiId != null && parsedApiId > 0) {
            "--api-id must be a positive Int (1..${Int.MAX_VALUE})"
        }
        val docker = dockerOption.value?.let { image ->
            if (image == "default") {
                require(RELEASE_VERSION.matches(runningVersion)) {
                    "--docker default requires a release build with an X.Y.Z version; this build reports " +
                        "'$runningVersion'. Pass an explicit published image reference instead."
                }
                "ghcr.io/tolboy/telegram-mcp-tdlib:$runningVersion-stdio"
            } else {
                image
            }
        }
        require(docker == null || isDockerImageReference(docker)) {
            "--docker image must be a valid lowercase Docker/OCI reference with an optional tag or digest"
        }
        val httpUrl = httpOption.value?.let { url ->
            if (url == "default") "http://127.0.0.1:8080/mcp" else url
        }
        require(httpUrl == null || isAbsoluteHttpUrl(httpUrl)) {
            "--http must be an absolute http(s) URI with a host, for example https://mcp.example.com/mcp"
        }
        return ClientConfigPrinter.Options(
            client = client,
            profile = profile,
            readOnly = !writes,
            apiId = parsedApiId.toString(),
            // A generated config pins the version it was generated from, so the
            // tag never floats to whatever the machine happened to cache.
            docker = docker,
            httpUrl = httpUrl,
        )
    }

    internal fun printClientConfig(
        arguments: List<String>,
        stdout: (String) -> Unit = { value -> System.out.println(value) },
        stderr: (String) -> Unit = { value -> System.err.println(value) },
    ) {
        val options = resolveConfigOptions(arguments)
        stdout(ClientConfigPrinter.render(options))
        ClientConfigPrinter.notes(options).forEach { note -> stderr("# $note") }
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

    private fun isAbsoluteHttpUrl(value: String): Boolean {
        val uri = runCatching { URI(value) }.getOrNull() ?: return false
        return uri.isAbsolute &&
            uri.scheme.lowercase() in setOf("http", "https") &&
            !uri.host.isNullOrBlank()
    }

    /**
     * Validates the useful, portable subset of the Docker distribution
     * reference grammar. Character filtering alone accepts strings Docker
     * rejects (for example `repo::tag` or `repo/../image`).
     */
    private fun isDockerImageReference(value: String): Boolean {
        if (value.isBlank() || value.length > MAX_DOCKER_REFERENCE_LENGTH || value != value.trim()) return false

        val digestParts = value.split('@')
        if (digestParts.size > 2) return false
        val nameAndTag = digestParts[0]
        val digest = digestParts.getOrNull(1)
        if (digest != null && !DOCKER_DIGEST.matches(digest)) return false

        val lastSlash = nameAndTag.lastIndexOf('/')
        val lastColon = nameAndTag.lastIndexOf(':')
        val hasTag = lastColon > lastSlash
        val name = if (hasTag) nameAndTag.substring(0, lastColon) else nameAndTag
        val tag = if (hasTag) nameAndTag.substring(lastColon + 1) else null
        if (tag != null && !DOCKER_TAG.matches(tag)) return false

        val components = name.split('/')
        if (components.any(String::isEmpty)) return false
        val first = components.first()
        val hasRegistry = components.size > 1 &&
            (first == "localhost" || '.' in first || ':' in first)
        val repositories = if (hasRegistry) components.drop(1) else components
        if (repositories.isEmpty() || repositories.any { !DOCKER_REPOSITORY_COMPONENT.matches(it) }) return false
        return !hasRegistry || isDockerRegistry(first)
    }

    private fun isDockerRegistry(value: String): Boolean {
        val parts = value.split(':')
        if (parts.size > 2 || !DOCKER_REGISTRY_HOST.matches(parts[0])) return false
        val port = parts.getOrNull(1) ?: return true
        val portNumber = port.toIntOrNull() ?: return false
        return portNumber in 1..65_535
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
        println("  telegram-mcp config [--client claude|claude-code|cursor|vscode|codex] [--profile <name>]")
        println("                      [--writes] [--api-id <id>] [--docker default|<image>]")
        println("                      [--http default|<url>]")
        println("  telegram-mcp session <doctor|logout|clear> [options]")
        println("  telegram-mcp version")
        println()
        println("Running without a command preserves the legacy Streamable HTTP startup.")
    }

    /** Profile names accepted by `MCP_TOOL_PROFILE`, rejected here rather than at startup. */
    private val VALID_PROFILES = setOf("all", "reader", "inbox", "community-admin", "research")

    /** Profiles whose definition already excludes every write tool. */
    private val READ_ONLY_PROFILES = setOf("reader", "research")

    /** Conventional shell exit status for a misused command. */
    private const val USAGE_EXIT_CODE = 2
    private val RELEASE_VERSION = Regex("""\d+\.\d+\.\d+""")
    private val DOCKER_REPOSITORY_COMPONENT = Regex("""[a-z0-9]+(?:(?:[._]|__|[-]+)[a-z0-9]+)*""")
    private val DOCKER_REGISTRY_HOST =
        Regex("""(?:localhost|[a-z0-9](?:[a-z0-9-]*[a-z0-9])?(?:\.[a-z0-9](?:[a-z0-9-]*[a-z0-9])?)*)""")
    private val DOCKER_TAG = Regex("""[A-Za-z0-9_][A-Za-z0-9_.-]{0,127}""")
    private val DOCKER_DIGEST = Regex("""[a-z][a-z0-9]*(?:[+._-][a-z][a-z0-9]*)*:[A-Fa-f0-9]{32,}""")
    private const val MAX_DOCKER_REFERENCE_LENGTH = 255

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
