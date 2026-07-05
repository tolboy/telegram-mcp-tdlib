package dev.telegrammcp.server.service

import dev.telegrammcp.server.client.TelegramAccountContext
import dev.telegrammcp.server.client.TelegramAccountRegistry
import dev.telegrammcp.server.config.AntiSpamProperties
import dev.telegrammcp.server.model.ParseMode
import dev.telegrammcp.server.util.StructuredLogger
import jakarta.annotation.PostConstruct
import jakarta.annotation.PreDestroy
import org.springframework.stereotype.Component
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

/**
 * Periodically drains [AntiSpamGuardService.popPendingEvents] and posts a single
 * digest message to each registered admin chat. Notifications themselves run
 * inside [AntiSpamGuardService.withBypass] so the notifier cannot be throttled
 * by its own guard.
 *
 * If no internal chats are registered yet (the host has not called
 * `register_internal_chat`), events stay queued; nothing is lost as long as
 * the bounded buffer in the guard isn't exceeded.
 *
 * The notifier owns a private single-thread [ScheduledExecutorService] —
 * Spring's `@EnableScheduling` is intentionally **not** used because it
 * registers a global `taskScheduler` that collides with the MVC
 * `applicationTaskExecutor` when both are eligible candidates for a
 * `TaskExecutor` injection.
 */
@Component
class AntiSpamNotifier(
    private val antiSpamGuardService: AntiSpamGuardService,
    private val accountRegistry: TelegramAccountRegistry,
    private val accountContext: TelegramAccountContext,
    private val props: AntiSpamProperties,
) {

    private val log = StructuredLogger.forClass<AntiSpamNotifier>()

    private val timeFormatter: DateTimeFormatter = DateTimeFormatter
        .ofPattern("HH:mm:ss")
        .withZone(ZoneId.systemDefault())

    private val executor: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor { runnable ->
        Thread(runnable, "anti-spam-notifier").apply { isDaemon = true }
    }

    @PostConstruct
    fun start() {
        if (!props.enabled || !props.notifier.enabled) {
            log.info("Anti-spam notifier disabled (enabled={}, notifier.enabled={})", props.enabled, props.notifier.enabled)
            return
        }
        val intervalSeconds = props.notifier.intervalSeconds.coerceAtLeast(5L)
        log.info("Anti-spam notifier scheduled every {}s", intervalSeconds)
        executor.scheduleWithFixedDelay(
            ::dispatchDigestSafe,
            intervalSeconds,
            intervalSeconds,
            TimeUnit.SECONDS,
        )
    }

    @PreDestroy
    fun stop() {
        executor.shutdownNow()
    }

    private fun dispatchDigestSafe() {
        try {
            dispatchDigest()
        } catch (error: Throwable) {
            log.warn("Anti-spam notifier digest job failed: {}", error.message, error)
        }
    }

    private fun dispatchDigest() {
        if (!props.enabled || !props.notifier.enabled) return

        accountRegistry.labels().forEach(::dispatchAccountDigest)
    }

    private fun dispatchAccountDigest(account: String) {
        val targets = antiSpamGuardService.internalChatIds(account)
        if (targets.isEmpty()) return // Keep the matching account's events queued.

        val events = antiSpamGuardService.popPendingEvents(account, props.notifier.maxEventsPerNotification)
        if (events.isEmpty()) return

        val text = renderDigest(account, events)
        antiSpamGuardService.withBypass {
            accountContext.withAccount(account) {
                val telegramClient = accountRegistry.require(account)
                for (chatId in targets) {
                    runCatching {
                        telegramClient.sendMessage(
                            chatId = chatId,
                            text = text,
                            parseMode = ParseMode.PLAIN,
                        )
                    }.onFailure { error ->
                        log.warn(
                            "Anti-spam notifier could not deliver digest for account '{}' to chat {}: {}",
                            account, chatId, error.message,
                        )
                    }
                }
            }
        }
    }

    private fun renderDigest(account: String, events: List<AntiSpamGuardService.ThrottleEvent>): String {
        val now = Instant.now()
        val grouped = events.groupBy { it.toolName to it.reason }
        val sb = StringBuilder()
        sb.append("⚠️ Anti-spam guard blocked ")
        sb.append(events.size)
        sb.append(if (events.size == 1) " operation" else " operations")
        sb.append(" for account '")
        sb.append(account)
        sb.append("'")
        sb.append(":\n\n")
        for ((key, group) in grouped.entries.sortedByDescending { it.value.size }) {
            val (tool, reason) = key
            val externalCount = group.count { it.external }
            val maxAge = group.maxOf { Duration.between(it.timestamp, now).seconds }
            sb.append("• ")
            sb.append(tool)
            sb.append(" × ")
            sb.append(group.size)
            if (externalCount > 0) {
                sb.append(" (external: ")
                sb.append(externalCount)
                sb.append(")")
            }
            sb.append(" — ")
            sb.append(reason)
            if (maxAge > 0) {
                sb.append(" — latest ")
                sb.append(maxAge)
                sb.append("s ago")
            }
            sb.append("\n")
        }
        sb.append("\nTime: ")
        sb.append(timeFormatter.format(now))
        sb.append(". Review the logs and client behavior; if this is legitimate activity, adjust anti-spam.rules.")
        return sb.toString()
    }
}
