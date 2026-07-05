package dev.telegrammcp.server.client

import dev.telegrammcp.server.config.TdLibProperties
import dev.telegrammcp.server.security.SecretResolver
import it.tdlight.jni.TdApi
import java.util.Locale

/**
 * Validates the public proxy settings and turns them into TDLib's native
 * representation. This is deliberately kept separate from client startup so
 * invalid settings fail before TDLib opens a network connection.
 */
internal class TdLibProxyResolver(
    private val secretResolver: SecretResolver,
) {

    fun resolve(settings: TdLibProperties.Proxy, accountLabel: String): TdApi.Proxy? {
        val rawType = settings.type.trim()
        if (rawType.isBlank()) {
            require(settings.isEmpty()) {
                "Telegram account '$accountLabel' proxy settings require tdlib.proxy.type"
            }
            return null
        }

        val server = settings.server.trim()
        require(server.isNotBlank() && server.none { it.isWhitespace() || it in "/?#@" }) {
            "Telegram account '$accountLabel' proxy server must be a hostname or IP address without a URL scheme"
        }
        require(settings.port in 1..65535) {
            "Telegram account '$accountLabel' proxy port must be between 1 and 65535"
        }

        return when (rawType.lowercase(Locale.ROOT)) {
            "socks5" -> {
                require(!settings.httpOnly) {
                    "Telegram account '$accountLabel' proxy http-only is only valid for an HTTP proxy"
                }
                val username = settings.username.trim()
                val password = proxyPassword(settings, accountLabel)
                require(username.isBlank() == password.isBlank()) {
                    "Telegram account '$accountLabel' SOCKS5 proxy username and password must be configured together"
                }
                TdApi.Proxy(server, settings.port, TdApi.ProxyTypeSocks5(username, password))
            }

            "http" -> {
                val username = settings.username.trim()
                val password = proxyPassword(settings, accountLabel)
                require(username.isBlank() == password.isBlank()) {
                    "Telegram account '$accountLabel' HTTP proxy username and password must be configured together"
                }
                TdApi.Proxy(server, settings.port, TdApi.ProxyTypeHttp(username, password, settings.httpOnly))
            }

            "mtproto" -> {
                require(settings.username.isBlank() && settings.password.isBlank() && settings.passwordFile.isBlank()) {
                    "Telegram account '$accountLabel' MTProto proxy does not accept username or password"
                }
                require(!settings.httpOnly) {
                    "Telegram account '$accountLabel' proxy http-only is only valid for an HTTP proxy"
                }
                val secret = secretResolver.resolve(
                    settings.secret,
                    settings.secretFile,
                    "Telegram account '$accountLabel' MTProto proxy secret",
                    required = true,
                )
                TdApi.Proxy(server, settings.port, TdApi.ProxyTypeMtproto(secret))
            }

            else -> throw IllegalArgumentException(
                "Telegram account '$accountLabel' proxy type must be one of: socks5, http, mtproto",
            )
        }
    }

    private fun proxyPassword(settings: TdLibProperties.Proxy, accountLabel: String): String {
        require(settings.secret.isBlank() && settings.secretFile.isBlank()) {
            "Telegram account '$accountLabel' SOCKS5/HTTP proxy does not accept an MTProto secret"
        }
        return secretResolver.resolve(
            settings.password,
            settings.passwordFile,
            "Telegram account '$accountLabel' proxy password",
        )
    }
}
