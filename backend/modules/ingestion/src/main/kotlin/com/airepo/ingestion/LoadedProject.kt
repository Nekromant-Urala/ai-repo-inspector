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
)