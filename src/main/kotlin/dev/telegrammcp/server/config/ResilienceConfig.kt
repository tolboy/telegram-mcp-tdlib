package dev.telegrammcp.server.config

import io.github.resilience4j.circuitbreaker.CircuitBreaker
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry
import io.github.resilience4j.ratelimiter.RateLimiter
import io.github.resilience4j.ratelimiter.RateLimiterRegistry
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/**
 * Provides named Resilience4j instances for Telegram API calls.
 *
 * Instance configs (`telegram-api`) are driven by `resilience4j.*` keys in
 * application.yml — this class only exposes them as injectable beans.
 */
@Configuration
class ResilienceConfig {

    companion object {
        const val TELEGRAM_API = "telegram-api"
    }

    @Bean
    fun telegramRateLimiter(registry: RateLimiterRegistry): RateLimiter =
        registry.rateLimiter(TELEGRAM_API)

    @Bean
    fun telegramCircuitBreaker(registry: CircuitBreakerRegistry): CircuitBreaker =
        registry.circuitBreaker(TELEGRAM_API)
}
