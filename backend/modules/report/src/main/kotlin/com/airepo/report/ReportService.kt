package com.airepo.report

import com.airepo.analyzer.AnalysisContext
import com.airepo.analyzer.CriterionAnalyzer
import com.airepo.domain.AnalysisReport
import com.airepo.ingestion.LoadedProject
import org.springframework.stereotype.Service

/**
 * Собирает итоговый отчёт: прогоняет все найденные Spring-бины критериев
 * по одному и тому же контексту проекта и агрегирует результаты.
 *
 * Новый критерий автоматически попадает в отчёт — достаточно добавить
 * реализацию `CriterionAnalyzer` в любой модуль.
 */
@Service
class ReportService(
    private val analyzers: List<CriterionAnalyzer>,
) {

    fun analyze(loaded: LoadedProject, checkId: Long): AnalysisReport {
        val context = AnalysisContext(
            rootDir = loaded.rootDir,
            source = loaded.source,
        )
        val criteria = analyzers
            .sortedBy { it.id }
            .map { it.analyze(context) }

        return AnalysisReport(
            checkId = checkId,
            source = loaded.source,
            criteria = criteria,
        )
    }
}