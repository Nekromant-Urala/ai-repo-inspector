package com.airepo.agent

import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.Duration

/**
 * Настройки ИИ-агента.
 *
 * По умолчанию агент выключен (graceful degradation): без `agent.enabled=true`
 * кастомные критерии не оцениваются, рекомендации не формируются.
 *
 * В локальной среде подразумевается локальный Ollama (`http://localhost:11434`),
 * в Docker — сервис `ollama` из docker-compose.
 */
@ConfigurationProperties(prefix = "agent")
class AgentProperties {

    /** Включает вызовы LLM. Когда false, агент возвращает пустые результаты. */
    var enabled: Boolean = false

    /** Базовый URL OpenAI-совместимого эндпоинта (Ollama: `http://host:11434`). */
    var baseUrl: String = "http://localhost:11434"

    /** Имя модели (например, `llama3.2`). */
    var model: String = "llama3.2"

    /**
     * Ключ доступа для внешнего OpenAI-совместимого API.
     * Если задан — шлётся заголовок `Authorization: Bearer <api-key>`.
     * Для локального Ollama оставляется пустым.
     */
    var apiKey: String = ""

    /** Таймаут HTTP-запроса к LLM. */
    var timeout: Duration = Duration.ofSeconds(120)
}