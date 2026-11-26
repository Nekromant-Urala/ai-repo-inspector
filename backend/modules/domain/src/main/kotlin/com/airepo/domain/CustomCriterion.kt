package com.airepo.domain

/**
 * Пользовательский (кастомный) критерий, задаваемый через форму на фронте.
 *
 * Это инструкция на естественном языке, поэтому у него нет детерминированной
 * логики: оценку по нему выставляет ИИ-агент, анализируя проект.
 *
 * @param name        название критерия
 * @param scale       шкала оценивания (например, "от 0 до 10")
 * @param description что именно проверить
 */
data class CustomCriterion(
    val name: String,
    val scale: String,
    val description: String,
)