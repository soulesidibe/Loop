pluginManagement {
    includeBuild("build-logic")
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        mavenCentral()
    }
}

rootProject.name = "loop"

include(
    ":spec-core",
    ":plugin-api",
    ":store",
    ":mcp",
    ":plugins:tracker-files",
    ":plugins:tracker-github",
    ":plugins:runner-process",
    ":plugins:runner-command",
    ":plugins:runner-claude-code",
    ":plugins:runner-codex-app-server",
    ":conformance",
    ":cli",
)
