package com.airepo.agent

import kotlin.test.Test
import kotlin.test.assertEquals

class AgentResultParserTest {

    private val parser = AgentResultParser()

    @Test
    fun `parseDescription извлекает поле description из JSON`() {
        val raw = """{"description":"Хорошо оформлено и задокументировано"}"""
        assertEquals("Хорошо оформлено и задокументировано", parser.parseDescription(raw))
    }

    @Test
    fun `parseDescription работает с markdown-обёрткой`() {
        val raw = "```json\n{\"description\": \"Есть замечания\"}\n```"
        assertEquals("Есть замечания", parser.parseDescription(raw))
    }

    @Test
    fun `parseDescription без JSON возвращает очищенный текст`() {
        assertEquals("Простое описание", parser.parseDescription("Простое описание"))
    }

    @Test
    fun `parseStrings разбирает JSON-массив строк`() {
        val raw = """["Добавить README","Исправить TODO"]"""
        assertEquals(listOf("Добавить README", "Исправить TODO"), parser.parseStrings(raw))
    }

    @Test
    fun `parseStrings без JSON разбирает строки как fallback`() {
        val raw = "- Добавить README\n- Исправить TODO"
        assertEquals(listOf("Добавить README", "Исправить TODO"), parser.parseStrings(raw))
    }
}