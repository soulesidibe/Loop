plugins {
    id("loop.kotlin-jvm")
}

dependencies {
    implementation(project(":plugin-api"))
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.serialization.json)
    // The OpenTelemetry SDK and the OTLP exporter, built on EventSink (m4-01).
    implementation(platform(libs.opentelemetry.bom))
    implementation(libs.opentelemetry.sdk)
    implementation(libs.opentelemetry.exporter.otlp)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(testFixtures(project(":plugin-api")))
}
