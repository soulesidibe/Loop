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
