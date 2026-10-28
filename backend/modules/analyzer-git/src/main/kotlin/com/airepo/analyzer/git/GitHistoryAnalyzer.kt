package com.airepo.analyzer.git

import com.airepo.analyzer.AnalysisContext
import com.airepo.analyzer.CriterionAnalyzer
import com.airepo.domain.CriterionResult
import com.airepo.domain.Finding
import com.airepo.domain.Severity
import org.eclipse.jgit.api.Git
import org.springframework.stereotype.Component
import java.nio.file.Files
import java.util.Locale

/**
 * Критерий «История коммитов и вклад участников».
 *
 * Анализирует:
 * - распределение коммитов между участниками;
 * - качество сообщений коммитов;
 * - наличие стандартных префиксов;
 * - потенциально неинформативные сообщения.
 *
 * Анализируются последние 500 коммитов.
 */
@Component
class GitHistoryAnalyzer : CriterionAnalyzer {

    override val id: String = "git"
    override val name: String = "История коммитов и вклад участников"

    private val commitLimit = 500

    private val meaninglessMessages = setOf(
        "fix",
        "update",
        "test",
        "changes",
        "123",
        "1234",
        "asdf",
        "wip",
        "tmp",
        "исправил",
    )

    private val conventionalPrefixes = listOf(
        "feat:",
        "fix:",
        "docs:",
        "refactor:",
        "test:",
        "chore:",
        "style:",
        "perf:",
        "build:",
        "ci:",
    )

    override fun analyze(context: AnalysisContext): CriterionResult {
        val gitDirectory = context.rootDir.resolve(".git")

        if (!Files.exists(gitDirectory)) {
            return CriterionResult(
                criterionId = id,
                criterionName = name,
                score = 0.0,
                summary = "История недоступна: проект загружен без .git.",
                findings = listOf(
                    Finding(
                        severity = Severity.INFO,
                        message = "Для анализа истории загрузите репозиторий по ссылке GIT_URL, а не архивом.",
                    ),
                ),
            )
        }

        return try {
            Git.open(context.rootDir.toFile()).use { git ->
                val commits = git.log()
                    .setMaxCount(commitLimit)
                    .call()
                    .toList()

                if (commits.isEmpty()) {
                    return CriterionResult(
                        criterionId = id,
                        criterionName = name,
                        score = 0.0,
                        summary = "В репозитории пока нет коммитов.",
                        findings = listOf(
                            Finding(
                                severity = Severity.INFO,
                                message = "История Git пуста, поэтому оценить её качество невозможно.",
                            ),
                        ),
                    )
                }

                val findings = mutableListOf<Finding>()

                // 1. Распределение коммитов между участниками.
                val authorCounts = commits
                    .groupingBy { commit ->
                        val identity = commit.authorIdent
                        identity.emailAddress
                            ?.trim()
                            ?.takeIf { it.isNotEmpty() }
                            ?: identity.name
                                ?.trim()
                                ?.takeIf { it.isNotEmpty() }
                            ?: "Неизвестный автор"
                    }
                    .eachCount()

                val totalCommits = commits.size

                authorCounts
                    .toList()
                    .sortedByDescending { it.second }
                    .forEach { (author, count) ->
                        val percentage = count * 100.0 / totalCommits

                        findings.add(
                            Finding(
                                severity = Severity.INFO,
                                message = "Участник $author: $count коммитов " +
                                    "(${String.format(Locale.US, "%.1f", percentage)}%).",
                            ),
                        )
                    }

                // 2. Проверка качества сообщений.
                val badMessages = commits.filter { commit ->
                    val message = commit.shortMessage.trim()
                    val normalized = message.lowercase(Locale.ROOT)

                    message.length < 10 ||
                        normalized in meaninglessMessages ||
                        normalized.matches(Regex("""\d+"""))
                }

                if (badMessages.isNotEmpty()) {
                    findings.add(
                        Finding(
                            severity = Severity.WARNING,
                            message = "Найдено ${badMessages.size} потенциально " +
                                "неинформативных сообщений из $totalCommits.",
                        ),
                    )

                    badMessages.take(5).forEach { commit ->
                        findings.add(
                            Finding(
                                severity = Severity.WARNING,
                                message = "Проверьте сообщение коммита: " +
                                    "\"${commit.shortMessage.take(120)}\" " +
                                    "(автор: ${commit.authorIdent.name}).",
                            ),
                        )
                    }
                }

                // 3. Проверка оформления сообщений.
                val withoutPrefix = commits.filter { commit ->
                    val message = commit.shortMessage.trim()
                    conventionalPrefixes.none { prefix ->
                        message.startsWith(prefix, ignoreCase = true)
                    }
                }

                if (withoutPrefix.isNotEmpty()) {
                    findings.add(
                        Finding(
                            severity = Severity.INFO,
                            message = "${withoutPrefix.size} из $totalCommits " +
                                "сообщений не используют стандартные префиксы " +
                                "(например, feat: или fix:).",
                        ),
                    )
                }

                // 4. Проверка концентрации коммитов.
                val largestContribution = authorCounts.values.maxOrNull() ?: 0
                val largestShare = largestContribution * 100.0 / totalCommits

                if (authorCounts.size > 1 && largestShare >= 80.0) {
                    findings.add(
                        Finding(
                            severity = Severity.WARNING,
                            message = "На одного участника приходится " +
                                "${String.format(Locale.US, "%.1f", largestShare)}% " +
                                "коммитов. Стоит проверить распределение работы.",
                        ),
                    )
                }

                // 5. Расчёт итоговой оценки.
                val badMessageRatio =
                    badMessages.size.toDouble() / totalCommits

                val missingPrefixRatio =
                    withoutPrefix.size.toDouble() / totalCommits

                val score = (
                    100.0 -
                        badMessageRatio * 60.0 -
                        missingPrefixRatio * 20.0
                    ).coerceIn(0.0, 100.0)

                val summary = "Проанализировано $totalCommits коммитов " +
                    "и ${authorCounts.size} участников. " +
                    "Неинформативных сообщений: ${badMessages.size}. " +
                    "Без стандартного префикса: ${withoutPrefix.size}. " +
                    "Оценка: ${String.format(Locale.US, "%.1f", score)}/100."

                CriterionResult(
                    criterionId = id,
                    criterionName = name,
                    score = score,
                    summary = summary,
                    findings = findings,
                )
            }
        } catch (e: Exception) {
            CriterionResult(
                criterionId = id,
                criterionName = name,
                score = 0.0,
                summary = "Не удалось проанализировать Git-историю: " +
                    (e.message ?: e.javaClass.simpleName),
                findings = listOf(
                    Finding(
                        severity = Severity.ERROR,
                        message = "Ошибка чтения репозитория Git. " +
                            "Проверьте его целостность и доступность.",
                    ),
                ),
            )
        }
    }
}