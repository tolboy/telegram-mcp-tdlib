package dev.telegrammcp.server.client

import java.time.Duration
import kotlinx.coroutines.asContextElement

/**
 * Overall time budget for the Telegram work of one MCP tool call.
 *
 * MCP clients give up on a tool call after a fixed time (60 s by default in
 * the TypeScript SDK, Claude Desktop and others). A tool can make several
 * TDLib requests in a row (rate-limiter wait, send, wait for the final message
 * state, chat-title and sender lookups), so per-request timeouts alone do not
 * bound the call. [run] opens a budget for the current thread; every TDLib
 * wait in [TdLibClientService] is capped at the time that is left, so the
 * whole chain ends within [budget] and the server can still answer with a
 * structured error before the client drops the call.
 *
 * Time spent waiting for an operator in [excluding] (destructive-tool
 * approval, which has its own timeout) is not charged to the budget, so the
 * deadline does not cover it: a call with an approval can take up to the
 * approval timeout plus [budget].
 *
 * Coroutine fan-out carries the same state through [coroutineContext], so
 * workers share the original budget and write history. Background work
 * without an open budget uses the per-request timeouts.
 */
object ToolCallDeadline {

    /** Below the 60 s MCP client timeout, leaving time to deliver the error. */
    val DEFAULT_BUDGET: Duration = Duration.ofSeconds(50)

    /** Default budget, set from `tdlib.requests.tool-deadline` at startup. */
    @Volatile
    var budget: Duration = DEFAULT_BUDGET

    /** Clock used for the budget; replaceable in tests. */
    @Volatile
    internal var nanoTime: () -> Long = System::nanoTime

    private class State(val budgetNanos: Long, val startNanos: Long) {
        @Volatile
        var excludedNanos = 0L
        var excludedSince = -1L
        @Volatile
        var writeDispatched = false
    }

    private val current = ThreadLocal<State?>()

    /** Captures the original call state, rather than starting a fresh budget in each worker. */
    fun coroutineContext(): kotlin.coroutines.CoroutineContext = current.asContextElement()

    /** Runs [block] with a fresh budget; a nested call keeps the outer budget. */
    fun <T> run(budget: Duration = this.budget, block: () -> T): T {
        if (current.get() != null) return block()
        current.set(State(budget.toNanos(), nanoTime()))
        try {
            return block()
        } finally {
            current.remove()
        }
    }

    /** Runs [block] without charging its duration to the open budget, if any. */
    fun <T> excluding(block: () -> T): T {
        val state = current.get()
        if (state == null || state.excludedSince >= 0) return block()
        state.excludedSince = nanoTime()
        try {
            return block()
        } finally {
            state.excludedNanos += nanoTime() - state.excludedSince
            state.excludedSince = -1L
        }
    }

    /** True while a budget is open on this thread. */
    val isActive: Boolean get() = current.get() != null

    /** Nanoseconds left in the open budget (may be <= 0), or null when none is open. */
    fun remainingNanos(): Long? {
        val state = current.get() ?: return null
        val elapsed = nanoTime() - state.startNanos - state.excludedNanos
        return state.budgetNanos - elapsed
    }

    /** The configured budget of the open call, or null when none is open. */
    fun budgetNanos(): Long? = current.get()?.budgetNanos

    /** Records that a request with possible side effects was handed to TDLib during this call. */
    fun markWriteDispatched() {
        current.get()?.writeDispatched = true
    }

    /**
     * True when this call already handed a write to TDLib. A later failure in the
     * same call is then never advertised as safe to retry unchanged.
     */
    fun writeDispatched(): Boolean = current.get()?.writeDispatched == true

}
