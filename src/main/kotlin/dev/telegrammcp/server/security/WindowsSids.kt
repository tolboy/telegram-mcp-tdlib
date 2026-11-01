package dev.telegrammcp.server.security

import org.slf4j.LoggerFactory
import java.lang.foreign.Arena
import java.lang.foreign.FunctionDescriptor
import java.lang.foreign.Linker
import java.lang.foreign.MemorySegment
import java.lang.foreign.SymbolLookup
import java.lang.foreign.ValueLayout
import java.lang.invoke.MethodHandle
import java.nio.charset.StandardCharsets
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Account name → string SID on Windows, through `advapi32` via the FFM API.
 *
 * The JDK exposes ACL principals only by (localized) name; its internal SID
 * is not reachable without `--add-opens`. Any failure returns null so callers
 * fall back to the English-name check instead of failing startup.
 */
internal object WindowsSids {

    private val log = LoggerFactory.getLogger(WindowsSids::class.java)
    private val warned = AtomicBoolean(false)

    val available: Boolean by lazy {
        System.getProperty("os.name").orEmpty().startsWith("Windows", ignoreCase = true) && functions != null
    }

    fun lookup(accountName: String): String? {
        val native = functions ?: return null
        return runCatching { native.lookup(accountName) }
            .onFailure { warnOnce("SID lookup failed ({}); falling back to English group names", it.toString()) }
            .getOrNull()
    }

    private fun warnOnce(message: String, detail: String) {
        if (warned.compareAndSet(false, true)) log.warn(message, detail)
    }

    private val functions: Functions? by lazy {
        if (!System.getProperty("os.name").orEmpty().startsWith("Windows", ignoreCase = true)) return@lazy null
        runCatching { Functions() }
            .onFailure { warnOnce("Windows SID lookup is unavailable ({}); falling back to English group names", it.toString()) }
            .getOrNull()
    }

    private class Functions {
        private val linker = Linker.nativeLinker()
        private val advapi = SymbolLookup.libraryLookup("Advapi32", Arena.global())
        private val kernel = SymbolLookup.libraryLookup("Kernel32", Arena.global())

        /** BOOL LookupAccountNameW(LPCWSTR, LPCWSTR, PSID, LPDWORD, LPWSTR, LPDWORD, PSID_NAME_USE) */
        private val lookupAccountName: MethodHandle = linker.downcallHandle(
            advapi.find("LookupAccountNameW").orElseThrow(),
            FunctionDescriptor.of(
                ValueLayout.JAVA_INT,
                ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS,
                ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS,
            ),
        )

        /** BOOL ConvertSidToStringSidW(PSID, LPWSTR*) */
        private val convertSidToStringSid: MethodHandle = linker.downcallHandle(
            advapi.find("ConvertSidToStringSidW").orElseThrow(),
            FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.ADDRESS),
        )

        /** HLOCAL LocalFree(HLOCAL) */
        private val localFree: MethodHandle = linker.downcallHandle(
            kernel.find("LocalFree").orElseThrow(),
            FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.ADDRESS),
        )

        fun lookup(accountName: String): String? = Arena.ofConfined().use { arena ->
            val name = arena.allocateFrom(accountName, StandardCharsets.UTF_16LE)
            // Fixed buffers sized for the largest SID and domain name avoid the
            // GetLastError-based two-call protocol.
            val sid = arena.allocate(SECURITY_MAX_SID_SIZE)
            val sidSize = arena.allocate(ValueLayout.JAVA_INT)
            sidSize.set(ValueLayout.JAVA_INT, 0, SECURITY_MAX_SID_SIZE.toInt())
            val domain = arena.allocate(DOMAIN_CHARS * 2)
            val domainChars = arena.allocate(ValueLayout.JAVA_INT)
            domainChars.set(ValueLayout.JAVA_INT, 0, DOMAIN_CHARS.toInt())
            val use = arena.allocate(ValueLayout.JAVA_INT)
            val found = lookupAccountName.invoke(
                MemorySegment.NULL, name, sid, sidSize, domain, domainChars, use,
            ) as Int
            if (found == 0) return@use null

            val stringSidOut = arena.allocate(ValueLayout.ADDRESS)
            val converted = convertSidToStringSid.invoke(sid, stringSidOut) as Int
            if (converted == 0) return@use null
            val stringSid = stringSidOut.get(ValueLayout.ADDRESS, 0)
            try {
                stringSid.reinterpret(MAX_SID_STRING_BYTES).getString(0, StandardCharsets.UTF_16LE)
            } finally {
                localFree.invoke(stringSid)
            }
        }

        private companion object {
            const val SECURITY_MAX_SID_SIZE = 68L
            const val DOMAIN_CHARS = 512L
            const val MAX_SID_STRING_BYTES = 512L
        }
    }
}
