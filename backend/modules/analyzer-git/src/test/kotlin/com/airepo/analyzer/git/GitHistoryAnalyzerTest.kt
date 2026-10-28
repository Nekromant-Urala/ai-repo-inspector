package com.airepo.analyzer.git

import com.airepo.analyzer.AnalysisContext
import com.airepo.domain.RepositorySource
import com.airepo.domain.Severity
import com.airepo.domain.SourceType
import org.eclipse.jgit.api.Git
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.assertTrue

class GitHistoryAnalyzerTest {

    @Test
    fun `analyzer detects bad commit messages and calculates score`(@TempDir tempDir: Path) {
        // 1. Создаем временный git-репозиторий
        Git.init().setDirectory(tempDir.toFile()).call().use { git ->
            val file = tempDir.resolve("README.md").toFile()
            file.writeText("hello")

            // Коммит 1: Плохое сообщение (короткое)
            git.add().addFilepattern(".").call()
            git.commit().setMessage("fix").call()

            // Коммит 2: Плохое сообщение (без префикса)
            file.appendText("\nmore")
            git.add().addFilepattern(".").call()
            git.commit().setMessage("update code").call()

            // Коммит 3: Хорошее сообщение
            file.appendText("\nmore2")
            git.add().addFilepattern(".").call()
            git.commit().setMessage("feat: add feature").call()
        }

        // 2. Настраиваем контекст и запускаем анализатор
        val context = AnalysisContext(
            rootDir = tempDir,
            source = RepositorySource(type = SourceType.GIT_URL, value = "https://github.com/test/repo")
        )
        
        val result = GitHistoryAnalyzer().analyze(context)

        // 3. Проверяем результаты
        // Оценка должна быть меньше 100, так как есть плохие коммиты
        assertTrue(result.score < 100.0, "Score should be less than 100")
        
        // В результатах должно быть предупреждение (WARNING) про плохие сообщения
        assertTrue(
            result.findings.any { it.severity == Severity.WARNING },
            "Should have warnings about bad messages"
        )
        
        // В результатах должна быть информация (INFO) про вклад участников
        assertTrue(
            result.findings.any { it.severity == Severity.INFO && it.message.contains("коммитов") },
            "Should have contributor info"
        )
    }
}

