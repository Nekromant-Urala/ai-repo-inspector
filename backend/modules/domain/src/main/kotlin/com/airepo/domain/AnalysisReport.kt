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

    /** Пользовательские критерии, добавленные через форму на фронте. */
    val customCriteria: List<CustomCriterion> = emptyList(),

    /** Оценки пользовательских критериев, выданные ИИ-агентом (только описание). */
    val customAssessments: List<CustomCriterionAssessment> = emptyList(),

    /** Список рекомендаций по исправлению (формирует ИИ-агент). */
    val recommendations: List<String> = emptyList(),
) {

    /** Общая оценка «качества» проекта как среднее по критериям. */
    val overallScore: Double
        get() = if (criteria.isEmpty()) 0.0
        else criteria.map { it.score }.average()
}