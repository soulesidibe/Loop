package loop.runner.acp

import loop.api.RunnerConfigException

/**
 * Splits `runners.acp.command` into the argv words launched without a shell: supports single quotes,
 * double quotes and backslashes, and refuses unquoted shell operators, expansions, globs, leading `~`
 * and leading environment assignments so shell syntax fails at config validation time.
 */
internal object CommandWords {
    private const val SHELL_CHARACTERS = "|&;<>()\$`*?["

    private const val DOUBLE_QUOTE_ESCAPES = "\"\\\$`"

    /** A word a shell reads as an env assignment, capturing the variable name before `=`. */
    private val ASSIGNMENT = Regex("^([A-Za-z_][A-Za-z0-9_]*)=")

    /** The words of [command]; [key] names the setting in any [RunnerConfigException]. */
    fun of(
        command: String,
        key: String,
    ): List<String> {
        val words = mutableListOf<String>()
        val word = StringBuilder()
        var inWord = false
        var i = 0
        while (i < command.length) {
            val c = command[i]
            when {
                c.isWhitespace() -> {
                    if (inWord) words += word.toString()
                    word.clear()
                    inWord = false
                }
                c == '\'' -> {
                    val end = command.indexOf('\'', i + 1)
                    if (end < 0) throw RunnerConfigException("$key has an unterminated single quote")
                    word.append(command, i + 1, end)
                    inWord = true
                    i = end
                }
                c == '"' -> {
                    i++
                    while (i < command.length && command[i] != '"') {
                        if (command[i] == '\\' && i + 1 < command.length && command[i + 1] in DOUBLE_QUOTE_ESCAPES) i++
                        word.append(command[i])
                        i++
                    }
                    if (i >= command.length) throw RunnerConfigException("$key has an unterminated double quote")
                    inWord = true
                }
                c == '\\' -> {
                    if (i + 1 >= command.length) throw RunnerConfigException("$key ends with a backslash")
                    word.append(command[i + 1])
                    inWord = true
                    i++
                }
                c in SHELL_CHARACTERS || (c == '~' && !inWord) ->
                    throw RunnerConfigException("$key is run without a shell, so it cannot hold $c unquoted")
                else -> {
                    word.append(c)
                    inWord = true
                }
            }
            i++
        }
        if (inWord) words += word.toString()
        val program = words.firstOrNull()
        if (program.isNullOrEmpty()) throw RunnerConfigException("$key names no program")
        ASSIGNMENT.find(program)?.let {
            throw RunnerConfigException(
                "$key starts with the env assignment ${it.groupValues[1]}, which no shell runs: set it in agent.env instead",
            )
        }
        return words
    }
}
