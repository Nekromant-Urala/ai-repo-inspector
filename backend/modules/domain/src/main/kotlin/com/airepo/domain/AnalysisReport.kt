package com.airepo.domain

/**
 * Итоговый отчёт по репозиторию: совокупность результатов всех критериев.
 */
data class AnalysisReport(
    /** Идентификатор задачи проверки (совпадает с записью в БД). */
    val checkId: Long,

    /** Репозиторий, который анализировали. */
    val source: RepositorySource,

    /** Результаты по каждому критерию. */
    val criteria: List<CriterionResult>,
    val customCriteria: List<CustomResult> = emptyList(),
) {

    /** Общая оценка «качества» проекта как среднее по критериям. */
    val overallScore: Double
        get() = if (criteria.isEmpty()) 0.0
        else criteria.map { it.score }.average()
}
data class CustomResult(val name: String, val scale: String, val description: String, val score: Int?)
