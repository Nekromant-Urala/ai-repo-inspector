package com.airepo.analyzer.tests.exec

import com.airepo.analyzer.tests.TestsModuleProperties
import com.airepo.analyzer.tests.coverage.CoverageParser
import com.airepo.analyzer.tests.coverage.CoverageReport
import com.airepo.analyzer.tests.stack.TestStackPlan
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.time.Instant
import java.util.UUID

/**
 * Запуск тестов в изолированном Docker-контейнере.
 *
 * Изоляция:
 * - тесты исполняются в отдельном контейнере, а не в процессе бэкенда;
 * - проект копируется во временный именованный volume, хостовая ФС не монтируется;
 * - read-only rootfs, сброшенные capabilities, `no-new-privileges`;
 * - лимиты CPU, памяти, числа PID и жёсткий таймаут;
 * - по умолчанию доступна сеть (нужна пакетным менеджерам), режим `none` —
 *   для полностью герметичного прогона.
 *
 * Docker опрашивается один раз и результат кешируется.
 */
@Component
class DockerTestRunner(
    private val properties: TestsModuleProperties,
    private val coverageParser: CoverageParser,
) : TestRunner {

    private val log = LoggerFactory.getLogger(DockerTestRunner::class.java)

    override val mode: ExecutionMode = ExecutionMode.DOCKER
    override val name: String = "docker"
    override val priority: Int = 100

    @Volatile
    private var availability: Boolean? = null

    override fun isAvailable(): Boolean {
        availability?.let { return it }
        val sandbox = properties.sandbox
        val result = runCatching {
            Sandbox.runCommand(
                command = listOf(sandbox.dockerBinary, "version", "--format", "{{.Server.Version}}"),
                environment = dockerEnvironment(),
                timeout = Duration.ofSeconds(sandbox.dockerProbeTimeoutSeconds.coerceAtLeast(1L)),
                maxOutputChars = 2_000,
            )
        }.getOrNull()
        val available = result != null && !result.timedOut && result.exitCode == 0
        if (!available) {
            log.info("Docker недоступен — контейнерный запуск тестов будет пропущен")
        }
        availability = available
        return available
    }

    override fun run(plan: TestStackPlan, projectRoot: Path): TestRunOutcome {
        val startedAt = Instant.now()
        val sandbox = properties.sandbox
        val image = properties.imageFor(plan)
        val tempDir = runCatching { Files.createTempDirectory("airepo-test-docker-") }.getOrElse { error ->
            return failure(plan, "не удалось создать временный каталог: ${error.message}", startedAt)
        }

        val suffix = UUID.randomUUID().toString().replace("-", "").take(12)
        val containerName = DockerCommandBuilder.resourceName("airepo-test", suffix)
        val uploadName = DockerCommandBuilder.resourceName("airepo-upload", suffix)
        val downloadName = DockerCommandBuilder.resourceName("airepo-download", suffix)
        val volumeName = DockerCommandBuilder.resourceName("airepo-ws", suffix)
        var workspace: Path? = null

        try {
            workspace = Sandbox.prepareWorkspace(projectRoot, plan, sandbox)
            val workspaceTar = tempDir.resolve("workspace.tar")
            Sandbox.createTar(workspace, workspaceTar)

            // 1. Копия проекта уезжает в именованный volume.
            val upload = docker(
                DockerCommandBuilder.uploadArguments(uploadName, volumeName, image, sandbox),
                timeout = Duration.ofMinutes(10),
                inputFile = workspaceTar,
            )
            if (upload.exitCode != 0) {
                return failure(
                    plan,
                    "не удалось подготовить контейнер (${upload.exitCode}): ${upload.output}",
                    startedAt,
                    timedOut = upload.timedOut,
                )
            }

            // 2. Тестовый контейнер с лимитами ресурсов.
            val create = docker(
                DockerCommandBuilder.createArguments(containerName, volumeName, image, plan, sandbox),
                timeout = Duration.ofMinutes(5),
            )
            if (create.exitCode != 0) {
                return failure(plan, "docker create: ${create.output}", startedAt, timedOut = create.timedOut)
            }

            val testRun = runCatching {
                Sandbox.runCommand(
                    command = listOf(sandbox.dockerBinary, "start", "-a", containerName),
                    environment = dockerEnvironment(),
                    timeout = properties.timeout,
                    maxOutputChars = sandbox.maxOutputChars,
                    onTimeout = { killContainer(containerName) },
                )
            }.getOrElse { error ->
                return failure(plan, "docker start: ${error.message}", startedAt)
            }

            // 3. Забираем артефакты покрытия.
            val artifactsTar = tempDir.resolve("artifacts.tar")
            val download = docker(
                DockerCommandBuilder.downloadArguments(downloadName, volumeName, image, sandbox),
                timeout = Duration.ofMinutes(5),
                outputFile = artifactsTar,
                errorFile = tempDir.resolve("download.err"),
            )
            val coverage = readCoverage(artifactsTar, download, plan)

            return TestRunOutcome(
                runner = name,
                plan = plan,
                exitCode = testRun.exitCode,
                timedOut = testRun.timedOut,
                duration = Duration.between(startedAt, Instant.now()),
                output = testRun.output,
                coverage = coverage,
            )
        } catch (error: Exception) {
            return failure(plan, "${error.javaClass.simpleName}: ${error.message}", startedAt)
        } finally {
            // Убираем всё, что могло остаться после падения или таймаута.
            dockerQuietly("rm", "-f", containerName, uploadName, downloadName)
            dockerQuietly("volume", "rm", "-f", volumeName)
            if (!sandbox.keepWorkspace) workspace?.let { Sandbox.deleteRecursively(it) }
            Sandbox.deleteRecursively(tempDir)
        }
    }

    private fun readCoverage(artifactsTar: Path, download: ProcessResult, plan: TestStackPlan): CoverageReport? {
        if (download.exitCode != 0) return null
        val size = runCatching { Files.size(artifactsTar) }.getOrDefault(0L)
        if (size <= 0L) return null

        val extracted = runCatching { Files.createTempDirectory("airepo-test-artifacts-") }.getOrNull()
            ?: return null
        return try {
            Sandbox.extractTar(artifactsTar, extracted)
            coverageParser.parse(plan.coverageArtifacts, extracted)
        } catch (error: Exception) {
            log.debug("Не удалось разобрать артефакты покрытия для {}", plan.id, error)
            null
        } finally {
            Sandbox.deleteRecursively(extracted)
        }
    }

    private fun docker(
        arguments: List<String>,
        timeout: Duration,
        maxOutputChars: Int = properties.sandbox.maxOutputChars,
        inputFile: Path? = null,
        outputFile: Path? = null,
        errorFile: Path? = null,
    ): ProcessResult = Sandbox.runCommand(
        command = listOf(properties.sandbox.dockerBinary) + arguments,
        environment = dockerEnvironment(),
        timeout = timeout,
        maxOutputChars = maxOutputChars,
        inputFile = inputFile,
        outputFile = outputFile,
        errorFile = errorFile,
    )

    private fun killContainer(containerName: String) {
        dockerQuietly("kill", containerName)
    }

    private fun dockerQuietly(vararg arguments: String) {
        runCatching {
            Sandbox.runCommand(
                command = listOf(properties.sandbox.dockerBinary) + arguments,
                environment = dockerEnvironment(),
                timeout = Duration.ofSeconds(60),
                maxOutputChars = 1_000,
            )
        }
    }

    private fun dockerEnvironment(): Map<String, String> =
        properties.sandbox.dockerHost?.takeIf { it.isNotBlank() }?.let { mapOf("DOCKER_HOST" to it) }
            ?: emptyMap()

    private fun failure(
        plan: TestStackPlan,
        message: String,
        startedAt: Instant,
        timedOut: Boolean = false,
    ): TestRunOutcome = TestRunOutcome(
        runner = name,
        plan = plan,
        exitCode = null,
        timedOut = timedOut,
        duration = Duration.between(startedAt, Instant.now()),
        output = "",
        error = Output.tail(message, 1_500),
    )
}
