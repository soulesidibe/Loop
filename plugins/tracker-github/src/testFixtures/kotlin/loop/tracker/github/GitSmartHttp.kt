package loop.tracker.github

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpHandler
import java.nio.file.Path
import java.util.Base64
import java.util.Collections
import java.util.concurrent.TimeUnit

/**
 * Test only (m2-25): git's smart HTTP protocol for one bare repository [bare], served by `git http-backend` behind a
 * [com.sun.net.httpserver.HttpServer] context, as GitHub serves `https://github.com/<owner>/<repo>.git`. Register it
 * on a loopback server under the repository's path, such as `/acme/widgets.git/`. Every request without the basic
 * credentials [user] and [password] gets a 401 challenge, so git must ask its credential helper. `git http-backend`
 * runs with no user or system config and the [path] it is given, nothing inherited.
 */
class GitSmartHttp(
    private val bare: Path,
    private val password: String,
    private val user: String = "x-access-token",
    private val path: String = System.getenv("PATH") ?: "/usr/bin:/bin",
) : HttpHandler {
    /** Every request: its method, path and query, and its Authorization header. */
    val requests: MutableList<Pair<String, String?>> = Collections.synchronizedList(mutableListOf())

    private val expected = "Basic " + Base64.getEncoder().encodeToString("$user:$password".toByteArray())

    override fun handle(exchange: HttpExchange) {
        exchange.use {
            val uri = exchange.requestURI
            val authorization = exchange.requestHeaders.getFirst("Authorization")
            requests += "${exchange.requestMethod} ${uri.path}?${uri.rawQuery.orEmpty()}" to authorization
            val body = exchange.requestBody.readBytes()
            if (authorization != expected) {
                exchange.responseHeaders.add("WWW-Authenticate", "Basic realm=\"loop-test\"")
                exchange.sendResponseHeaders(UNAUTHORIZED, -1)
                return
            }
            val context = exchange.httpContext.path.trimEnd('/')
            val rest = uri.path.removePrefix(context)
            val builder = ProcessBuilder("git", "http-backend").redirectError(ProcessBuilder.Redirect.DISCARD)
            builder.environment().clear()
            builder.environment().putAll(
                mapOf(
                    "PATH" to path,
                    "GIT_CONFIG_GLOBAL" to "/dev/null",
                    "GIT_CONFIG_NOSYSTEM" to "1",
                    "GIT_PROJECT_ROOT" to bare.parent.toString(),
                    "GIT_HTTP_EXPORT_ALL" to "1",
                    "PATH_INFO" to "/${bare.fileName}$rest",
                    "REQUEST_METHOD" to exchange.requestMethod,
                    "QUERY_STRING" to uri.rawQuery.orEmpty(),
                    "CONTENT_TYPE" to exchange.requestHeaders.getFirst("Content-Type").orEmpty(),
                    "CONTENT_LENGTH" to body.size.toString(),
                    "REMOTE_USER" to user,
                    "REMOTE_ADDR" to "127.0.0.1",
                ),
            )
            exchange.requestHeaders.getFirst("Content-Encoding")?.let { builder.environment()["HTTP_CONTENT_ENCODING"] = it }
            exchange.requestHeaders.getFirst("Git-Protocol")?.let { builder.environment()["GIT_PROTOCOL"] = it }
            val process = builder.start()
            process.outputStream.use { it.write(body) }
            val output = process.inputStream.readBytes()
            process.waitFor(WAIT_SECONDS, TimeUnit.SECONDS)
            respond(exchange, output)
        }
    }

    /** Sends the CGI [output] of `git http-backend`: its headers, its `Status`, then its body. */
    private fun respond(
        exchange: HttpExchange,
        output: ByteArray,
    ) {
        val text = String(output, Charsets.ISO_8859_1)
        val split = listOf("\r\n\r\n", "\n\n").map { it to text.indexOf(it) }.filter { it.second >= 0 }.minByOrNull { it.second }
        val headerText = if (split == null) "" else text.substring(0, split.second)
        val bodyStart = if (split == null) 0 else split.second + split.first.length
        var status = OK
        for (line in headerText.lines().map { it.trimEnd('\r') }.filter { it.isNotEmpty() }) {
            val name = line.substringBefore(':').trim()
            val value = line.substringAfter(':').trim()
            if (name.equals("Status", ignoreCase = true)) {
                status = value.substringBefore(' ').toInt()
            } else {
                exchange.responseHeaders.add(name, value)
            }
        }
        val body = output.copyOfRange(bodyStart, output.size)
        exchange.sendResponseHeaders(status, if (body.isEmpty()) -1 else body.size.toLong())
        if (body.isNotEmpty()) exchange.responseBody.use { it.write(body) }
    }

    private companion object {
        const val OK = 200
        const val UNAUTHORIZED = 401
        const val WAIT_SECONDS = 30L
    }
}
