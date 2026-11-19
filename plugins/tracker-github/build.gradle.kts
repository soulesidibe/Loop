plugins {
    id("loop.kotlin-jvm")
    `java-test-fixtures`
}

dependencies {
    implementation(project(":plugin-api"))
    implementation(libs.kotlinx.serialization.json)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(testFixtures(project(":plugin-api")))
    // m2-25: a git smart HTTP server on loopback, for the push tests here and in cli.
    testFixturesImplementation(kotlin("stdlib"))
}

// The adapter profile (spec 11.2 and 17.3) is checked against the adapter by GitHubTrackerTest.
val adapterProfile = layout.projectDirectory.file("../../docs/plugins/tracker-github.md")

tasks.test {
    inputs
        .file(adapterProfile)
        .withPropertyName("adapterProfile")
        .withPathSensitivity(PathSensitivity.RELATIVE)
    systemProperty("loop.adapterProfile", adapterProfile.asFile.absolutePath)
}
