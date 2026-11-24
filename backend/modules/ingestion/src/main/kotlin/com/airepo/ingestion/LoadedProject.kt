package com.airepo.ingestion

import com.airepo.domain.RepositorySource
import java.nio.file.Path

/**
 * Результат загрузки репозитория: корневая директория, готов к анализу.
 */
data class LoadedProject(
    /** Корень распакованного/склонированного проекта. */
    val rootDir: Path,
    /** Исходный источник, по которому загружали. */
    val source: RepositorySource,
    val workspace: Path = rootDir,
) : AutoCloseable {
    override fun close() = removeWorkspace(workspace)
}

internal fun removeWorkspace(root: Path) {
    java.nio.file.Files.walk(root).use { paths ->
        paths.sorted(Comparator.reverseOrder()).forEach(java.nio.file.Files::delete)
    }
}