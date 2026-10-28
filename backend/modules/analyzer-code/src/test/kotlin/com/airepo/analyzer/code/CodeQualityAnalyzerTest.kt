package com.airepo.analyzer.code

import com.airepo.analyzer.AnalysisContext
import com.airepo.domain.RepositorySource
import com.airepo.domain.Severity
import com.airepo.domain.SourceType
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.assertTrue

class CodeQualityAnalyzerTest {

    @Test
    fun `analyzer ignores TODO inside node_modules`(@TempDir tempDir: Path) {
        // Создаем фейковую папку node_modules с файлом, где есть TODO
        val nodeModules = tempDir.resolve("node_modules").resolve("some-lib")
        nodeModules.toFile().mkdirs()
        nodeModules.resolve("index.js").toFile().writeText("// TODO: fix this in library")

        val context = AnalysisContext(
            rootDir = tempDir,
            source = RepositorySource(type = SourceType.GIT_URL, value = "https://github.com/test/repo")
        )

        val result = CodeQualityAnalyzer().analyze(context)

        // Анализатор не должен найти маркеры в node_modules
        assertTrue(
            result.findings.none { it.message.contains("маркер") },
            "Analyzer should ignore files in node_modules"
        )
    }

    @Test
    fun `analyzer detects markers only as whole words`(@TempDir tempDir: Path) {
        val srcDir = tempDir.resolve("src")
        srcDir.toFile().mkdirs()
        
        // В этом файле есть слово METHOD (содержит TODO как часть слова) и реальный TODO
        srcDir.resolve("Main.kt").toFile().writeText("""
            val METHOD_NAME = "test"
            // TODO: сделать нормально
        """.trimIndent())

        val context = AnalysisContext(
            rootDir = tempDir,
            source = RepositorySource(type = SourceType.GIT_URL, value = "https://github.com/test/repo")
        )

        val result = CodeQualityAnalyzer().analyze(context)

        // Должен быть найден ровно 1 файл с маркером (сам Main.kt)
        // И в нем должно быть указание на строку 2
        val markerFinding = result.findings.find { it.message.contains("маркер TODO") }
        assertTrue(markerFinding != null, "Should find TODO marker")
        assertTrue(markerFinding.file!!.contains("Main.kt"))
        assertTrue(markerFinding.line == 2, "Should point to line 2")
    }

    @Test
    fun `analyzer gives max score for project with linter config and no markers`(@TempDir tempDir: Path) {
        // Создаем конфиг линтера
        tempDir.resolve(".editorconfig").toFile().writeText("root = true")
        
        // Создаем чистый файл без маркеров
        val srcDir = tempDir.resolve("src")
        srcDir.toFile().mkdirs()
        srcDir.resolve("Clean.kt").toFile().writeText("fun main() { println(\"Hello\") }")

        val context = AnalysisContext(
            rootDir = tempDir,
            source = RepositorySource(type = SourceType.GIT_URL, value = "https://github.com/test/repo")
        )

        val result = CodeQualityAnalyzer().analyze(context)

        // Оценка должна быть 100, так как конфиг есть, а маркеров нет
        assertTrue(result.score == 100.0, "Score should be 100 for clean project with linter config")
    }
}