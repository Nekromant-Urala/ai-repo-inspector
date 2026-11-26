package com.airepo.analyzer.tests.exec

import com.airepo.analyzer.tests.SandboxProperties
import com.airepo.analyzer.tests.stack.TestStackPlan
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.time.Duration
import java.util.Comparator
import java.util.concurrent.TimeUnit

/** Ошибка лимитов копии проекта. */
class WorkspaceLimitException(message: String) : IllegalStateException(message)

/** POSIX-экранирование команд для скрипта внутри контейнера. */
internal object Shell {

    private val SAFE = Regex("[A-Za-z0-9_@%+=:,./-]+")

    fun quote(value: String): String {
        if (value.isEmpty()) return "''"
        if (SAFE.matches(value)) return value
        return "'" + value.replace("'", "'\\''") + "'"
    }

    fun join(command: List<String>): String = command.joinToString(" ") { quote(it) }
}

/** Работа с выводом процессов. */
internal object Output {

    private val ANSI = Regex("\u001B\\[[0-9;?]*[ -/]*[@-~]")

    fun stripAnsi(text: String): String = text.replace(ANSI, "")

    /** Оставляет только «хвост» вывода, чтобы не раздувать отчёт. */
    fun tail(text: String, maxChars: Int): String {
        if (maxChars <= 0 || text.length <= maxChars) return text
        return "…\n" + text.substring(text.length - maxChars)
    }
}

/** Результат запуска внешнего процесса. */
internal data class ProcessResult(
    val exitCode: Int?,
    val output: String,
    val timedOut: Boolean,
    val duration: Duration,
)

/**
 * Подготовка песочницы и запуск процессов.
 *
 * Копия проекта создаётся во временном каталоге: оригинальный репозиторий
 * никогда не изменяется и не монтируется в контейнер напрямую.
 */
internal object Sandbox {

    /** Каталоги, которые не переносим в контейнер: зависимости и артефакты сборки. */
    private val SKIPPED_DIRS = setOf(
        ".git", "node_modules", "build", "target", ".gradle", "dist", "out",
        ".idea", "coverage", ".venv", "venv", "__pycache__", ".tox",
        ".pytest_cache", ".mvn", "obj", ".next", ".nuxt",
    )

    private const val MODE_EXECUTABLE = 493 // 0755
    private const val MODE_REGULAR = 420 // 0644

    /**
     * Копирует проект в временный каталог и подкладывает файлы из [TestStackPlan.injectedFiles].
     *
     * @throws WorkspaceLimitException если проект больше лимитов из настроек
     */
    fun prepareWorkspace(projectRoot: Path, plan: TestStackPlan, sandbox: SandboxProperties): Path {
        val workspace = Files.createTempDirectory("airepo-test-workspace-")
        try {
            var files = 0
            var bytes = 0L
            Files.walk(projectRoot).use { paths ->
                paths.filter { Files.isRegularFile(it, LinkOption.NOFOLLOW_LINKS) }.forEach { file ->
                    val relative = projectRoot.relativize(file)
                    if (relative.any { it.toString() in SKIPPED_DIRS }) return@forEach

                    files++
                    bytes += runCatching { Files.size(file) }.getOrDefault(0L)
                    if (files > sandbox.maxWorkspaceFiles) {
                        throw WorkspaceLimitException(
                            "В проекте больше ${sandbox.maxWorkspaceFiles} файлов — запуск тестов пропущен.",
                        )
                    }
                    if (bytes > sandbox.maxWorkspaceBytes) {
                        throw WorkspaceLimitException(
                            "Копия проекта превышает ${sandbox.maxWorkspaceBytes / (1024 * 1024)} МБ — запуск тестов пропущен.",
                        )
                    }

                    val target = workspace.resolve(relative.toString())
                    Files.createDirectories(target.parent)
                    Files.copy(file, target, StandardCopyOption.REPLACE_EXISTING)
                }
            }
            writeInjectedFiles(workspace, plan)
            return workspace
        } catch (e: Exception) {
            deleteRecursively(workspace)
            throw e
        }
    }

    private fun writeInjectedFiles(workspace: Path, plan: TestStackPlan) {
        if (plan.injectedFiles.isEmpty()) return
        val normalizedWorkingDirectory = plan.workingDirectory.trim().trim('/')
        val base = if (normalizedWorkingDirectory.isEmpty() || normalizedWorkingDirectory == ".") {
            workspace
        } else {
            workspace.resolve(normalizedWorkingDirectory)
        }
        Files.createDirectories(base)
        plan.injectedFiles.forEach { (relativePath, content) ->
            val target = base.resolve(relativePath).normalize()
            if (!target.startsWith(workspace)) {
                throw SecurityException("Injected file escapes workspace: $relativePath")
            }
            Files.createDirectories(target.parent)
            Files.writeString(target, content)
        }
    }

    /** Упаковывает каталог в tar (потоково, без загрузки в память). */
    fun createTar(sourceDir: Path, tarFile: Path) {
        TarArchiveOutputStream(Files.newOutputStream(tarFile)).use { output ->
            output.setLongFileMode(TarArchiveOutputStream.LONGFILE_POSIX)
            output.setBigNumberMode(TarArchiveOutputStream.BIGNUMBER_POSIX)
            Files.walk(sourceDir).use { paths ->
                paths.filter { Files.isRegularFile(it, LinkOption.NOFOLLOW_LINKS) }
                    .sorted()
                    .forEach { file ->
                        val relative = sourceDir.relativize(file).toString().replace('\\', '/')
                        val entry = TarArchiveEntry(relative)
                        entry.size = Files.size(file)
                        entry.mode = if (Files.isExecutable(file)) MODE_EXECUTABLE else MODE_REGULAR
                        output.putArchiveEntry(entry)
                        Files.copy(file, output)
                        output.closeArchiveEntry()
                    }
            }
        }
    }

    /** Распаковывает tar с защитой от path traversal. */
    fun extractTar(tarFile: Path, targetDir: Path) {
        Files.createDirectories(targetDir)
        TarArchiveInputStream(Files.newInputStream(tarFile)).use { input ->
            var entry = input.nextEntry
            while (entry != null) {
                val target = targetDir.resolve(entry.name).normalize()
                if (!target.startsWith(targetDir)) {
                    throw SecurityException("Unsafe tar entry: ${entry.name}")
                }
                if (entry.isDirectory) {
                    Files.createDirectories(target)
                } else {
                    Files.createDirectories(target.parent)
                    Files.copy(input, target, StandardCopyOption.REPLACE_EXISTING)
                }
                entry = input.nextEntry
            }
        }
    }

    fun deleteRecursively(path: Path) {
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) return
        runCatching {
            Files.walk(path).use { paths ->
                paths.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
            }
        }
    }

    /**
     * Запускает процесс с ограничением по времени и объёму вывода.
     *
     * @param onTimeout вызывается до принудительного завершения (например, `docker kill`)
     */
    fun runCommand(
        command: List<String>,
        workDirectory: Path? = null,
        environment: Map<String, String> = emptyMap(),
        timeout: Duration,
        maxOutputChars: Int = 20_000,
        inputFile: Path? = null,
        outputFile: Path? = null,
        errorFile: Path? = null,
        onTimeout: () -> Unit = {},
    ): ProcessResult {
        require(command.isNotEmpty()) { "Command must not be empty" }

        val builder = ProcessBuilder(command)
        if (workDirectory != null) builder.directory(workDirectory.toFile())
        builder.environment().putAll(environment)
        if (inputFile != null) builder.redirectInput(inputFile.toFile())
        if (outputFile != null) {
            builder.redirectOutput(outputFile.toFile())
            if (errorFile != null) {
                builder.redirectError(errorFile.toFile())
            } else {
                builder.redirectError(ProcessBuilder.Redirect.DISCARD)
            }
        } else {
            builder.redirectErrorStream(true)
        }

        val startedAt = System.nanoTime()
        val process = try {
            builder.start()
        } catch (e: IOException) {
            return ProcessResult(
                exitCode = null,
                output = "Не удалось запустить ${command.first()}: ${e.message}",
                timedOut = false,
                duration = Duration.ZERO,
            )
        }

        val buffer = StringBuilder()
        val reader = if (outputFile == null) {
            Thread {
                runCatching {
                    process.inputStream.bufferedReader().use { stream ->
                        val chunk = CharArray(8192)
                        while (true) {
                            val read = stream.read(chunk)
                            if (read < 0) break
                            synchronized(buffer) {
                                buffer.append(chunk, 0, read)
                                // Держим в памяти только хвост вывода.
                                if (buffer.length > maxOutputChars * 2) {
                                    buffer.delete(0, buffer.length - maxOutputChars)
                                }
                            }
                        }
                    }
                }
            }.apply {
                isDaemon = true
                start()
            }
        } else {
            null
        }

        var timedOut = false
        val finished = try {
            process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            false
        }
        if (!finished) {
            timedOut = true
            runCatching { onTimeout() }
            process.destroyForcibly()
            runCatching { process.waitFor(10, TimeUnit.SECONDS) }
        }
        reader?.join(2_000)

        val exitCode = if (timedOut) null else runCatching { process.exitValue() }.getOrNull()
        val output = synchronized(buffer) { buffer.toString() }
        return ProcessResult(
            exitCode = exitCode,
            output = Output.tail(Output.stripAnsi(output), maxOutputChars),
            timedOut = timedOut,
            duration = Duration.ofNanos(System.nanoTime() - startedAt),
        )
    }
}
