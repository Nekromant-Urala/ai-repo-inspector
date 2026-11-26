package com.airepo.agent

import com.airepo.analyzer.AnalysisContext
import com.airepo.domain.CustomCriterion
import com.airepo.domain.RepositorySource
import com.airepo.domain.SourceType
import kotlin.io.path.createTempDirectory
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AnalysisAgentTest {

    private fun agent(client: LlmClient, enabled: Boolean): AnalysisAgent {
        val props = AgentProperties().apply { this.enabled = enabled }
        return AnalysisAgent(client, props, AgentPromptBuilder(), AgentResultParser())
    }

    private fun contextWithFile(content: String = "fun main() {}"): AnalysisContext {
        val dir = createTempDirectory("airepo-test")
        dir.resolve("Main.kt").writeText(content)
        return AnalysisContext(
            rootDir = dir,
            source = RepositorySource(SourceType.GIT_URL, "https://github.com/owner/project"),
        )
    }

    @Test
    fun `evaluateCustomCriteria возвращает описание, выданное LLM`() {
        val fake = FakeLlmClient { _, _ -> """{"description":"Проект хорошо документирован"}""" }

        val result = agent(fake, enabled = true)
            .evaluateCustomCriteria(contextWithFile(), listOf(CustomCriterion("Документация", "0-10", "оценить README")))

        assertEquals(1, result.size)
        val assessment = result.single()
        assertEquals("Документация", assessment.name)
        assertEquals("Проект хорошо документирован", assessment.description)
    }

    @Test
    fun `evaluateCustomCriteria возвращает пустой список при выключенном агенте`() {
        val fake = FakeLlmClient { _, _ -> throw AssertionError("не должен вызываться") }

        val result = agent(fake, enabled = false)
            .evaluateCustomCriteria(contextWithFile(), listOf(CustomCriterion("Документация", "0-10", "оценить README")))

        assertTrue(result.isEmpty())
    }

    @Test
    fun `recommendFixes возвращает todo-список`() {
        val fake = FakeLlmClient { _, _ -> """["Добавить README","Исправить TODO"]""" }

        val result = agent(fake, enabled = true).recommendFixes(emptyList(), emptyList())

        assertEquals(listOf("Добавить README", "Исправить TODO"), result)
    }

    @Test
    fun `recommendFixes не вызывает LLM при выключенном агенте`() {
        val fake = FakeLlmClient { _, _ -> throw AssertionError("не должен вызываться") }

        val result = agent(fake, enabled = false).recommendFixes(emptyList(), emptyList())

        assertTrue(result.isEmpty())
    }
}

/** Заглушка LLM-клиента на основе лямбды. */
private class FakeLlmClient(private val response: (system: String, user: String) -> String) : LlmClient {
    override fun chat(system: String, user: String): String = response(system, user)
}