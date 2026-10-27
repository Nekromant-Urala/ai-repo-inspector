package com.airepo.analyzer.security

import com.airepo.analyzer.AnalysisContext
import com.airepo.analyzer.CriterionAnalyzer
import com.airepo.common.FileUtils
import com.airepo.domain.CriterionResult
import com.airepo.domain.Finding
import com.airepo.domain.Severity
import org.springframework.stereotype.Component

/**
 * Критерий «Безопасность».
 *
 * Проверяет, что в коде не осталось приватных значений: API-ключей,
 * токенов, паролей и т.п.
 *
 * TODO (xiii): расширить набор паттернов, исключать тестовые/демо-значения,
 * подключать сканер наподобие gitleaks/trufflehog.
 */
@Component
class SecurityAnalyzer : CriterionAnalyzer {

    override val id: String = "security"
    override val name: String = "Безопасность"

    /** Паттерны «похоже на секрет». */
    private val secretPatterns = listOf(
        Regex("""(?i)(api[_-]?key|secret|token|password|passwd)\s*[:=]\s*['"][A-Za-z0-9_\-]{8,}['"]"""),
        Regex("""(?i)aws_access_key_id\s*[:=]"""),
        Regex("""(?i)(BEGIN (RSA |EC |OPENSSH )?PRIVATE KEY)"""),
        Regex("""sk-[A-Za-z0-9]{20,}"""), // OpenAI-style ключи
        Regex("""ghp_[A-Za-z0-9]{36}"""),  // GitHub PAT
    )

    /** Расширения, которые точно не стоит сканировать на секреты. */
    private val excludedExtensions = setOf("png", "jpg", "jpeg", "gif", "ico", "pdf", "zip", "jar", "class")

    override fun analyze(context: AnalysisContext): CriterionResult {
        val root = context.rootDir
        val findings = mutableListOf<Finding>()

        FileUtils.listFilesRecursively(root)
            .asSequence()
            .filter { FileUtils.extensionOf(it) !in excludedExtensions }
            .filterNot { it.fileName.toString() in listOf(".gitignore", ".env.example") }
            .forEach { file ->
                val content = FileUtils.readTextSafely(file) ?: return@forEach
                for (pattern in secretPatterns) {
                    val match = pattern.find(content)
                    if (match != null) {
                        val line = content.substring(0, match.range.first).count { it == '\n' } + 1
                        findings += Finding(
                            file = root.relativize(file).toString(),
                            line = line,
                            severity = Severity.ERROR,
                            message = "Обнаружен возможный секрет: «${mask(match.value)}».",
                        )
                        break
                    }
                }
            }

        val score = if (findings.isEmpty()) 100.0 else maxOf(0.0, 100.0 - findings.size * 15.0)
        return CriterionResult(
            criterionId = id,
            criterionName = name,
            score = score,
            summary = if (findings.isEmpty()) "Секреты в коде не обнаружены."
            else "Найдено подозрительных секретов: ${findings.size}.",
            findings = findings,
        )
    }

    /** Маскирует секрет, чтобы не светить его в отчёте. */
    private fun mask(value: String): String {
        if (value.length <= 4) return "***"
        val head = value.take(2)
        val tail = value.takeLast(2)
        return "$head***$tail"
    }
}