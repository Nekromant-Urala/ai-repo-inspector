package com.airepo.analyzer.tests

import com.airepo.analyzer.AnalysisContext
import com.airepo.analyzer.CriterionAnalyzer
import com.airepo.analyzer.tests.coverage.CoverageReport
import com.airepo.analyzer.tests.exec.ExecutionMode
import com.airepo.analyzer.tests.exec.Output
import com.airepo.analyzer.tests.exec.TestRunOutcome
import com.airepo.analyzer.tests.exec.TestRunner
import com.airepo.analyzer.tests.stack.DetectionOptions
import com.airepo.analyzer.tests.stack.StackDetector
import com.airepo.analyzer.tests.stack.TestStackPlan
import com.airepo.domain.CriterionResult
import com.airepo.domain.Finding
import com.airepo.domain.Severity
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.stereotype.Component
import java.io.IOException
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes
import java.time.Duration
import java.util.Locale

/**
 * Критерий «Покрытие тестами».
 *
 * Работает в два слоя:
 *
 * 1. **Статический анализ** — находит тестовые каталоги и файлы, даже если
 *    запуск тестов недоступен (нет Docker, отключён автоматический прогон).
 * 2. **Изолированный запуск** — определяет технологический стек, поднимает
 *    контейнер с нужным фреймворком и флагами сбора покрытия, выполняет тесты
 *    и забирает отчёт (JaCoCo, Cobertura, LCOV, Go cover, Clover, SimpleCov,
 *    Istanbul).
 *
 * Итоговая оценка считается от фактического покрытия: 100 баллов при
 * достижении [TestsModuleProperties.targetCoverage], пропорционально меньше —
 * при более низком. Если покрытие собрать не удалось, используется статическая
 * оценка с ограничением сверху.
 */
@Component
class TestsCoverageAnalyzer @Autowired constructor(
    private val properties: TestsModuleProperties,
    private val runners: List<TestRunner>,
    private val detector: StackDetector = StackDetector(),
) : CriterionAnalyzer {

    override val id: String = "tests"
    override val name: String = "Покрытие тестами"

    /** Характерные пути тестовой директории. */
    private val testDirCandidates = listOf(
        "src/test", "src/test/kotlin", "src/test/java",
        "tests", "test", "__tests__", "spec", "src/__tests__",
    )

    override fun analyze(context: AnalysisContext): CriterionResult {
        val root = context.rootDir
        val findings = mutableListOf<Finding>()

        val hasTestDir = testDirCandidates.any { Files.isDirectory(root.resolve(it)) }
        val testFiles = countTestFiles(root)
        val staticScore = staticScore(hasTestDir, testFiles)

        if (!hasTestDir && testFiles == 0) {
            findings += Finding(
                severity = Severity.ERROR,
                message = "В проекте не найдено ни одного теста.",
            )
        }

        val plans = runCatching {
            detector.detect(
                root,
                DetectionOptions(
                    maxDepth = properties.maxDetectionDepth,
                    maxPlans = properties.maxPlans,
                ),
            )
        }.getOrElse { emptyList() }

        val runner = selectRunner()
        val outcomes = mutableListOf<TestRunOutcome>()
        if (properties.enabled && plans.isNotEmpty() && runner != null) {
            plans.forEach { plan -> outcomes += runSafely(runner, plan, root) }
        }

        val coverage = outcomes.mapNotNull { it.coverage }.reduceOrNull { acc, report -> acc + report }
        reportExecution(plans, runner, outcomes, findings)
        reportCoverage(coverage, findings)

        if (coverage == null) {
            val tools = detectCoverageTooling(root)
            if (tools.isNotEmpty()) {
                findings += Finding(
                    severity = Severity.INFO,
                    message = "В проекте настроен сбор покрытия (${tools.joinToString(", ")}), но измерить его не удалось.",
                )
            }
        }

        return CriterionResult(
            criterionId = id,
            criterionName = name,
            score = computeScore(staticScore, outcomes, coverage),
            summary = buildSummary(hasTestDir, testFiles, plans, runner, outcomes, coverage),
            findings = findings.take(MAX_FINDINGS),
        )
    }

    // ------------------------------------------------------------- execution

    private fun selectRunner(): TestRunner? {
        if (!properties.enabled || properties.mode == ExecutionMode.STATIC_ONLY) return null
        val candidates = when (properties.mode) {
            ExecutionMode.AUTO -> runners.sortedByDescending { it.priority }
            else -> runners.filter { it.mode == properties.mode }
        }
        return candidates.firstOrNull { candidate ->
            runCatching { candidate.isAvailable() }.getOrDefault(false)
        }
    }

    private fun runSafely(runner: TestRunner, plan: TestStackPlan, root: Path): TestRunOutcome =
        runCatching { runner.run(plan, root) }.getOrElse { error ->
            TestRunOutcome(
                runner = runner.name,
                plan = plan,
                exitCode = null,
                timedOut = false,
                duration = Duration.ZERO,
                output = "",
                error = "${error.javaClass.simpleName}: ${error.message}",
            )
        }

    private fun reportExecution(
        plans: List<TestStackPlan>,
        runner: TestRunner?,
        outcomes: List<TestRunOutcome>,
        findings: MutableList<Finding>,
    ) {
        if (plans.isEmpty()) {
            findings += Finding(
                severity = Severity.INFO,
                message = "Не найден поддерживаемый стек для автоматического запуска тестов.",
            )
            return
        }

        val skipReason = when {
            !properties.enabled ->
                "Автоматический запуск тестов отключён (airepo.tests.enabled=false)."
            properties.mode == ExecutionMode.STATIC_ONLY ->
                "Включён статический режим (airepo.tests.mode=STATIC_ONLY)."
            runner == null && properties.mode == ExecutionMode.DOCKER ->
                "Docker недоступен: контейнерный запуск тестов невозможен."
            runner == null && properties.mode == ExecutionMode.LOCAL ->
                "Локальный запуск тестов недоступен или выключен (allow-local-execution)."
            runner == null && !properties.allowLocalExecution ->
                "Docker недоступен, локальный запуск выключен (airepo.tests.allow-local-execution=false)."
            runner == null ->
                "Нет доступного раннера тестов."
            else -> null
        }
        if (skipReason != null) {
            findings += Finding(severity = Severity.INFO, message = skipReason)
            return
        }

        outcomes.take(MAX_RUN_FINDINGS).forEach { outcome ->
            val label = outcome.plan.label
            when {
                outcome.error != null -> findings += Finding(
                    severity = Severity.ERROR,
                    message = "Запуск тестов не удался: $label — ${outcome.error}.",
                )
                outcome.timedOut -> findings += Finding(
                    severity = Severity.ERROR,
                    message = "Тесты не завершились за ${properties.timeout.toMinutes()} мин: $label.",
                )
                outcome.exitCode != 0 -> findings += Finding(
                    severity = Severity.ERROR,
                    message = "Тесты завершились с ошибкой (код ${outcome.exitCode}): $label.${failureTail(outcome.output)}",
                )
                outcome.coverage != null -> findings += Finding(
                    severity = Severity.INFO,
                    message = "Покрытие ($label): ${formatPercent(outcome.coverage.percent)}% " +
                        "(${outcome.coverage.covered}/${outcome.coverage.total}).",
                )
                else -> findings += Finding(
                    severity = Severity.WARNING,
                    message = "Тесты прошли, но файл покрытия не найден: $label." + notesSuffix(outcome.plan),
                )
            }
        }

        if (outcomes.isNotEmpty() && runner?.mode == ExecutionMode.LOCAL) {
            findings += Finding(
                severity = Severity.WARNING,
                message = "Тесты выполнялись на хосте без контейнерной изоляции (режим LOCAL).",
            )
        }
    }

    private fun reportCoverage(coverage: CoverageReport?, findings: MutableList<Finding>) {
        if (coverage == null) return
        val percent = coverage.percent
        when {
            percent < properties.warningCoverage -> findings += Finding(
                severity = Severity.ERROR,
                message = "Покрытие ${formatPercent(percent)}% ниже минимального порога " +
                    "${formatPercent(properties.warningCoverage)}%.",
            )
            percent < properties.targetCoverage -> findings += Finding(
                severity = Severity.WARNING,
                message = "Покрытие ${formatPercent(percent)}% ниже целевого уровня " +
                    "${formatPercent(properties.targetCoverage)}%.",
            )
            else -> findings += Finding(
                severity = Severity.INFO,
                message = "Покрытие ${formatPercent(percent)}% соответствует целевому уровню " +
                    "${formatPercent(properties.targetCoverage)}%.",
            )
        }
    }

    // ---------------------------------------------------------------- scoring

    private fun staticScore(hasTestDir: Boolean, testFiles: Int): Double {
        var score = 100.0
        // Go/Rust/PHP часто держат тесты рядом с кодом: несколько тестовых файлов
        // без отдельного каталога — это нормально.
        if (!hasTestDir) score -= if (testFiles >= 5) 20 else 50
        if (testFiles == 0) score -= 50 else score -= minOf(20.0, testFiles * 2.0)
        return score.coerceIn(0.0, 100.0)
    }

    private fun computeScore(
        staticScore: Double,
        outcomes: List<TestRunOutcome>,
        coverage: CoverageReport?,
    ): Double {
        val anyFailed = outcomes.any { !it.succeeded }
        return when {
            coverage != null -> {
                val target = properties.targetCoverage.coerceAtLeast(1.0)
                val base = 100.0 * (coverage.percent / target).coerceIn(0.0, 1.0)
                (if (anyFailed) base * 0.75 else base).coerceIn(0.0, 100.0)
            }
            outcomes.isNotEmpty() && outcomes.all { it.succeeded } -> minOf(staticScore, 70.0)
            outcomes.isNotEmpty() -> minOf(staticScore, 40.0)
            else -> staticScore
        }
    }

    // ----------------------------------------------------------------- static

    private fun countTestFiles(root: Path): Int {
        var count = 0
        runCatching {
            Files.walkFileTree(root, object : SimpleFileVisitor<Path>() {
                override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult {
                    if (dir != root && dir.fileName.toString() in SKIPPED_DIRS) {
                        return FileVisitResult.SKIP_SUBTREE
                    }
                    return FileVisitResult.CONTINUE
                }

                override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                    if (attrs.isRegularFile && matchesTestFile(file.fileName.toString())) count++
                    return FileVisitResult.CONTINUE
                }

                override fun visitFileFailed(file: Path, exc: IOException): FileVisitResult =
                    FileVisitResult.CONTINUE
            })
        }
        return count
    }

    private fun matchesTestFile(fileName: String): Boolean {
        val lower = fileName.lowercase(Locale.ROOT)
        return TEST_FILE_SUFFIXES.any { lower.endsWith(it) } ||
            TEST_FILE_PATTERNS.any { it.matches(fileName) }
    }

    /**
     * Статически определяет, настроен ли в проекте инструмент покрытия.
     * Полезно, когда автоматический запуск недоступен: отчёт объясняет,
     * что покрытие в принципе собирается, просто измерить его не вышло.
     */
    private fun detectCoverageTooling(root: Path): List<String> {
        val tools = linkedSetOf<String>()

        fun read(path: String): String =
            runCatching { Files.readString(root.resolve(path)) }.getOrDefault("")

        val jvmBuild = read("build.gradle") + read("build.gradle.kts") + read("pom.xml")
        if (jvmBuild.contains("jacoco", ignoreCase = true)) tools += "JaCoCo"

        val packageJson = read("package.json")
        if (listOf("\"jest\"", "\"vitest\"", "\"nyc\"", "\"c8\"").any { packageJson.contains(it) }) {
            tools += "Istanbul"
        }

        val pythonConfig = read("pyproject.toml") + read("requirements.txt") +
            read("setup.cfg") + read("tox.ini")
        if (pythonConfig.contains("pytest-cov") || pythonConfig.contains("coverage")) tools += "pytest-cov"

        if (read("Gemfile").contains("simplecov", ignoreCase = true)) tools += "SimpleCov"
        if (read("composer.json").contains("phpunit")) tools += "PHPUnit"

        return tools.toList()
    }

    // ---------------------------------------------------------------- summary

    private fun buildSummary(
        hasTestDir: Boolean,
        testFiles: Int,
        plans: List<TestStackPlan>,
        runner: TestRunner?,
        outcomes: List<TestRunOutcome>,
        coverage: CoverageReport?,
    ): String = buildString {
        append("Тестовая директория: ${if (hasTestDir) "есть" else "нет"}; тестовых файлов: $testFiles")
        if (plans.isNotEmpty()) {
            append("; стеки: ${plans.joinToString(", ") { it.label }}")
        }
        if (coverage != null) {
            append("; покрытие ${formatPercent(coverage.percent)}% (${coverage.covered}/${coverage.total})")
        } else {
            append("; покрытие не измерено")
        }
        append("; ").append(
            when {
                outcomes.isNotEmpty() -> "раннер: ${outcomes.first().runner}"
                runner != null -> "раннер: ${runner.name} (тесты не запускались)"
                else -> "тесты не запускались"
            },
        )
        append('.')
    }

    private fun failureTail(output: String): String {
        if (output.isBlank()) return ""
        return " Хвост вывода:\n" + Output.tail(output, FAILURE_OUTPUT_CHARS)
    }

    private fun notesSuffix(plan: TestStackPlan): String =
        if (plan.notes.isEmpty()) "" else " ${plan.notes.joinToString(" ")}"

    private fun formatPercent(value: Double): String = String.format(Locale.ROOT, "%.1f", value)

    private companion object {
        const val MAX_FINDINGS = 12
        const val MAX_RUN_FINDINGS = 6
        const val FAILURE_OUTPUT_CHARS = 1_200

        val SKIPPED_DIRS = setOf(
            "node_modules", "build", "target", ".git", ".gradle", "dist", "out",
            "vendor", ".venv", "venv", "env", "__pycache__", ".idea", ".mvn",
            "coverage", ".tox", ".pytest_cache", "obj", ".next", ".nuxt",
        )

        /** Dotted-имена: `user.service.test.ts`, `api.spec.rb`, `test_store.py`. */
        val TEST_FILE_SUFFIXES = listOf(
            ".test.kt", ".test.java", ".test.ts", ".test.tsx", ".test.js", ".test.jsx",
            ".test.mjs", ".test.cjs", ".test.rb", ".test.py", ".test.php",
            ".spec.kt", ".spec.java", ".spec.ts", ".spec.tsx", ".spec.js", ".spec.jsx",
            ".spec.mjs", ".spec.rb", ".spec.py",
            "_test.go", "_test.py", "_test.rb", "_spec.rb",
        )

        /** CamelCase-имена тестовых классов и файлов. */
        val TEST_FILE_PATTERNS = listOf(
            Regex("""^.*(?:Test|Tests|IT)\.(?:kt|java|cs|php|scala|groovy|swift)$"""),
            Regex("""^test_.*\.py$"""),
        )
    }
}
