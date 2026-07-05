package dev.telegrammcp.server.auth

import org.springframework.stereotype.Component
import java.util.concurrent.atomic.AtomicReference

/**
 * Runtime credential store for interactive auth.
 *
 * Values submitted via `POST /auth/credentials` are stored here and take priority
 * over [TdLibProperties] (environment variables) when creating the TDLib client.
 */
@Component
class RuntimeCredentialsHolder {

    data class Credentials(
        val apiId: Int,
        val apiHash: String,
        val phoneNumber: String? = null,
    )

    private val ref = AtomicReference<Credentials?>()

    fun get(): Credentials? = ref.get()

    fun set(credentials: Credentials) {
        ref.set(credentials)
    }

    fun hasCredentials(): Boolean {
        val c = ref.get() ?: return false
        return c.apiId > 0 && c.apiHash.isNotBlank()
    }

    fun clear() {
        ref.set(null)
    }
}
