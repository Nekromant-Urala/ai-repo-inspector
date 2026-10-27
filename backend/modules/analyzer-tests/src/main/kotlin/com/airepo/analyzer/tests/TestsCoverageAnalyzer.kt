package com.airepo.analyzer.tests

import com.airepo.analyzer.AnalysisContext
import com.airepo.analyzer.CriterionAnalyzer
import com.airepo.common.FileUtils
import com.airepo.domain.CriterionResult
import com.airepo.domain.Finding
import com.airepo.domain.Severity
import org.springframework.stereotype.Component
import java.nio.file.Path

/**
 * Критерий «Покрытие тестами».
 *
 * Оценивает наличие тестов в проекте: тестовая директория, тестовые файлы,
 * тестовый раннер (JUnit, pytest, Jest и т.п.).
 *
 * TODO (Серёжа): считать фактический процент покрытия, например через JaCoCo,
 * и учитывать интеграционные тесты на Testcontainers (базовый класс
 * `AbstractPostgresContainerTest` из модуля `test-support`).
 */
@Component
class TestsCoverageAnalyzer : CriterionAnalyzer {

    override val id: String = "tests"
    override val name: String = "Покрытие тестами"

    /** Характерные пути тестовой директории. */
    private val testDirCandidates = listOf(
        "src/test", "src/test/kotlin", "src/test/java",
        "tests", "test", "__tests__", "spec",
    )

    private val testFileExtensions = setOf("test.kt", "test.java", "test.ts", "test.js", "test.py")

    override fun analyze(context: AnalysisContext): CriterionResult {
        val root = context.rootDir
        val testDirs = testDirCandidates.map { root.resolve(it) }
        val findings = mutableListOf<Finding>()

        val hasTestDir = testDirs.any { java.nio.file.Files.isDirectory(it) }
        val testFiles = FileUtils.listFilesRecursively(root)
            .count { file -> testFileExtensions.any { file.fileName.toString().endsWith(it) } }

        if (!hasTestDir && testFiles == 0) {
            findings += Finding(
                severity = Severity.ERROR,
                message = "В проекте не найдено ни одного теста.",
            )
        }

        var score = 100.0
        if (!hasTestDir) score -= 50
        if (testFiles == 0) score -= 50 else score -= minOf(20.0, testFiles * 2.0)
        score = score.coerceIn(0.0, 100.0)

        return CriterionResult(
            criterionId = id,
            criterionName = name,
            score = score,
            summary = "Тестовая директория: ${if (hasTestDir) "есть" else "нет"}; тестовых файлов: $testFiles.",
            findings = findings,
        )
    }
}