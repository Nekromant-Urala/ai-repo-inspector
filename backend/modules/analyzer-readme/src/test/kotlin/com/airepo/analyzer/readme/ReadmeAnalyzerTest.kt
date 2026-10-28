package com.airepo.analyzer.readme

import com.airepo.analyzer.AnalysisContext
import com.airepo.domain.RepositorySource
import com.airepo.domain.Severity
import com.airepo.domain.SourceType
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ReadmeAnalyzerTest {

    @Test
    fun `analyzer gives 0 score when README is missing`(@TempDir tempDir: Path) {
        // Создаем пустую папку (без README)
        val context = AnalysisContext(
            rootDir = tempDir,
            source = RepositorySource(type = SourceType.GIT_URL, value = "https://github.com/test/repo")
        )

        val result = ReadmeAnalyzer().analyze(context)

        // Оценка должна быть 0
        assertEquals(0.0, result.score)
        // Должна быть ошибка (ERROR) о том, что README нет
        assertTrue(result.findings.any { it.severity == Severity.ERROR })
    }

    @Test
    fun `analyzer detects missing sections in README`(@TempDir tempDir: Path) {
        // Создаем короткий README без нужных разделов
        val readme = tempDir.resolve("README.md")
        readme.toFile().writeText("Это просто тестовый проект. Тут ничего нет.")

        val context = AnalysisContext(
            rootDir = tempDir,
            source = RepositorySource(type = SourceType.GIT_URL, value = "https://github.com/test/repo")
        )

        val result = ReadmeAnalyzer().analyze(context)

        // Оценка должна быть низкой (меньше 50), так как нет разделов
        assertTrue(result.score < 50.0, "Score should be low due to missing sections")
        // Должны быть предупреждения (WARNING) о нехватке разделов и коротком тексте
        assertTrue(result.findings.any { it.severity == Severity.WARNING })
    }

    @Test
    fun `analyzer gives high score for good README with build instructions`(@TempDir tempDir: Path) {
        // Создаем хороший README с нужными разделами
        val readme = tempDir.resolve("README.md")
        readme.toFile().writeText("""
            # Мой проект
            
            ## Установка
            Скачайте проект.
            
            ## Сборка
            Запустите gradlew build.
            
            ## Запуск
            Запустите ./gradlew run.
            
            ## Использование
            Используйте на здоровье.
            
            Дополнительный текст для объема, чтобы пройти проверку на минимальную длину (200 символов).
        """.trimIndent())

        // Создаем пустой build.gradle.kts, чтобы анализатор не ругался на несоответствие
        tempDir.resolve("build.gradle.kts").toFile().writeText("// stub")

        val context = AnalysisContext(
            rootDir = tempDir,
            source = RepositorySource(type = SourceType.GIT_URL, value = "https://github.com/test/repo")
        )

        val result = ReadmeAnalyzer().analyze(context)

        // Оценка должна быть высокой (90+), так как все разделы есть
        assertTrue(result.score >= 90.0, "Score should be high for a good README")
    }
}