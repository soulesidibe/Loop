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
    // Run test classes and methods concurrently inside one worker JVM so large test classes do not serialize the suite.
    // Factor 0.5 avoids oversubscription when Gradle runs several subprojects' test tasks in parallel.
    systemProperty("junit.jupiter.execution.parallel.enabled", "true")
    systemProperty("junit.jupiter.execution.parallel.mode.default", "concurrent")
    systemProperty("junit.jupiter.execution.parallel.mode.classes.default", "concurrent")
    systemProperty("junit.jupiter.execution.parallel.config.strategy", "dynamic")
    systemProperty("junit.jupiter.execution.parallel.config.dynamic.factor", "0.5")
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
