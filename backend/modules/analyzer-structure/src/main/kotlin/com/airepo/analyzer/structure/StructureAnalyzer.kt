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
 * Оценивает, насколько осмысленно организованы файлы проекта:
 * какая доля «значащих» файлов (исходников, конфигов и т.п.) сгруппирована
 * по директориям, а не свалена в корень. Мусорные и бинарные файлы
 * (node_modules, build, .git и т.п.) в расчёт не берутся.
 *
 * TODO: углубить анализ (например, определять язык/фреймворк проекта и
 * проверять соответствие канонической структуре для него).
 */
@Component
class StructureAnalyzer : CriterionAnalyzer {

    override val id: String = "structure"
    override val name: String = "Структура проекта"

    /** Директории, файлы в которых не считаются «значащими» для оценки структуры. */
    private val ignoredDirs = setOf(
        ".git", ".github", ".idea", ".gradle", "node_modules", "build", "dist",
        "target", "out", "bin", "vendor", "__pycache__", ".venv", "venv", ".next", "coverage",
    )

    /** Бинарные расширения — их игнорируем при оценке «осмысленности» файлов. */
    private val binaryExtensions = setOf(
        "png", "jpg", "jpeg", "gif", "ico", "webp", "bmp", "svg",
        "woff", "woff2", "ttf", "otf", "eot",
        "jar", "zip", "tar", "gz", "7z", "rar", "class", "pyc",
        "so", "dylib", "dll", "exe", "obj", "o", "pdf", "db", "lock",
    )

    override fun analyze(context: AnalysisContext): CriterionResult {
        val root = context.rootDir
        val findings = mutableListOf<Finding>()

        val allFiles = FileUtils.listFilesRecursively(root)
        val significantFiles = allFiles.filter { isSignificant(root, it) }
        // «Осмысленные» файлы — значащие файлы, сгруппированные в поддиректории (не в корне).
        val meaningfulFiles = significantFiles.count { it.parent != root }
        val total = significantFiles.size

        if (allFiles.isEmpty()) {
            findings += Finding(severity = Severity.ERROR, message = "В проекте не найдено ни одного файла.")
        }

        // Плоский проект: большинство значащих файлов лежит прямо в корне без группировки.
        val filesInRoot = significantFiles.count { it.parent == root }
        val tooFlat = total > 0 && filesInRoot > total / 2
        if (tooFlat) {
            findings += Finding(
                severity = Severity.WARNING,
                message = "Проект выглядит «плоским»: $filesInRoot из $total значащих файлов лежит в корне без группировки.",
            )
        }

        val score = if (total == 0) 0.0
        else (meaningfulFiles.toDouble() / total * 100.0).coerceIn(0.0, 100.0)

        return CriterionResult(
            criterionId = id,
            criterionName = name,
            score = score,
            summary = "Осмысленных файлов: $meaningfulFiles из $total значащих файлов " +
                "(всего в проекте: ${allFiles.size}).",
            findings = findings,
        )
    }

    /** Файл «значащий», если он не в мусорной директории и не бинарный. */
    private fun isSignificant(root: Path, file: Path): Boolean {
        val rel = root.relativize(file).toString().replace("\\", "/")
        if (ignoredDirs.any { rel.startsWith("$it/") || rel == it || rel.contains("/$it/") }) return false
        return FileUtils.extensionOf(file) !in binaryExtensions
    }
}