package dev.telegrammcp.server.config

import dev.telegrammcp.server.exception.InvalidToolInputException
import dev.telegrammcp.server.exception.TelegramApiException
import io.github.resilience4j.circuitbreaker.CircuitBreaker
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry
import io.github.resilience4j.ratelimiter.RateLimiter
import io.github.resilience4j.ratelimiter.RateLimiterRegistry
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.util.function.Predicate

/**
 * Provides named Resilience4j instances for Telegram API calls.
 *
 * Instance configs (`telegram-api`) are driven by `resilience4j.*` keys in
 * application.yml. The circuit breaker additionally ignores caller errors
 * (see [TelegramCallerErrorPredicate]) so that bad arguments or missing
 * rights cannot open the breaker and block every account operation.
 */
@Configuration
class ResilienceConfig {

    companion object {
        const val TELEGRAM_API = "telegram-api"

        /** Copies [base] and additionally ignores Telegram caller errors. */
        fun ignoringCallerErrors(base: CircuitBreakerConfig): CircuitBreakerConfig {
            val existing = base.ignoreExceptionPredicate
            val callerError = TelegramCallerErrorPredicate()
            return CircuitBreakerConfig.from(base)
                .ignoreException { existing.test(it) || callerError.test(it) }
                .build()
        }
    }

    @Bean
    fun telegramRateLimiter(registry: RateLimiterRegistry): RateLimiter =
        registry.rateLimiter(TELEGRAM_API)

    @Bean
    fun telegramCircuitBreaker(registry: CircuitBreakerRegistry): CircuitBreaker {
        // The Spring Boot starter may already have created the instance from
        // application.yml; replace it so metrics and health see the same breaker.
        val configured = registry.circuitBreaker(TELEGRAM_API)
        val breaker = CircuitBreaker.of(TELEGRAM_API, ignoringCallerErrors(configured.circuitBreakerConfig), configured.tags)
        registry.replace(TELEGRAM_API, breaker)
        return breaker
    }
}

/**
 * True for failures caused by the request rather than by Telegram's health:
 * TDLib 4xx rejections (400/401/403/404, ...) and local argument validation.
 * Flood control (420/429, FLOOD_WAIT_N) is deliberately NOT ignored: it is
 * real back-pressure and must count toward opening the breaker.
 */
class TelegramCallerErrorPredicate : Predicate<Throwable> {
    override fun test(error: Throwable): Boolean = when (error) {
        is TelegramApiException -> error.isCallerError
        is InvalidToolInputException -> true
        else -> false
    }
}
