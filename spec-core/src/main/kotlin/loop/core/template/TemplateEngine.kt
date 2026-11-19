package loop.core.template

import liqp.TemplateContext
import liqp.TemplateParser
import liqp.exceptions.VariableNotExistException
import liqp.org.antlr.v4.runtime.tree.ParseTree
import liquid.parser.v4.LiquidParser

/**
 * Strict Liquid templates over liqp.
 *
 * Fail closed: an unknown variable or property fails the render with
 * [TemplateError.Render]; an unknown filter, an unknown tag, the unsupported
 * `tablerow` block or a template too deeply nested fails the parse with
 * [TemplateError.Parse]. A render miss is accepted only when its path resolves,
 * key by key, to a key bound to null in the render variables: such a variable
 * renders empty and is falsy.
 *
 * Known gaps, all failing with [TemplateError.Render] although the value is a
 * present null: a null reached through a loop variable (`b.state` inside
 * `{% for b in issue.blocked_by %}`), an index lookup (`issue.labels[0]`), and
 * a template local nil (an `assign` of a null). The fix for any of them is to
 * resolve the path more precisely, never to loosen the check.
 */
class TemplateEngine {
    private val parser =
        TemplateParser
            .Builder()
            .withStrictVariables(true)
            .withErrorMode(TemplateParser.ErrorMode.STRICT)
            .build()

    // liqp's strict mode cannot tell a missing variable from one bound to null.
    // Rendering in WARN mode collects the misses instead of throwing, and
    // Template.render rejects every miss that is not a present null.
    private val renderParser =
        TemplateParser
            .Builder(parser)
            .withErrorMode(TemplateParser.ErrorMode.WARN)
            .build()

    /**
     * Parses [source]. With a [schema], every lookup is also checked statically,
     * branches not taken included: a variable outside the schema fails with
     * [TemplateError.Render], as it would at render time.
     */
    fun parse(
        source: String,
        schema: TemplateSchema? = null,
    ): Result<Template> =
        try {
            val template = parser.parse(source)
            checkTree(template.parseTree)
            if (schema != null) checkLookups(template.parseTree, schema)
            Result.success(Template(template, renderParser))
        } catch (e: UnknownVariable) {
            Result.failure(TemplateError.Render(e, variable = e.variable))
        } catch (e: UnknownFilter) {
            Result.failure(TemplateError.Parse(e, filter = e.filter))
        } catch (e: RuntimeException) {
            Result.failure(TemplateError.Parse(e))
        } catch (e: StackOverflowError) {
            // A deeply nested template overflows liqp's recursive parser.
            Result.failure(TemplateError.Parse(e))
        }

    // liqp only resolves filters when it renders, so an unknown filter is
    // caught here to report it as a parse error. tablerow is rejected because
    // liqp drops the errors raised inside it, which would hide unknown variables.
    private fun checkTree(node: ParseTree) {
        if (node is LiquidParser.Table_tagContext) {
            throw IllegalArgumentException("tablerow is not supported")
        }
        if (node is LiquidParser.FilterContext) {
            val name = node.Id().text
            if (parser.filters.get(name) == null) throw UnknownFilter(name)
        }
        for (i in 0 until node.childCount) {
            checkTree(node.getChild(i))
        }
    }

    // Template locals (loop variables, assigned and captured names, forloop) are
    // allowed anywhere in the template, not only in their scope. Index lookups
    // (`issue["x"]`, `issue.labels[0]`) are left to the render.
    private fun checkLookups(
        tree: ParseTree,
        schema: TemplateSchema,
    ) {
        val locals = mutableSetOf(FORLOOP)
        collectLocals(tree, locals)
        checkLookup(tree, schema, locals)
    }

    private fun collectLocals(
        node: ParseTree,
        into: MutableSet<String>,
    ) {
        when (node) {
            is LiquidParser.For_arrayContext -> into += node.id().text
            is LiquidParser.For_rangeContext -> into += node.id().text
            is LiquidParser.AssignmentContext -> into += node.id().text
            is LiquidParser.Capture_tag_IdContext -> into += node.id().text
            is LiquidParser.Capture_tag_StrContext -> into += node.Str().text.trim('"', '\'')
            is LiquidParser.Simple_tagContext ->
                if (node.SimpleTagId().text in COUNTER_TAGS) {
                    node
                        .other_tag_parameters()
                        ?.text
                        ?.trim()
                        ?.let { into += it }
                }
        }
        for (i in 0 until node.childCount) {
            collectLocals(node.getChild(i), into)
        }
    }

    private fun checkLookup(
        node: ParseTree,
        schema: TemplateSchema,
        locals: Set<String>,
    ) {
        if (node is LiquidParser.Lookup_id_indexesContext) {
            val root = node.id().text
            if (root !in locals) {
                if (root !in schema.roots) throw UnknownVariable(root)
                val keys = schema.roots[root]
                val key =
                    node
                        .index()
                        .firstOrNull()
                        ?.id2()
                        ?.text
                if (keys != null && key != null && key !in keys) throw UnknownVariable("$root.$key")
            }
        }
        for (i in 0 until node.childCount) {
            checkLookup(node.getChild(i), schema, locals)
        }
    }

    private class UnknownVariable(
        val variable: String,
    ) : RuntimeException("unknown variable: $variable")

    private class UnknownFilter(
        val filter: String,
    ) : RuntimeException("unknown filter: $filter")

    private companion object {
        const val FORLOOP = "forloop"

        // `{% increment n %}` and `{% decrement n %}` define the counter `n`.
        val COUNTER_TAGS = setOf("increment", "decrement")
    }
}

/**
 * The variables a template may use, for [TemplateEngine.parse]: each root name
 * with the keys allowed right after it, or null to allow any key.
 */
data class TemplateSchema(
    val roots: Map<String, Set<String>?>,
)

/**
 * A parsed template. liqp keeps per render state on the template, so renders
 * are serialized.
 */
class Template internal constructor(
    private val delegate: liqp.Template,
    private val renderParser: TemplateParser,
) {
    @Synchronized
    fun render(vars: Map<String, Any?>): Result<String> =
        try {
            val output = delegate.renderUnguarded(vars, TemplateContext(renderParser, emptyMap()), true)
            val error = delegate.errors().firstOrNull { !isPresentNull(it, vars) }
            if (error == null) {
                Result.success(output)
            } else {
                Result.failure(TemplateError.Render(error, variable = (error as? VariableNotExistException)?.variableName))
            }
        } catch (e: RuntimeException) {
            Result.failure(TemplateError.Render(e))
        } catch (e: StackOverflowError) {
            // A deeply recursive render overflows liqp's evaluator.
            Result.failure(TemplateError.Render(e))
        }

    /**
     * True when the template reads [key] of the variable [root] anywhere, branches not taken included
     * (`{{ issue.description }}`, `{% if issue.description %}`, `issue["description"]`), or uses [root]
     * whole (`{{ issue }}`), which renders every key (bl-06). Text inside `raw` and `comment` blocks is
     * never a use, nor is a key reached through a template local such as an `assign` of [root].
     */
    fun uses(
        root: String,
        key: String,
    ): Boolean = reads(delegate.parseTree, root, key)

    private fun reads(
        node: ParseTree,
        root: String,
        key: String,
    ): Boolean {
        if (node is LiquidParser.Lookup_id_indexesContext && node.id().text == root) {
            val first = node.index().firstOrNull() ?: return true
            val name = first.id2()?.text ?: first.expr()?.text?.let(::unquoted)
            if (name == key) return true
        }
        return (0 until node.childCount).any { reads(node.getChild(it), root, key) }
    }

    /** The text of a quoted string literal without its quotes, null for any other expression. */
    private fun unquoted(text: String): String? =
        text.takeIf { it.length >= 2 && it.first() == it.last() && it.first() in "\"'" }?.substring(1, text.length - 1)

    private fun isPresentNull(
        error: Exception,
        vars: Map<String, Any?>,
    ): Boolean {
        if (error !is VariableNotExistException) return false
        var scope: Any? = vars
        for (key in error.variableName.split('.')) {
            if (scope !is Map<*, *> || !scope.containsKey(key)) return false
            scope = scope[key]
        }
        return scope == null
    }
}

/** Maps to the spec's template_parse_error and template_render_error. */
sealed class TemplateError(
    cause: Throwable,
) : Exception(cause.message, cause) {
    class Parse(
        cause: Throwable,
        /** The unknown filter, when that is the error. */
        val filter: String? = null,
    ) : TemplateError(cause)

    class Render(
        cause: Throwable,
        /** The unknown variable path, when that is the error. */
        val variable: String? = null,
    ) : TemplateError(cause)
}
