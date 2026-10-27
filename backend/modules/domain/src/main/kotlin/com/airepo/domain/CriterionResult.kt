package com.airepo.domain

/**
 * Результат работы одного критерия аудита.
 *
 * @param criterionId    уникальный идентификатор критерия, например "readme"
 * @param criterionName  человекочитаемое имя критерия
 * @param score          оценка в диапазоне 0..100
 * @param summary        краткое текстовое резюме по критерию
 * @param findings       список точечных замечаний
 */
data class CriterionResult(
    val criterionId: String,
    val criterionName: String,
    val score: Double,
    val summary: String,
    val findings: List<Finding> = emptyList(),
)