package com.airepo.agent

import com.fasterxml.jackson.databind.ObjectMapper
import org.springframework.stereotype.Component

/**
 * Разбирает ответы LLM в нужные структуры.
 *
 * Модель может вернуть текст с markdown-обёрткой или пояснениями вокруг JSON,
 * поэтому извлечение устойчиво к лишним символам.
 */
@Component
class AgentResultParser {

    private val mapper = ObjectMapper()

    /**
     * Извлекает итоговое описание. Пытается вытащить поле `description` из JSON
     * вида `{"description": "..."}`; если JSON нет — возвращает очищенный текст.
     */
    fun parseDescription(raw: String): String {
        val text = stripFences(raw)
        if (text.startsWith("{") && text.endsWith("}")) {
            runCatching { mapper.readTree(text).path("description").asText("") }
                .getOrNull()
                ?.takeIf { it.isNotBlank() }
                ?.let { return it.trim() }
        }
        val regexMatch = DESCRIPTION_REGEX.find(raw)
        if (regexMatch != null) {
            val value = regexMatch.groupValues[1]
            return unescape(value).trim()
        }
        return text.trim().removePrefix("\"").removeSuffix("\"")
    }

    /** Разбирает массив строк (todo-список), с fallback на построчный разбор. */
    fun parseStrings(raw: String): List<String> {
        val json = extractJsonArray(raw)
        if (json != null) {
            val parsed = runCatching { mapper.readValue(json, Array<String>::class.java) }.getOrNull()
            if (parsed != null && parsed.isNotEmpty()) {
                return parsed.map { it.trim() }.filter { it.isNotEmpty() }
            }
        }
        return raw.lines()
            .map { it.trim().removePrefix("-").trim().removePrefix("*").trim() }
            .filter { it.isNotEmpty() && !it.startsWith("```") }
    }

    private fun stripFences(raw: String): String =
        raw.trim().removePrefix("```json").removePrefix("```").trim().removeSuffix("```").trim()

    private fun extractJsonArray(raw: String): String? {
        val start = raw.indexOf('[')
        val end = raw.lastIndexOf(']')
        return if (start >= 0 && end > start) raw.substring(start, end + 1) else null
    }

    private fun unescape(value: String): String =
        value.replace("\\\"", "\"").replace("\\\\", "\\").replace("\\n", "\n")

    private companion object {
        val DESCRIPTION_REGEX = Regex("\"description\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"")
    }
}