package com.airepo.analyzer.tests.exec

import com.airepo.analyzer.tests.SandboxProperties
import com.airepo.analyzer.tests.stack.TestStackPlan

/**
 * Собирает команды Docker для изолированного запуска тестов.
 *
 * Схема прогона:
 * 1. `docker run` во временном volume распаковывает копию проекта;
 * 2. `docker create` + `docker start -a` выполняет тесты в контейнере с лимитами;
 * 3. `docker run` вытаскивает tar с артефактами покрытия.
 *
 * Volume используется намеренно: он не зависит от того, запущен ли бэкенд
 * в контейнере (Docker-out-of-Docker) и не даёт контейнеру доступ к хостовой ФС.
 *
 * Вынесено в отдельный объект, чтобы флаги изоляции и команды сбора покрытия
 * можно было проверить юнит-тестами без Docker.
 */
object DockerCommandBuilder {

    /** Путь к tar-архиву с найденными файлами покрытия внутри контейнера. */
    const val ARTIFACTS_TAR: String = "/workspace/.airepo-artifacts.tar"

    /** Путь к списку найденных файлов покрытия. */
    private const val ARTIFACT_LIST: String = "/tmp/airepo/artifacts.list"

    /**
     * Аргументы `docker create` для тестового контейнера.
     *
     * @param containerName имя контейнера
     * @param volumeName    именованный volume с копией проекта
     * @param image         образ (уже с учётом переопределений)
     */
    fun createArguments(
        containerName: String,
        volumeName: String,
        image: String,
        plan: TestStackPlan,
        sandbox: SandboxProperties,
    ): List<String> {
        val arguments = mutableListOf("create", "--name", containerName)
        arguments += listOf("--label", "airepo.test.sandbox=true")
        arguments += listOf("-v", "$volumeName:/workspace")
        arguments += listOf("-w", "/workspace")

        if (sandbox.readOnlyRootFilesystem) arguments += "--read-only"
        arguments += listOf("--tmpfs", "/tmp:rw,exec,size=${sandbox.tmpfsSize}")
        arguments += listOf("--tmpfs", "/run:rw,size=64m")
        if (sandbox.dropAllCapabilities) arguments += listOf("--cap-drop", "ALL")
        if (sandbox.noNewPrivileges) arguments += listOf("--security-opt", "no-new-privileges")
        arguments += listOf("--pids-limit", sandbox.pidsLimit.toString())
        arguments += listOf("--memory", sandbox.memory, "--memory-swap", sandbox.memory)
        arguments += listOf("--cpus", sandbox.cpus)
        arguments += listOf("--network", sandbox.network)
        arguments += listOf("--ulimit", "nofile=4096:4096")
        arguments += listOf("--stop-timeout", "5")
        arguments += listOf("--init")

        sandbox.user?.takeIf { it.isNotBlank() }?.let { user ->
            arguments += listOf("--user", user)
        }
        environment(plan).forEach { (key, value) ->
            arguments += listOf("-e", "$key=$value")
        }

        arguments += listOf("--entrypoint", "sh", image, "-c", script(plan))
        return arguments
    }

    /** Аргументы контейнера, который распаковывает tar с копией проекта в volume. */
    fun uploadArguments(
        containerName: String,
        volumeName: String,
        image: String,
        sandbox: SandboxProperties,
    ): List<String> = helperBase(containerName, volumeName, sandbox) + listOf(
        "-i",
        "--entrypoint", "sh",
        image, "-c",
        "tar -xf - -C /workspace",
    )

    /** Аргументы контейнера, который отдаёт tar с артефактами покрытия в stdout. */
    fun downloadArguments(
        containerName: String,
        volumeName: String,
        image: String,
        sandbox: SandboxProperties,
    ): List<String> = helperBase(containerName, volumeName, sandbox) + listOf(
        "--entrypoint", "sh",
        image, "-c",
        "if [ -f $ARTIFACTS_TAR ]; then cat $ARTIFACTS_TAR; fi",
    )

    /**
     * Shell-скрипт, который выполняется внутри тестового контейнера.
     *
     * Скрипт всегда доходит до сборки артефактов, даже если тесты упали,
     * и возвращает код завершения тестов.
     */
    fun script(plan: TestStackPlan): String = buildString {
        appendLine("set -u")
        appendLine("export HOME=\"/tmp/home\"")
        appendLine("export TMPDIR=\"/tmp\"")
        appendLine("mkdir -p \"\$HOME\" /tmp/airepo 2>/dev/null || true")
        appendLine("cd " + Shell.quote(plan.containerWorkingDirectory) +
            " || { echo '[airepo] project directory not found'; exit 97; }")

        plan.bootstrapCommands.forEach { command ->
            val joined = Shell.join(command)
            appendLine("$joined || echo " + Shell.quote("[airepo] bootstrap step failed: $joined"))
        }

        appendLine(Shell.join(plan.testCommand))
        appendLine("airepo_test_status=\$?")
        appendLine("cd /workspace 2>/dev/null || true")

        if (plan.coverageArtifacts.isNotEmpty()) {
            val names = plan.coverageArtifacts.map { it.fileName }.distinct()
            val findExpression = names.joinToString(" -o ") { name -> "-name " + Shell.quote(name) }
            appendLine("find . -type f \\( $findExpression \\) -print > $ARTIFACT_LIST 2>/dev/null || true")
            appendLine(
                "if [ -s $ARTIFACT_LIST ]; then tar -cf $ARTIFACTS_TAR -T $ARTIFACT_LIST 2>/dev/null || true; fi",
            )
        }

        appendLine("exit \"\$airepo_test_status\"")
    }

    private fun helperBase(
        containerName: String,
        volumeName: String,
        sandbox: SandboxProperties,
    ): List<String> = buildList {
        add("run")
        add("--rm")
        add("--name")
        add(containerName)
        add("--label")
        add("airepo.test.sandbox=true")
        add("-v")
        add("$volumeName:/workspace")
        add("--network")
        add("none")
        add("--read-only")
        add("--tmpfs")
        add("/tmp:rw,size=64m")
        if (sandbox.dropAllCapabilities) {
            add("--cap-drop")
            add("ALL")
        }
        if (sandbox.noNewPrivileges) {
            add("--security-opt")
            add("no-new-privileges")
        }
        add("--memory")
        add("512m")
        add("--pids-limit")
        add("128")
    }

    /** Общие переменные окружения контейнера + настройки стека. */
    fun environment(plan: TestStackPlan): Map<String, String> = linkedMapOf(
        "HOME" to "/tmp/home",
        "TMPDIR" to "/tmp",
        "XDG_CACHE_HOME" to "/tmp/home/.cache",
        "CI" to "true",
    ).apply { putAll(plan.environment) }

    /** Генерирует безопасное имя контейнера/volume. */
    fun resourceName(prefix: String, suffix: String): String =
        "$prefix-" + suffix.filter { it.isLetterOrDigit() }.take(12)
}
