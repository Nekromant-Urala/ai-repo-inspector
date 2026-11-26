package com.airepo.agent

import com.airepo.analyzer.AnalysisContext
import com.airepo.domain.CriterionResult
import com.airepo.domain.CustomCriterion
import com.airepo.domain.CustomCriterionAssessment
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Configuration
import org.springframework.stereotype.Component

/**
 * ИИ-агент: оценивает пользовательские критерии и формирует список рекомендаций.
 *
 * Для полного охвата исходники передаются чанками: каждый чанк файлов оценивается
 * отдельным запросом, затем частичные наблюдения склеиваются в одно итоговое
 * описание.
 *
 * Когда `agent.enabled=false`, все операции возвращают пустые результаты —
 * приложение работает без LLM (тесты, CI).
 */
@Component
class AnalysisAgent(
    private val client: LlmClient,
    private val props: AgentProperties,
    private val promptBuilder: AgentPromptBuilder,
    private val parser: AgentResultParser,
) {

    /**
     * Оценивает пользовательские критерии. Для каждого критерия возвращается только
     * текстовое описание результата ([CustomCriterionAssessment]).
     */
    fun evaluateCustomCriteria(
        context: AnalysisContext,
        customCriteria: List<CustomCriterion>,
    ): List<CustomCriterionAssessment> {
        if (!props.enabled || customCriteria.isEmpty()) return emptyList()

        val chunks = promptBuilder.splitIntoChunks(context.rootDir)
        if (chunks.isEmpty()) {
            return customCriteria.map { criterion ->
                CustomCriterionAssessment(
                    name = criterion.name,
                    description = "Не удалось прочитать исходники проекта по критерию «${criterion.name}».",
                )
            }
        }

        return customCriteria.map { criterion ->
            val partials = chunks.mapIndexed { index, chunk ->
                val (system, user) = promptBuilder.evaluateChunkPrompt(
                    criterion = criterion,
                    chunkIndex = index + 1,
                    chunkCount = chunks.size,
                    chunk = chunk,
                )
                runCatching { client.chat(system, user).trim() }.getOrDefault("")
            }

            val description = if (chunks.size > 1) {
                val (system, user) = promptBuilder.aggregatePrompt(criterion, partials.filter { it.isNotBlank() })
                runCatching { parser.parseDescription(client.chat(system, user)) }.getOrDefault("")
            } else {
                parser.parseDescription(partials.firstOrNull().orEmpty())
            }

            CustomCriterionAssessment(
                name = criterion.name,
                description = description.ifBlank {
                    "Не удалось получить описание от ИИ-агента по критерию «${criterion.name}»."
                },
            )
        }
    }

    /**
     * Формирует список рекомендаций по исправлению (todo) на основе всех результатов.
     */
    fun recommendFixes(
        criteria: List<CriterionResult>,
        customCriteria: List<CustomCriterion>,
    ): List<String> {
        if (!props.enabled) return emptyList()

        val (system, user) = promptBuilder.recommendationsPrompt(criteria, customCriteria)
        return runCatching { parser.parseStrings(client.chat(system, user)) }
            .getOrDefault(emptyList())
            .take(MAX_RECOMMENDATIONS)
    }

    private companion object {
        const val MAX_RECOMMENDATIONS = 10
    }
}

/** Регистрирует [AgentProperties] как Spring-бин. */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(AgentProperties::class)
class AgentConfiguration