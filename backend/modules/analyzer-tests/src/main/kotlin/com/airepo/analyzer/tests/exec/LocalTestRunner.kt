package com.airepo.analyzer.tests.exec

import com.airepo.analyzer.tests.TestsModuleProperties
import com.airepo.analyzer.tests.coverage.CoverageParser
import com.airepo.analyzer.tests.stack.TestStackPlan
import org.springframework.stereotype.Component
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.time.Instant

/**
 * Резервный запуск тестов прямо на хосте.
 *
 * Используется только при `airepo.tests.allow-local-execution=true` и явном
 * выборе режима (или отсутствии Docker): исполнение тестов чужого репозитория
 * на хосте — это исполнение произвольного кода, поэтому по умолчанию выключено.
 *
 * Проект всё равно анализируется по копии во временном каталоге, чтобы не
 * засорять исходный репозиторий артефактами сборки.
 */
@Component
class LocalTestRunner(
    private val properties: TestsModuleProperties,
    private val coverageParser: CoverageParser,
) : TestRunner {

    override val mode: ExecutionMode = ExecutionMode.LOCAL
    override val name: String = "local"
    override val priority: Int = 10

    override fun isAvailable(): Boolean {
        if (!properties.allowLocalExecution) return false
        val probe = runCatching {
            Sandbox.runCommand(
                command = listOf("sh", "-c", "command -v sh >/dev/null 2>&1 && command -v tar >/dev/null 2>&1"),
                timeout = Duration.ofSeconds(5),
                maxOutputChars = 256,
            )
        }.getOrNull()
        return probe != null && probe.exitCode == 0
    }

    override fun run(plan: TestStackPlan, projectRoot: Path): TestRunOutcome {
        val startedAt = Instant.now()
        var workspace: Path? = null
        try {
            workspace = Sandbox.prepareWorkspace(projectRoot, plan, properties.sandbox)
            val workingDirectory = resolveWorkingDirectory(workspace, plan)
            val environment = mapOf("CI" to "true") + plan.environment

            plan.bootstrapCommands.forEach { command ->
                Sandbox.runCommand(
                    command = command,
                    workDirectory = workingDirectory,
                    environment = environment,
                    timeout = properties.timeout,
                    maxOutputChars = properties.sandbox.maxOutputChars,
                )
            }

            val result = Sandbox.runCommand(
                command = plan.testCommand,
                workDirectory = workingDirectory,
                environment = environment,
                timeout = properties.timeout,
                maxOutputChars = properties.sandbox.maxOutputChars,
            )
            val coverage = coverageParser.parse(plan.coverageArtifacts, workspace)

            return TestRunOutcome(
                runner = name,
                plan = plan,
                exitCode = result.exitCode,
                timedOut = result.timedOut,
                duration = Duration.between(startedAt, Instant.now()),
                output = result.output,
                coverage = coverage,
            )
        } catch (error: Exception) {
            return TestRunOutcome(
                runner = name,
                plan = plan,
                exitCode = null,
                timedOut = false,
                duration = Duration.between(startedAt, Instant.now()),
                output = "",
                error = Output.tail("${error.javaClass.simpleName}: ${error.message}", 1_500),
            )
        } finally {
            if (!properties.sandbox.keepWorkspace) {
                workspace?.let { Sandbox.deleteRecursively(it) }
            }
        }
    }

    private fun resolveWorkingDirectory(workspace: Path, plan: TestStackPlan): Path {
        val normalized = plan.workingDirectory.trim().trim('/')
        if (normalized.isEmpty() || normalized == ".") return workspace
        val directory = workspace.resolve(normalized).normalize()
        if (!directory.startsWith(workspace)) {
            throw SecurityException("Working directory escapes workspace: ${plan.workingDirectory}")
        }
        if (!Files.isDirectory(directory)) {
            throw IllegalStateException("Project directory not found in workspace: ${plan.workingDirectory}")
        }
        return directory
    }
}
