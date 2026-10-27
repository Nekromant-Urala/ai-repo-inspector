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
 * TODO (mrrreowred): углубить — например, сверять указанную команду сборки
 * с фактическими файлами проекта (build.gradle.kts, package.json и т.д.).
 */
@Component
class ReadmeAnalyzer : CriterionAnalyzer {

    override val id: String = "readme"
    override val name: String = "README"

    /** Разделы, наличие которых в README желательно. */
    private val requiredSections = listOf(
        "install" to "Установка",
        "build" to "Сборка",
        "run" to "Запуск",
        "usage" to "Использование",
    )

    override fun analyze(context: AnalysisContext): CriterionResult {
        val readme = findReadme(context.rootDir)
        if (readme == null) {
            return CriterionResult(
                criterionId = id,
                criterionName = name,
                score = 0.0,
                summary = "README не найден в корне проекта.",
                findings = listOf(
                    Finding(file = "README.md", severity = Severity.ERROR, message = "В проекте отсутствует README."),
                ),
            )
        }

        val content = FileUtils.readTextSafely(readme) ?: ""
        val lower = content.lowercase()
        val findings = mutableListOf<Finding>()
        var present = 0

        for ((keyword, sectionName) in requiredSections) {
            if (keyword in lower || sectionName.lowercase() in lower) {
                present++
            } else {
                findings += Finding(
                    file = readme.fileName.toString(),
                    severity = Severity.WARNING,
                    message = "В README не найден раздел «$sectionName».",
                )
            }
        }

        val ratio = if (requiredSections.isEmpty()) 1.0 else present.toDouble() / requiredSections.size
        return CriterionResult(
            criterionId = id,
            criterionName = name,
            score = ratio * 100,
            summary = "README найден, покрытие ключевых разделов: $present/${requiredSections.size}.",
            findings = findings,
        )
    }

    private fun findReadme(root: Path): Path? =
        listOf("README.md", "Readme.md", "readme.md", "README.MD")
            .map { root.resolve(it) }
            .firstOrNull { Files.isRegularFile(it) }
}