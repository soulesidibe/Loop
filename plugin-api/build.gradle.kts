plugins {
    id("loop.kotlin-jvm")
    `java-test-fixtures`
}

kotlin {
    explicitApi()
}

dependencies {
    // Flow is part of the AgentRunner signature.
    api(libs.kotlinx.coroutines.core)

    // The AgentRunner contract suite, run by each runner's own tests.
    testFixturesApi(kotlin("test"))
    testFixturesApi(platform(libs.junit.bom))
    testFixturesApi(libs.junit.jupiter)
    testFixturesApi(libs.kotlinx.coroutines.core)
}
