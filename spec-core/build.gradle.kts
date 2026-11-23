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
    // bl-79: the round trip of the waits through the machine level store.
    testImplementation(project(":store"))
}

// m4-07: the GitHub answers recorded in m4-00, read by CiWaitOrchestratorTest for the run GitHub refused to start.
val githubRecordedDir = layout.projectDirectory.dir("../plugins/tracker-github/src/test/resources/github/recorded")

tasks.test {
    inputs
        .dir(githubRecordedDir)
        .withPropertyName("githubRecorded")
        .withPathSensitivity(PathSensitivity.RELATIVE)
    systemProperty("loop.githubRecorded", githubRecordedDir.asFile.absolutePath)
    setEnvironment(environment.filterKeys { !it.startsWith("GIT_") })
    // A fixed host variable for the hook environment test.
    environment("HOOK_TEST_HOST_VAR", "x")
}
