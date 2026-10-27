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
    fun load(source: RepositorySource): LoadedProject {
        val target = workDirProvider()
        return when (source.type) {
            SourceType.GIT_URL -> cloneGit(source, target)
            SourceType.ARCHIVE -> extractArchive(source, target)
        }
    }

    /**
     * Клонирует репозиторий по [source.value] (URL GitHub/GitLab).
     * Используем `--depth 1` — для аудита не нужна полная история.
     */
    private fun cloneGit(source: RepositorySource, target: Path): LoadedProject {
        val repoName = source.value.substringAfterLast('/').removeSuffix(".git").ifBlank { "repo" }
        val dest = target.resolve(repoName)
        Files.createDirectories(dest)

        val process = ProcessBuilder("git", "clone", "--depth", "1", source.value, dest.absolutePathString())
            .redirectErrorStream(true)
            .start()

        val output = process.inputStream.bufferedReader().readText()
        val exit = process.waitFor()
        if (exit != 0) {
            throw IllegalStateException("git clone failed ($exit): $output")
        }
        log.info("Cloned {} into {}", source.value, dest)
        return LoadedProject(rootDir = dest, source = source)
    }

    /**
     * Распаковывает ZIP-архив [source.value] в [target].
     *
     * TODO: добавить поддержку tar / tar.gz / rar (например, через commons-compress),
     * сейчас — только ZIP на встроенных средствах Java.
     */
    private fun extractArchive(source: RepositorySource, target: Path): LoadedProject {
        val archive = Path.of(source.value)
        if (!Files.exists(archive)) throw IllegalArgumentException("Archive not found: $archive")

        ZipInputStream(Files.newInputStream(archive)).use { zip ->
            var entry: ZipEntry? = zip.nextEntry
            while (entry != null) {
                val out = target.resolve(entry.name).normalize()
                // защита от path traversal: файл не должен вылезать за пределы target
                if (!out.startsWith(target)) throw SecurityException("Unsafe zip entry: ${entry.name}")

                if (entry.isDirectory) {
                    Files.createDirectories(out)
                } else {
                    Files.createDirectories(out.parent)
                    Files.newOutputStream(out).use { it.write(zip.readBytes()) }
                }
                zip.closeEntry()
                entry = zip.nextEntry
            }
        }
        log.info("Extracted {} into {}", archive, target)
        return LoadedProject(rootDir = target, source = source)
    }
}

/** Небольшой хелпер, чтобы не тащить java.nio API в каждый вызов. */
private fun Path.absolutePathString(): String = toAbsolutePath().toString()