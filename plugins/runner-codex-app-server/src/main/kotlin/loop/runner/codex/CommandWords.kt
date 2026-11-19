package loop.runner.codex

import loop.api.RunnerConfigException

/**
 * `runners.codex.command` as the argv the Codex runner launches (m3-20): split into words at blanks as a shell would,
 * with single quotes, double quotes and backslashes, but nothing expanded and no shell run, so no login profile can
 * change the agent env after the env policy built it.
 *
 * Inside single quotes every character is literal. Inside double quotes a backslash escapes only `"`, `\`, `$` and a
 * backtick. Outside quotes a backslash escapes the next character. A character a shell would act on, outside quotes,
 * makes the command a [RunnerConfigException] naming the key, so a command written for a shell fails when the config
 * is read instead of running with literal words: an operator (`|`, `&`, `;`, `<`, `>`, `(`, `)`), `$`, a backtick, a
 * glob (`*`, `?`, `[`) or a `~` that starts a word. So do an unterminated quote, a trailing backslash and an empty
 * program name.
 */
internal object CommandWords {
    private const val SHELL_CHARACTERS = "|&;<>()\$`*?["

    private const val DOUBLE_QUOTE_ESCAPES = "\"\\\$`"

    /** The words of [command], see [CommandWords]; [key] names the setting in a refusal. */
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
        if (words.firstOrNull().isNullOrEmpty()) throw RunnerConfigException("$key names no program")
        return words
    }
}
