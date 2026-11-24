import loop.buildlogic.version.GitVersion
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
 * The build's version and full commit from git (bl-159), parsed by [GitVersion]: `0.1.0` on a tag, `0.1.0-5-g<hex>`
 * after it, the 12 hex commit with no tag reachable, each with `-dirty` for tracked changes, or `unknown` without git.
 * Never fails the build. [GenerateBuildInfo] reads it at execution time; the distribution archives name themselves
 * after the version, so a build that includes them also runs git while configuring, and a new commit or a dirty tree
 * reconfigures it. Nothing compiled or tested depends on the version.
 */
abstract class GitVersionSource : ValueSource<GitVersion.Result, GitVersionSource.Parameters> {
    interface Parameters : ValueSourceParameters {
        val repository: DirectoryProperty
    }

    @get:Inject
    abstract val exec: ExecOperations

    override fun obtain(): GitVersion.Result =
        try {
            GitVersion.parse(
                describe = git("describe", "--tags", "--match", "[0-9]*", "--abbrev=12", "--always", "--dirty"),
                fullSha = git("rev-parse", "HEAD"),
            )
        } catch (e: GradleException) {
            // git is missing or cannot start (an exec failure): the build goes on without a version.
            GitVersion.parse(null, null)
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
}

/**
 * Writes `build-info.properties` (`version=...`, `commit=<full sha>`), shipped at the root of the distribution
 * (m1-12, bl-159).
 */
abstract class GenerateBuildInfo : DefaultTask() {
    @get:Input
    abstract val version: Property<String>

    @get:Input
    abstract val commit: Property<String>

    @get:OutputFile
    abstract val output: RegularFileProperty

    @TaskAction
    fun write() {
        output.get().asFile.writeText("version=${version.get()}\ncommit=${commit.get()}\n")
    }
}

val gitVersion =
    providers.of(GitVersionSource::class) {
        parameters.repository.set(layout.projectDirectory.dir(".."))
    }

val generateBuildInfo =
    tasks.register<GenerateBuildInfo>("generateBuildInfo") {
        version.set(gitVersion.map { it.version })
        commit.set(gitVersion.map { it.commit })
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
    // m4-01: on the runtime classpath for plugin loading, so each resource provider (m4-24) and exporter (m4-42)
    // registers through its own META-INF/services file, added by its own issue, with no Gradle change.
    runtimeOnly(project(":plugins:resource-ios"))
    runtimeOnly(project(":plugins:resource-android"))
    runtimeOnly(project(":plugins:export-otel"))
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
        // bl-159: the Java of `$APP_HOME/runtime/` first (Homebrew links its JDK there), then JAVA_HOME, then
        // java on the PATH. Nothing is exported: agents inherit Loop's environment and must keep their own JAVA_HOME.
        val javaAnchor = "if [ -n \"\$JAVA_HOME\" ] ; then\n"
        val runtimeJava =
            "if [ -x \"\$APP_HOME/runtime/bin/java\" ] ; then\n" +
                "    JAVACMD=\$APP_HOME/runtime/bin/java\n" +
                "elif [ -n \"\$JAVA_HOME\" ] ; then\n"
        val text = unix.readText()
        // A Gradle upgrade that changes the template must fail here, not silently drop the runtime lookup.
        check(text.split(javaAnchor).size == 2) { "the start script template changed: no single JAVA_HOME check" }
        unix.writeText(text.replace(javaAnchor, runtimeJava).replace("__APP_HOME__", "'\"\$APP_HOME\"'"))
        val windows = scripts.windowsScript
        windows.writeText(windows.readText().replace("__APP_HOME__", "%APP_HOME%"))
    }
}

// The release archives (bl-159): loop-<version>.tar.gz and loop-<version>.zip, top folder loop-<version>/, no
// Windows launcher. installDist keeps loop.bat. The excludes match the source path of the start script.
tasks.distTar {
    compression = Compression.GZIP
    archiveExtension.set("tar.gz")
}
listOf(tasks.distTar, tasks.distZip).forEach { archive ->
    archive.configure {
        archiveVersion.set(gitVersion.map { it.version })
        exclude("**/loop.bat")
    }
}

// The version parsing lives in build-logic, free of the Gradle API, and is unit tested here (bl-159).
kotlin.sourceSets.named("test") {
    kotlin.srcDir("../build-logic/src/main/kotlin/loop/buildlogic/version")
}

val launcherInstallDir = layout.buildDirectory.dir("install/loop")
val cdsArchiveFile = layout.buildDirectory.file("tmp/cds/loop.jsa")

// Dynamic AppCDS archive shared by short-lived test launcher JVMs in LoopFixture (m4 / CI speedup).
val generateTestCdsArchive =
    tasks.register<Exec>("generateTestCdsArchive") {
        dependsOn(tasks.installDist)
        // Without build-info.properties: a new commit alone does not regenerate the CDS archive.
        inputs
            .files(fileTree(launcherInstallDir) { exclude("build-info.properties") })
            .withPropertyName("launcherInstall")
            .withPathSensitivity(PathSensitivity.RELATIVE)
        outputs.file(cdsArchiveFile)
        val launcher = launcherInstallDir.map { it.file("bin/loop").asFile.absolutePath }
        val archive = cdsArchiveFile.map { it.asFile.absolutePath }
        val javaHome =
            javaToolchains
                .launcherFor { languageVersion.set(JavaLanguageVersion.of(21)) }
                .map { it.metadata.installationPath.asFile.absolutePath }
        executable = launcher.get()
        args("--help")
        doFirst {
            val exec = this as Exec
            val outFile = File(archive.get())
            outFile.parentFile.mkdirs()
            outFile.delete()
            exec.standardOutput = OutputStream.nullOutputStream()
            exec.environment("JAVA_HOME", javaHome.get())
            exec.environment(
                "LOOP_OPTS",
                "-XX:TieredStopAtLevel=1 -XX:+UseSerialGC -Xmx256m \"-XX:ArchiveClassesAtExit=${outFile.absolutePath}\"",
            )
        }
    }

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
    dependsOn(tasks.installDist, generateTestCdsArchive)
    val cdsArchivePath = cdsArchiveFile.map { it.asFile.absolutePath }
    jvmArgumentProviders.add(
        CommandLineArgumentProvider {
            listOf("-Dloop.cdsArchive=${cdsArchivePath.get()}")
        },
    )
    // JUnit runs test classes and methods concurrently in one worker JVM, capped so spawned launcher JVMs do not oversubscribe.
    maxParallelForks = 1
    val cliParallelism = (Runtime.getRuntime().availableProcessors() / 2).coerceIn(2, 6)
    systemProperty("junit.jupiter.execution.parallel.enabled", "true")
    systemProperty("junit.jupiter.execution.parallel.mode.default", "concurrent")
    systemProperty("junit.jupiter.execution.parallel.mode.classes.default", "concurrent")
    systemProperty("junit.jupiter.execution.parallel.config.strategy", "fixed")
    systemProperty("junit.jupiter.execution.parallel.config.fixed.parallelism", cliParallelism.toString())
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
    // Optional deterministic sharding for CI (`-PcliTestShard=1/4`): assigns each test source file to one shard
    // by greedy size-balanced packing (LPT), so all shards cover 100% of test classes with balanced runtime.
    providers.gradleProperty("cliTestShard").orNull?.let { spec ->
        val parts = spec.split("/")
        require(parts.size == 2) { "cliTestShard must be <index>/<total> (1-based), got: $spec" }
        val shardIndex = parts[0].toInt()
        val shardTotal = parts[1].toInt()
        require(shardTotal >= 1 && shardIndex in 1..shardTotal) {
            "cliTestShard out of range: $spec"
        }
        val testSrcRoot = layout.projectDirectory.dir("src/test/kotlin").asFile
        val testFiles =
            testSrcRoot
                .walkTopDown()
                .filter { it.isFile && it.extension == "kt" }
                .map { file -> file.relativeTo(testSrcRoot).invariantSeparatorsPath to file.length() }
                .sortedWith(compareByDescending<Pair<String, Long>> { it.second }.thenBy { it.first })
                .toList()
        val shardSizes = LongArray(shardTotal)
        val assigned = mutableListOf<String>()
        for ((relPath, size) in testFiles) {
            val target = shardSizes.indices.minBy { shardSizes[it] }
            shardSizes[target] += size
            if (target == shardIndex - 1) {
                val base = relPath.removeSuffix(".kt")
                assigned.add("$base.class")
                assigned.add("$base\$*.class")
            }
        }
        include(assigned)
    }
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
