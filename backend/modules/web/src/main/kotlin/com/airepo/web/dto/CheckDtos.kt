package com.airepo.web.dto

import com.airepo.domain.CheckStatus
import com.airepo.domain.SourceType

/**
 * Тело запроса на создание задачи проверки.
 *
 * @param type  способ загрузки репозитория: GIT_URL или ARCHIVE
 * @param value URL репозитория либо имя/путь загруженного архива
 */
data class CreateCheckRequest(
    val type: SourceType,
    val value: String,
)

/**
 * Краткая информация о задаче (для страницы ожидания на фронте).
 */
data class CheckResponse(
    val id: Long,
    val status: CheckStatus,
    val sourceType: SourceType,
    val sourceValue: String,
)