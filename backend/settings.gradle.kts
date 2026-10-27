pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
        google()
    }
}

plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

rootProject.name = "ai-repo-backend"

include(
    "modules:common",
    "modules:domain",
    "modules:test-support",
    "modules:storage",
    "modules:ingestion",
    "modules:analyzer-api",
    "modules:analyzer-readme",
    "modules:analyzer-code",
    "modules:analyzer-tests",
    "modules:analyzer-structure",
    "modules:analyzer-git",
    "modules:analyzer-security",
    "modules:report",
    "modules:web",
)