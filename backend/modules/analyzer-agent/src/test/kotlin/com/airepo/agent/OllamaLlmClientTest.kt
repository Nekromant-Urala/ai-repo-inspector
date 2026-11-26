package com.airepo.agent

import kotlin.test.Test
import kotlin.test.assertEquals

class OllamaLlmClientTest {

    @Test
    fun `chatCompletionsUrl для локального Ollama добавляет v1`() {
        assertEquals(
            "http://localhost:11434/v1/chat/completions",
            chatCompletionsUrl("http://localhost:11434"),
        )
        assertEquals(
            "http://localhost:11434/v1/chat/completions",
            chatCompletionsUrl("http://localhost:11434/"),
        )
    }

    @Test
    fun `chatCompletionsUrl для внешнего API с v1 не дублирует его`() {
        assertEquals(
            "https://api.openai.com/v1/chat/completions",
            chatCompletionsUrl("https://api.openai.com/v1"),
        )
        assertEquals(
            "https://api.openai.com/v1/chat/completions",
            chatCompletionsUrl("https://api.openai.com/v1/"),
        )
    }
}