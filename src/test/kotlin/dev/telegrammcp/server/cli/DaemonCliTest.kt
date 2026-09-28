package dev.telegrammcp.server.cli

import org.junit.jupiter.api.Test
import kotlin.test.*

class DaemonCliTest {
    @Test fun `PID alone never identifies a managed process`() {
        val current = ProcessHandle.current()
        val record = DaemonRecord(current.pid(), current.info().startInstant().orElseThrow().toString(), 8080, "test")
        assertTrue(DaemonCli.alive(record))
        assertFalse(DaemonCli.alive(record.copy(started = "1970-01-01T00:00:00Z")))
        assertFalse(DaemonCli.alive(record.copy(pid = Long.MAX_VALUE)))
    }
}
