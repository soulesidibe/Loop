plugins {
    id("loop.kotlin-jvm")
}

dependencies {
    implementation(project(":plugin-api"))
    implementation(libs.kaml)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(testFixtures(project(":plugin-api")))
}

// The adapter profile (spec 11.2 and 17.3) is checked against the adapter by FilesTrackerTest.
val adapterProfile = layout.projectDirectory.file("../../docs/plugins/tracker-files.md")

tasks.test {
    inputs
        .file(adapterProfile)
        .withPropertyName("adapterProfile")
        .withPathSensitivity(PathSensitivity.RELATIVE)
    systemProperty("loop.adapterProfile", adapterProfile.asFile.absolutePath)
}
