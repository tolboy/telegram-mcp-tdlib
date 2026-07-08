package dev.telegrammcp.server.client

import dev.telegrammcp.server.config.TdLibProperties
import dev.telegrammcp.server.config.TelegramProperties
import dev.telegrammcp.server.exception.TdLibAuthException
import dev.telegrammcp.server.security.SecretResolver
import dev.telegrammcp.server.service.PlatformPaths
import dev.telegrammcp.server.util.StructuredLogger
import io.github.resilience4j.circuitbreaker.CircuitBreaker
import io.github.resilience4j.ratelimiter.RateLimiter
import io.micrometer.core.instrument.MeterRegistry
import it.tdlight.Init
import it.tdlight.Log
import it.tdlight.client.APIToken
import it.tdlight.client.AuthenticationSupplier
import it.tdlight.client.ClientInteraction
import it.tdlight.client.InputParameter
import it.tdlight.client.ParameterInfo
import it.tdlight.client.SimpleTelegramClient
import it.tdlight.client.SimpleTelegramClientBuilder
import it.tdlight.client.SimpleTelegramClientFactory
import it.tdlight.client.TDLibSettings
import it.tdlight.jni.TdApi
import jakarta.annotation.PreDestroy
import org.springframework.boot.info.BuildProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Primary
import org.springframework.context.annotation.Profile
import org.springframework.core.env.Environment
import org.springframework.core.env.Profiles
import java.nio.file.Path
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * Builds one isolated TDLib factory and database per configured Telegram
 * account. The old `tdlib.*` block remains the backwards-compatible `default`
 * account; `telegram.accounts.<label>.*` adds independently scoped accounts.
 */
@Configuration(proxyBeanMethods = false)
@Profile("!auth-wizard")
class TdLibConfig(
    private val defaultProperties: TdLibProperties,
    private val telegramProperties: TelegramProperties,
    private val secretResolver: SecretResolver,
    private val platformPaths: PlatformPaths,
    private val rateLimiter: RateLimiter,
    private val circuitBreaker: CircuitBreaker,
    private val meterRegistry: MeterRegistry,
    private val environment: Environment,
    private val buildProperties: BuildProperties? = null,
) {
    private val log = StructuredLogger.forClass<TdLibConfig>()
    private val nativeInitialized = AtomicBoolean(false)
    private val proxyResolver = TdLibProxyResolver(secretResolver)

    @Volatile
    private var runtimeRegistry: TelegramAccountRegistry? = null

    @Bean
    fun telegramAccountRegistry(
        delegating: DelegatingTelegramClientService,
    ): TelegramAccountRegistry {
        val registry = TelegramAccountRegistry()
        val accountConfigurations = configuredAccounts()

        if (accountConfigurations.isEmpty()) {
            // Keep the interactive /auth flow backwards compatible. The
            // delegate starts as NoOp and will be hot-swapped after login.
            registry.register(TelegramAccountRegistry.AccountHandle("default", delegating.proxy))
            runtimeRegistry = registry
            return registry
        }

        validateDistinctStorage(accountConfigurations)
        ensureNativeInitialized(accountConfigurations.maxOf { it.logVerbosityLevel })

        accountConfigurations.forEach { account ->
            val handle = startAccount(account)
            if (account.label == "default") {
                delegating.swap(handle.service)
                registry.register(
                    TelegramAccountRegistry.AccountHandle(
                        label = "default",
                        service = delegating.proxy,
                        close = handle.close,
                    ),
                )
            } else {
                registry.register(handle)
            }
        }

        runtimeRegistry = registry
        log.info("Initialized {} isolated Telegram account(s): {}", registry.labels().size, registry.labels())
        return registry
    }

    @Bean
    fun telegramAccountContext(registry: TelegramAccountRegistry): TelegramAccountContext =
        TelegramAccountContext(registry)

    @Bean
    @Primary
    fun telegramClientService(
        registry: TelegramAccountRegistry,
        accountContext: TelegramAccountContext,
    ): TelegramClientService = AccountRoutingTelegramClientService(registry, accountContext).proxy

    private fun configuredAccounts(): List<RuntimeAccountConfig> {
        val result = mutableListOf<RuntimeAccountConfig>()

        if (defaultAccountWasConfigured()) {
            result += resolveAccount(
                "default",
                defaultProperties.api,
                defaultProperties.auth,
                defaultProperties.database,
                defaultProperties.session,
                defaultProperties.proxy,
                defaultProperties.logVerbosityLevel,
            )
        }

        telegramProperties.accounts.forEach { (rawLabel, account) ->
            val label = TelegramAccountRegistry.normalizeLabel(rawLabel)
            require(result.none { it.label == label }) {
                "Telegram account '$label' is configured both in tdlib.* and telegram.accounts.*"
            }
            result += resolveAccount(
                label = label,
                api = account.api,
                auth = account.auth,
                database = account.database,
                session = account.session,
                proxy = account.proxy,
                logVerbosityLevel = account.logVerbosityLevel ?: defaultProperties.logVerbosityLevel,
            )
        }
        return result
    }

    private fun defaultAccountWasConfigured(): Boolean =
        defaultProperties.api.id != 0 ||
            defaultProperties.api.hash.isNotBlank() ||
            defaultProperties.api.hashFile.isNotBlank() ||
            defaultProperties.auth.phoneNumber.isNotBlank() ||
            defaultProperties.auth.botToken.isNotBlank() ||
            defaultProperties.auth.botTokenFile.isNotBlank()

    private fun resolveAccount(
        label: String,
        api: TdLibProperties.ApiCredentials,
        auth: TdLibProperties.Auth,
        database: TdLibProperties.Database,
        session: TdLibProperties.Session,
        proxy: TdLibProperties.Proxy,
        logVerbosityLevel: Int,
    ): RuntimeAccountConfig {
        require(api.id > 0) { "Telegram account '$label' requires a positive API ID" }
        require(logVerbosityLevel in 0..10) { "Telegram account '$label' log verbosity must be between 0 and 10" }

        val apiHash = secretResolver.resolve(api.hash, api.hashFile, "Telegram account '$label' API hash", required = true)
        val botToken = secretResolver.resolve(auth.botToken, auth.botTokenFile, "Telegram account '$label' bot token")
        val password = secretResolver.resolve(auth.password, auth.passwordFile, "Telegram account '$label' 2FA password")
        val code = secretResolver.resolve(auth.code, auth.codeFile, "Telegram account '$label' auth code")
        require(auth.phoneNumber.isBlank() || botToken.isBlank()) {
            "Telegram account '$label' must configure either phone-number or bot-token, not both"
        }

        val databaseDirectory = database.directory.takeIf { it.isNotBlank() }
            ?.let { platformPaths.resolveApplicationPath(it, "Telegram account '$label' database directory") }
            ?: platformPaths.tdLibDatabaseDirectory(label)
        val downloadsDirectory = database.downloadsDirectory.takeIf { it.isNotBlank() }
            ?.let { platformPaths.resolveApplicationPath(it, "Telegram account '$label' downloads directory") }
            ?: platformPaths.tdLibDownloadsDirectory(label)

        return RuntimeAccountConfig(
            label = label,
            apiId = api.id,
            apiHash = apiHash,
            phoneNumber = auth.phoneNumber.trim(),
            botToken = botToken,
            password = password,
            code = code,
            databaseDirectory = databaseDirectory,
            downloadsDirectory = downloadsDirectory,
            useFileDatabase = database.useFileDatabase,
            useChatInfoDatabase = database.useChatInfoDatabase,
            useMessageDatabase = database.useMessageDatabase,
            useTestDc = session.useTestDc,
            systemLanguageCode = session.systemLanguageCode.ifBlank { "en" },
            deviceModel = session.deviceModel.ifBlank { "Telegram MCP Server" },
            proxy = proxyResolver.resolve(proxy, label),
            logVerbosityLevel = logVerbosityLevel,
        )
    }

    private fun validateDistinctStorage(accounts: List<RuntimeAccountConfig>) {
        validateDistinctAccountStorage(
            accounts.map { AccountStorage(it.label, it.databaseDirectory, it.downloadsDirectory) },
        )
    }

    private fun ensureNativeInitialized(logVerbosityLevel: Int) {
        if (nativeInitialized.compareAndSet(false, true)) {
            log.info("Initializing TDLib native libraries for multi-account runtime")
            Init.init()
            @Suppress("DEPRECATION") // tdlight's current public logging entry point
            Log.setVerbosityLevel(logVerbosityLevel)
        }
    }

    private fun startAccount(config: RuntimeAccountConfig): TelegramAccountRegistry.AccountHandle {
        val factory = SimpleTelegramClientFactory()
        return try {
            val builder: SimpleTelegramClientBuilder = factory.builder(buildSettings(config))
            val authReady = CountDownLatch(1)
            val authError = AtomicReference<String>()
            val chatFolderState = ChatFolderState()
            val messageSendTracker = MessageSendTracker()
            builder.addUpdateHandler(TdApi.UpdateAuthorizationState::class.java) { update ->
                handleAuthState(config.label, update, authReady, authError)
            }
            builder.addUpdateHandler(TdApi.UpdateChatFolders::class.java) { update ->
                chatFolderState.replace(update)
            }
            builder.addUpdateHandler(TdApi.UpdateMessageSendSucceeded::class.java, messageSendTracker::onSucceeded)
            builder.addUpdateHandler(TdApi.UpdateMessageSendFailed::class.java, messageSendTracker::onFailed)
            builder.setClientInteraction(
                HeadlessClientInteraction(
                    label = config.label,
                    configuredCode = config.code,
                    configuredPassword = config.password,
                    // STDIO reserves stdout for JSON-RPC frames and stdin for the
                    // transport reader; a console prompt would corrupt the protocol.
                    allowConsolePrompts = !environment.acceptsProfiles(Profiles.of("stdio")),
                    log = log,
                ),
            )
            val client = builder.build(authenticationSupplier(config))
            configureProxy(client, config)

            log.info("Waiting for TDLib authentication for account '{}' (up to 90s)", config.label)
            if (!authReady.await(90, TimeUnit.SECONDS)) {
                throw TdLibAuthException(authError.get() ?: "Authentication timed out after 90 seconds for account '${config.label}'")
            }
            // A circuit failure or rate burst from one account must not throttle
            // or open the breaker for a different account.
            val accountRateLimiter = RateLimiter.of(
                "telegram-${config.label}",
                rateLimiter.rateLimiterConfig,
            )
            val accountCircuitBreaker = CircuitBreaker.of(
                "telegram-${config.label}",
                circuitBreaker.circuitBreakerConfig,
            )
            TelegramAccountRegistry.AccountHandle(
                label = config.label,
                service = TdLibClientService(
                    client,
                    accountRateLimiter,
                    accountCircuitBreaker,
                    meterRegistry,
                    chatFolderState,
                    messageSendTracker,
                ),
                close = factory::close,
            )
        } catch (e: Exception) {
            runCatching { factory.close() }
            throw e
        }
    }

    /** Registers and enables the proxy before authentication can make Telegram calls. */
    private fun configureProxy(client: SimpleTelegramClient, config: RuntimeAccountConfig) {
        val proxy = config.proxy ?: return
        try {
            client.send(TdApi.AddProxy(proxy, true, "telegram-mcp:${config.label}")).get(10, TimeUnit.SECONDS)
        } catch (interrupted: InterruptedException) {
            Thread.currentThread().interrupt()
            throw IllegalStateException("Interrupted while configuring proxy for Telegram account '${config.label}'", interrupted)
        } catch (error: Exception) {
            throw IllegalStateException("Unable to configure proxy for Telegram account '${config.label}'", error)
        }
        log.info("Enabled TDLib {} proxy for account '{}'", proxy.type.javaClass.simpleName, config.label)
    }

    private fun buildSettings(config: RuntimeAccountConfig): TDLibSettings =
        TDLibSettings.create(APIToken(config.apiId, config.apiHash)).also { settings ->
            settings.databaseDirectoryPath = config.databaseDirectory
            settings.downloadedFilesDirectoryPath = config.downloadsDirectory
            settings.setFileDatabaseEnabled(config.useFileDatabase)
            settings.setChatInfoDatabaseEnabled(config.useChatInfoDatabase)
            settings.setMessageDatabaseEnabled(config.useMessageDatabase)
            settings.setUseTestDatacenter(config.useTestDc)
            settings.systemLanguageCode = config.systemLanguageCode
            settings.deviceModel = config.deviceModel
            settings.applicationVersion = liveApplicationVersion()
        }

    private fun liveApplicationVersion(): String =
        buildProperties?.version
            ?: error("build-info.properties missing — rebuild the jar with springBoot buildInfo enabled")

    private fun authenticationSupplier(config: RuntimeAccountConfig): AuthenticationSupplier<*> = when {
        config.phoneNumber.isNotBlank() -> AuthenticationSupplier.user(config.phoneNumber)
        config.botToken.isNotBlank() -> AuthenticationSupplier.bot(config.botToken)
        else -> AuthenticationSupplier.qrCode()
    }

    private fun handleAuthState(
        label: String,
        update: TdApi.UpdateAuthorizationState,
        authReady: CountDownLatch,
        authError: AtomicReference<String>,
    ) {
        when (val state = update.authorizationState) {
            is TdApi.AuthorizationStateReady -> {
                log.info("TDLib account '{}' is ready", label)
                authReady.countDown()
            }
            is TdApi.AuthorizationStateClosed -> {
                authError.compareAndSet(null, "TDLib session closed before reaching READY")
                authReady.countDown()
            }
            is TdApi.AuthorizationStateWaitOtherDeviceConfirmation ->
                log.info("TDLib account '{}' needs QR confirmation", label)
            is TdApi.AuthorizationStateWaitEmailAddress,
            is TdApi.AuthorizationStateWaitEmailCode ->
                log.warn("TDLib account '{}' requires email verification", label)
            else -> log.debug("TDLib account '{}' auth state: {}", label, state.javaClass.simpleName)
        }
    }

    @PreDestroy
    fun shutdown() {
        runtimeRegistry?.clear()
    }

    private data class RuntimeAccountConfig(
        val label: String,
        val apiId: Int,
        val apiHash: String,
        val phoneNumber: String,
        val botToken: String,
        val password: String,
        val code: String,
        val databaseDirectory: Path,
        val downloadsDirectory: Path,
        val useFileDatabase: Boolean,
        val useChatInfoDatabase: Boolean,
        val useMessageDatabase: Boolean,
        val useTestDc: Boolean,
        val systemLanguageCode: String,
        val deviceModel: String,
        val proxy: TdApi.Proxy?,
        val logVerbosityLevel: Int,
    )

}

internal data class AccountStorage(
    val label: String,
    val databaseDirectory: Path,
    val downloadsDirectory: Path,
)

/**
 * Session isolation depends on disjoint storage: a shared or nested TDLib
 * database corrupts sessions, and a shared downloads directory mixes one
 * account's files into another's file surface.
 */
internal fun validateDistinctAccountStorage(accounts: List<AccountStorage>) {
    accounts.forEachIndexed { leftIndex, left ->
        accounts.drop(leftIndex + 1).forEach { right ->
            require(!left.databaseDirectory.startsWith(right.databaseDirectory) && !right.databaseDirectory.startsWith(left.databaseDirectory)) {
                "Telegram accounts '${left.label}' and '${right.label}' must use separate TDLib database directories"
            }
            require(!left.downloadsDirectory.startsWith(right.downloadsDirectory) && !right.downloadsDirectory.startsWith(left.downloadsDirectory)) {
                "Telegram accounts '${left.label}' and '${right.label}' must use separate downloads directories"
            }
            require(!left.downloadsDirectory.startsWith(right.databaseDirectory) && !right.downloadsDirectory.startsWith(left.databaseDirectory)) {
                "Telegram accounts '${left.label}' and '${right.label}' must not place a downloads directory inside another account's TDLib database directory"
            }
        }
    }
}

/**
 * Supplies TDLib authentication parameters from configuration and only falls
 * back to console prompts when the process owns its console. With the STDIO
 * MCP transport, stdout carries JSON-RPC frames and stdin feeds the transport
 * reader, so an unresolved parameter fails authentication instead of
 * corrupting the protocol stream.
 */
internal class HeadlessClientInteraction(
    private val label: String,
    private val configuredCode: String,
    private val configuredPassword: String,
    private val allowConsolePrompts: Boolean,
    private val log: StructuredLogger,
) : ClientInteraction {
    private val codeUsed = AtomicBoolean(false)

    override fun onParameterRequest(parameter: InputParameter, info: ParameterInfo): CompletableFuture<String> {
        val configured = when (parameter) {
            InputParameter.ASK_CODE -> configuredCode.takeIf { it.isNotBlank() && codeUsed.compareAndSet(false, true) }
            InputParameter.ASK_PASSWORD -> configuredPassword.takeIf { it.isNotBlank() }
            InputParameter.NOTIFY_LINK -> {
                log.info("TDLib account '{}' requires external confirmation", label)
                return CompletableFuture.completedFuture("")
            }
            InputParameter.TERMS_OF_SERVICE -> return CompletableFuture.completedFuture("Y")
            else -> null
        }
        if (configured != null) return CompletableFuture.completedFuture(configured)

        if (!allowConsolePrompts) {
            val message = "TDLib account '$label' needs interactive input ($parameter) but the STDIO " +
                "transport reserves stdin/stdout for MCP. Configure TDLIB_AUTH_CODE / TDLIB_2FA_PASSWORD " +
                "(or their *_FILE variants), or authenticate once with 'telegram-mcp auth'."
            log.error(message)
            return CompletableFuture.failedFuture(TdLibAuthException(message))
        }

        return CompletableFuture.supplyAsync {
            val prompt = when (parameter) {
                InputParameter.ASK_CODE -> "Enter Telegram login code for $label: "
                InputParameter.ASK_PASSWORD -> "Enter 2FA password for $label: "
                InputParameter.ASK_EMAIL_ADDRESS -> "Enter email for $label: "
                InputParameter.ASK_EMAIL_CODE -> "Enter email code for $label: "
                InputParameter.ASK_FIRST_NAME -> "Enter first name for $label: "
                InputParameter.ASK_LAST_NAME -> "Enter last name for $label: "
            }
            print(prompt)
            System.`in`.bufferedReader().readLine()?.trim().orEmpty()
        }
    }
}
