package dev.telegrammcp.server.security

import dev.telegrammcp.server.client.McpCallCancellation
import dev.telegrammcp.server.client.McpCallCancelledException
import io.modelcontextprotocol.json.jackson3.JacksonMcpJsonMapper
import io.modelcontextprotocol.server.McpServer
import io.modelcontextprotocol.server.transport.StdioServerTransportProvider
import io.modelcontextprotocol.spec.McpSchema
import org.junit.jupiter.api.Test
import tools.jackson.databind.json.JsonMapper
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.nio.charset.StandardCharsets
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class StdioCancellationTransportTest {
    @Test
    fun `stdio cancellation stops the tool thread and the next call is clean`() {
        val serverIn = PipedInputStream(64 * 1024)
        val clientToServer = PipedOutputStream(serverIn)
        val clientFromServer = PipedInputStream(64 * 1024)
        val serverOut = PipedOutputStream(clientFromServer)
        val lines = java.util.concurrent.LinkedBlockingQueue<String>()
        val reader = Thread({
            BufferedReader(InputStreamReader(clientFromServer, StandardCharsets.UTF_8)).use { input ->
                while (true) {
                    val line = input.readLine() ?: break
                    lines.add(line)
                }
            }
        }, "stdio-test-reader").apply { isDaemon = true; start() }

        val entered = CountDownLatch(1)
        val calls = AtomicInteger()
        val mapper = JacksonMcpJsonMapper(JsonMapper.builder().build())
        val transport = StdioCancellationTransport(StdioServerTransportProvider(mapper, serverIn, serverOut))
        val server = McpServer.sync(transport)
            .serverInfo("stdio-cancel-test", "1")
            .capabilities(McpSchema.ServerCapabilities.builder().tools(false).build())
            .immediateExecution(false)
            .toolCall(
                McpSchema.Tool.builder()
                    .name("block")
                    .description("blocks until the local wait is cancelled")
                    .inputSchema(McpSchema.JsonSchema("object", emptyMap(), emptyList(), false, emptyMap(), emptyMap()))
                    .build(),
            ) { _, _ ->
                val call = calls.incrementAndGet()
                if (call == 1) {
                    entered.countDown()
                    try {
                        McpCallCancellation.await(CompletableFuture<String>(), TimeUnit.SECONDS.toNanos(5))
                        text("still-waiting")
                    } catch (cancelled: McpCallCancelledException) {
                        text("cancelled")
                    }
                } else {
                    assertTrue(!McpCallCancellation.isSignalled(), "a finished call must not leave its signal behind")
                    text("ok")
                }
            }
            .build()
        try {
            send(clientToServer, """{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-06-18","capabilities":{},"clientInfo":{"name":"stdio-cancel-test","version":"1"}}}""")
            val initialized = awaitId(lines, "1")
            assertTrue("error" !in initialized, initialized)
            send(clientToServer, """{"jsonrpc":"2.0","method":"notifications/initialized"}""")
            send(clientToServer, """{"jsonrpc":"2.0","id":7,"method":"tools/call","params":{"name":"block","arguments":{}}}""")
            assertTrue(entered.await(5, TimeUnit.SECONDS), "the tool did not start")
            send(clientToServer, """{"jsonrpc":"2.0","method":"notifications/cancelled","params":{"requestId":7}}""")
            val cancelled = awaitId(lines, "7")
            assertTrue("cancelled" in cancelled, cancelled)
            send(clientToServer, """{"jsonrpc":"2.0","id":"next","method":"tools/call","params":{"name":"block","arguments":{}}}""")
            val next = awaitId(lines, "next")
            assertTrue("ok" in next, next)
            assertEquals(2, calls.get())
        } finally {
            clientToServer.close()
            server.closeGracefully()
            reader.join(2_000)
        }
    }

    private fun text(value: String): McpSchema.CallToolResult =
        McpSchema.CallToolResult.builder().addTextContent(value).build()

    private fun send(output: PipedOutputStream, json: String) {
        output.write(json.toByteArray(StandardCharsets.UTF_8))
        output.write('\n'.code)
        output.flush()
    }

    private fun awaitId(lines: java.util.concurrent.LinkedBlockingQueue<String>, id: String): String {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (System.nanoTime() < deadline) {
            val line = lines.poll(200, TimeUnit.MILLISECONDS) ?: continue
            if (""""id":"$id"""" in line || """"id":$id""" in line) return line
        }
        error("timed out waiting for response id=$id")
    }
}
