package com.airepo.analyzer.structure

import com.airepo.analyzer.AnalysisContext
import com.airepo.domain.RepositorySource
import com.airepo.domain.SourceType
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class StructureAnalyzerTest {

    private fun context(root: Path) = AnalysisContext(
        rootDir = root,
        source = RepositorySource(type = SourceType.GIT_URL, value = "https://github.com/test/repo"),
    )

    private fun write(root: Path, path: String, content: String = "") {
        val file = root.resolve(path)
        file.toFile().parentFile?.mkdirs()
        file.toFile().writeText(content)
    }

    @Test
    fun `well structured project gets high score`(@TempDir root: Path) {
        write(root, "src/main/Main.kt", "fun main() {}")
        write(root, "src/main/Util.kt", "fun helper() {}")
        write(root, "src/test/MainTest.kt", "class MainTest")
        // Пара служебных файлов в корне допустима
        write(root, "README.md", "# Project")
        write(root, "build.gradle.kts", "plugins {}")

        val result = StructureAnalyzer().analyze(context(root))

        // Осмысленные файлы = 3 (в src), значащих всего = 5
        assertTrue(result.score > 50.0, "Expected score > 50, got ${result.score}")
        assertTrue(result.summary.contains("Осмысленных файлов: 3 из 5"), "Unexpected summary: ${result.summary}")
        assertTrue(result.findings.none { it.message.contains("плоским") }, "Should not flag as flat")
    }

    @Test
    fun `all files in subdirectories get max score`(@TempDir root: Path) {
        write(root, "app/One.kt")
        write(root, "app/Two.kt")
        write(root, "lib/Three.kt")

        val result = StructureAnalyzer().analyze(context(root))

        assertEquals(100.0, result.score)
        assertTrue(result.summary.contains("Осмысленных файлов: 3 из 3"), "Unexpected summary: ${result.summary}")
    }

    @Test
    fun `flat project gets low score and warning`(@TempDir root: Path) {
        write(root, "a.kt")
        write(root, "b.kt")
        write(root, "c.kt")
        write(root, "src/one.kt")

        val result = StructureAnalyzer().analyze(context(root))

        // 1 осмысленный из 4
        assertTrue(result.score < 50.0, "Expected low score, got ${result.score}")
        assertTrue(result.summary.contains("Осмысленных файлов: 1 из 4"), "Unexpected summary: ${result.summary}")
        assertTrue(result.findings.any { it.message.contains("плоским") }, "Should warn about flat layout")
    }

    @Test
    fun `ignores node_modules and binary files`(@TempDir root: Path) {
        write(root, "node_modules/lib/index.js", "// lib")
        write(root, "src/Main.kt", "fun main() {}")
        write(root, "logo.png")

        val result = StructureAnalyzer().analyze(context(root))

        // Значащий только src/Main.kt (node_modules и png игнорируются)
        assertTrue(result.summary.contains("Осмысленных файлов: 1 из 1"), "Unexpected summary: ${result.summary}")
        assertEquals(100.0, result.score)
    }

    @Test
    fun `empty project scores zero`(@TempDir root: Path) {
        val result = StructureAnalyzer().analyze(context(root))

        assertEquals(0.0, result.score)
        assertTrue(result.findings.any { it.message.contains("не найдено ни одного файла") })
    }
}