package com.airepo.analyzer.tests

import com.airepo.analyzer.AnalysisContext
import com.airepo.analyzer.tests.coverage.CoverageReport
import com.airepo.analyzer.tests.exec.ExecutionMode
import com.airepo.analyzer.tests.exec.TestRunOutcome
import com.airepo.analyzer.tests.exec.TestRunner
import com.airepo.analyzer.tests.stack.TestStackCatalog
import com.airepo.analyzer.tests.stack.TestStackCatalog.NodePackageManager
import com.airepo.analyzer.tests.stack.TestStackCatalog.NodeTestFramework
import com.airepo.analyzer.tests.stack.TestStackPlan
import com.airepo.domain.RepositorySource
import com.airepo.domain.Severity
import com.airepo.domain.SourceType
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TestsCoverageAnalyzerTest {

    @Test
    fun `project without tests gets error finding and zero score`(@TempDir root: Path) {
        val analyzer = analyzer(staticOnly())

        val result = analyzer.analyze(context(root))

        assertEquals(0.0, result.score)
        assertTrue(result.findings.any { it.severity == Severity.ERROR && it.message.contains("ни одного теста") })
    }

    @Test
    fun `coverage drives the score`(@TempDir root: Path) {
        writeNodeProject(root)
        val coverage = CoverageReport.of(80, 100, "LCOV")
        val runner = fakeRunner { plan -> outcome(plan, coverage = coverage) }

        val result = analyzer(properties(), listOf(runner)).analyze(context(root))

        assertEquals(100.0, result.score, 0.001)
        assertTrue(result.summary.contains("80.0%"), result.summary)
        assertTrue(result.summary.contains("Node.js"), result.summary)
        assertEquals(1, runner.invocations)
        assertTrue(result.findings.any { it.severity == Severity.INFO && it.message.contains("Покрытие") })
    }

    @Test
    fun `coverage below target is penalized proportionally`(@TempDir root: Path) {
        writeNodeProject(root)
        val coverage = CoverageReport.of(60, 100, "LCOV")
        val runner = fakeRunner { plan -> outcome(plan, coverage = coverage) }

        val result = analyzer(properties(), listOf(runner)).analyze(context(root))

        assertEquals(75.0, result.score, 0.001)
        assertTrue(result.findings.any { it.severity == Severity.WARNING && it.message.contains("ниже целевого") })
    }

    @Test
    fun `coverage below minimum is an error`(@TempDir root: Path) {
        writeNodeProject(root)
        val coverage = CoverageReport.of(20, 100, "LCOV")
        val runner = fakeRunner { plan -> outcome(plan, coverage = coverage) }

        val result = analyzer(properties(), listOf(runner)).analyze(context(root))

        assertEquals(25.0, result.score, 0.001)
        assertTrue(result.findings.any { it.severity == Severity.ERROR && it.message.contains("ниже минимального") })
    }

    @Test
    fun `failed tests cap the score and expose output tail`(@TempDir root: Path) {
        writeNodeProject(root)
        val runner = fakeRunner { plan -> outcome(plan, exitCode = 1, output = "AssertionError: expected 1 but got 2") }

        val result = analyzer(properties(), listOf(runner)).analyze(context(root))

        assertEquals(40.0, result.score, 0.001)
        val failure = result.findings.first { it.severity == Severity.ERROR && it.message.contains("код 1") }
        assertTrue(failure.message.contains("AssertionError"), failure.message)
    }

    @Test
    fun `successful tests without coverage are capped`(@TempDir root: Path) {
        writeNodeProject(root)
        val runner = fakeRunner { plan -> outcome(plan, coverage = null) }

        val result = analyzer(properties(), listOf(runner)).analyze(context(root))

        assertEquals(48.0, result.score, 0.001)
        assertTrue(result.findings.any { it.severity == Severity.WARNING && it.message.contains("файл покрытия не найден") })
    }

    @Test
    fun `docker unavailability falls back to static analysis`(@TempDir root: Path) {
        writeNodeProject(root)
        val runner = fakeRunner(available = false) { plan -> outcome(plan) }

        val result = analyzer(properties(), listOf(runner)).analyze(context(root))

        assertEquals(0, runner.invocations)
        assertEquals(48.0, result.score, 0.001)
        assertTrue(result.findings.any { it.message.contains("Docker недоступен") })
    }

    @Test
    fun `disabled execution never calls the runner`(@TempDir root: Path) {
        writeNodeProject(root)
        val runner = fakeRunner { plan -> outcome(plan) }
        val properties = properties().apply { enabled = false }

        val result = analyzer(properties, listOf(runner)).analyze(context(root))

        assertEquals(0, runner.invocations)
        assertTrue(result.findings.any { it.message.contains("отключён") })
    }

    @Test
    fun `local runner produces an isolation warning`(@TempDir root: Path) {
        writeNodeProject(root)
        val coverage = CoverageReport.of(90, 100, "LCOV")
        val runner = fakeRunner(
            mode = ExecutionMode.LOCAL,
            name = "local",
            available = true,
        ) { plan -> outcome(plan, coverage = coverage) }
        val properties = properties().apply {
            mode = ExecutionMode.LOCAL
            allowLocalExecution = true
        }

        val result = analyzer(properties, listOf(runner)).analyze(context(root))

        assertTrue(result.findings.any { it.message.contains("без контейнерной изоляции") })
        assertEquals(100.0, result.score, 0.001)
    }

    @Test
    fun `static mode reports detected coverage tooling`(@TempDir root: Path) {
        Files.writeString(root.resolve("build.gradle.kts"), "plugins { id(\"jacoco\") }")
        Files.writeString(root.resolve("gradlew"), "#!/bin/sh")
        Files.createDirectories(root.resolve("src/test/kotlin"))
        Files.writeString(root.resolve("src/test/kotlin/AppTest.kt"), "class AppTest")

        val result = analyzer(staticOnly()).analyze(context(root))

        assertEquals(98.0, result.score, 0.001)
        assertTrue(result.findings.any { it.message.contains("JaCoCo") }, result.findings.toString())
    }

    @Test
    fun `unsupported stack is reported without failing the criterion`(@TempDir root: Path) {
        Files.createDirectories(root.resolve("src/test"))
        Files.writeString(root.resolve("src/test/Example.kt"), "class Example")

        val result = analyzer(properties(), emptyList()).analyze(context(root))

        assertEquals(50.0, result.score, 0.001)
        assertTrue(result.findings.any { it.message.contains("поддерживаемый стек") })
    }

    @Test
    fun `image overrides are applied by stack id`() {
        val properties = TestsModuleProperties()
        properties.images["node"] = "node:22-alpine"
        val plan = TestStackCatalog.node(".", NodePackageManager.NPM, NodeTestFramework.JEST, true)

        assertEquals("node:22-alpine", properties.imageFor(plan))
        assertEquals(plan.image, TestsModuleProperties().imageFor(plan))
    }

    // --------------------------------------------------------------- helpers

    private fun properties(): TestsModuleProperties = TestsModuleProperties().apply {
        mode = ExecutionMode.DOCKER
        timeout = Duration.ofMinutes(1)
        targetCoverage = 80.0
        warningCoverage = 50.0
    }

    private fun staticOnly(): TestsModuleProperties = TestsModuleProperties().apply {
        mode = ExecutionMode.STATIC_ONLY
    }

    private fun analyzer(
        properties: TestsModuleProperties,
        runners: List<TestRunner> = emptyList(),
    ): TestsCoverageAnalyzer = TestsCoverageAnalyzer(properties, runners)

    private fun context(root: Path): AnalysisContext = AnalysisContext(
        rootDir = root,
        source = RepositorySource(SourceType.GIT_URL, "https://github.com/example/app"),
    )

    private fun writeNodeProject(root: Path) {
        Files.writeString(
            root.resolve("package.json"),
            """{"devDependencies":{"jest":"29.7.0"},"scripts":{"test":"jest"}}""",
        )
        val source = root.resolve("src")
        Files.createDirectories(source)
        Files.writeString(source.resolve("app.test.ts"), "test('works', () => {})")
    }

    private fun fakeRunner(
        mode: ExecutionMode = ExecutionMode.DOCKER,
        name: String = "docker",
        available: Boolean = true,
        result: (TestStackPlan) -> TestRunOutcome,
    ): FakeRunner = FakeRunner(mode, name, available, result)

    private fun outcome(
        plan: TestStackPlan,
        coverage: CoverageReport? = null,
        exitCode: Int? = 0,
        output: String = "",
    ): TestRunOutcome = TestRunOutcome(
        runner = "docker",
        plan = plan,
        exitCode = exitCode,
        timedOut = false,
        duration = Duration.ofSeconds(1),
        output = output,
        coverage = coverage,
    )

    private class FakeRunner(
        override val mode: ExecutionMode,
        override val name: String,
        private val available: Boolean,
        private val result: (TestStackPlan) -> TestRunOutcome,
    ) : TestRunner {
        override val priority: Int = 100
        var invocations: Int = 0
            private set

        override fun isAvailable(): Boolean = available

        override fun run(plan: TestStackPlan, projectRoot: Path): TestRunOutcome {
            invocations++
            return result(plan)
        }
    }
}
