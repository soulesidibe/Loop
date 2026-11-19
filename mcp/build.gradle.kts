plugins {
    id("loop.kotlin-jvm")
}

dependencies {
    implementation(libs.mcp.kotlin.sdk.server)
    // The SDK logs through SLF4J; Loop keeps stderr for its own lines and stdout for the protocol.
    runtimeOnly(libs.slf4j.nop)
    implementation(libs.kotlinx.coroutines.core)
    api(libs.kotlinx.serialization.json)
    testImplementation(libs.kotlinx.coroutines.test)
}
