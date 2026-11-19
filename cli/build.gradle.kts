import org.gradle.process.ExecOperations
import java.io.ByteArrayOutputStream
import java.io.OutputStream
import javax.inject.Inject

plugins {
    id("loop.application")
}

application {
    applicationName = "loop"
    mainClass.set("loop.cli.MainKt")
    // The start script replaces the token with its own install folder (plan D7), so the
    // orchestrator knows the launcher to put on the agent's PATH.
    // sqlite-jdbc loads its native library; without this flag JDK 24 and later print a
    // restricted method warning on stderr, which breaks the key=value log (plan U3).
    applicationDefaultJvmArgs =
        listOf(
            "-Dloop.launcher=__APP_HOME__/bin/loop",
            // The build's version, written next to bin/ and lib/ by generateBuildInfo (m1-12).
            "-Dloop.buildInfo=__APP_HOME__/build-info.properties",
            "--enable-native-access=ALL-UNNAMED",
        )
}

/**
 * The checkout's commit as 12 hex digits, with `-dirty` when tracked files have changes, or
 * `unknown` when git, the repository or the commit is unavailable. Never fails the build. Only
 * read at execution time through [GenerateBuildInfo], so the configuration cache never runs git.
 */
abstract class GitVersionSource : ValueSource<String, GitVersionSource.Parameters> {
    interface Parameters : ValueSourceParameters {
        val repository: DirectoryProperty
    }

    @get:Inject
    abstract val exec: ExecOperations

    override fun obtain(): String =
        try {
            val sha = git("rev-parse", "--short=12", "HEAD")
            val status = git("status", "--porcelain", "--untracked-files=no")
            when {
                sha == null || status == null || !Regex("[0-9a-f]{12}").matches(sha) -> UNKNOWN
                status.isEmpty() -> sha
                else -> "$sha-dirty"
            }
        } catch (e: GradleException) {
            // git is missing or cannot start (an exec failure): the build goes on without a version.
            UNKNOWN
        }

    /** The trimmed output of a git command, null when it exits non zero. */
    private fun git(vararg args: String): String? {
        val out = ByteArrayOutputStream()
        val result =
            exec.exec {
                workingDir = parameters.repository.get().asFile
                commandLine(listOf("git") + args)
                standardOutput = out
                errorOutput = OutputStream.nullOutputStream()
                isIgnoreExitValue = true
            }
        return if (result.exitValue == 0) out.toString(Charsets.UTF_8).trim() else null
    }

    private companion object {
        const val UNKNOWN = "unknown"
    }
}

/** Writes `build-info.properties` (`version=...`), shipped at the root of the distribution (m1-12). */
abstract class GenerateBuildInfo : DefaultTask() {
    @get:Input
    abstract val version: Property<String>

    @get:OutputFile
    abstract val output: RegularFileProperty

    @TaskAction
    fun write() {
        output.get().asFile.writeText("version=${version.get()}\n")
    }
}

val generateBuildInfo =
    tasks.register<GenerateBuildInfo>("generateBuildInfo") {
        version.set(
            providers.of(GitVersionSource::class) {
                parameters.repository.set(layout.projectDirectory.dir(".."))
            },
        )
        output.set(layout.buildDirectory.file("build-info/build-info.properties"))
    }

// At the app home root, never in the jar: a new commit must not change the cli classpath.
distributions {
    main {
        contents {
            from(generateBuildInfo)
        }
    }
}

dependencies {
    implementation(project(":spec-core"))
    implementation(project(":plugin-api"))
    implementation(project(":store"))
    implementation(project(":mcp"))
    implementation(project(":plugins:tracker-files"))
    implementation(project(":plugins:tracker-github"))
    implementation(project(":plugins:runner-command"))
    implementation(project(":plugins:runner-process"))
    implementation(project(":plugins:runner-claude-code"))
    // m3-02: on the classpath for plugin loading (m3-04), so the codex-app-server runner registers through its own
    // META-INF/services/loop.api.RunnerProvider (m3-17) with no change to Host.kt.
    implementation(project(":plugins:runner-codex-app-server"))
    implementation(libs.clikt)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.serialization.json)
    // m2-25: the git smart HTTP server the pull request delivery test pushes to.
    testImplementation(testFixtures(project(":plugins:tracker-github")))
}

tasks.startScripts {
    doLast {
        // Only the task's own properties and literals here, so the configuration cache
        // never captures the build script.
        val scripts = this as CreateStartScripts
        // DEFAULT_JVM_OPTS is single quoted in the unix script: close the quote, expand
        // APP_HOME (computed earlier in the script), reopen.
        val unix = scripts.unixScript
        unix.writeText(unix.readText().replace("__APP_HOME__", "'\"\$APP_HOME\"'"))
        val windows = scripts.windowsScript
        windows.writeText(windows.readText().replace("__APP_HOME__", "%APP_HOME%"))
    }
}

val launcherInstallDir = layout.buildDirectory.dir("install/loop")

// Loop's own worktree hook scripts, tested against temporary repositories (m1-04 plan R11).
val hookScriptsDir = layout.projectDirectory.dir("../scripts/hooks")

// Loop's own WORKFLOW.md, checked by OwnWorkflowTest (m1-12).
val rootWorkflow = layout.projectDirectory.file("../WORKFLOW.md")

// The GitHub API responses the github tracker tests recorded, served to `loop run` by GitHubRunCommandTest (bl-23).
val githubRecordedDir = layout.projectDirectory.dir("../plugins/tracker-github/src/test/resources/github/recorded")

tasks.test {
    // Hook tests run real git in temporary repositories: no GIT_* variable from the host.
    setEnvironment(environment.filterKeys { !it.startsWith("GIT_") })
    // The cli tests spawn the installed launcher end to end.
    dependsOn(tasks.installDist)
    // Without build-info.properties: a new commit alone does not rerun the cli tests.
    inputs
        .files(fileTree(launcherInstallDir) { exclude("build-info.properties") })
        .withPropertyName("launcherInstall")
        .withPathSensitivity(PathSensitivity.RELATIVE)
    inputs
        .dir(hookScriptsDir)
        .withPropertyName("hookScripts")
        .withPathSensitivity(PathSensitivity.RELATIVE)
    inputs
        .file(rootWorkflow)
        .withPropertyName("rootWorkflow")
        .withPathSensitivity(PathSensitivity.RELATIVE)
    systemProperty("loop.hooksDir", hookScriptsDir.asFile.absolutePath)
    inputs
        .dir(githubRecordedDir)
        .withPropertyName("githubRecorded")
        .withPathSensitivity(PathSensitivity.RELATIVE)
    systemProperty("loop.rootWorkflow", rootWorkflow.asFile.absolutePath)
    systemProperty("loop.githubRecorded", githubRecordedDir.asFile.absolutePath)
    systemProperty(
        "loop.launcher",
        launcherInstallDir
            .get()
            .file("bin/loop")
            .asFile.absolutePath,
    )
}

/*
 * Installs the build outside the repository for self hosting (m1-12): runs the freshly installed
 * launcher's hidden `loop install`, which refuses while a run is live and replaces the previous
 * install. Dest: `-PloopInstallDir` (relative to the repository root), else `$LOOP_HOME/install`,
 * LOOP_HOME defaulting to `~/.loop`. Never part of build or check, and never up to date.
 */
tasks.register<Exec>("installLoop") {
    group = "distribution"
    description = "Installs this build outside the repository, default \$LOOP_HOME/install"
    dependsOn(tasks.installDist)
    // Plain values and providers only, so the configuration cache never captures the build script.
    val repoDir =
        layout.projectDirectory
            .dir("..")
            .asFile.canonicalPath
    // The client's env, not the daemon's: the same LOOP_HOME and HOME a `loop run` from this shell sees.
    val home = providers.environmentVariable("HOME").orElse(providers.systemProperty("user.home"))
    val loopHome = providers.environmentVariable("LOOP_HOME").orElse(home.map { "$it/.loop" })
    val dest =
        providers
            .gradleProperty("loopInstallDir")
            .map { File(it).let { file -> if (file.isAbsolute) file else File(repoDir, it) }.path }
            .orElse(loopHome.map { "$it/install" })
    executable =
        launcherInstallDir
            .get()
            .file("bin/loop")
            .asFile.absolutePath
    argumentProviders.add(CommandLineArgumentProvider { listOf("install", "--repo", repoDir, dest.get()) })
    doFirst {
        val exec = this as Exec
        exec.environment("LOOP_HOME", loopHome.get())
        exec.environment("HOME", home.get())
    }
    outputs.upToDateWhen { false }
}
