package dev.telegrammcp.server.security

import java.lang.foreign.Arena
import java.lang.foreign.FunctionDescriptor
import java.lang.foreign.Linker
import java.lang.foreign.MemorySegment
import java.lang.foreign.SymbolLookup
import java.lang.foreign.ValueLayout

/**
 * Whether the process's real stderr descriptor is an interactive terminal.
 *
 * `System.console()` describes stdin/stdout, so a launch such as
 * `telegram-mcp 2> approval.log` from a terminal still reports a console even
 * though stderr goes to a file. This asks the OS about stderr itself:
 * `isatty(2)` on POSIX and `GetConsoleMode` on the `STD_ERROR_HANDLE` on
 * Windows. Any failure (no native access, unknown platform, missing symbol)
 * answers false, so callers that print secrets fail closed.
 */
internal object NativeTerminal {

    fun stderrIsTerminal(): Boolean = runCatching { probe() }.getOrDefault(false)

    private fun probe(): Boolean {
        val linker = Linker.nativeLinker()
        return if (System.getProperty("os.name").orEmpty().startsWith("Windows", ignoreCase = true)) {
            windowsStderrIsConsole(linker)
        } else {
            posixIsatty(linker, STDERR_FILENO)
        }
    }

    private fun posixIsatty(linker: Linker, fd: Int): Boolean {
        val isatty = linker.downcallHandle(
            linker.defaultLookup().find("isatty").orElseThrow(),
            FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.JAVA_INT),
        )
        return (isatty.invoke(fd) as Int) == 1
    }

    private fun windowsStderrIsConsole(linker: Linker): Boolean = Arena.ofConfined().use { arena ->
        val kernel = SymbolLookup.libraryLookup("Kernel32", arena)
        /** HANDLE GetStdHandle(DWORD) */
        val getStdHandle = linker.downcallHandle(
            kernel.find("GetStdHandle").orElseThrow(),
            FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.JAVA_INT),
        )
        /** BOOL GetConsoleMode(HANDLE, LPDWORD) */
        val getConsoleMode = linker.downcallHandle(
            kernel.find("GetConsoleMode").orElseThrow(),
            FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.ADDRESS),
        )
        val handle = getStdHandle.invoke(STD_ERROR_HANDLE) as MemorySegment
        if (handle.address() == 0L || handle.address() == INVALID_HANDLE_VALUE) return@use false
        val mode = arena.allocate(ValueLayout.JAVA_INT)
        // Fails for files, pipes and NUL; succeeds only for a console screen buffer.
        (getConsoleMode.invoke(handle, mode) as Int) != 0
    }

    private const val STDERR_FILENO = 2
    private const val STD_ERROR_HANDLE = -12
    private const val INVALID_HANDLE_VALUE = -1L
}
