package com.airepo.agent

import com.airepo.domain.CriterionResult
import com.airepo.domain.CustomCriterion
import org.springframework.stereotype.Component
import java.nio.file.Files
import java.nio.file.Path

/**
 * Формирует промпты для ИИ-агента.
 *
 * Чтобы агент видел весь проект, исходники передаются чанками (map-reduce):
 * файлы группируются в чанки, каждый чанк оценивается отдельным запросом,
 * а затем частичные наблюдения склеиваются в финальное описание.
 */
@Component
class AgentPromptBuilder {

    /**
     * Разбивает файлы проекта на чанки. Каждый чанк — конкатенация нескольких
     * файлов с их относительными путями, ограниченная по размеру, чтобы влезть
     * в контекст модели.
     */
    fun splitIntoChunks(root: Path): List<String> {
        val files = Files.walk(root).use { stream ->
            stream
                .filter { Files.isRegularFile(it) }
                .filter { file ->
                    val rel = root.relativize(file).toString().replace("\\", "/")
                    isCandidate(rel) && Files.size(file) <= MAX_FILE_BYTES
                }
                .sorted(compareBy<Path> { depthOf(root, it) }.thenBy { it.toString() })
                .toList()
        }

        val chunks = mutableListOf<String>()
        val current = StringBuilder()
        for (file in files) {
            val rel = root.relativize(file).toString().replace("\\", "/")
            val content = runCatching { Files.readString(file) }.getOrDefault("")
            val block = "=== $rel ===\n$content\n"
            if (current.isNotEmpty() && current.length + block.length > CHUNK_CHARS) {
                chunks += current.toString()
                current.setLength(0)
            }
            current.append(block)
            if (chunks.size >= MAX_CHUNKS) break
        }
        if (current.isNotEmpty() && chunks.size < MAX_CHUNKS) {
            chunks += current.toString()
        }
        return chunks
    }

    /** Промпт оценки одного чанка: модель возвращает наблюдения по критерию. */
    fun evaluateChunkPrompt(
        criterion: CustomCriterion,
        chunkIndex: Int,
        chunkCount: Int,
        chunk: String,
    ): Pair<String, String> {
        val system = """
            Ты — инженер-аудитор качества кода. Оцениваешь фрагмент проекта по одному
            пользовательскому критерию. Верни свои наблюдения по этому фрагменту в виде
            обычного текста (что соответствует критерию, что нарушает его, аргументы).
            Не используй JSON и не делай итоговой оценки за весь проект — только по этому фрагменту.
        """.trimIndent()

        val user = buildString {
            appendLine("Критерий: ${criterion.name}")
            appendLine("Шкала: ${criterion.scale}")
            appendLine("Что проверить: ${criterion.description}")
            appendLine()
            appendLine("Фрагмент файлов $chunkIndex из $chunkCount:")
            appendLine(chunk)
        }
        return system to user
    }

    /** Промпт агрегации: склеивает частичные наблюдения в финальное описание (JSON). */
    fun aggregatePrompt(
        criterion: CustomCriterion,
        partials: List<String>,
    ): Pair<String, String> {
        val system = """
            Ты — инженер-аудитор качества кода. Ниже — наблюдения по фрагментам проекта
            для одного пользовательского критерия. Объедини их в единое итоговое описание
            результата проверки по этому критерию.
            Верни ТОЛЬКО валидный JSON: {"description": "итоговое описание"}.
            Никакого текста вне JSON.
        """.trimIndent()

        val user = buildString {
            appendLine("Критерий: ${criterion.name}")
            appendLine("Шкала: ${criterion.scale}")
            appendLine("Что проверить: ${criterion.description}")
            partials.forEachIndexed { index, partial ->
                appendLine()
                appendLine("Наблюдение ${index + 1}:")
                appendLine(partial)
            }
        }
        return system to user
    }

    /** Промпт формирования «todo исправлений» по результатам проверки. */
    fun recommendationsPrompt(
        criteria: List<CriterionResult>,
        customCriteria: List<CustomCriterion>,
    ): Pair<String, String> {
        val system = """
            Ты — инженер-аудитор качества кода. По результатам проверки репозитория
            составь список конкретных исправлений (todo). Верни ТОЛЬКО валидный JSON:
            массивом строк, например ["Исправить A", "Добавить B"]. Никакого текста вне JSON.
        """.trimIndent()

        val user = buildString {
            appendLine("Результаты проверки:")
            criteria.forEach { appendLine("- ${it.criterionName} (${it.score}/100): ${it.summary}") }
            if (customCriteria.isNotEmpty()) {
                appendLine("Добавленные пользователем критерии: ${customCriteria.joinToString("; ") { it.name }}")
            }
            appendLine("Сформируй до 10 конкретных, выполнимых исправлений, упорядоченных по важности.")
        }
        return system to user
    }

    private fun isCandidate(rel: String): Boolean {
        if (IGNORED_DIRS.any { rel.startsWith("$it/") || rel == it || rel.contains("/$it/") }) return false
        val ext = rel.substringAfterLast('.', "").lowercase()
        return ext !in BINARY_EXTENSIONS
    }

    private fun depthOf(root: Path, file: Path): Int =
        root.relativize(file).nameCount

    private companion object {
        const val MAX_FILE_BYTES = 20_000
        const val CHUNK_CHARS = 20_000
        const val MAX_CHUNKS = 25
        val IGNORED_DIRS = setOf(
            ".git", ".idea", "node_modules", "build", "dist", "target", "out", "bin", ".gradle",
        )
        val BINARY_EXTENSIONS = setOf(
            "png", "jpg", "jpeg", "gif", "ico", "webp", "woff", "woff2", "ttf", "otf",
            "jar", "zip", "tar", "gz", "class", "pyc", "so", "dylib", "pdf", "db", "lock",
        )
    }
}