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

tasks.test {
    setEnvironment(environment.filterKeys { !it.startsWith("GIT_") })
    // A fixed host variable for the hook environment test.
    environment("HOOK_TEST_HOST_VAR", "x")
}
