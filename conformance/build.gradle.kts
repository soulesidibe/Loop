plugins {
    id("loop.kotlin-jvm")
}

// Test only module: the conformance suite lives in src/test.
dependencies {
    testImplementation(project(":spec-core"))
    testImplementation(libs.kotlinx.coroutines.core)
    testImplementation(libs.kotlinx.coroutines.test)
}

// ConformanceMatrixTest finds the repository root from user.dir and reads the matrix of docs/spec-decisions.md, then
// every test file it names in any module (bl-154). Declared as inputs so the build cache cannot skip the check when
// either changes (bl-207). The matrix also names test fixtures, so those folders are inputs too. The patterns stay
// anchored so the walk skips build/, workspaces/ and .claude/worktrees/: a matrix path in a new folder layout
// (src/main, a deeper module) must be added here, or the check can pass from cache again.
val repositoryRoot = layout.projectDirectory.dir("..")
val specDecisions = repositoryRoot.file("docs/spec-decisions.md")
val testSources =
    fileTree(repositoryRoot) {
        include("*/src/test/**", "plugins/*/src/test/**", "*/src/testFixtures/**", "plugins/*/src/testFixtures/**")
    }

tasks.test {
    // Hook tests run real git in temporary repositories: no GIT_* variable from the host.
    setEnvironment(environment.filterKeys { !it.startsWith("GIT_") })
    inputs
        .file(specDecisions)
        .withPropertyName("specDecisions")
        .withPathSensitivity(PathSensitivity.RELATIVE)
    inputs
        .files(testSources)
        .withPropertyName("testSources")
        .withPathSensitivity(PathSensitivity.RELATIVE)
}
