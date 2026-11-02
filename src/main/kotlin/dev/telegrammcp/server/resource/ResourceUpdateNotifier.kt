package dev.telegrammcp.server.resource

import dev.telegrammcp.server.config.McpResourceProperties
import dev.telegrammcp.server.service.ChangeJournal
import dev.telegrammcp.server.util.StructuredLogger
import io.modelcontextprotocol.server.McpSyncServer
import io.modelcontextprotocol.spec.McpSchema
import io.modelcontextprotocol.spec.McpStreamableServerTransportProvider
import org.springframework.beans.factory.ObjectProvider
import org.springframework.context.SmartLifecycle
import org.springframework.core.env.Environment
import org.springframework.core.env.Profiles
import org.springframework.stereotype.Component
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

/** Where updated URIs go: HTTP sessions from [ResourceSubscriptionRegistry], or the single STDIO client. */
interface ResourceUpdateSink {
    /** Cheap pre-filter so unrelated chat traffic is never queued. */
    fun isWatched(uri: String): Boolean

    /** URIs a coverage gap for [account] invalidates. */
    fun watchedForAccount(account: String): Set<String>

    /**
     * Sends [uri] to its permitted subscribers. [sourceChats] names the chats whose
     * changes touched an account-wide `chats` URI; such a URI reaches only subscribers
     * that may read at least one of them. Null means the change is not chat-specific
     * (a coverage gap) or the URI names its chat itself.
     */
    fun deliver(uri: String, sourceChats: Set<Long>? = null)
}

/**
 * True unless [resource] is a `chats` list and none of [sourceChats] would appear
 * in it for the caller. [permitsChat] judges a chat in the list's own context
 * (`list_chats`), see [TelegramResourceAccess.permitsChatWithin].
 */
internal fun sourcePermitted(resource: TelegramResourceUri, sourceChats: Set<Long>?, permitsChat: (Long) -> Boolean): Boolean =
    resource !is TelegramResourceUri.Chats || sourceChats == null || sourceChats.any(permitsChat)

/** Each subscribed session is re-authorized with the identity that subscribed before it is told anything. */
class HttpResourceUpdateSink(
    private val registry: ResourceSubscriptionRegistry,
    private val access: TelegramResourceAccess,
    private val transport: () -> McpStreamableServerTransportProvider?,
) : ResourceUpdateSink {
    private val log = StructuredLogger.forClass<HttpResourceUpdateSink>()

    override fun isWatched(uri: String) = registry.isSubscribed(uri)

    override fun watchedForAccount(account: String) = registry.urisForAccount(account)

    override fun deliver(uri: String, sourceChats: Set<Long>?) {
        val resource = TelegramResourceUri.parse(uri) ?: return
        val provider = transport() ?: return
        registry.subscribers(uri).forEach { subscriber ->
            if (!access.permits(resource, subscriber.authentication)) return@forEach
            // A chat-list update caused only by chats outside the grant would leak their activity.
            if (!sourcePermitted(resource, sourceChats) { access.permitsChatWithin(resource, it, subscriber.authentication) }) return@forEach
            provider.notifyClient(subscriber.sessionId, McpSchema.METHOD_NOTIFICATION_RESOURCES_UPDATED,
                McpSchema.ResourcesUpdatedNotification(uri))
                .subscribe({}, { failure -> log.debug("Resource update was not delivered: {}", failure.javaClass.simpleName) })
        }
    }
}

/**
 * STDIO serves one process-isolated client. The SDK tracks that client's
 * subscriptions privately and sends only to subscribers, so every readable
 * candidate URI is handed to it.
 */
class StdioResourceUpdateSink(
    private val access: TelegramResourceAccess,
    private val server: () -> McpSyncServer?,
) : ResourceUpdateSink {
    private val log = StructuredLogger.forClass<StdioResourceUpdateSink>()

    override fun isWatched(uri: String) = true

    // The SDK's subscription list is private; a gap refreshes the chat list only.
    override fun watchedForAccount(account: String) = setOf(TelegramResourceUri.Chats(account).toString())

    override fun deliver(uri: String, sourceChats: Set<Long>?) {
        val resource = TelegramResourceUri.parse(uri) ?: return
        if (!access.permits(resource, null)) return
        if (!sourcePermitted(resource, sourceChats) { access.permitsChatWithin(resource, it, null) }) return
        try {
            server()?.notifyResourcesUpdated(McpSchema.ResourcesUpdatedNotification(uri))
        } catch (failure: Exception) {
            log.debug("Resource update was not delivered: {}", failure.javaClass.simpleName)
        }
    }
}

/**
 * Turns change-journal events into `notifications/resources/updated`.
 *
 * Changes to one URI are coalesced: the first change opens a [McpResourceProperties.debounce]
 * window and one notification goes out when it closes. Notifications carry only the URI;
 * clients read the resource again through the normal permission checks.
 */
class ResourceUpdateNotifier(
    private val sink: ResourceUpdateSink,
    private val debounceNanos: Long,
    private val maxPending: Int = MAX_PENDING,
) : ChangeJournal.Listener {
    private val log = StructuredLogger.forClass<ResourceUpdateNotifier>()
    /** First change time and the chats that caused it; null chats once a non-chat-specific change joined. */
    private class Pending(val since: Long, var sourceChats: MutableSet<Long>?)

    private val pending = LinkedHashMap<String, Pending>()
    private var droppedSinceLastFlush = false

    override fun onChange(account: String, kind: String, chatId: Long?, messageId: Long?) {
        if (kind != ChangeJournal.GAP && chatId == null) return // no source chat to authorize against
        enqueue(urisFor(account, kind, chatId, messageId), System.nanoTime(), chatId)
    }

    internal fun urisFor(account: String, kind: String, chatId: Long?, messageId: Long?): Set<String> {
        if (kind == ChangeJournal.GAP) return sink.watchedForAccount(account)
        val chats = TelegramResourceUri.Chats(account).toString()
        val chat = chatId?.let { TelegramResourceUri.Chat(account, it).toString() }
        val message = if (chatId != null && messageId != null && messageId > 0) {
            TelegramResourceUri.Message(account, chatId, messageId).toString()
        } else null
        return when (kind) {
            "new" -> setOfNotNull(chat, chats)
            "content_changed", "edited" -> setOfNotNull(chat, message)
            "deleted" -> setOfNotNull(chat, message, chats)
            else -> emptySet()
        }
    }

    /** Queues [uris]; [sourceChat] is the chat that changed, or null for a non-chat-specific change. */
    @Synchronized
    internal fun enqueue(uris: Collection<String>, now: Long, sourceChat: Long? = null) {
        uris.forEach { uri ->
            val queued = pending[uri]
            if (queued != null) {
                val chats = queued.sourceChats ?: return@forEach
                if (sourceChat == null) queued.sourceChats = null
                else if (chats.size < MAX_SOURCE_CHATS) chats += sourceChat // beyond the cap, fail closed
                return@forEach
            }
            if (!sink.isWatched(uri)) return@forEach
            if (pending.size >= maxPending) { droppedSinceLastFlush = true; return@forEach }
            pending[uri] = Pending(now, sourceChat?.let { mutableSetOf(it) })
        }
    }

    /** Delivers URIs whose debounce window has closed. */
    internal fun flush(now: Long) {
        val due = synchronized(this) {
            val ready = pending.filterValues { now - it.since >= debounceNanos }.map { (uri, entry) -> uri to entry.sourceChats?.toSet() }
            ready.forEach { pending.remove(it.first) }
            if (droppedSinceLastFlush) { droppedSinceLastFlush = false; log.warn("Resource update backlog was full; some updates were dropped") }
            ready
        }
        due.forEach { (uri, sourceChats) ->
            try { sink.deliver(uri, sourceChats) } catch (failure: Exception) {
                log.debug("Resource update delivery failed: {}", failure.javaClass.simpleName)
            }
        }
    }

    @Synchronized
    internal fun pendingCount(): Int = pending.size

    companion object {
        const val MAX_PENDING = 10_000

        /** Distinct source chats remembered per pending URI; later ones are not considered (fail closed). */
        const val MAX_SOURCE_CHATS = 256
        const val FLUSH_INTERVAL_MILLIS = 200L
    }
}

/** Wires the notifier to the journal and runs its flush loop while the server runs. */
@Component
class ResourceUpdateNotifierLifecycle(
    private val properties: McpResourceProperties,
    private val journal: ChangeJournal,
    private val access: TelegramResourceAccess,
    private val registry: ResourceSubscriptionRegistry,
    private val environment: Environment,
    private val transport: ObjectProvider<McpStreamableServerTransportProvider>,
    private val server: ObjectProvider<McpSyncServer>,
) : SmartLifecycle {
    @Volatile private var scheduler: ScheduledExecutorService? = null

    @Volatile private var notifier: ResourceUpdateNotifier? = null

    /** The active notifier, for tests that drive the debounce clock directly. */
    internal fun notifier(): ResourceUpdateNotifier? = notifier

    @Synchronized
    override fun start() {
        if (!properties.subscriptionsActive || scheduler != null) return
        val sink = if (environment.acceptsProfiles(Profiles.of("stdio"))) {
            StdioResourceUpdateSink(access) { server.ifAvailable }
        } else {
            HttpResourceUpdateSink(registry, access) { transport.ifAvailable }
        }
        val created = notifier ?: ResourceUpdateNotifier(sink, properties.debounce.toNanos()).also {
            notifier = it
            journal.addListener(it)
        }
        scheduler = Executors.newSingleThreadScheduledExecutor { task ->
            Thread(task, "mcp-resource-updates").apply { isDaemon = true }
        }.also {
            it.scheduleWithFixedDelay({ created.flush(System.nanoTime()) },
                ResourceUpdateNotifier.FLUSH_INTERVAL_MILLIS, ResourceUpdateNotifier.FLUSH_INTERVAL_MILLIS, TimeUnit.MILLISECONDS)
        }
    }

    @Synchronized
    override fun stop() {
        scheduler?.shutdownNow()
        scheduler = null
    }

    override fun isRunning() = scheduler != null
}
