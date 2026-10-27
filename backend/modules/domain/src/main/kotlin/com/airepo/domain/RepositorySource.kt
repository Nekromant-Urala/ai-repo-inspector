package com.airepo.domain

/**
 * Описание источника, который нужно проаудитить.
 *
 * @param type      откуда берём репозиторий (ссылка или архив)
 * @param value     URL репозитория либо путь/имя загруженного архива
 */
data class RepositorySource(
    val type: SourceType,
    val value: String,
)