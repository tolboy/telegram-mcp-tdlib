package dev.telegrammcp.server.client

import dev.telegrammcp.server.exception.TelegramUnavailableException
import org.slf4j.LoggerFactory
import java.lang.reflect.InvocationHandler
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/**
 * Owns the isolated Telegram client service for every configured account.
 *
 * Account labels are deliberately small, stable identifiers: they appear in
 * MCP arguments and API-key scopes but never contain a phone number, token, or
 * Telegram profile name. Each label has its own TDLib database directory and
 * client factory; services never share a TDLib session.
 */
class TelegramAccountRegistry {

    private val log = LoggerFactory.getLogger(TelegramAccountRegistry::class.java)
    private val accounts = ConcurrentHashMap<String, AccountHandle>()

    data class AccountHandle(
        val label: String,
        val service: TelegramClientService,
        val close: (() -> Unit)? = null,
    )

    fun register(handle: AccountHandle) {
        val label = normalizeLabel(handle.label)
        val previous = accounts.putIfAbsent(label, handle.copy(label = label))
        require(previous == null) { "Telegram account '$label' is configured more than once" }
        log.info("Registered Telegram account '{}'", label)
    }

    fun replace(handle: AccountHandle) {
        val label = normalizeLabel(handle.label)
        val previous = accounts.put(label, handle.copy(label = label))
        previous?.closeSafely(label)
        log.info("Replaced Telegram account '{}'", label)
    }

    fun require(label: String): TelegramClientService =
        accounts[normalizeLabel(label)]?.service
            ?: throw TelegramUnavailableException(
                IllegalArgumentException("Unknown Telegram account '$label'. Available accounts: ${labels().joinToString()}")
            )

    fun labels(): List<String> = accounts.keys.sorted()

    fun isMultiAccount(): Boolean = accounts.size > 1

    fun has(label: String): Boolean = accounts.containsKey(normalizeLabel(label))

    fun clear() {
        val previous = accounts.values.toList()
        accounts.clear()
        previous.forEach { it.closeSafely(it.label) }
    }

    private fun AccountHandle.closeSafely(label: String) {
        runCatching { close?.invoke() }
            .onFailure { log.warn("Failed to close Telegram account '{}': {}", label, it.message) }
    }

    companion object {
        private val LABEL = Regex("[a-z][a-z0-9_-]{0,31}")

        fun normalizeLabel(rawLabel: String): String {
            val label = rawLabel.trim().lowercase(Locale.ROOT)
            require(LABEL.matches(label)) {
                "Telegram account labels must match ${LABEL.pattern}"
            }
            return label
        }
    }
}

/** Per-request account selector populated by [dev.telegrammcp.server.config.McpConfig]. */
class TelegramAccountContext(
    private val registry: TelegramAccountRegistry,
) {
    private val selected = ThreadLocal<String?>()

    fun <T> withAccount(label: String, action: () -> T): T {
        val normalized = TelegramAccountRegistry.normalizeLabel(label)
        registry.require(normalized) // validate before mutating request state
        val previous = selected.get()
        selected.set(normalized)
        return try {
            action()
        } finally {
            if (previous == null) selected.remove() else selected.set(previous)
        }
    }

    fun currentAccount(): String = selected.get()
        ?: throw TelegramUnavailableException(
            IllegalStateException("Telegram account selection is missing for this request"),
        )
}

/**
 * A zero-maintenance proxy that routes every Telegram operation to the TDLib
 * service selected for the current MCP tool call.
 */
class AccountRoutingTelegramClientService(
    registry: TelegramAccountRegistry,
    context: TelegramAccountContext,
) {
    val proxy: TelegramClientService = Proxy.newProxyInstance(
        TelegramClientService::class.java.classLoader,
        arrayOf(TelegramClientService::class.java),
        RoutingInvocationHandler(registry, context),
    ) as TelegramClientService

    private class RoutingInvocationHandler(
        private val registry: TelegramAccountRegistry,
        private val context: TelegramAccountContext,
    ) : InvocationHandler {

        override fun invoke(proxy: Any, method: Method, args: Array<out Any>?): Any? {
            if (method.declaringClass == Any::class.java) {
                return when (method.name) {
                    "toString" -> "AccountRoutingTelegramClientService"
                    "hashCode" -> System.identityHashCode(proxy)
                    "equals" -> proxy === args?.firstOrNull()
                    else -> null
                }
            }

            val target = registry.require(context.currentAccount())
            return try {
                if (args == null) method.invoke(target) else method.invoke(target, *args)
            } catch (e: InvocationTargetException) {
                throw e.cause ?: e
            } catch (e: ReflectiveOperationException) {
                throw IllegalStateException("Unable to route Telegram operation ${method.name}", e)
            }
        }
    }
}
