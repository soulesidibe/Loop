plugins {
    id("loop.kotlin-jvm")
}

// Test only module: the conformance suite lives in src/test.
dependencies {
    testImplementation(project(":spec-core"))
    testImplementation(libs.kotlinx.coroutines.core)
    testImplementation(libs.kotlinx.coroutines.test)
}

tasks.test {
    // Hook tests run real git in temporary repositories: no GIT_* variable from the host.
    setEnvironment(environment.filterKeys { !it.startsWith("GIT_") })
}

// bl-154: ConformanceMatrixTest checks that every test file and test name the
// conformance test matrix of docs/spec-decisions.md lists exists. The test sources
// of every module are inputs, so a renamed or deleted test reruns the check.
val repoRoot = layout.projectDirectory.dir("..")
val specDecisions = repoRoot.file("docs/spec-decisions.md")
val testSources =
    fileTree(repoRoot) {
        include("*/src/test*/kotlin/**/*.kt", "plugins/*/src/test*/kotlin/**/*.kt")
    }

tasks.test {
    inputs
        .file(specDecisions)
        .withPropertyName("specDecisions")
        .withPathSensitivity(PathSensitivity.RELATIVE)
    inputs
        .files(testSources)
        .withPropertyName("testSources")
        .withPathSensitivity(PathSensitivity.RELATIVE)
    systemProperty("loop.specDecisions", specDecisions.asFile.absolutePath)
    systemProperty("loop.repoRoot", repoRoot.asFile.absolutePath)
}
