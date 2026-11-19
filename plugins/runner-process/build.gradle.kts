plugins {
    id("loop.kotlin-jvm")
}

// Process group launch shared by the runners: setsid launcher, group signalling, output drain.
dependencies {
    api(project(":plugin-api"))
    implementation(libs.kotlinx.coroutines.core)
}
