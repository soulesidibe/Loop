plugins {
    id("loop.kotlin-jvm")
}

dependencies {
    implementation(project(":plugin-api"))
    implementation(project(":plugins:runner-process"))
    implementation(libs.kotlinx.coroutines.core)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(testFixtures(project(":plugin-api")))
}
