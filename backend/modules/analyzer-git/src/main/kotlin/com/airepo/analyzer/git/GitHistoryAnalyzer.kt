package com.airepo.analyzer.git

import com.airepo.analyzer.AnalysisContext
import com.airepo.analyzer.CriterionAnalyzer
import com.airepo.domain.CriterionResult
import com.airepo.domain.Finding
import com.airepo.domain.Severity
import org.springframework.stereotype.Component
import java.nio.file.Files

/**
 * Критерий «История коммитов и вклад участников».
 *
 * Анализирует git-историю репозитория: качество сообщений коммитов,
 * атомарность изменений, а также процентный вклад каждого участника.
 *
 * TODO: 
 *  - через JGit или `git log` собирать коммиты и оценивать сообщения
 *    (структура «тип: описание», размер диффа и т.п.);
 *  - считать распределение вклада участников по числу коммитов/изменённых строк.
 *
 * Важно: работает только для источника GIT_URL (архивы не содержат `.git`).
 */
@Component
class GitHistoryAnalyzer : CriterionAnalyzer {

    override val id: String = "git"
    override val name: String = "История коммитов и вклад участников"

    override fun analyze(context: AnalysisContext): CriterionResult {
        val hasGitDir = Files.isDirectory(context.rootDir.resolve(".git"))

        if (!hasGitDir) {
            return CriterionResult(
                criterionId = id,
                criterionName = name,
                score = 0.0,
                summary = "История недоступна: проект загружен архивом без .git.",
                findings = listOf(
                    Finding(
                        severity = Severity.INFO,
                        message = "Для оценки git-истории загрузите репозиторий по ссылке (GIT_URL), а не архивом.",
                    ),
                ),
            )
        }

        // TODO: реализовать сбор и оценку коммитов через JGit.
        return CriterionResult(
            criterionId = id,
            criterionName = name,
            score = 50.0,
            summary = "git-история доступна, но оценка ещё не реализована.",
            findings = emptyList(),
        )
    }
}