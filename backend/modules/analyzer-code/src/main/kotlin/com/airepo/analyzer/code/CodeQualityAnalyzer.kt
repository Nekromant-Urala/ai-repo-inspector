package com.airepo.analyzer.code

import com.airepo.analyzer.AnalysisContext
import com.airepo.analyzer.CriterionAnalyzer
import com.airepo.common.FileUtils
import com.airepo.domain.CriterionResult
import com.airepo.domain.Finding
import com.airepo.domain.Severity
import org.springframework.stereotype.Component
import java.nio.file.Files

/**
 * Критерий «Качество кода».
 *
 * Проверяет, что проект использует линтер/форматтер и что в нём нет явных
 * признаков небрежности (закомментированный код, TODO-маркеры и т.п.).
 *
 * TODO: по-настоящему запускать линтеры (ktlint, ESLint, Ruff...) на склонированном
 * проекте и собирать найденные замечания.
 */
@Component
class CodeQualityAnalyzer : CriterionAnalyzer {

    override val id: String = "code-quality"
    override val name: String = "Качество кода"

    /** Файлы конфигурации популярных линтеров/форматтеров. */
    private val lintConfigs = listOf(
        ".editorconfig",
        ".eslintrc", ".eslintrc.js", ".eslintrc.json", ".eslintrc.yml",
        ".prettierrc", ".prettierrc.json",
        ".ktlint", ".ruff.toml", "ruff.toml",
    )

    /** Маркеры небрежности в исходниках. */
    private val redFlags = listOf("TODO", "FIXME", "HACK")

    override fun analyze(context: AnalysisContext): CriterionResult {
        val root = context.rootDir
        val findings = mutableListOf<Finding>()

        val hasLintConfig = lintConfigs.any { Files.isRegularFile(root.resolve(it)) }
        if (!hasLintConfig) {
            findings += Finding(
                severity = Severity.WARNING,
                message = "Не найден конфигурационный файл линтера/форматтера.",
            )
        }

        var flaggedFiles = 0
        FileUtils.listFilesRecursively(root)
            .asSequence()
            .filter { FileUtils.extensionOf(it) in sourceExtensions }
            .forEach { file ->
                val content = FileUtils.readTextSafely(file) ?: return@forEach
                if (redFlags.any { content.contains(it) }) {
                    flaggedFiles++
                    if (findings.size < 10) {
                        findings += Finding(
                            file = root.relativize(file).toString(),
                            severity = Severity.INFO,
                            message = "В файле встречаются маркеры ${redFlags.joinToString("/")}.",
                        )
                    }
                }
            }

        var score = 100.0
        if (!hasLintConfig) score -= 40
        if (flaggedFiles > 0) score -= minOf(30.0, flaggedFiles * 5.0)
        score = score.coerceIn(0.0, 100.0)

        return CriterionResult(
            criterionId = id,
            criterionName = name,
            score = score,
            summary = "Конфиг линтера: ${if (hasLintConfig) "есть" else "нет"}; файлов с маркерами TODO/FIXME: $flaggedFiles.",
            findings = findings,
        )
    }

    private companion object {
        val sourceExtensions = setOf("kt", "java", "kts", "py", "js", "ts", "tsx", "go", "rs")
    }
}