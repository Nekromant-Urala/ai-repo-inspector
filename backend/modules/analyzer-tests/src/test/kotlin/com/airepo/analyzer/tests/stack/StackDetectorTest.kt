package com.airepo.analyzer.tests.stack

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class StackDetectorTest {

    private val detector = StackDetector()

    @Test
    fun `detects gradle project with wrapper`(@TempDir root: Path) {
        Files.writeString(root.resolve("build.gradle.kts"), "plugins { kotlin(\"jvm\") }")
        Files.writeString(root.resolve("settings.gradle.kts"), "rootProject.name = \"app\"")
        Files.writeString(root.resolve("gradlew"), "#!/bin/sh")

        val plan = detector.detect(root).single { it.id == "gradle" }

        assertEquals(".", plan.workingDirectory)
        assertEquals("eclipse-temurin:21-jdk", plan.image)
        assertTrue(plan.testCommand.contains("--init-script"))
        assertTrue(plan.injectedFiles.containsKey(TestStackCatalog.JACOCO_INIT_SCRIPT_FILE))
        assertTrue(plan.coverageArtifacts.any { it.format == CoverageFormat.JACOCO_XML })
    }

    @Test
    fun `detects gradle project without wrapper`(@TempDir root: Path) {
        Files.writeString(root.resolve("build.gradle"), "plugins { id 'java' }")

        val plan = detector.detect(root).single { it.id == "gradle" }

        assertEquals("gradle", plan.testCommand.first())
        assertEquals("gradle:8.10-jdk21", plan.image)
    }

    @Test
    fun `detects jest project with coverage flags`(@TempDir root: Path) {
        Files.writeString(root.resolve("package.json"), """{"devDependencies":{"jest":"29.7.0"}}""")
        Files.writeString(root.resolve("package-lock.json"), "{}")

        val plan = detector.detect(root).single { it.id == "node" }

        assertEquals("node:20-alpine", plan.image)
        assertTrue(plan.testCommand.contains("jest"))
        assertTrue(plan.testCommand.contains("--coverage"))
        assertTrue(plan.coverageArtifacts.any { it.format == CoverageFormat.LCOV })
        assertTrue(plan.bootstrapCommands.isNotEmpty())
    }

    @Test
    fun `vitest without coverage plugin runs without coverage flags`(@TempDir root: Path) {
        Files.writeString(root.resolve("package.json"), """{"devDependencies":{"vitest":"2.0.0"}}""")

        val plan = detector.detect(root).single { it.id == "node" }

        assertFalse(plan.testCommand.contains("--coverage"))
        assertTrue(plan.notes.any { it.contains("@vitest/coverage") })
    }

    @Test
    fun `detects nested maven module relative to repository root`(@TempDir root: Path) {
        val module = root.resolve("backend/api")
        Files.createDirectories(module)
        Files.writeString(module.resolve("pom.xml"), "<project/>")

        val plan = detector.detect(root).single { it.id == "maven" }

        assertEquals("backend/api", plan.workingDirectory)
        assertEquals("backend/api/**/target/site/jacoco/jacoco.xml", plan.coverageArtifacts.first().glob)
    }

    @Test
    fun `detects go and python projects in the same directory`(@TempDir root: Path) {
        Files.writeString(root.resolve("go.mod"), "module example.com/app")
        Files.writeString(root.resolve("main.go"), "package main")
        Files.writeString(root.resolve("requirements.txt"), "pytest")

        val ids = detector.detect(root).map { it.id }.toSet()

        assertTrue(ids.contains("go"), "Go project expected, actual: $ids")
        assertTrue(ids.contains("python"), "Python project expected, actual: $ids")
    }

    @Test
    fun `detects dotnet project`(@TempDir root: Path) {
        Files.writeString(root.resolve("App.csproj"), "<Project/>")

        val plan = detector.detect(root).single { it.id == "dotnet" }

        assertEquals("mcr.microsoft.com/dotnet/sdk:8.0", plan.image)
        assertTrue(plan.testCommand.any { it.contains("XPlat Code Coverage") })
        assertTrue(plan.coverageArtifacts.any { it.format == CoverageFormat.COBERTURA_XML })
    }

    @Test
    fun `detects php coverage only when a driver is declared`(@TempDir root: Path) {
        Files.writeString(
            root.resolve("composer.json"),
            """{"require-dev":{"phpunit/phpunit":"^10.0","xdebug/xdebug":"3.3.0"}}""",
        )

        val plan = detector.detect(root).single { it.id == "php" }

        assertTrue(plan.testCommand.contains("--coverage-clover"))
        assertTrue(plan.coverageArtifacts.any { it.format == CoverageFormat.CLOVER_XML })
    }

    @Test
    fun `does not start php coverage without a driver`(@TempDir root: Path) {
        Files.writeString(
            root.resolve("composer.json"),
            """{"require-dev":{"phpunit/phpunit":"^10.0"}}""",
        )

        val plan = detector.detect(root).single { it.id == "php" }

        assertFalse(plan.testCommand.contains("--coverage-clover"))
        assertTrue(plan.notes.isNotEmpty())
    }

    @Test
    fun `skips dependency and build directories`(@TempDir root: Path) {
        val nodeModules = root.resolve("node_modules/some-lib")
        Files.createDirectories(nodeModules)
        Files.writeString(nodeModules.resolve("package.json"), "{}")

        val buildDirectory = root.resolve("build")
        Files.createDirectories(buildDirectory)
        Files.writeString(buildDirectory.resolve("pom.xml"), "<project/>")

        assertTrue(detector.detect(root).isEmpty())
    }

    @Test
    fun `respects plan limit`(@TempDir root: Path) {
        Files.writeString(root.resolve("go.mod"), "module example.com/app")
        Files.writeString(root.resolve("requirements.txt"), "pytest")
        Files.writeString(root.resolve("Cargo.toml"), "[package]\nname = \"app\"")

        val plans = detector.detect(root, DetectionOptions(maxPlans = 1))

        assertEquals(1, plans.size)
    }

    @Test
    fun `gradle subprojects are covered by the root build only`(@TempDir root: Path) {
        Files.writeString(root.resolve("settings.gradle.kts"), "include(\":api\")")
        Files.writeString(root.resolve("build.gradle.kts"), "")
        val api = root.resolve("api")
        Files.createDirectories(api)
        Files.writeString(api.resolve("build.gradle.kts"), "")

        val gradlePlans = detector.detect(root).filter { it.id == "gradle" }

        assertEquals(1, gradlePlans.size)
        assertEquals(".", gradlePlans.single().workingDirectory)
    }

    @Test
    fun `maven modules are covered by the root reactor`(@TempDir root: Path) {
        Files.writeString(root.resolve("pom.xml"), "<project><modules><module>api</module></modules></project>")
        val api = root.resolve("api")
        Files.createDirectories(api)
        Files.writeString(api.resolve("pom.xml"), "<project/>")

        val mavenPlans = detector.detect(root).filter { it.id == "maven" }

        assertEquals(1, mavenPlans.size)
        assertEquals(".", mavenPlans.single().workingDirectory)
    }

    @Test
    fun `node workspaces are covered by the root package`(@TempDir root: Path) {
        Files.writeString(
            root.resolve("package.json"),
            """{"workspaces":["apps/*"],"devDependencies":{"jest":"29.7.0"}}""",
        )
        val app = root.resolve("apps/web")
        Files.createDirectories(app)
        Files.writeString(app.resolve("package.json"), """{"name":"web"}""")

        val nodePlans = detector.detect(root).filter { it.id == "node" }

        assertEquals(1, nodePlans.size)
        assertEquals(".", nodePlans.single().workingDirectory)
    }

    @Test
    fun `cargo workspace members are covered by the root manifest`(@TempDir root: Path) {
        Files.writeString(root.resolve("Cargo.toml"), "[workspace]\nmembers = [\"crates/app\"]")
        val crate = root.resolve("crates/app")
        Files.createDirectories(crate)
        Files.writeString(crate.resolve("Cargo.toml"), "[package]\nname = \"app\"")

        val rustPlans = detector.detect(root).filter { it.id == "rust" }

        assertEquals(1, rustPlans.size)
        assertEquals(".", rustPlans.single().workingDirectory)
    }

    @Test
    fun `dotnet solution projects are covered by the solution`(@TempDir root: Path) {
        Files.writeString(root.resolve("App.sln"), "Microsoft Visual Studio Solution File")
        val project = root.resolve("src/App")
        Files.createDirectories(project)
        Files.writeString(project.resolve("App.csproj"), "<Project/>")

        val dotnetPlans = detector.detect(root).filter { it.id == "dotnet" }

        assertEquals(1, dotnetPlans.size)
        assertEquals(".", dotnetPlans.single().workingDirectory)
    }
}
