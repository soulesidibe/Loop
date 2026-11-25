package loop.runner.codex

import loop.runner.process.DeniedCommands
import java.io.IOException
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.isDirectory
import kotlin.io.path.isRegularFile
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name
import kotlin.io.path.readText

/**
 * The Codex execpolicy rules a session would load, read before it starts (bl-135). A `prefix_rule` with the decision
 * `allow` makes Codex run a matching command with no approval request, even under the `untrusted` policy, so Loop's
 * [ServerRequestPolicy] never sees it. The pinned app server has no switch to skip the rules files (`--ignore-rules`
 * is a flag of `codex exec` only), so the runner refuses to start a session when one of them allows a denied command.
 *
 * The files read are each `*.rules` file of the `rules` folder of `$CODEX_HOME` (default `~/.codex`), of the system
 * config folder `/etc/codex`, and of each `.codex` folder from the workspace up to the project root, the first folder
 * that holds `.git`, or up to the file system root when none does. An agent can write the workspace's, and after its
 * first thread Codex trusts the workspace, so they load in later sessions; the operator writes the others.
 *
 * A rule allows a denied command when its decision is `allow`, or absent, which Codex reads as `allow`, and its
 * pattern can match one of [DeniedCommands]: at each place both have, the pattern's word, or one of its alternatives,
 * is the denied word, the first also by a path to it. A `git` rule whose second word is an option allows a denied
 * command too, since `git -C . push` matches it. A file is read as literals only: a rule whose pattern or decision is
 * not a literal, a `prefix_rule` that is not called directly, or a file that does not parse, may allow anything, so it
 * refuses the session as well. Not covered: a rule that allows another program that runs a denied command for it,
 * such as an interpreter, and a rules file written during a session, if Codex reads it before the session ends.
 */
internal object ExecPolicyRules {
    /** The system config folder of Codex on Unix. */
    val SYSTEM_DIR: Path = Path.of("/etc/codex")

    private const val RULE = "prefix_rule"

    private const val ALLOW = "allow"

    /** The names of the positional arguments of a `prefix_rule`, in order. */
    private val POSITIONAL = listOf("pattern", "decision")

    /** The `rules` folders a session in [workspace] reads, with [env] the agent env, see [ExecPolicyRules]. */
    fun folders(
        workspace: Path,
        env: Map<String, String>,
        systemDir: Path? = SYSTEM_DIR,
    ): List<Path> {
        val codexHome =
            env["CODEX_HOME"]?.takeIf { it.isNotBlank() }?.let(Path::of)
                ?: Path.of(env["HOME"]?.takeIf { it.isNotBlank() } ?: System.getProperty("user.home"), ".codex")
        val project = mutableListOf<Path>()
        var dir: Path? = workspace.toAbsolutePath().normalize()
        while (dir != null) {
            project.add(dir.resolve(".codex").resolve("rules"))
            if (dir.resolve(".git").exists()) break
            dir = dir.parent
        }
        return listOfNotNull(codexHome.resolve("rules"), systemDir?.resolve("rules")) + project
    }

    /** Why the rules files in [folders] refuse the session, null when none allows a denied command. */
    fun refusal(folders: List<Path>): String? {
        for (folder in folders.distinct()) {
            if (!folder.isDirectory()) continue
            val files =
                try {
                    folder.listDirectoryEntries("*.rules").filter { it.isRegularFile() }.sortedBy { it.name }
                } catch (e: IOException) {
                    return "exec_policy_rule: $folder cannot be listed (${e.javaClass.simpleName})"
                }
            for (file in files) {
                val text =
                    try {
                        file.readText()
                    } catch (e: IOException) {
                        return "exec_policy_rule: $file cannot be read (${e.javaClass.simpleName})"
                    }
                val reason = refusalOf(text) ?: continue
                return "exec_policy_rule: $file $reason"
            }
        }
        return null
    }

    /** Why the rules [text] refuses the session, after the file's name, null when it does not. */
    private fun refusalOf(text: String): String? {
        val rules =
            try {
                Parser(Lexer(text).tokens()).rules()
            } catch (e: Unreadable) {
                return "cannot be read as literal rules (${e.message}), so it may allow a denied command"
            }
        for (rule in rules) {
            if (rule.decision != ALLOW) continue
            val denied = DeniedCommands.words.firstOrNull { allows(rule.pattern, it) } ?: continue
            return "allows the denied command ${denied.joinToString(" ")} with no approval request"
        }
        return null
    }

    /** True when [pattern], each word a set of alternatives, can match the [denied] command, see [ExecPolicyRules]. */
    private fun allows(
        pattern: List<List<String>>,
        denied: List<String>,
    ): Boolean {
        if (pattern.isEmpty()) return false
        val head = pattern[0].any { DeniedCommands.runs(it, denied[0]) }
        if (!head) return false
        if (denied[0] == "git" && pattern.size > 1 && pattern[1].any { it.startsWith("-") }) return true
        return (1 until minOf(pattern.size, denied.size)).all { i -> denied[i] in pattern[i] }
    }

    /** One `prefix_rule`: its pattern, each word as its alternatives, and its decision. */
    private class Rule(
        val pattern: List<List<String>>,
        val decision: String,
    )

    private class Unreadable(
        message: String,
    ) : Exception(message)

    private sealed interface Token {
        data class Str(
            val value: String,
        ) : Token

        data class Name(
            val value: String,
        ) : Token

        data class Punct(
            val char: Char,
        ) : Token
    }

    /** The tokens of a Starlark text: strings, names and numbers, punctuation; comments and blanks dropped. */
    private class Lexer(
        private val text: String,
    ) {
        private var i = 0

        fun tokens(): List<Token> {
            val tokens = mutableListOf<Token>()
            while (i < text.length) {
                val c = text[i]
                when {
                    c.isWhitespace() -> i++
                    c == '#' -> while (i < text.length && text[i] != '\n') i++
                    c == '"' || c == '\'' -> tokens += Token.Str(string(raw = false))
                    c.isLetterOrDigit() || c == '_' -> {
                        val start = i
                        while (i < text.length && (text[i].isLetterOrDigit() || text[i] == '_')) i++
                        val name = text.substring(start, i)
                        val prefix = name.lowercase() in STRING_PREFIXES
                        if (prefix && i < text.length && (text[i] == '"' || text[i] == '\'')) {
                            tokens += Token.Str(string(raw = 'r' in name.lowercase()))
                        } else {
                            tokens += Token.Name(name)
                        }
                    }
                    else -> {
                        tokens += Token.Punct(c)
                        i++
                    }
                }
            }
            return tokens
        }

        /** The string that starts at [i], single, double or triple quoted, its escapes read unless [raw]. */
        private fun string(raw: Boolean): String {
            val quote = text[i]
            val triple = text.startsWith("$quote$quote$quote", i)
            val end = if (triple) "$quote$quote$quote" else "$quote"
            i += end.length
            val value = StringBuilder()
            while (true) {
                if (i >= text.length) throw Unreadable("an unterminated string")
                if (text.startsWith(end, i)) {
                    i += end.length
                    return value.toString()
                }
                val c = text[i]
                if (c == '\n' && !triple) throw Unreadable("an unterminated string")
                if (c == '\\' && i + 1 < text.length) {
                    val next = text[i + 1]
                    if (raw) value.append(c).append(next) else value.append(ESCAPES[next] ?: next)
                    i += 2
                } else {
                    value.append(c)
                    i++
                }
            }
        }

        companion object {
            val STRING_PREFIXES = setOf("r", "b", "rb", "br")
            val ESCAPES = mapOf('n' to '\n', 't' to '\t', 'r' to '\r', '0' to '\u0000')
        }
    }

    /** The `prefix_rule` calls of a token list, see [ExecPolicyRules]. */
    private class Parser(
        private val tokens: List<Token>,
    ) {
        private var i = 0

        fun rules(): List<Rule> {
            val rules = mutableListOf<Rule>()
            while (i < tokens.size) {
                val token = tokens[i++]
                if (token != Token.Name(RULE)) continue
                if (tokens.getOrNull(i - 2) == Token.Punct('.')) throw Unreadable("$RULE is used as an attribute")
                if (tokens.getOrNull(i) != Token.Punct('(')) throw Unreadable("$RULE is used other than in a call")
                i++
                rules += call()
            }
            return rules
        }

        /** The arguments of one call, after its `(`, up to its `)`. */
        private fun call(): Rule {
            var pattern: Value? = null
            var decision: Value? = null
            var positional = 0
            while (true) {
                if (peek() == Token.Punct(')')) {
                    i++
                    break
                }
                val keyword = (peek() as? Token.Name)?.takeIf { tokens.getOrNull(i + 1) == Token.Punct('=') }?.value
                if (keyword != null) i += 2
                val value = value()
                // The signature is prefix_rule(pattern, decision, ...), so the first two may be positional.
                val name = keyword ?: POSITIONAL.getOrNull(positional++)
                if (name == "pattern") pattern = value
                if (name == "decision") decision = value
                when (val next = tokens.getOrNull(i++)) {
                    Token.Punct(',') -> continue
                    Token.Punct(')') -> break
                    else -> throw Unreadable("$RULE has ${describe(next)} where a , or ) belongs")
                }
            }
            val items = (pattern as? Value.ListOf)?.items ?: throw Unreadable("$RULE has no literal pattern")
            val words = items.map { word -> if (word is Value.ListOf) word.items.map(::literal) else listOf(literal(word)) }
            val verdict =
                when (decision) {
                    null -> ALLOW
                    is Value.Text -> decision.value
                    else -> throw Unreadable("$RULE has a decision that is not literal")
                }
            return Rule(words, verdict)
        }

        /** The string of a pattern word, which must be a literal. */
        private fun literal(value: Value): String =
            (value as? Value.Text)?.value ?: throw Unreadable("$RULE has a pattern that is not literal")

        /** One argument value: a string, a list of values, or anything else, skipped up to its end. */
        private fun value(): Value {
            val token = tokens.getOrNull(i) ?: throw Unreadable("$RULE is not closed")
            return when {
                token is Token.Str -> {
                    i++
                    Value.Text(token.value)
                }
                token == Token.Punct('[') -> {
                    i++
                    val items = mutableListOf<Value>()
                    while (true) {
                        if (peek() == Token.Punct(']')) {
                            i++
                            break
                        }
                        items += value()
                        when (val next = tokens.getOrNull(i++)) {
                            Token.Punct(',') -> continue
                            Token.Punct(']') -> break
                            else -> throw Unreadable("$RULE has ${describe(next)} where a , or ] belongs")
                        }
                    }
                    Value.ListOf(items)
                }
                else -> {
                    skip()
                    Value.Other
                }
            }
        }

        /** Skips a value that is not a literal, up to the `,` or `)` that ends it at its own depth. */
        private fun skip() {
            var depth = 0
            while (true) {
                val token = tokens.getOrNull(i) ?: throw Unreadable("$RULE is not closed")
                if (depth == 0 && (token == Token.Punct(',') || token == Token.Punct(')'))) return
                when (token) {
                    Token.Punct('('), Token.Punct('['), Token.Punct('{') -> depth++
                    Token.Punct(')'), Token.Punct(']'), Token.Punct('}') -> depth--
                    else -> Unit
                }
                i++
            }
        }

        private fun peek(): Token = tokens.getOrNull(i) ?: throw Unreadable("$RULE is not closed")

        private fun describe(token: Token?): String =
            when (token) {
                null -> "an end of file"
                is Token.Punct -> "a ${token.char}"
                is Token.Name -> "a name"
                is Token.Str -> "a string"
            }
    }

    private sealed interface Value {
        data class Text(
            val value: String,
        ) : Value

        data class ListOf(
            val items: List<Value>,
        ) : Value

        data object Other : Value
    }
}
