package com.airepo.analyzer.tests.stack

/**
 * Формат файла с результатом покрытия.
 *
 * Парсеры для этих форматов живут в пакете `coverage`: большинство
 * современных инструментов умеют отдавать один из них.
 */
enum class CoverageFormat {
    /** JaCoCo XML (`build/reports/jacoco/test/jacocoTestReport.xml`, `target/site/jacoco/jacoco.xml`). */
    JACOCO_XML,

    /** Cobertura XML (pytest-cov, .NET XPlat Code Coverage). */
    COBERTURA_XML,

    /** LCOV (`coverage/lcov.info`). */
    LCOV,

    /** Go cover profile (`coverage.out`). */
    GO_COVER,

    /** Clover XML (PHPUnit). */
    CLOVER_XML,

    /** SimpleCov JSON (`coverage/.resultset.json`, Ruby). */
    SIMPLECOV_JSON,

    /** Istanbul `coverage-summary.json` (Jest/Vitest/nyc). */
    ISTANBUL_JSON,
}

/**
 * Ожидаемый файл покрытия.
 *
 * @param glob   путь относительно корня проекта, допускает `*` и `**`
 * @param format формат содержимого
 */
data class CoverageArtifact(
    val glob: String,
    val format: CoverageFormat,
) {
    init {
        require(glob.isNotBlank()) { "Coverage artifact glob must not be blank" }
    }

    /** Имя файла без каталогов — используется для поиска артефактов внутри контейнера. */
    val fileName: String get() = glob.substringAfterLast('/').ifBlank { glob }
}

/**
 * Описание одного проекта/стека, который нужно прогнать в изолированной среде.
 *
 * План полностью декларативный: раннер не знает ничего про конкретный язык,
 * он лишь копирует проект, выполняет команды и забирает артефакты покрытия.
 *
 * @param id                идентификатор стека (`gradle`, `maven`, `node`, ...) — ключ для переопределения образа
 * @param displayName       человекочитаемое имя для отчёта
 * @param image             Docker-образ по умолчанию
 * @param workingDirectory  каталог проекта относительно корня репозитория (`"."` — корень)
 * @param bootstrapCommands команды подготовки (установка зависимостей); ошибки не прерывают запуск
 * @param testCommand       команда запуска тестов с флагами сбора покрытия
 * @param coverageArtifacts файлы покрытия, которые нужно забрать из контейнера
 * @param injectedFiles     файлы, которые нужно доложить в копию проекта (например, init-скрипт JaCoCo)
 * @param environment       переменные окружения для контейнера
 * @param notes             пояснения, попадающие в отчёт только при проблемах
 */
data class TestStackPlan(
    val id: String,
    val displayName: String,
    val image: String,
    val workingDirectory: String = ".",
    val bootstrapCommands: List<List<String>> = emptyList(),
    val testCommand: List<String>,
    val coverageArtifacts: List<CoverageArtifact> = emptyList(),
    val injectedFiles: Map<String, String> = emptyMap(),
    val environment: Map<String, String> = emptyMap(),
    val notes: List<String> = emptyList(),
) {
    init {
        require(id.isNotBlank()) { "Stack id must not be blank" }
        require(image.isNotBlank()) { "Docker image must not be blank" }
        require(testCommand.isNotEmpty()) { "Test command must not be empty" }
        require(!workingDirectory.contains("..")) { "Working directory must stay inside the workspace" }
    }

    /** Абсолютный путь к рабочему каталогу внутри контейнера. */
    val containerWorkingDirectory: String
        get() {
            val normalized = workingDirectory.trim().trim('/')
            return if (normalized.isEmpty() || normalized == ".") "/workspace" else "/workspace/$normalized"
        }

    /** Полное имя проекта для отчёта. */
    val label: String
        get() = if (workingDirectory.trim().trim('/').let { it.isEmpty() || it == "." }) {
            "$displayName (корень)"
        } else {
            "$displayName ($workingDirectory)"
        }
}
