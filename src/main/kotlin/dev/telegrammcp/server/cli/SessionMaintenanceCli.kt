package dev.telegrammcp.server.cli

/** Command-line entry point for offline-safe TDLib session inspection. */
object SessionMaintenanceCli {
    fun tryRun(args: Array<String>): Boolean {
        if (args.firstOrNull() != "session") return false
        val command = args.getOrNull(1) ?: "help"
        val maintenance = SessionMaintenance.fromEnvironment()
        when (command) {
            "doctor" -> printDoctor(maintenance.doctor())
            "clear" -> {
                val accountIndex = args.indexOf("--account")
                val account = args.getOrNull(accountIndex + 1)?.takeIf { accountIndex >= 2 && !it.startsWith("--") }
                    ?: error("session clear requires --account <label>")
                val flags = args.drop(2).filterIndexed { index, _ -> index + 2 !in accountIndex..accountIndex + 1 }
                require(flags.all { it == "--yes" || it == "--no-backup" }) {
                    "session clear accepts only --account <label>, --yes and --no-backup"
                }
                val result = maintenance.clear(account, "--yes" in flags, keepBackup = "--no-backup" !in flags)
                println("${result.message}: account=${result.label}, directory=${result.databaseDirectory}, deleted=${result.deleted}")
            }
            "logout" -> println(maintenance.doctor().logoutInstruction)
            "help", "--help", "-h" -> printUsage()
            else -> error("Unknown session command '$command'. Use 'session help'.")
        }
        return true
    }

    private fun printDoctor(report: SessionDoctorReport) {
        println("Telegram MCP session doctor")
        println("application_data_directory=${report.applicationDataDirectory}")
        report.accounts.forEach { account ->
            println("account=${account.label}")
            println("  database_directory=${account.databaseDirectory}")
            println("  exists=${account.exists}")
            println("  tdlib_lock=${account.lock}")
            println("  lock_owner=${account.lockOwner}")
            account.stagedDirectories.forEach { staged ->
                println("  staged_directory=$staged (requires manual recovery; stop the owner first)")
            }
        }
        println("logout=${report.logoutInstruction}")
        println("clear=${report.clearInstruction}")
    }

    private fun printUsage() {
        println("Usage: java -jar telegram-mcp-server.jar session <doctor|logout|clear> [options]")
        println("  session doctor")
        println("  session logout                 # prints the safe running-server logout path")
        println("  session clear --account <label> --yes [--no-backup]")
        println("  Clear keeps an unencrypted copy of session credentials by default. --no-backup skips that copy.")
    }
}
