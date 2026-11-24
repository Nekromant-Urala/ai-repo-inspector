package com.airepo.ingestion

import com.airepo.domain.RepositorySource
import com.airepo.domain.SourceType
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream

/**
 * Загружает репозиторий на диск: либо клонирует Git-репозиторий по ссылке,
 * либо распаковывает переданный архив проекта.
 *
 * Результат — локальная директория, по которой дальше прогоняются критерии.
 */
@Component
class ProjectLoader(
    /** Фабрика временной рабочей директории. В проде — из конфига, в тестах — в `build/`. */
    private val workDirProvider: () -> Path = { Files.createTempDirectory("airepo-") },
) {
    private val log = LoggerFactory.getLogger(ProjectLoader::class.java)

    /**
     * Основная точка входа: по источнику решает, клонировать или распаковывать.
     */
    fun load(source: RepositorySource, archive: ByteArray? = null): LoadedProject {
        val target = workDirProvider()
        return try {
            when (source.type) {
                SourceType.GIT_URL -> cloneGit(source, target)
                SourceType.ARCHIVE -> extractArchive(source, target, requireNotNull(archive))
            }
        } catch (e: Exception) {
            removeWorkspace(target)
            throw e
        }
    }

    /**
     * Клонирует репозиторий по [source.value] (URL GitHub/GitLab).
     * Используем `--depth 1` — для аудита не нужна полная история.
     */
    private fun cloneGit(source: RepositorySource, target: Path): LoadedProject {
        val dest = target.resolve("repository")
        val process = ProcessBuilder("git", "clone", "--depth", "1", source.value, dest.absolutePathString())
            .redirectErrorStream(true)
            .start()

        val output = process.inputStream.bufferedReader().readText()
        val exit = process.waitFor()
        if (exit != 0) {
            throw IllegalStateException("git clone failed ($exit): $output")
        }
        log.info("Cloned {} into {}", source.value, dest)
        return LoadedProject(rootDir = dest, source = source, workspace = target)
    }

    /**
     * Распаковывает ZIP-архив [source.value] в [target].
     *
     * TODO: добавить поддержку tar / tar.gz / rar (например, через commons-compress),
     * сейчас — только ZIP на встроенных средствах Java.
     */
    private fun extractArchive(source: RepositorySource, target: Path, bytes: ByteArray): LoadedProject {
        var count = 0
        var total = 0L
        val buffer = ByteArray(8192)

        ZipInputStream(bytes.inputStream()).use { zip ->
            var entry: ZipEntry? = zip.nextEntry
            while (entry != null) {
                require(++count <= 10000) { "Слишком много файлов" }
                val out = target.resolve(entry.name).normalize()
                // защита от path traversal: файл не должен вылезать за пределы target
                if (!out.startsWith(target)) throw SecurityException("Unsafe zip entry: ${entry.name}")

                if (entry.isDirectory) {
                    Files.createDirectories(out)
                } else {
                    Files.createDirectories(out.parent)
                    Files.newOutputStream(out, java.nio.file.StandardOpenOption.CREATE_NEW).use { stream ->
                        while (true) {
                            val n = zip.read(buffer)
                            if (n < 0) break
                            total += n
                            require(total <= 256L * 1024 * 1024) { "Архив слишком большой после распаковки" }
                            stream.write(buffer, 0, n)
                        }
                    }
                }
                zip.closeEntry()
                entry = zip.nextEntry
            }
        }
        require(count > 0) { "Пустой или повреждённый ZIP" }
        val folder = target.toFile().listFiles()?.singleOrNull()?.takeIf { it.isDirectory }
        return LoadedProject(rootDir = folder?.toPath() ?: target, source = source, workspace = target)
    }
}

/** Небольшой хелпер, чтобы не тащить java.nio API в каждый вызов. */
private fun Path.absolutePathString(): String = toAbsolutePath().toString()