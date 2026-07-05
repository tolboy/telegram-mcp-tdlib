package dev.telegrammcp.server.auth

import dev.telegrammcp.server.client.DelegatingTelegramClientService
import dev.telegrammcp.server.client.MessageSendTracker
import dev.telegrammcp.server.client.TdLibClientService
import dev.telegrammcp.server.config.TdLibProperties
import dev.telegrammcp.server.config.TelegramProperties
import dev.telegrammcp.server.service.PlatformPaths
import dev.telegrammcp.server.util.StructuredLogger
import io.github.resilience4j.circuitbreaker.CircuitBreaker
import io.github.resilience4j.ratelimiter.RateLimiter
import io.micrometer.core.instrument.MeterRegistry
import it.tdlight.Init
import it.tdlight.Log
import it.tdlight.client.APIToken
import it.tdlight.client.AuthenticationSupplier
import it.tdlight.client.SimpleTelegramClient
import it.tdlight.client.SimpleTelegramClientBuilder
import it.tdlight.client.SimpleTelegramClientFactory
import it.tdlight.client.TDLibSettings
import it.tdlight.jni.TdApi
import org.springframework.core.task.TaskExecutor
import org.springframework.stereotype.Service
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * Orchestrates the interactive TDLib authentication lifecycle.
 *
 * Responsible for:
 * - Creating / re-creating [SimpleTelegramClient] with runtime credentials.
 * - Registering the resulting [TelegramClientService] bean dynamically.
 * - QR-code auth via [AuthenticationSupplier.qrCode()] (avoids "Phone number must be non-empty" error).
 * - Thread-safe client lifecycle management via [ReentrantLock] + activeBuilds tracking.
 *
 * If a persisted TDLib session already exists in `TDLIB_DATA_DIR`, TDLib itself will
 * transition straight to `AuthorizationStateReady` — no phone/code/QR prompt needed.
 */
@Service
class TelegramAuthOrchestrator(
    private val props: TdLibProperties,
    private val authStateHolder: TelegramAuthStateHolder,
    private val runtimeCredentials: RuntimeCredentialsHolder,
    private val rateLimiter: RateLimiter,
    private val circuitBreaker: CircuitBreaker,
    private val meterRegistry: MeterRegistry,
    private val delegatingClient: DelegatingTelegramClientService,
    private val platformPaths: PlatformPaths,
    private val taskExecutor: TaskExecutor,
    private val buildProperties: org.springframework.boot.info.BuildProperties? = null,
    private val authWizard: AuthWizardProperties = AuthWizardProperties(),
    private val telegramProperties: TelegramProperties = TelegramProperties(),
) {

    private val log = StructuredLogger.forClass<TelegramAuthOrchestrator>()
    private val lock = ReentrantLock()

    /** Incremented on every initAuth call; lets the background thread detect if it was superseded. */
    private val generation = AtomicInteger(0)

    /**
     * Number of buildTdlibClient() tasks currently in flight. Used together
     * with [currentClient] to make [initAuth] idempotent even during the
     * window where the background thread hasn't yet returned a usable client
     * (TDLib cold-start can be 5–15s). Without this, a user clicking
     * "Start authentication" twice tears down the in-progress client and blocks
     * the HTTP thread on factory.close() long enough to trip reqwest's 10s
     * timeout → "telegram-mcp unreachable".
     */
    private val activeBuilds = AtomicInteger(0)

    /**
     * Set by [logout] immediately before tearing down the client. TDLib's
     * subsequent `AuthorizationStateClosed` update is expected and must not be
     * surfaced as an error in the wizard — otherwise the user sees a red
     * "TDLib session closed" banner after pressing "Disconnect" even though
     * that is exactly what they asked for.
     */
    private val intentionalLogout = java.util.concurrent.atomic.AtomicBoolean(false)

    @Volatile
    private var currentClient: SimpleTelegramClient? = null

    @Volatile
    private var clientFactory: SimpleTelegramClientFactory? = null

    @Volatile
    private var messageSendTracker: MessageSendTracker? = null

    /** Lazy thread-safe native TDLib init — safe to call from any thread. */
    private val nativeInitOnce: Unit by lazy {
        Init.init()
        @Suppress("DEPRECATION")
        Log.setVerbosityLevel(props.logVerbosityLevel)
    }

    /**
     * Initiates authentication with the given credentials.
     *
     * If the client is already in READY state, this is a no-op.
     * If a previous attempt failed or is idle, a new client is created.
     */
    fun initAuth(apiId: Int, apiHash: String, phoneNumber: String?) {
        lock.withLock {
            val currentState = authStateHolder.getState()
            val normalizedPhoneNumber = phoneNumber.orEmpty()

            // Already authenticated — nothing to do
            if (currentState is AuthState.Ready && currentClient != null) {
                log.info("initAuth called but client is already READY — ignoring")
                return
            }

            // A live client with matching credentials already exists. This
            // typically means the wizard re-fired submitCredentials (e.g. a
            // retry after a spurious error banner). Tearing down the
            // in-progress TDLib client here would kill a valid auth flow
            // AND block the HTTP thread on factory.close() long enough to
            // trip a host application's 10s timeout → "telegram-mcp unreachable".
            val prev = runtimeCredentials.get()
            val matchesPrev = prev != null &&
                prev.apiId == apiId &&
                prev.apiHash == apiHash &&
                prev.phoneNumber == phoneNumber
            // Idempotent when (a) a live client already exists, or (b) a build
            // is still running. Case (b) is critical — without it, a second
            // click during TDLib cold-start (5–15s) supersedes the first build
            // and synchronously closes its factory, blocking this HTTP thread
            // past reqwest's 10s timeout → "telegram-mcp unreachable".
            if (matchesPrev && (currentClient != null || activeBuilds.get() > 0)) {
                log.info(
                    "initAuth: ignoring duplicate call ({}; state={})",
                    if (currentClient != null) "client already live" else "build in progress",
                    currentState.name,
                )
                return
            }

            // Store runtime credentials
            runtimeCredentials.set(
                RuntimeCredentialsHolder.Credentials(
                    apiId = apiId,
                    apiHash = apiHash,
                    phoneNumber = phoneNumber,
                ),
            )

            // Clean up previous client if any
            destroyCurrentClient()
            authStateHolder.reset()
            // Fresh auth attempt — discard any stale logout flag so the
            // upcoming TDLib lifecycle events go through the normal error
            // path rather than being silently absorbed as "logout".
            intentionalLogout.set(false)

            // Capture generation before releasing the lock so the background thread
            // can detect if it has been superseded by a subsequent initAuth call.
            val myGeneration = generation.incrementAndGet()
            activeBuilds.incrementAndGet()

            // Submit to the Spring-managed executor to avoid untracked daemon threads.
            taskExecutor.execute {
                try {
                    val (factory, client) = buildTdlibClient(apiId, apiHash, normalizedPhoneNumber)
                    lock.withLock {
                        if (generation.get() == myGeneration) {
                            clientFactory = factory
                            currentClient = client
                            log.info("TDLib interactive client created — waiting for auth state updates")
                        } else {
                            log.warn("initAuth superseded — discarding stale client (gen {})", myGeneration)
                            // Hand close off to another executor task so we don't hold
                            // the lock during factory.close() (which can take seconds).
                            closeFactoryAsync(factory, "superseded generation $myGeneration")
                        }
                    }
                } catch (e: Exception) {
                    log.error("Failed to initialize TDLib client: {}", e.message, e)
                    authStateHolder.setState(AuthState.Error(errorMessage = e.message ?: "Unknown error"))
                } finally {
                    activeBuilds.decrementAndGet()
                }
            }
        }
    }

    /**
     * Initiates QR-code authentication on the already-created client.
     *
     * Tolerant of in-progress builds (cold-start window of 5–15s). If no client yet
     * but activeBuilds > 0, logs and returns early. The post-build remediation +
     * update handlers will drive the state to WaitingQr automatically. Prevents
     * spurious "client not initialized" errors / 409 conflicts when the wizard
     * calls /auth/request-qr immediately after /auth/credentials (empty phone).
     */
    fun requestQr() {
        val client = lock.withLock {
            val c = currentClient
            if (c == null) {
                val builds = activeBuilds.get()
                if (builds > 0) {
                    log.info(
                        "requestQr: build still in progress (activeBuilds={}, gen={}); " +
                            "QR will be auto-triggered by remediation handler — skipping",
                        builds, generation.get()
                    )
                    return@withLock null
                }
                throw IllegalStateException("TDLib client is not initialized. Call /auth/credentials first.")
            }
            c
        } ?: return

        // Read currentClient under the lock to prevent a TOCTOU race with initAuth/logout.
        // The send() call itself is intentionally outside the lock to avoid holding it
        // during async I/O; the local `client` reference remains valid regardless.
        client.send(TdApi.RequestQrCodeAuthentication()) { result ->
            if (result.isError) {
                // Don't overwrite the real auth state (WaitingCode / WaitingPassword /
                // Ready) with an Error — an out-of-phase QR request is often spurious
                // (UI race) and the underlying flow is still progressing. The controller
                // already guards the happy path; log here and let polling show truth.
                log.warn("RequestQrCodeAuthentication rejected by TDLib: {}", result.error)
            } else {
                log.info("QR code authentication requested — waiting for TDLib state update")
            }
        }
    }

    /**
     * Logs out from the current TDLib session.
     *
     * Sets [intentionalLogout] before tearing the client down so the ensuing
     * `AuthorizationStateClosed` update is routed to [AuthState.LoggedOut]
     * instead of [AuthState.Error]. The flag is cleared on the next successful
     * `initAuth`.
     *
     * If there is no live client (e.g. session already torn down or never
     * authenticated), we still emit [AuthState.LoggedOut] so callers get a
     * consistent "disconnected" signal rather than a silent no-op.
     */
    fun logout() {
        lock.withLock {
            intentionalLogout.set(true)
            val client = currentClient
            if (client == null) {
                authStateHolder.setState(AuthState.LoggedOut())
                return
            }
            client.send(TdApi.LogOut()) { result ->
                if (result.isError) {
                    log.warn("Logout failed: {}", result.error)
                } else {
                    log.info("Logout successful")
                }
            }
            destroyCurrentClient()
            authStateHolder.setState(AuthState.LoggedOut())
        }
    }

    /** Returns the currently active client (if any). Used by tools via dynamic bean lookup. */
    fun getClient(): SimpleTelegramClient? = currentClient

    // ── Private ─────────────────────────────────────────────────────────────

    /**
     * Builds a TDLib factory + client without touching shared state.
     * Uses [AuthenticationSupplier.qrCode()] when no phone is provided to properly
     * initialize the QR flow and avoid the "Phone number must be non-empty" error
     * that occurred with user("").
     * Shared-state assignment is done by caller under lock.
     */
    private fun buildTdlibClient(
        apiId: Int,
        apiHash: String,
        phoneNumber: String,
    ): Pair<SimpleTelegramClientFactory, SimpleTelegramClient> {
        ensureNativeInit()

        val settings = buildSettings(apiId, apiHash)
        val factory = SimpleTelegramClientFactory()
        val builder: SimpleTelegramClientBuilder = factory.builder(settings)
        val sendTracker = MessageSendTracker()

        builder.addUpdateHandler(TdApi.UpdateAuthorizationState::class.java) { update ->
            handleAuthState(update, null, phoneNumber)
        }
        builder.addUpdateHandler(TdApi.UpdateMessageSendSucceeded::class.java, sendTracker::onSucceeded)
        builder.addUpdateHandler(TdApi.UpdateMessageSendFailed::class.java, sendTracker::onFailed)
        builder.setClientInteraction(InteractiveClientInteraction(authStateHolder, phoneNumber))

        val authSupplier = if (phoneNumber.isNotBlank()) {
            log.info("Starting user-account auth with phone number")
            AuthenticationSupplier.user(phoneNumber)
        } else {
            log.info("Starting QR-code auth (no phone provided) — using AuthenticationSupplier.qrCode()")
            AuthenticationSupplier.qrCode()
        }

        val client = builder.build(authSupplier)
        messageSendTracker = sendTracker

        return factory to client
    }

    private fun handleAuthState(
        update: TdApi.UpdateAuthorizationState,
        client: SimpleTelegramClient?,
        phoneNumber: String,
    ) {
        when (val state = update.authorizationState) {
            is TdApi.AuthorizationStateReady -> {
                log.info("TDLib auth: READY (interactive)")
                authStateHolder.setState(AuthState.Ready())
                registerClientServiceBean()
            }

            is TdApi.AuthorizationStateClosed -> {
                if (intentionalLogout.compareAndSet(true, false)) {
                    log.info("TDLib auth: CLOSED (after explicit logout)")
                    // logout() already published LoggedOut — don't overwrite it.
                } else {
                    log.warn("TDLib auth: CLOSED (unexpected)")
                    authStateHolder.setState(AuthState.Error(errorMessage = "TDLib session closed"))
                }
            }

            is TdApi.AuthorizationStateClosing -> {
                log.info("TDLib auth: CLOSING")
            }

            is TdApi.AuthorizationStateWaitPhoneNumber -> {
                log.info(
                    "TDLib auth: WAIT_PHONE_NUMBER (phoneBlank={}) — wizard should call /auth/request-qr for QR flow",
                    phoneNumber.isBlank(),
                )
                authStateHolder.setState(AuthState.WaitingPhoneNumber())
            }

            is TdApi.AuthorizationStateWaitCode -> {
                log.info("TDLib auth: WAIT_CODE")
                // State is set by InteractiveClientInteraction.onParameterRequest
            }

            is TdApi.AuthorizationStateWaitPassword -> {
                log.info("TDLib auth: WAIT_PASSWORD (2FA)")
                // State is set by InteractiveClientInteraction.onParameterRequest
            }

            is TdApi.AuthorizationStateWaitOtherDeviceConfirmation -> {
                log.info("TDLib auth: WAIT_OTHER_DEVICE_CONFIRMATION (QR link received)")
                authStateHolder.setState(AuthState.WaitingQr(qrLink = state.link))
            }

            is TdApi.AuthorizationStateWaitTdlibParameters -> {
                log.debug("TDLib auth: WAIT_PARAMETERS")
            }

            else -> log.debug("TDLib auth: {}", state.javaClass.simpleName)
        }
    }

    /**
     * Hot-swaps the [DelegatingTelegramClientService] delegate to a real
     * [TdLibClientService] backed by the newly authenticated client.
     *
     * All tools that hold a reference to the proxy will immediately start
     * using the live TDLib client on their next call.
     */
    private fun registerClientServiceBean() {
        val client = currentClient ?: return
        val service = TdLibClientService(
            client,
            rateLimiter,
            circuitBreaker,
            meterRegistry,
            messageSendTracker = messageSendTracker,
        )
        delegatingClient.swap(service)
        log.info("Hot-swapped TelegramClientService delegate to live TDLib client")
    }

    /** Must be called while holding [lock]. Detaches shared references before closing. */
    private fun destroyCurrentClient() {
        // Detach references first to keep in-memory state consistent even if close fails.
        val oldFactory = clientFactory
        clientFactory = null
        currentClient = null
        messageSendTracker = null

        if (oldFactory != null) {
            // Close asynchronously: factory.close() can block seconds, and we're
            // called inside lock.withLock {} on the HTTP thread — holding the lock
            // that long trips a host application's 10s HTTP timeout.
            closeFactoryAsync(oldFactory, "current client cleanup")
        }
    }

    /**
     * Close the factory on the shared executor so the calling thread (and any
     * lock it holds) is freed immediately. Safe to call from inside [lock].
     */
    private fun closeFactoryAsync(factory: SimpleTelegramClientFactory, context: String) {
        taskExecutor.execute {
            try {
                factory.close()
                log.debug("TDLib factory closed ({})", context)
            } catch (e: Exception) {
                log.warn("Error closing TDLib client factory ({}): {}", context, e.message, e)
            }
        }
    }

    private fun ensureNativeInit() {
        // Kotlin's `by lazy` is synchronized by default — safe for concurrent access.
        nativeInitOnce
    }

    private fun buildSettings(apiId: Int, apiHash: String): TDLibSettings {
        val settings = TDLibSettings.create(APIToken(apiId, apiHash))
        val label = if (authWizard.enabled) authWizard.accountLabel else "default"
        val named = telegramProperties.accounts.entries
            .firstOrNull { (candidate, _) ->
                dev.telegrammcp.server.client.TelegramAccountRegistry.normalizeLabel(candidate) == label
            }
            ?.value
        val database = named?.database ?: props.database
        val session = named?.session ?: props.session

        settings.databaseDirectoryPath = database.directory.takeIf { it.isNotBlank() }
            ?.let { platformPaths.resolveApplicationPath(it, "Telegram account '$label' database directory") }
            ?: platformPaths.tdLibDatabaseDirectory(label)
        database.downloadsDirectory.takeIf { it.isNotBlank() }?.let {
            settings.downloadedFilesDirectoryPath =
                platformPaths.resolveApplicationPath(it, "Telegram account '$label' downloads directory")
        } ?: run {
            settings.downloadedFilesDirectoryPath = platformPaths.tdLibDownloadsDirectory(label)
        }
        settings.setFileDatabaseEnabled(database.useFileDatabase)
        settings.setChatInfoDatabaseEnabled(database.useChatInfoDatabase)
        settings.setMessageDatabaseEnabled(database.useMessageDatabase)

        settings.setUseTestDatacenter(session.useTestDc)
        settings.systemLanguageCode = session.systemLanguageCode
        settings.deviceModel = session.deviceModel
        // Version reported to Telegram → Settings → Devices. Bound to
        // BuildProperties (= gradle project.version, derived from the git tag
        // at build time) so it stays honest across releases. No YAML fallback
        // — a missing build-info.properties should fail loudly, not mask
        // behind a stale placeholder.
        settings.applicationVersion = buildProperties?.version
            ?: error("build-info.properties missing — rebuild the jar with `springBoot { buildInfo() }` enabled")
        return settings
    }
}
