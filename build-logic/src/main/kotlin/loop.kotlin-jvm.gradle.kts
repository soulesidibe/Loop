plugins {
    id("org.jetbrains.kotlin.jvm")
    id("org.jetbrains.kotlin.plugin.serialization")
    id("org.jlleitschuh.gradle.ktlint")
}

group = "loop"

val libs = versionCatalogs.named("libs")

kotlin {
    jvmToolchain(21)
    compilerOptions {
        // Warnings fail the build. Escape hatch: suppress one warning at its
        // site with @Suppress and a reason, or, for a module-wide blocker, set
        // allWarningsAsErrors = false in that module's build file with a comment
        // linking the issue that removes it.
        allWarningsAsErrors.set(true)
    }
}

dependencies {
    "testImplementation"(kotlin("test"))
    "testImplementation"(platform(libs.findLibrary("junit-bom").get()))
    "testImplementation"(libs.findLibrary("junit-jupiter").get())
    "testRuntimeOnly"(libs.findLibrary("junit-platform-launcher").get())
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
    // Run test classes across worker JVMs so each fork keeps its own ForkJoinPool.commonPool() and modules do not oversubscribe.
    maxParallelForks = (Runtime.getRuntime().availableProcessors() / 2).coerceIn(1, 4)
    // Keep sqlite-jdbc's native library extraction out of the host's TMPDIR, which can hold hundreds of thousands of files.
    val sqliteTmpDir = layout.buildDirectory.dir("tmp/sqlite")
    jvmArgumentProviders.add(
        CommandLineArgumentProvider {
            val dir = sqliteTmpDir.get().asFile
            dir.mkdirs()
            listOf("-Dorg.sqlite.tmpdir=${dir.absolutePath}")
        },
    )
    testLogging {
        events("passed", "skipped", "failed")
    }
}
