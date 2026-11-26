package com.airepo.analyzer.tests.exec

import com.airepo.analyzer.tests.coverage.CoverageReport
import com.airepo.analyzer.tests.stack.TestStackPlan
import java.nio.file.Path
import java.time.Duration

/** Режим выполнения тестов. */
enum class ExecutionMode {
    /** Docker, если доступен; иначе — статический анализ. */
    AUTO,

    /** Только Docker-контейнер. */
    DOCKER,

    /** Только локальный запуск на хосте (требует `allow-local-execution=true`). */
    LOCAL,

    /** Тесты не запускать, оценивать только по исходникам. */
    STATIC_ONLY,
}

/**
 * Раннер тестов в изолированной среде.
 *
 * Реализации не знают о конкретных языках: они получают [TestStackPlan]
 * с готовыми командами и артефактами покрытия.
 */
interface TestRunner {

    /** Режим, который обслуживает раннер. */
    val mode: ExecutionMode

    /** Короткое имя для отчёта (`docker`, `local`). */
    val name: String

    /** Чем больше, тем приоритетнее раннер в режиме [ExecutionMode.AUTO]. */
    val priority: Int

    /** Проверка доступности раннера (Docker-демон, наличие shell и т.п.). */
    fun isAvailable(): Boolean

    /** Копирует проект в песочницу, выполняет тесты и забирает покрытие. */
    fun run(plan: TestStackPlan, projectRoot: Path): TestRunOutcome
}

/**
 * Результат одного прогона тестов.
 *
 * @param exitCode код завершения тестовой команды (`null`, если раннер не смог её выполнить)
 * @param timedOut сработал ли таймаут
 * @param output   объединённый вывод тестов (обрезан по настройкам)
 * @param coverage покрытие, если удалось найти и разобрать артефакты
 * @param error    инфраструктурная ошибка (Docker недоступен, копирование не удалось, ...)
 */
data class TestRunOutcome(
    val runner: String,
    val plan: TestStackPlan,
    val exitCode: Int?,
    val timedOut: Boolean,
    val duration: Duration,
    val output: String,
    val coverage: CoverageReport? = null,
    val error: String? = null,
) {
    /** Тесты выполнились и завершились с кодом 0. */
    val succeeded: Boolean get() = error == null && !timedOut && exitCode == 0
}
