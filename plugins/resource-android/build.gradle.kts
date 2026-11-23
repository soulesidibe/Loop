plugins {
    id("loop.kotlin-jvm")
}

dependencies {
    implementation(project(":plugin-api"))
    // The providers spawn xcrun simctl, adb and emulator through the shared launch (m4-01).
    implementation(project(":plugins:runner-process"))
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.serialization.json)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(testFixtures(project(":plugin-api")))
}
