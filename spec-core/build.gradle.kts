plugins {
    id("loop.kotlin-jvm")
}

dependencies {
    api(project(":plugin-api"))
    implementation(libs.liqp)
    implementation(libs.kaml)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.core)
    testImplementation(libs.kotlinx.coroutines.test)
    // m4-30: HumanChannelContract runs against the terminal channel.
    testImplementation(testFixtures(project(":plugin-api")))
    // bl-79: the round trip of the waits through the machine level store.
    testImplementation(project(":store"))
}

// The GitHub API responses the github tracker tests recorded, read by relative path by CiWaitOrchestratorTest and the
// reaction tests (bl-207), declared the way cli declares githubRecorded.
val githubRecordedDir = layout.projectDirectory.dir("../plugins/tracker-github/src/test/resources/github/recorded")

tasks.test {
    setEnvironment(environment.filterKeys { !it.startsWith("GIT_") })
    // A fixed host variable for the hook environment test.
    environment("HOOK_TEST_HOST_VAR", "x")
    inputs
        .dir(githubRecordedDir)
        .withPropertyName("githubRecorded")
        .withPathSensitivity(PathSensitivity.RELATIVE)
}
