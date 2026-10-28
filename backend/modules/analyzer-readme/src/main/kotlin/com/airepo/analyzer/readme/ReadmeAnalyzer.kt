package com.airepo.analyzer.readme

import com.airepo.analyzer.AnalysisContext
import com.airepo.analyzer.CriterionAnalyzer
import com.airepo.common.FileUtils
import com.airepo.domain.CriterionResult
import com.airepo.domain.Finding
import com.airepo.domain.Severity
import org.springframework.stereotype.Component
import java.nio.file.Files
import java.nio.file.Path

/**
 * Критерий «README».
 *
 * Проверяет, что в проекте есть README, и что он покрывает ключевые разделы:
 * описание проекта, инструкция по сборке/запуску, зависимости.
 *
 * Реализовано (mrrreowred):
 * - Поиск README теперь не только в корне, но и в папке `docs/`.
 * - Поддержка русских ключевых слов для разделов (установка, сборка и т.д.).
 * - Сверка указанных команд сборки (Gradle, NPM) с реальными файлами в проекте.
 * - Проверка на минимальный объем README (чтобы отсеять пустышки).
 *
 */
@Component
class ReadmeAnalyzer : CriterionAnalyzer {

    override val id: String = "readme"
    override val name: String = "README"

    // Ключевые слова для поиска разделов (добавлены русские варианты)
    private val requiredSections = listOf(
        listOf("install", "установка") to "Установка",
        listOf("build", "сборка") to "Сборка",
        listOf("run", "запуск", "start") to "Запуск",
        listOf("usage", "использование") to "Использование",
    )

    override fun analyze(context: AnalysisContext): CriterionResult {
        val root = context.rootDir
        val readme = findReadme(root)
        
        if (readme == null) {
            return CriterionResult(
                criterionId = id,
                criterionName = name,
                score = 0.0,
                summary = "README не найден.",
                findings = listOf(Finding(file = "README.md", severity = Severity.ERROR, message = "В проекте отсутствует README.")),
            )
        }

        val content = FileUtils.readTextSafely(readme) ?: ""
        val lower = content.lowercase()
        val findings = mutableListOf<Finding>()
        
        // 1. Проверка на минимальный объем
        if (content.length < 200) {
            findings += Finding(
                file = readme.fileName.toString(),
                severity = Severity.WARNING,
                message = "README слишком короткий (менее 200 символов). Возможно, он неинформативен."
            )
        }

        // 2. Проверка разделов
        var present = 0
        for ((keywords, sectionName) in requiredSections) {
            if (keywords.any { it in lower }) {
                present++
            } else {
                findings += Finding(
                    file = readme.fileName.toString(),
                    severity = Severity.WARNING,
                    message = "В README не найден раздел «$sectionName».",
                )
            }
        }

        // 3. Проверка соответствия команд сборки реальности
        if (lower.contains("gradlew") || lower.contains("gradle")) {
            if (!Files.exists(root.resolve("build.gradle.kts")) && !Files.exists(root.resolve("build.gradle"))) {
                findings += Finding(
                    file = readme.fileName.toString(),
                    severity = Severity.INFO,
                    message = "В README упоминается Gradle, но в проекте нет build.gradle(.kts).",
                )
            }
        }
        if (lower.contains("npm") || lower.contains("yarn") || lower.contains("pnpm")) {
            if (!Files.exists(root.resolve("package.json"))) {
                findings += Finding(
                    file = readme.fileName.toString(),
                    severity = Severity.INFO,
                    message = "В README упоминаются JS-пакетные менеджеры, но нет package.json.",
                )
            }
        }

        val ratio = if (requiredSections.isEmpty()) 1.0 else present.toDouble() / requiredSections.size
        var score = ratio * 100
        // Если нет файлов сборки, но README говорит о них — немного снижаем балл
        score -= findings.count { it.severity == Severity.INFO } * 10.0

        return CriterionResult(
            criterionId = id,
            criterionName = name,
            score = score.coerceIn(0.0, 100.0),
            summary = "README найден. Покрытие разделов: $present/${requiredSections.size}.",
            findings = findings,
        )
    }

    // Ищем README в корне или в docs/
    private fun findReadme(root: Path): Path? {
        val names = listOf("README.md", "Readme.md", "readme.md", "README.MD", "README", "README.txt")
        val possiblePaths = names.flatMap { listOf(root.resolve(it), root.resolve("docs").resolve(it)) }
        return possiblePaths.firstOrNull { Files.isRegularFile(it) }
    }
}