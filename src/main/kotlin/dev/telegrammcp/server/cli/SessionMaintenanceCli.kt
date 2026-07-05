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
                val account = option(args, "--account") ?: error("session clear requires --account <label>")
                val result = maintenance.clear(account, args.contains("--yes"))
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
        }
        println("logout=${report.logoutInstruction}")
        println("clear=${report.clearInstruction}")
    }

    private fun option(args: Array<String>, name: String): String? =
        args.indexOf(name).takeIf { it >= 0 }?.let { index -> args.getOrNull(index + 1) }

    private fun printUsage() {
        println("Usage: java -jar telegram-mcp-server.jar session <doctor|logout|clear> [options]")
        println("  session doctor")
        println("  session logout                 # prints the safe running-server logout path")
        println("  session clear --account <label> --yes")
    }
}
