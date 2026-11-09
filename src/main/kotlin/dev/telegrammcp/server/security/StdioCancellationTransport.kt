package dev.telegrammcp.server.security

import com.fasterxml.jackson.databind.JsonNode
import dev.telegrammcp.server.client.McpCallCancellation
import io.modelcontextprotocol.json.jackson3.JacksonMcpJsonMapper
import io.modelcontextprotocol.server.McpInitRequestHandler
import io.modelcontextprotocol.server.McpNotificationHandler
import io.modelcontextprotocol.server.McpRequestHandler
import io.modelcontextprotocol.server.transport.StdioServerTransportProvider
import io.modelcontextprotocol.spec.McpSchema
import io.modelcontextprotocol.spec.McpServerSession
import io.modelcontextprotocol.spec.McpServerTransport
import io.modelcontextprotocol.spec.McpServerTransportProvider
import java.time.Duration
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Profile
import reactor.core.publisher.Mono
import tools.jackson.databind.json.JsonMapper

/**
 * STDIO has no servlet filter. The SDK reads frames on its own thread and runs
 * the tool on an elastic scheduler, and its public builder cannot register
 * `notifications/cancelled`. This transport wraps the session the SDK creates
 * so the notification wakes the same local signal HTTP uses, and so the tool
 * thread is bound to that JSON-RPC id before the handler runs.
 */
internal class StdioCancellationTransport(
    private val delegate: StdioServerTransportProvider,
) : McpServerTransportProvider {

    override fun setSessionFactory(sessionFactory: McpServerSession.Factory) {
        McpCallCancellation.installWorkerPropagation()
        delegate.setSessionFactory { transport ->
            StdioCancellationSession(sessionFactory.create(transport))
        }
    }

    override fun notifyClients(method: String, params: Any?): Mono<Void> = delegate.notifyClients(method, params)

    override fun notifyClient(sessionId: String, method: String, params: Any?): Mono<Void> =
        delegate.notifyClient(sessionId, method, params)

    override fun closeGracefully(): Mono<Void> = delegate.closeGracefully()

    override fun close() = delegate.close()

    override fun protocolVersions(): List<String> = delegate.protocolVersions()
}

/**
 * Forwards one already-built STDIO session. [handle] is the only behavior that
 * differs: cancellation is consumed here, and a tool call is bound before the
 * SDK hops threads.
 */
internal class StdioCancellationSession(
    private val delegate: McpServerSession,
) : McpServerSession(
    "stdio-cancellation",
    Duration.ofSeconds(1),
    UnusedStdioTransport,
    McpInitRequestHandler { Mono.empty() },
    emptyMap<String, McpRequestHandler<*>>(),
    emptyMap<String, McpNotificationHandler>(),
) {
    override fun getId(): String = delegate.getId()

    override fun init(clientCapabilities: McpSchema.ClientCapabilities, clientInfo: McpSchema.Implementation) {
        delegate.init(clientCapabilities, clientInfo)
    }

    override fun setMinLoggingLevel(minLoggingLevel: McpSchema.LoggingLevel) {
        delegate.setMinLoggingLevel(minLoggingLevel)
    }

    override fun isNotificationForLevelAllowed(loggingLevel: McpSchema.LoggingLevel): Boolean =
        delegate.isNotificationForLevelAllowed(loggingLevel)

    override fun <T : Any> sendRequest(method: String, requestParams: Any?, typeRef: io.modelcontextprotocol.json.TypeRef<T>): Mono<T> =
        delegate.sendRequest(method, requestParams, typeRef)

    override fun sendNotification(method: String, params: Any?): Mono<Void> = delegate.sendNotification(method, params)

    override fun closeGracefully(): Mono<Void> = delegate.closeGracefully()

    override fun close() = delegate.close()

    override fun handle(message: McpSchema.JSONRPCMessage): Mono<Void> {
        if (message is McpSchema.JSONRPCNotification && message.method() == McpCancellationFilter.METHOD) {
            McpCancellationFilter.canonicalId(requestId(message.params()))?.let { id ->
                McpCallCancellation.cancel(McpCancellationFilter.key(McpCancellationFilter.STDIO_SESSION, id))
            }
            return Mono.empty()
        }
        val callId = (message as? McpSchema.JSONRPCRequest)
            ?.takeIf { it.method() == "tools/call" }
            ?.let { McpCancellationFilter.canonicalId(it.id()) }
        if (callId == null) return delegate.handle(message)
        val key = McpCancellationFilter.key(McpCancellationFilter.STDIO_SESSION, callId)
        return Mono.defer {
            val flag = McpCallCancellation.bind(key)
            // FluxSubscribeOnCallable schedules from inside subscribe(), after it has
            // signalled onSubscribe. Detach only after subscribe() returns so the
            // worker hook still sees the flag, then the reader can take the next frame.
            delegate.handle(message)
                .doFinally { McpCallCancellation.release(key, flag) }
                .requestThen { McpCallCancellation.detachThread() }
        }
    }

    private fun requestId(params: Any?): Any? = when (params) {
        is Map<*, *> -> params["requestId"]
        is JsonNode -> params.get("requestId")
        else -> null
    }
}

private fun Mono<Void>.requestThen(action: () -> Unit): Mono<Void> = Mono.create { sink ->
    subscribe(
        { _: Void? -> },
        { error -> sink.error(error) },
        { sink.success() },
        { subscription ->
            sink.onDispose { subscription.cancel() }
            subscription.request(Long.MAX_VALUE)
        },
    )
    action()
}

private object UnusedStdioTransport : McpServerTransport {
    override fun sendMessage(message: McpSchema.JSONRPCMessage): Mono<Void> =
        Mono.error(IllegalStateException("stdio cancellation session does not own a transport"))

    override fun <T : Any> unmarshalFrom(data: Any, typeRef: io.modelcontextprotocol.json.TypeRef<T>): T =
        error("stdio cancellation session does not own a transport")

    override fun closeGracefully(): Mono<Void> = Mono.empty()
}

@Configuration
@Profile("stdio")
class McpStdioCancellationConfiguration {
    @Bean
    fun stdioServerTransport(
        @Qualifier("mcpServerJsonMapper") mapper: JsonMapper,
    ): McpServerTransportProvider =
        StdioCancellationTransport(StdioServerTransportProvider(JacksonMcpJsonMapper(mapper)))
}
