package dev.telegrammcp.server.security

import dev.telegrammcp.server.service.stderrIsTerminal
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Child-process entry point: reports what the approval code would decide. */
object NativeTerminalProbe {
    @JvmStatic
    fun main(args: Array<String>) {
        println("console=${System.console()?.isTerminal == true}")
        println("stderr=${stderrIsTerminal()}")
        System.out.flush()
    }
}

class NativeTerminalTest {

    @Test fun `stderr captured by the test runner is not a terminal`() {
        assertFalse(stderrIsTerminal())
    }

    /**
     * Regression: with stdin/stdout on a terminal and `2> file`, the old
     * `System.console().isTerminal` check said "terminal" and the approval link
     * was written into the file.
     */
    @Test fun `stderr redirected to a file is not a terminal even when stdout is`(@TempDir dir: Path) {
        val redirected = runInPty(dir, "2> ${dir.resolve("err.log")}")
        assertEquals("true", redirected["console"], "precondition: the process does have a console")
        assertEquals("false", redirected["stderr"])
    }

    @Test fun `stderr on a terminal is detected`(@TempDir dir: Path) {
        val attached = runInPty(dir, "")
        assertEquals("true", attached["stderr"])
    }

    /** Runs [NativeTerminalProbe] under `script(1)`, which gives it a pseudo-terminal. */
    private fun runInPty(dir: Path, stderrRedirect: String): Map<String, String> {
        assumeTrue(!System.getProperty("os.name").startsWith("Windows", ignoreCase = true), "POSIX pty test")
        val script = listOf("/usr/bin/script", "/bin/script").map(Path::of).firstOrNull(Files::isExecutable)
        assumeTrue(script != null, "script(1) is not installed")
        val java = Path.of(System.getProperty("java.home"), "bin", "java").toString()
        val argFile = dir.resolve("probe.args")
        Files.writeString(argFile, "-cp\n\"${System.getProperty("java.class.path").replace("\\", "\\\\")}\"\n")
        val command = "'$java' --enable-native-access=ALL-UNNAMED '@$argFile' ${NativeTerminalProbe::class.java.name} $stderrRedirect"
        val process = ProcessBuilder(script.toString(), "-qefc", command, "/dev/null")
            .redirectErrorStream(true)
            .redirectInput(ProcessBuilder.Redirect.PIPE)
            .start()
        val output = process.inputStream.bufferedReader().readText()
        assertTrue(process.waitFor(60, TimeUnit.SECONDS), "probe finished")
        val values = output.lineSequence().map(String::trim).filter { "=" in it }
            .associate { it.substringBefore('=') to it.substringAfter('=') }
        assumeTrue("stderr" in values, "pty probe produced no result: $output")
        return values
    }
}
