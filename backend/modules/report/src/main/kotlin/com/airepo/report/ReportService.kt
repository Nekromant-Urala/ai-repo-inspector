package com.airepo.report

import com.airepo.agent.AnalysisAgent
import com.airepo.analyzer.AnalysisContext
import com.airepo.analyzer.CriterionAnalyzer
import com.airepo.domain.AnalysisReport
import com.airepo.domain.CustomCriterion
import com.airepo.ingestion.LoadedProject
import org.springframework.stereotype.Service

/**
 * Собирает итоговый отчёт: прогоняет все найденные Spring-бины критериев
 * по одному и тому же контексту проекта, затем оценивает пользовательские
 * критерии через ИИ-агента и формирует рекомендации.
 *
 * Новый встроенный критерий автоматически попадает в отчёт — достаточно добавить
 * реализацию `CriterionAnalyzer` в любой модуль.
 */
@Service
class ReportService(
    private val analyzers: List<CriterionAnalyzer>,
    private val agent: AnalysisAgent,
) {

    fun analyze(
        loaded: LoadedProject,
        checkId: Long,
        customCriteria: List<CustomCriterion> = emptyList(),
    ): AnalysisReport {
        val context = AnalysisContext(
            rootDir = loaded.rootDir,
            source = loaded.source,
        )
        val builtIn = analyzers
            .sortedBy { it.id }
            .map { it.analyze(context) }

        val customAssessments = agent.evaluateCustomCriteria(context, customCriteria)
        val recommendations = agent.recommendFixes(builtIn, customCriteria)

        return AnalysisReport(
            checkId = checkId,
            source = loaded.source,
            criteria = builtIn,
            customCriteria = customCriteria,
            customAssessments = customAssessments,
            recommendations = recommendations,
        )
    }
}