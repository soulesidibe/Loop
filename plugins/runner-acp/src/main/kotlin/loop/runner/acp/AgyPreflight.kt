package loop.runner.acp

import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * Outcome of [AgyPreflight.check]: [error] stops startup, while [warnings] are reported and the
 * run proceeds.
 */
data class AgyPreflightResult(
    val error: String? = null,
    val warnings: List<String> = emptyList(),
)

/**
 * Startup preflight for `runner.kind: acp` with `preset: agy`:
 * 1. Verifies the `agy` binary in `runners.acp.command` is reachable and executable (error if missing).
 * 2. Runs `<agy> --version` and verifies major version `1` and at least [AcpSettings.MIN_AGY_VERSION] (error if
 *    unreadable, of another major, or older).
 * 3. Runs `<agy> models` to warn on login issues, offline failures, or a configured model not listed
 *    (warnings only, never fails preflight).
 */
class AgyPreflight(
    private val command: String,
    private val model: String?,
    private val hostEnv: Map<String, String> = System.getenv(),
    private val pinnedVersion: String = AcpSettings.PINNED_AGY_VERSION,
    private val minimumVersion: String = AcpSettings.MIN_AGY_VERSION,
    private val resolveBinary: (String, Map<String, String>) -> String? = ::defaultResolveBinary,
    private val runProbe: (List<String>, Map<String, String>, Long) -> ProbeOutput? = ::defaultRunProbe,
) {
    /** Output of one short preflight subprocess probe. */
    data class ProbeOutput(
        val exitCode: Int,
        val stdout: String,
        val stderr: String,
    )

    fun check(): AgyPreflightResult {
        val program =
            runCatching { CommandWords.of(command, "runners.${AcpSettings.SECTION}.command").first() }
                .getOrElse { return AgyPreflightResult(error = it.message ?: "invalid runners.acp.command") }

        val resolved =
            resolveBinary(program, hostEnv)
                ?: return AgyPreflightResult(
                    error = "the agy binary $program cannot be found on PATH; install Antigravity CLI or set runners.acp.command",
                )

        val versionProbe =
            runProbe(listOf(resolved, "--version"), hostEnv, VERSION_TIMEOUT_SECONDS)
                ?: return AgyPreflightResult(error = "could not run $program --version to verify the agy major version")
        if (versionProbe.exitCode != 0) {
            return AgyPreflightResult(
                error = "$program --version exited ${versionProbe.exitCode}; could not verify the agy major version",
            )
        }
        val version =
            extractSemver(versionProbe.stdout)
                ?: extractSemver(versionProbe.stderr)
                ?: return AgyPreflightResult(error = "could not parse an agy version from $program --version")
        when (AgyVersion.check(version, pinnedVersion, minimumVersion)) {
            is AgyVersion.Problem.WrongMajor ->
                return AgyPreflightResult(
                    error = "agy $version is not of the pinned major version ${pinnedVersion.substringBefore('.')} (pinned $pinnedVersion)",
                )
            is AgyVersion.Problem.TooOld ->
                return AgyPreflightResult(
                    error =
                        "agy $version is older than the minimum version $minimumVersion (pinned $pinnedVersion): " +
                            "${AcpSettings.MIN_AGY_REASON}; run agy update",
                )
            null -> Unit
        }

        val warnings = mutableListOf<String>()
        val modelsProbe = runProbe(listOf(resolved, "models"), hostEnv, MODELS_TIMEOUT_SECONDS)
        if (modelsProbe == null) {
            warnings += "could not run $program models (timed out or unavailable); continuing without verifying login or model"
        } else if (modelsProbe.exitCode != 0) {
            val detail = firstLine(modelsProbe.stderr).ifEmpty { firstLine(modelsProbe.stdout) }
            val lower = detail.lowercase(Locale.ROOT)
            val isLoginFailure = LOGIN_MARKERS.any { it in lower }
            warnings +=
                if (isLoginFailure) {
                    val suffix = if (detail.isNotEmpty()) " ($detail)" else ""
                    "agy login check failed$suffix; run agy --login or check your Google account"
                } else {
                    val suffix = if (detail.isNotEmpty()) " ($detail)" else ""
                    "could not list models with $program models$suffix; continuing without verifying model"
                }
        } else if (model != null) {
            val listed = parseModelIds(modelsProbe.stdout)
            if (listed.isNotEmpty() && model !in listed) {
                warnings += "runners.acp.model $model was not listed by $program models; the session may reject it"
            }
        }

        return AgyPreflightResult(warnings = warnings)
    }

    companion object {
        private const val VERSION_TIMEOUT_SECONDS = 10L
        private const val MODELS_TIMEOUT_SECONDS = 15L
        private val SEMVER = Regex("""\b(\d+\.\d+(?:\.\d+)?(?:[-+][A-Za-z0-9.-]+)?)\b""")
        private val ANSI = Regex("""\u001B\[[0-9;]*[A-Za-z]""")
        private val LOGIN_MARKERS =
            listOf(
                "login",
                "unauthenticated",
                "ineligibletiererror",
                "auth",
                "credential",
                "sign in",
                "not logged in",
                "401",
                "403",
            )

        internal fun extractSemver(text: String): String? = SEMVER.find(text)?.groupValues?.get(1)

        internal fun parseModelIds(stdout: String): Set<String> =
            stdout
                .lineSequence()
                .map { line -> ANSI.replace(line.substringAfterLast('\r'), "").trim() }
                .filter { it.isNotEmpty() }
                .mapNotNull { line -> line.split(Regex("\\s+")).firstOrNull()?.takeIf { it.isNotEmpty() } }
                .toSet()

        private fun firstLine(text: String): String =
            text
                .lineSequence()
                .map { ANSI.replace(it.substringAfterLast('\r'), "").trim() }
                .firstOrNull { it.isNotEmpty() }
                .orEmpty()
                .trimEnd('.')

        internal fun defaultResolveBinary(
            program: String,
            hostEnv: Map<String, String>,
        ): String? {
            val executable = { path: Path -> Files.isRegularFile(path) && Files.isExecutable(path) }
            if ('/' !in program) {
                return hostEnv["PATH"]
                    .orEmpty()
                    .split(File.pathSeparatorChar)
                    .filter { it.isNotEmpty() }
                    .firstNotNullOfOrNull { dir ->
                        runCatching { Path.of(dir, program) }.getOrNull()?.takeIf(executable)
                    }?.toAbsolutePath()
                    ?.toString()
            }
            val path = runCatching { Path.of(program) }.getOrNull() ?: return null
            return if (executable(path)) path.toAbsolutePath().toString() else null
        }

        private fun defaultRunProbe(
            argv: List<String>,
            hostEnv: Map<String, String>,
            timeoutSeconds: Long,
        ): ProbeOutput? =
            try {
                val builder = ProcessBuilder(argv)
                builder.environment().apply {
                    clear()
                    putAll(hostEnv)
                }
                val process = builder.start()
                process.outputStream.close()
                var stdout = ""
                var stderr = ""
                val outThread =
                    Thread {
                        stdout = runCatching { process.inputStream.readAllBytes().toString(Charsets.UTF_8) }.getOrDefault("")
                    }.apply {
                        isDaemon = true
                        start()
                    }
                val errThread =
                    Thread {
                        stderr = runCatching { process.errorStream.readAllBytes().toString(Charsets.UTF_8) }.getOrDefault("")
                    }.apply {
                        isDaemon = true
                        start()
                    }
                val finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS)
                if (!finished) {
                    process.destroyForcibly()
                    return null
                }
                outThread.join(1_000)
                errThread.join(1_000)
                ProbeOutput(process.exitValue(), stdout, stderr)
            } catch (e: IOException) {
                null
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                null
            }
    }
}
