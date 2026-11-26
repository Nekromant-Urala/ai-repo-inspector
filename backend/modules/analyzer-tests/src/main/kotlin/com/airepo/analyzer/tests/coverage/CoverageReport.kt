package com.airepo.analyzer.tests.coverage

/**
 * Агрегированное покрытие проекта.
 *
 * @param covered число покрытых строк/инструкций
 * @param total   общее число строк/инструкций, участвующих в покрытии
 * @param format  человекочитаемое имя инструмента, отдавшего данные
 * @param files   сколько файлов покрытия было разобрано
 */
data class CoverageReport(
    val covered: Long,
    val total: Long,
    val format: String,
    val files: Int = 1,
) {
    /** Процент покрытия в диапазоне 0..100. */
    val percent: Double
        get() = if (total <= 0L) 0.0 else covered.toDouble() * 100.0 / total

    /** Складывает отчёты (например, по модулям многомодульной сборки). */
    operator fun plus(other: CoverageReport): CoverageReport = CoverageReport(
        covered = covered + other.covered,
        total = total + other.total,
        format = format,
        files = files + other.files,
    )

    companion object {
        /** Создаёт отчёт, только если есть осмысленные данные (total > 0). */
        fun of(covered: Long, total: Long, format: String, files: Int = 1): CoverageReport? {
            if (total <= 0L) return null
            return CoverageReport(
                covered = covered.coerceIn(0L, total),
                total = total,
                format = format,
                files = files,
            )
        }
    }
}
