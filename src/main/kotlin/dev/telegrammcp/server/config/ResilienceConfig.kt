package dev.telegrammcp.server.config

import dev.telegrammcp.server.exception.InvalidToolInputException
import dev.telegrammcp.server.exception.SendOutcomeUnknownException
import dev.telegrammcp.server.exception.TelegramApiException
import dev.telegrammcp.server.exception.TelegramErrorKind
import dev.telegrammcp.server.exception.TelegramFloodGateException
import dev.telegrammcp.server.exception.TelegramTimeoutException
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
 * application.yml. The circuit breaker additionally ignores failures that say
 * nothing about Telegram's health (see [TelegramBreakerExclusionPredicate]):
 * caller errors, flood waits and cancellations must not open the breaker and
 * block every operation of the account.
 */
@Configuration
class ResilienceConfig {

    companion object {
        const val TELEGRAM_API = "telegram-api"

        /** Copies [base] and additionally ignores failures that do not reflect Telegram's health. */
        fun ignoringNonHealthFailures(base: CircuitBreakerConfig): CircuitBreakerConfig {
            val existing = base.ignoreExceptionPredicate
            val excluded = TelegramBreakerExclusionPredicate()
            return CircuitBreakerConfig.from(base)
                .ignoreException { existing.test(it) || excluded.test(it) }
                .build()
        }
    }

    @Bean
    fun telegramRateLimiter(registry: RateLimiterRegistry): RateLimiter =
        registry.rateLimiter(TELEGRAM_API)

    @Bean
    fun telegramCircuitBreaker(registry: CircuitBreakerRegistry): CircuitBreaker {
        // registry.circuitBreaker(TELEGRAM_API) returns (creating if needed) the
        // instance built from the `telegram-api` properties. Its config cannot be
        // changed in place, so register a replacement under the same name with the
        // extended ignore rules; metrics and health then report the breaker in use.
        val configured = registry.circuitBreaker(TELEGRAM_API)
        val breaker = CircuitBreaker.of(TELEGRAM_API, ignoringNonHealthFailures(configured.circuitBreakerConfig), configured.tags)
        registry.replace(TELEGRAM_API, breaker)
        return breaker
    }
}

/**
 * True for failures that do not indicate an unhealthy Telegram connection:
 * - TDLib errors whose [TelegramErrorKind] does not count toward the breaker
 *   (caller 4xx errors, fixed 500 outcomes, flood waits, cancellations);
 * - local flood-gate rejections (nothing was sent);
 * - local timeouts that end because the tool call's overall deadline ran out
 *   (the wait was shorter than the per-request timeout) and DownloadFile
 *   timeouts (a large file is slow, not a sign of an unhealthy connection);
 *   a full per-request timeout still counts unless TdLibTimeoutHealth found
 *   TDLib healthy (connection Ready, other requests answered, see there);
 * - local argument validation.
 *
 * Flood waits are excluded because TDLib already waited out 420/SLOWMODE
 * internally and a 429 is usually scoped to one chat or method; the per-chat
 * [dev.telegrammcp.server.client.TelegramFloodGate] handles them instead.
 */
class TelegramBreakerExclusionPredicate : Predicate<Throwable> {
    override fun test(error: Throwable): Boolean = when (error) {
        is TelegramApiException -> !error.countsTowardBreaker
        is TelegramFloodGateException -> true
        is TelegramTimeoutException -> !error.countsTowardBreaker
        is SendOutcomeUnknownException -> error.deadlineExceeded || !error.healthFailure
        is InvalidToolInputException -> true
        else -> false
    }
}
