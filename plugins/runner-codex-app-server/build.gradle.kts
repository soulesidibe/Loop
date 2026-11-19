plugins {
    id("loop.kotlin-jvm")
}

// m3-02: the Kotlin types of the codex app server protocol are generated at build time from the schema committed in
// schema/, written by the codex CLI pinned as codex-cli in gradle/libs.versions.toml. The generator lives in its own
// source set, so the types always match the committed schema; regenerating the schema (schema/regenerate.sh) is the
// only manual step.
val generator: SourceSet by sourceSets.creating

dependencies {
    // The generated types expose JsonElement for the parts of the schema they leave untyped.
    api(libs.kotlinx.serialization.json)
    implementation(project(":plugin-api"))
    implementation(project(":plugins:runner-process"))
    implementation(libs.kotlinx.coroutines.core)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(testFixtures(project(":plugin-api")))
    "generatorImplementation"(libs.kotlinx.serialization.json)
    testImplementation(generator.output)
}

val schemaDir = layout.projectDirectory.dir("schema")
val schemaFile = schemaDir.file("codex_app_server_protocol.schemas.json")
val schemaVersionFile = schemaDir.file("codex-version")
val codexVersion = libs.versions.codex.cli
val generatedDir = layout.buildDirectory.dir("generated/codex-protocol")

val generateCodexProtocol =
    tasks.register<JavaExec>("generateCodexProtocol") {
        description = "Generates the Kotlin types of the codex app server protocol from schema/"
        classpath = generator.runtimeClasspath
        mainClass.set("loop.runner.codex.schema.ProtocolGeneratorKt")
        javaLauncher.set(javaToolchains.launcherFor { languageVersion.set(JavaLanguageVersion.of(21)) })
        inputs.file(schemaFile).withPropertyName("schema").withPathSensitivity(PathSensitivity.RELATIVE)
        inputs.file(schemaVersionFile).withPropertyName("schemaVersion").withPathSensitivity(PathSensitivity.RELATIVE)
        inputs.property("codexVersion", codexVersion)
        outputs.dir(generatedDir).withPropertyName("generated")
        // Plain values and providers only, so the configuration cache never captures the build script.
        val schemaPath = schemaFile.asFile.path
        val versionPath = schemaVersionFile.asFile.path
        val version = codexVersion
        val outDir = generatedDir
        argumentProviders.add(
            CommandLineArgumentProvider { listOf(schemaPath, versionPath, version.get(), outDir.get().asFile.path) },
        )
    }

kotlin.sourceSets.named("main") {
    kotlin.srcDir(generateCodexProtocol)
}

// The generated sources follow the schema, not the code style.
ktlint {
    filter {
        exclude { it.file.path.contains("${File.separator}generated${File.separator}") }
    }
}

tasks.test {
    inputs.dir(schemaDir).withPropertyName("schemaDir").withPathSensitivity(PathSensitivity.RELATIVE)
    systemProperty("loop.codexSchema", schemaFile.asFile.absolutePath)
    systemProperty("loop.codexSchemaVersion", schemaVersionFile.asFile.absolutePath)
    systemProperty("loop.codexVersion", codexVersion.get())
}
