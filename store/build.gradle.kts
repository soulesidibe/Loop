plugins {
    id("loop.kotlin-jvm")
}

dependencies {
    // The store implements plugin-api's EventSink and exposes its RecordedEvent.
    api(project(":plugin-api"))
    implementation(libs.sqlite.jdbc)
    implementation(libs.kotlinx.serialization.json)
    testImplementation(libs.kotlinx.coroutines.test)
}
