package dev.telegrammcp.server.client

import dev.telegrammcp.server.cli.SessionMaintenance
import dev.telegrammcp.server.cli.SessionMaintenanceCli
import dev.telegrammcp.server.config.TdLibProperties
import it.tdlight.jni.TdApi
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ExpectedAccountCheckTest {
    @TempDir lateinit var directory: Path

    private val clock = Clock.fixed(Instant.parse("2026-11-17T12:00:00Z"), ZoneOffset.UTC)

    private fun user(id: Long, phone: String) = TdApi.User().apply {
        this.id = id
        phoneNumber = phone
    }

    private fun check(
        expected: ExpectedAccount,
        recordDirectory: Path? = directory,
        timeout: Duration = Duration.ofSeconds(5),
        retryBackoff: List<Duration> = emptyList(),
        retryTimeout: Duration = timeout,
        budget: Duration? = null,
    ) = ExpectedAccountCheck("work", expected, recordDirectory, timeout, clock, retryBackoff, retryTimeout, budget)

    /** Runs the check and waits for whichever callback fires; fails if both or neither do. */
    private fun runCheck(check: ExpectedAccountCheck, getMe: () -> CompletableFuture<TdApi.User>): Pair<Boolean, AccountCheckRecord> {
        val outcome = AtomicReference<Pair<Boolean, AccountCheckRecord>>()
        val calls = java.util.concurrent.atomic.AtomicInteger()
        val done = CountDownLatch(1)
        check.run(
            getMe = getMe,
            onAccepted = { calls.incrementAndGet(); outcome.set(true to it); done.countDown() },
            onRefused = { calls.incrementAndGet(); outcome.set(false to it); done.countDown() },
        )
        assertTrue(done.await(10, TimeUnit.SECONDS), "check never completed")
        Thread.sleep(50)
        assertEquals(1, calls.get(), "exactly one callback must run")
        return outcome.get()
    }

    @Test
    fun `without an expectation the session is accepted and TDLib is never asked`() {
        val (accepted, record) = runCheck(check(ExpectedAccount.NONE)) { error("getMe must not be called") }
        assertTrue(accepted)
        assertEquals(AccountCheckResult.NOT_CONFIGURED, record.result)
        assertFalse(ExpectedAccount.NONE.configured)
    }

    @Test
    fun `a matching user ID and phone are accepted`() {
        val expected = ExpectedAccount(userId = 4242, phoneNumber = "+1 555 123-4567")
        val (accepted, record) = runCheck(check(expected)) { CompletableFuture.completedFuture(user(4242, "15551234567")) }
        assertTrue(accepted)
        assertEquals(AccountCheckResult.MATCH, record.result)
        assertEquals(4242, record.actualUserId)
    }

    @Test
    fun `a different user ID is refused with a clear message`() {
        val (accepted, record) = runCheck(check(ExpectedAccount(userId = 4242))) {
            CompletableFuture.completedFuture(user(9999, "15551234567"))
        }
        assertFalse(accepted)
        assertEquals(AccountCheckResult.MISMATCH, record.result)
        assertTrue("user ID 9999, not the expected 4242" in record.message, record.message)
        assertTrue("Refusing to serve this session" in record.message, record.message)
        assertTrue("session clear --account work" in record.message, record.message)
    }

    @Test
    fun `a different phone is refused and neither phone appears unmasked`() {
        val record = check(ExpectedAccount(phoneNumber = "+15551234567")).evaluate(user(1, "447700900123"))
        assertEquals(AccountCheckResult.MISMATCH, record.result)
        assertFalse("447700900123" in record.message, record.message)
        assertFalse("15551234567" in record.message, record.message)
        assertEquals("+1********67", record.expectedPhone)
        assertEquals("+4*********23", record.actualPhone)
    }

    @Test
    fun `both expectations must match when both are set`() {
        val expected = ExpectedAccount(userId = 4242, phoneNumber = "+15551234567")
        assertEquals(AccountCheckResult.MISMATCH, check(expected).evaluate(user(4242, "15550000000")).result)
        assertEquals(AccountCheckResult.MISMATCH, check(expected).evaluate(user(1, "15551234567")).result)
        assertEquals(AccountCheckResult.MATCH, check(expected).evaluate(user(4242, "15551234567")).result)
    }

    @Test
    fun `a failed or missing getMe answer fails closed`() {
        val (failedAccepted, failed) = runCheck(check(ExpectedAccount(userId = 4242))) {
            CompletableFuture.failedFuture(IllegalStateException("boom"))
        }
        assertFalse(failedAccepted)
        assertEquals(AccountCheckResult.UNVERIFIED, failed.result)

        val (thrownAccepted, thrown) = runCheck(check(ExpectedAccount(userId = 4242))) { throw IllegalStateException("closed") }
        assertFalse(thrownAccepted)
        assertEquals(AccountCheckResult.UNVERIFIED, thrown.result)

        val (timedOutAccepted, timedOut) = runCheck(check(ExpectedAccount(userId = 4242), timeout = Duration.ofMillis(100))) {
            CompletableFuture()
        }
        assertFalse(timedOutAccepted)
        assertEquals(AccountCheckResult.UNVERIFIED, timedOut.result)
    }

    @Test
    fun `invalid expectations are rejected at configuration time`() {
        assertFailsWith<IllegalArgumentException> { ExpectedAccount(userId = 0) }
        assertFailsWith<IllegalArgumentException> { ExpectedAccount(userId = -5) }
        assertFailsWith<IllegalArgumentException> { ExpectedAccount(phoneNumber = "12") }
        assertEquals(ExpectedAccount(4242, "+15551234567"),
            TdLibProperties.Auth(expectedUserId = 4242, expectedPhoneNumber = " +15551234567 ").expectedAccount())
        assertFalse(TdLibProperties.Auth().expectedAccount().configured)
    }

    @Test
    fun `the result is recorded for session doctor with masked phones only`() {
        val (accepted, _) = runCheck(check(ExpectedAccount(userId = 4242, phoneNumber = "+15551234567"))) {
            CompletableFuture.completedFuture(user(9999, "447700900123"))
        }
        assertFalse(accepted)
        val file = directory.resolve(ExpectedAccountCheck.RECORD_FILE)
        val raw = Files.readString(file)
        assertFalse("447700900123" in raw || "15551234567" in raw, raw)

        val record = assertNotNull(ExpectedAccountCheck.read(directory))
        assertEquals(AccountCheckResult.MISMATCH, record.result)
        assertEquals(Instant.parse("2026-11-17T12:00:00Z"), record.checkedAt)
        assertEquals(9999, record.actualUserId)

        val line = SessionMaintenanceCli.describeAccountCheck(record)
        assertTrue(line.startsWith("MISMATCH ("), line)
        assertTrue("expected_user_id=4242" in line && "actual_user_id=9999" in line, line)
        assertTrue("Refusing to serve this session" in line, line)
        assertFalse("447700900123" in line, line)
        assertTrue(SessionMaintenanceCli.describeAccountCheck(null).startsWith("NOT_RUN"))
    }

    @Test
    fun `session doctor reads the recorded check from the account database directory`() {
        val database = directory.resolve("tdlib").resolve("default")
        Files.createDirectories(database)
        ExpectedAccountCheck("default", ExpectedAccount(userId = 4242), database, clock = clock)
            .run({ CompletableFuture.completedFuture(user(4242, "")) }, {}, {})
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (ExpectedAccountCheck.read(database) == null && System.nanoTime() < deadline) Thread.sleep(20)

        val report = SessionMaintenance(directory, emptyMap()).doctor()
        val account = report.accounts.single { it.label == "default" }
        assertEquals(AccountCheckResult.MATCH, assertNotNull(account.accountCheck).result)
    }

    @Test
    fun `missing, oversized or corrupt records read as no check`() {
        assertNull(ExpectedAccountCheck.read(directory))
        Files.writeString(directory.resolve(ExpectedAccountCheck.RECORD_FILE), "{not json")
        assertNull(ExpectedAccountCheck.read(directory))
        Files.write(directory.resolve(ExpectedAccountCheck.RECORD_FILE), ByteArray(70 * 1024))
        assertNull(ExpectedAccountCheck.read(directory))
        // No directory means nothing is written and nothing throws.
        ExpectedAccountCheck.write(directory.resolve("absent"), check(ExpectedAccount(userId = 1)).evaluate(user(1, "")))
        assertFalse(Files.exists(directory.resolve("absent")))
    }

    @Test
    fun `a missing answer is retried with backoff and a later answer is accepted`() {
        val calls = java.util.concurrent.atomic.AtomicInteger()
        val started = System.nanoTime()
        val (accepted, record) = runCheck(check(ExpectedAccount(userId = 4242), timeout = Duration.ofMillis(100),
            retryBackoff = listOf(Duration.ofMillis(50), Duration.ofMillis(100), Duration.ofMillis(200)))) {
            when (calls.incrementAndGet()) {
                1 -> CompletableFuture.failedFuture(IllegalStateException("not yet"))
                2 -> CompletableFuture() // times out
                else -> CompletableFuture.completedFuture(user(4242, ""))
            }
        }
        assertTrue(accepted)
        assertEquals(AccountCheckResult.MATCH, record.result)
        assertEquals(3, calls.get())
        assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started) >= 150, "backoff must pause between attempts")
    }

    @Test
    fun `retries are bounded and end UNVERIFIED with the attempt count`() {
        val calls = java.util.concurrent.atomic.AtomicInteger()
        val (accepted, record) = runCheck(check(ExpectedAccount(userId = 4242),
            retryBackoff = listOf(Duration.ofMillis(10), Duration.ofMillis(20), Duration.ofMillis(40)))) {
            calls.incrementAndGet()
            CompletableFuture.failedFuture(IllegalStateException("down"))
        }
        assertFalse(accepted)
        assertEquals(AccountCheckResult.UNVERIFIED, record.result)
        assertEquals(4, calls.get())
        assertTrue("after 4 attempts" in record.message, record.message)
    }

    @Test
    fun `a wrong account is refused at once without retrying`() {
        val calls = java.util.concurrent.atomic.AtomicInteger()
        val (accepted, record) = runCheck(check(ExpectedAccount(userId = 4242),
            retryBackoff = listOf(Duration.ofMillis(10), Duration.ofMillis(20)))) {
            calls.incrementAndGet()
            CompletableFuture.completedFuture(user(9999, ""))
        }
        assertFalse(accepted)
        assertEquals(AccountCheckResult.MISMATCH, record.result)
        assertEquals(1, calls.get())
        assertEquals(3, ExpectedAccountCheck.DEFAULT_RETRY_BACKOFF.size)
    }

    @Test
    fun `retries use the shorter retry timeout`() {
        val startedAt = java.util.concurrent.CopyOnWriteArrayList<Long>()
        val (accepted, record) = runCheck(check(ExpectedAccount(userId = 4242), timeout = Duration.ofMillis(1_500),
            retryTimeout = Duration.ofMillis(100), retryBackoff = listOf(Duration.ofMillis(10), Duration.ofMillis(10)))) {
            startedAt += System.nanoTime()
            CompletableFuture<TdApi.User>()
        }
        assertFalse(accepted)
        assertEquals(AccountCheckResult.UNVERIFIED, record.result)
        assertEquals(3, startedAt.size)
        val firstAttempt = TimeUnit.NANOSECONDS.toMillis(startedAt[1] - startedAt[0])
        val retryAttempts = listOf(startedAt[2] - startedAt[1]).map(TimeUnit.NANOSECONDS::toMillis)
        // The first attempt runs its full 1.5s; each retry gets only 100ms. Reusing the first
        // timeout would make every retry gap at least 1.5s, far above the 1s bound.
        assertTrue(firstAttempt >= 1_500, "first attempt took $firstAttempt ms")
        retryAttempts.forEach { assertTrue(it in 100..1_000, "retry attempt took $it ms") }
    }

    @Test
    fun `defaults give the first attempt 15 seconds and every retry 5`() {
        val defaults = ExpectedAccountCheck("work", ExpectedAccount(userId = 4242), null)
        assertEquals(Duration.ofSeconds(15), defaults.attemptTimeout(0))
        (1..ExpectedAccountCheck.DEFAULT_RETRY_BACKOFF.size).forEach { assertEquals(Duration.ofSeconds(5), defaults.attemptTimeout(it)) }
    }

    @Test
    fun `a retry that starts after the budget ran out is refused without calling getMe`() {
        val calls = java.util.concurrent.atomic.AtomicInteger()
        // A busy executor: the retry was scheduled with time to spare but only runs after the deadline.
        val lateScheduler: (Duration, Runnable) -> Unit = { _, task ->
            Thread { Thread.sleep(1_300); task.run() }.start()
        }
        val check = ExpectedAccountCheck("work", ExpectedAccount(userId = 4242), directory, Duration.ofMillis(50), clock,
            retryBackoff = listOf(Duration.ofMillis(10)), retryTimeout = Duration.ofSeconds(5),
            budget = Duration.ofMillis(1_200), scheduleRetry = lateScheduler)
        val (accepted, record) = runCheck(check) {
            calls.incrementAndGet()
            CompletableFuture.failedFuture(IllegalStateException("not yet"))
        }
        assertFalse(accepted)
        assertEquals(1, calls.get(), "the late retry must not call getMe")
        assertEquals(AccountCheckResult.UNVERIFIED, record.result)
        assertTrue("time budget ran out" in record.message, record.message)
    }

    @Test
    fun `the deadline is reported before the first attempt so waiters can share it`() {
        val reported = java.util.concurrent.atomic.AtomicLong()
        val before = System.nanoTime()
        val check = check(ExpectedAccount(userId = 4242), budget = Duration.ofSeconds(2))
        val done = CountDownLatch(1)
        check.run({
            assertTrue(reported.get() != 0L, "deadline must be reported before GetMe")
            CompletableFuture.completedFuture(user(4242, ""))
        }, { done.countDown() }, { done.countDown() }, onDeadline = reported::set)
        assertTrue(done.await(5, TimeUnit.SECONDS))
        val offset = TimeUnit.NANOSECONDS.toMillis(reported.get() - before)
        assertTrue(offset in 1_900..2_100, "deadline is about 2s out: $offset ms")

        val unconfigured = java.util.concurrent.atomic.AtomicBoolean()
        check(ExpectedAccount.NONE, budget = Duration.ofSeconds(2)).run({ error("unused") }, {}, {}, onDeadline = { unconfigured.set(true) })
        assertFalse(unconfigured.get(), "no check, no deadline")
    }

    @Test
    fun `the whole check stays within its budget and skips retries that cannot fit`() {
        val calls = java.util.concurrent.atomic.AtomicInteger()
        val started = System.nanoTime()
        // A 1.5s budget: the first attempt is cut to 1.5s, and no retry fits the 1s minimum after a 200ms pause.
        val (accepted, record) = runCheck(check(ExpectedAccount(userId = 4242), timeout = Duration.ofSeconds(30),
            retryTimeout = Duration.ofSeconds(30), retryBackoff = listOf(Duration.ofMillis(200), Duration.ofMillis(200)),
            budget = Duration.ofMillis(1_500))) {
            calls.incrementAndGet()
            CompletableFuture()
        }
        val elapsed = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)
        assertFalse(accepted)
        assertEquals(AccountCheckResult.UNVERIFIED, record.result)
        assertEquals(1, calls.get())
        assertTrue(elapsed < 3_000, "took $elapsed ms")
        assertTrue("after 1 attempt " in record.message, record.message)
    }

    @Test
    fun `a budget leaves room for retries that fit`() {
        val calls = java.util.concurrent.atomic.AtomicInteger()
        val (accepted, _) = runCheck(check(ExpectedAccount(userId = 4242), timeout = Duration.ofMillis(100),
            retryTimeout = Duration.ofMillis(100), retryBackoff = listOf(Duration.ofMillis(10)), budget = Duration.ofSeconds(5))) {
            if (calls.incrementAndGet() == 1) CompletableFuture.failedFuture(IllegalStateException("not yet"))
            else CompletableFuture.completedFuture(user(4242, ""))
        }
        assertTrue(accepted)
        assertEquals(2, calls.get())
    }

    @Test
    fun `default timeouts fit the default ready-timeout budget`() {
        val budget = ExpectedAccountCheck.budgetFor(Duration.ofSeconds(45))
        assertEquals(Duration.ofSeconds(40), budget)
        val worstCase = Duration.ofSeconds(15) + ExpectedAccountCheck.DEFAULT_RETRY_BACKOFF.fold(Duration.ZERO) { sum, pause ->
            sum + pause + Duration.ofSeconds(5)
        }
        assertTrue(worstCase < budget, "worst case $worstCase must fit $budget")
        assertEquals(Duration.ofSeconds(8), ExpectedAccountCheck.budgetFor(Duration.ofSeconds(10)))
        assertTrue(ExpectedAccountCheck.budgetFor(TdLibProperties.Auth().readyTimeout) < TdLibProperties.Auth().readyTimeout)
    }
}
