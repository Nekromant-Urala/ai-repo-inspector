package com.airepo.domain

/**
 * Оценка пользовательского критерия, выданная ИИ-агентом.
 *
 * По решению для задачи хранится только текстовое описание результата проверки
 * (без числовой оценки и списка замечаний).
 *
 * @param name        название критерия (из запроса)
 * @param description описание результата, которое вернул агент
 */
data class CustomCriterionAssessment(
    val name: String,
    val description: String,
)