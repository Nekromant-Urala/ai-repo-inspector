package com.airepo.domain

/**
 * Одно точечное замечание, найденное каким-либо критерием.
 *
 * Все замечания должны ссылаться на конкретный файл/строку,
 * чтобы отчёт был практичным (требование SMARTER · Measurable).
 */
data class Finding(
    /** Путь к файлу относительно корня репозитория. */
    val file: String? = null,

    /** Номер строки (если применимо). */
    val line: Int? = null,

    /** Человекочитаемое описание проблемы. */
    val message: String,

    /** Уровень важности замечания. */
    val severity: Severity = Severity.WARNING,
)

enum class Severity {
    INFO,
    WARNING,
    ERROR,
}