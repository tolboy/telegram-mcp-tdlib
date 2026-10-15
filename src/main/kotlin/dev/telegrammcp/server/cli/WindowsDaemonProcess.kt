package dev.telegrammcp.server.cli

import com.sun.jna.Native
import com.sun.jna.platform.win32.Kernel32
import com.sun.jna.platform.win32.WinBase
import com.sun.jna.platform.win32.WinDef.DWORD
import com.sun.jna.platform.win32.WinNT.HANDLE
import com.sun.jna.ptr.IntByReference
import com.sun.jna.win32.StdCallLibrary
import com.sun.jna.win32.W32APIOptions

internal interface WindowsJobControl : StdCallLibrary {
    fun IsProcessInJob(process: HANDLE, job: HANDLE?, result: IntByReference): Boolean
    fun ResumeThread(thread: HANDLE): Int
}

/** Start outside the invoking host's kill-on-close Job Object, without a shell. */
internal object WindowsDaemonProcess {
    fun start(command: List<String>): ProcessHandle {
        val kernel = Kernel32.INSTANCE
        val jobs = Native.load("kernel32", WindowsJobControl::class.java, W32APIOptions.DEFAULT_OPTIONS)
        val startup = WinBase.STARTUPINFO()
        val info = WinBase.PROCESS_INFORMATION()
        // No handles are inherited. The child opens its own log before startup;
        // environment and working directory inherit without copying secrets to argv.
        val created = kernel.CreateProcess(command.first(), command.joinToString(" ", transform = ::quote),
            null, null, false, DWORD(0x01000000L or 0x08000000L or 0x00000004L), null, null, startup, info)
        check(created) {
            "Windows refused detached daemon startup (error ${Native.getLastError()}). " +
                "Start from an independent terminal that permits Job Object breakaway. " +
                "No attached fallback process was started."
        }
        var resumed = false
        return try {
            // In nested jobs CreateProcess can succeed after leaving only the
            // innermost job, while retaining a restrictive ancestor. Validate
            // before executing any child code, not just the creation flag.
            val inJob = IntByReference()
            check(jobs.IsProcessInJob(info.hProcess, null, inJob)) { "Cannot verify detached daemon process (error ${Native.getLastError()})" }
            check(inJob.value == 0) {
                "Windows retained the daemon in a parent Job Object despite breakaway. " +
                    "Start from an independent terminal. No attached fallback process was started."
            }
            check(jobs.ResumeThread(info.hThread) != -1) { "Cannot resume detached daemon process (error ${Native.getLastError()})" }
            resumed = true
            ProcessHandle.of(info.dwProcessId.toLong()).orElseThrow { IllegalStateException("Daemon exited during launch") }
        } finally {
            if (!resumed) {
                kernel.TerminateProcess(info.hProcess, 1)
                kernel.WaitForSingleObject(info.hProcess, 5_000)
            }
            kernel.CloseHandle(info.hThread)
            kernel.CloseHandle(info.hProcess)
        }
    }

    /** Windows CRT argument quoting, including trailing backslashes before quotes. */
    internal fun quote(argument: String): String {
        require('\u0000' !in argument) { "Process arguments cannot contain NUL" }
        return buildString {
            append('"')
            var slashes = 0
            for (c in argument) {
                when (c) {
                    '\\' -> slashes++
                    '"' -> { repeat(slashes * 2 + 1) { append('\\') }; append(c); slashes = 0 }
                    else -> { repeat(slashes) { append('\\') }; append(c); slashes = 0 }
                }
            }
            repeat(slashes * 2) { append('\\') }
            append('"')
        }
    }
}
