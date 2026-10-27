package com.airepo.analyzer

import com.airepo.domain.RepositorySource
import java.nio.file.Path

/**
 * Контекст анализа: локальная директория проекта и источник, из которого он получен.
 * Передаётся каждому критерию.
 */
data class AnalysisContext(
    /** Корень склонированного/распакованного репозитория. */
    val rootDir: Path,

    /** Откуда проект взялся (URL или архив). */
    val source: RepositorySource,
)