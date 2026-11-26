package com.airepo.agent

import com.fasterxml.jackson.databind.ObjectMapper
import org.springframework.stereotype.Component
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse

/**
 * Реализация [LlmClient] поверх локального Ollama через его OpenAI-совместимый
 * эндпоинт `POST {baseUrl}/v1/chat/completions`.
 *
 * Используется встроенный `java.net.http.HttpClient`, чтобы не тянуть
 * в модуль лишние HTTP-зависимости.
 */
@Component
class OllamaLlmClient(
    private val props: AgentProperties,
) : LlmClient {

    private val mapper = ObjectMapper()
    private val http: HttpClient = HttpClient.newBuilder()
        .connectTimeout(props.timeout)
        .build()

    override fun chat(system: String, user: String): String {
        val payload = mapper.writeValueAsString(
            mapOf(
                "model" to props.model,
                "stream" to false,
                "messages" to listOf(
                    mapOf("role" to "system", "content" to system),
                    mapOf("role" to "user", "content" to user),
                ),
            )
        )

        val requestBuilder = HttpRequest.newBuilder(URI.create(chatCompletionsUrl(props.baseUrl)))
            .timeout(props.timeout)
            .header("Content-Type", "application/json")
        if (props.apiKey.isNotBlank()) {
            requestBuilder.header("Authorization", "Bearer ${props.apiKey}")
        }
        val request = requestBuilder
            .POST(HttpRequest.BodyPublishers.ofString(payload))
            .build()

        val response = http.send(request, HttpResponse.BodyHandlers.ofString())
        if (response.statusCode() !in 200..299) {
            throw IllegalStateException("LLM request failed (${response.statusCode()}): ${response.body()}")
        }

        val tree = mapper.readTree(response.body())
        return tree.path("choices")[0].path("message").path("content").asText("")
    }
}

/**
 * Собирает полный URL OpenAI-совместимого чат-эндпоинта.
 *
 * Локальный Ollama задаётся как `http://host:11434` (без `/v1`), а внешние API —
 * уже с `/v1` (например `https://api.openai.com/v1`). Поэтому `/v1` добавляется
 * только если его ещё нет.
 */
internal fun chatCompletionsUrl(baseUrl: String): String {
    val base = baseUrl.trimEnd('/')
    return if (base.endsWith("/v1")) "$base/chat/completions" else "$base/v1/chat/completions"
}