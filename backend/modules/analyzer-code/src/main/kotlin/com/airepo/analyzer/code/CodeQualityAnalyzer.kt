package com.airepo.analyzer.code

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
 * Критерий «Качество кода».
 *
 * Проверяет, что проект использует линтер/форматтер и что в нём нет явных
 * признаков небрежности (закомментированный код, TODO-маркеры и т.п.).
 *
 * Реализовано (mrrreowred):
 * - Расширен список распознаваемых конфигов линтеров (добавлены pyproject.toml, eslint.config.js и др.).
 * - Поиск маркеров TODO/FIXME/HACK теперь идет по границам слов (через Regex), 
 *   чтобы не находить ложные срабатывания в словах вроде "METHOD".
 * - Игнорируются системные папки (node_modules, build, dist, .git и т.д.), 
 *   чтобы не засчитывать TODO из сторонних библиотек.
 * - Добавлено ограничение на размер файла (пропускаются файлы > 2MB).
 * - Расширен список проверяемых расширений (добавлены jsx, vue, svelte, c, cpp, php и др.).
 *
 */
@Component
class CodeQualityAnalyzer : CriterionAnalyzer {

    override val id: String = "code-quality"
    override val name: String = "Качество кода"

    // Расширенный список популярных конфигов
    private val lintConfigs = listOf(
        ".editorconfig",
        ".eslintrc", ".eslintrc.js", ".eslintrc.json", ".eslintrc.yml", "eslint.config.js",
        ".prettierrc", ".prettierrc.json",
        ".ktlint", "pyproject.toml", "setup.cfg", "tox.ini", ".flake8",
        ".golangci.yml", "rustfmt.toml", "clippy.toml"
    )

    // Папки, которые нужно игнорировать при поиске маркеров
    private val ignoredDirs = setOf("node_modules", "build", "dist", "target", ".git", "vendor", "out", "bin")

    // Регулярка ищет слова TODO, FIXME, HACK как отдельные слова
    private val redFlagRegex = Regex("\\b(TODO|FIXME|HACK)\\b")

    override fun analyze(context: AnalysisContext): CriterionResult {
        val root = context.rootDir
        val findings = mutableListOf<Finding>()

        // 1. Проверка конфигов
        val hasLintConfig = lintConfigs.any { Files.isRegularFile(root.resolve(it)) }
        if (!hasLintConfig) {
            findings += Finding(
                severity = Severity.WARNING,
                message = "Не найден конфигурационный файл линтера/форматтера.",
            )
        }

        // 2. Поиск маркеров в файлах
        var flaggedFiles = 0
        FileUtils.listFilesRecursively(root)
            .asSequence()
            .filter { file ->
                // Игнорируем ненужные папки
                val pathStr = root.relativize(file).toString().replace("\\", "/")
                ignoredDirs.none { pathStr.contains("$it/") || pathStr.startsWith("$it/") }
            }
            .filter { FileUtils.extensionOf(it) in sourceExtensions }
            .forEach { file ->
                // Игнорируем слишком большие файлы (> 2MB)
                try {
                    if (Files.size(file) > 2 * 1024 * 1024) return@forEach
                } catch (e: Exception) { return@forEach }

                val content = FileUtils.readTextSafely(file) ?: return@forEach
                val matchResult = redFlagRegex.find(content)
                
                if (matchResult != null) {
                    flaggedFiles++
                    if (findings.size < 10) { // Не засоряем отчет
                        val marker = matchResult.value
                        val line = content.substring(0, matchResult.range.first).count { it == '\n' } + 1
                        
                        findings += Finding(
                            file = root.relativize(file).toString(),
                            line = line,
                            severity = if (marker == "TODO") Severity.INFO else Severity.WARNING,
                            message = "Найден маркер $marker.",
                        )
                    }
                }
            }

        // 3. Подсчет очков
        var score = 100.0
        if (!hasLintConfig) score -= 40
        if (flaggedFiles > 0) score -= minOf(30.0, flaggedFiles * 5.0)
        score = score.coerceIn(0.0, 100.0)

        return CriterionResult(
            criterionId = id,
            criterionName = name,
            score = score,
            summary = "Конфиг линтера: ${if (hasLintConfig) "есть" else "нет"}; файлов с маркерами: $flaggedFiles.",
            findings = findings,
        )
    }

    private companion object {
        val sourceExtensions = setOf(
            "kt", "java", "kts", "py", "js", "ts", "tsx", "jsx", "vue", "svelte", 
            "go", "rs", "c", "cpp", "h", "hpp", "cs", "php", "rb", "swift", "scala", "sh"
        )
    }
}