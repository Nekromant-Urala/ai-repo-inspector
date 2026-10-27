package com.airepo.analyzer.structure

import com.airepo.analyzer.AnalysisContext
import com.airepo.analyzer.CriterionAnalyzer
import com.airepo.common.FileUtils
import com.airepo.domain.CriterionResult
import com.airepo.domain.Finding
import com.airepo.domain.Severity
import org.springframework.stereotype.Component
import java.nio.file.Path

/**
 * Критерий «Структура проекта».
 *
 * Оценивает, насколько осмысленно выстроена архитектура файлов:
 * есть ли разделение на исходники/тесты, не «плоский» ли проект,
 * насколько равномерно распределены файлы по директориям.
 *
 * TODO: углубить анализ (например, определять язык/фреймворк проекта и
 * проверять соответствие канонической структуре для него).
 */
@Component
class StructureAnalyzer : CriterionAnalyzer {

    override val id: String = "structure"
    override val name: String = "Структура проекта"

    /** Типичные корневые директории, которые говорят о продуманной структуре. */
    private val meaningfulRootDirs = listOf(
        "src", "src/main", "src/test", "lib", "app", "core", "docs", "config",
    )

    override fun analyze(context: AnalysisContext): CriterionResult {
        val root = context.rootDir
        val findings = mutableListOf<Finding>()

        val rootDirs = listDirectories(root)
        val meaningful = rootDirs.count { dir ->
            meaningfulRootDirs.any { it == dir.fileName.toString() }
        }

        val allFiles = FileUtils.listFilesRecursively(root)
        val tooFlat = allFiles.count { it.parent == root } > allFiles.size / 2

        if (rootDirs.isEmpty()) {
            findings += Finding(severity = Severity.ERROR, message = "В корне проекта нет ни одной директории.")
        }
        if (tooFlat && allFiles.isNotEmpty()) {
            findings += Finding(
                severity = Severity.WARNING,
                message = "Проект выглядит «плоским»: большинство файлов лежит прямо в корне без группировки.",
            )
        }

        val dirScore = if (rootDirs.isEmpty()) 0.0
        else minOf(60.0, meaningful.toDouble() / maxOf(1, meaningfulRootDirs.size / 2) * 60.0)
        val flatPenalty = if (tooFlat) 20.0 else 0.0
        val score = (dirScore - flatPenalty).coerceIn(0.0, 100.0)

        return CriterionResult(
            criterionId = id,
            criterionName = name,
            score = score,
            summary = "Корневых директорий: ${rootDirs.size}, осмысленных: $meaningful, файлов всего: ${allFiles.size}.",
            findings = findings,
        )
    }
}

/** Вспомогательная функция: перечисление директорий в корне (без скрытых). */
private fun listDirectories(root: Path): List<Path> {
    if (!java.nio.file.Files.isDirectory(root)) return emptyList()
    return java.nio.file.Files.list(root).use { stream ->
        stream
            .filter { p -> java.nio.file.Files.isDirectory(p) }
            .filter { p -> !p.fileName.toString().startsWith(".") }
            .toList()
    }
}