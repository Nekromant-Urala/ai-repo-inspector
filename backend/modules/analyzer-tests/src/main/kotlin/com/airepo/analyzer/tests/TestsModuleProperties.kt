package com.airepo.analyzer.tests

import com.airepo.analyzer.tests.exec.ExecutionMode
import com.airepo.analyzer.tests.stack.TestStackPlan
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.stereotype.Component
import java.time.Duration

/**
 * Конфигурация критерия «Покрытие тестами».
 *
 * Все значения переопределяются через `application.yml` / переменные окружения
 * (`AIREPO_TESTS_ENABLED`, `AIREPO_TESTS_MODE`, ...).
 *
 * Пример:
 * ```yaml
 * airepo:
 *   tests:
 *     enabled: true
 *     mode: AUTO              # AUTO | DOCKER | LOCAL | STATIC_ONLY
 *     timeout: 10m
 *     target-coverage: 80
 *     warning-coverage: 50
 *     sandbox:
 *       network: none         # герметичный запуск без сети
 *       memory: 2g
 *       cpus: "2.0"
 *     images:
 *       gradle: eclipse-temurin:21-jdk
 * ```
 */
@Component
@ConfigurationProperties(prefix = "airepo.tests")
class TestsModuleProperties {

    /** Включает автоматический запуск тестов. При `false` критерий работает только статически. */
    var enabled: Boolean = true

    /** Как запускать тесты: [ExecutionMode.AUTO] — Docker, если он доступен. */
    var mode: ExecutionMode = ExecutionMode.AUTO

    /**
     * Разрешает запуск тестов на хосте (без контейнера).
     *
     * По умолчанию выключено: тесты чужого репозитория — это исполнение
     * произвольного кода, поэтому локальный запуск требует явного согласия.
     */
    var allowLocalExecution: Boolean = false

    /** Максимальное время выполнения одного шага (установка зависимостей / тесты). */
    var timeout: Duration = Duration.ofMinutes(10)

    /** Целевой процент покрытия: при его достижении критерий получает 100 баллов. */
    var targetCoverage: Double = 80.0

    /** Порог, ниже которого покрытие считается критически низким. */
    var warningCoverage: Double = 50.0

    /** Насколько глубоко искать вложенные проекты (монорепозитории). */
    var maxDetectionDepth: Int = 3

    /** Максимум проектов/стеков, которые запускаются в рамках одной проверки. */
    var maxPlans: Int = 8

    /** Параметры изолированной среды выполнения. */
    var sandbox: SandboxProperties = SandboxProperties()

    /** Переопределение Docker-образов по идентификатору стека (`gradle`, `node`, `python`, ...). */
    var images: MutableMap<String, String> = LinkedHashMap()

    /** Возвращает образ для плана с учётом переопределений из конфигурации. */
    fun imageFor(plan: TestStackPlan): String =
        images[plan.id]?.takeIf { it.isNotBlank() } ?: plan.image
}

/**
 * Настройки контейнера, в котором выполняются тесты.
 *
 * По умолчанию включена максимально жёсткая изоляция: read-only rootfs,
 * сброшенные capabilities, лимиты CPU/памяти/PID, отдельный tmpfs для кешей
 * и таймаут выполнения. Сеть по умолчанию доступна, потому что большинству
 * пакетных менеджеров нужно скачать зависимости; для герметичных прогонов
 * установите `network: none`.
 */
class SandboxProperties {

    /** CLI Docker (можно указать абсолютный путь или podman с docker-совместимым CLI). */
    var dockerBinary: String = "docker"

    /** Значение `DOCKER_HOST`, если нужно ходить на удалённый демон. */
    var dockerHost: String? = null

    /** Лимит CPU, например `2.0`. */
    var cpus: String = "2.0"

    /** Лимит памяти контейнера, например `2g`. */
    var memory: String = "2g"

    /** Размер tmpfs в `/tmp` (кеши пакетных менеджеров, временные файлы). */
    var tmpfsSize: String = "2g"

    /** Лимит числа процессов в контейнере. */
    var pidsLimit: Int = 512

    /** Сеть контейнера: `none` — полностью без сети, `bridge` — с доступом в интернет. */
    var network: String = "bridge"

    /** Монтировать rootfs контейнера только для чтения (запись — только в `/workspace` и `/tmp`). */
    var readOnlyRootFilesystem: Boolean = true

    /** Сбросить все Linux capabilities (`--cap-drop ALL`). */
    var dropAllCapabilities: Boolean = true

    /** Запретить повышение привилегий (`no-new-privileges`). */
    var noNewPrivileges: Boolean = true

    /** Пользователь контейнера (`uid:gid`). По умолчанию — пользователь образа. */
    var user: String? = null

    /** Сколько символов вывода тестов сохранять в отчёте. */
    var maxOutputChars: Int = 20_000

    /** Максимальный размер копии проекта, передаваемой в контейнер. */
    var maxWorkspaceBytes: Long = 512L * 1024 * 1024

    /** Максимальное число файлов в копии проекта. */
    var maxWorkspaceFiles: Int = 20_000

    /** Оставить временную копию проекта после запуска (для отладки). */
    var keepWorkspace: Boolean = false

    /** Таймаут проверки доступности Docker-демона. */
    var dockerProbeTimeoutSeconds: Long = 10
}
