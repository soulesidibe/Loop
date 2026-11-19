package loop.core.workflow

/** The settings of `runner.kind: command` with `runners.command.command` set to [command], as the loader reads them. */
fun commandRunner(command: String): RunnerSettings = RunnerSettings("command", mapOf("command" to mapOf("command" to command)))

/** `runners.command.command` of settings [commandRunner] built. */
val RunnerSettings.command: String get() = (sections["command"] as Map<*, *>)["command"] as String
