package com.airepo.domain

/**
 * Способ, которым репозиторий попал в систему.
 */
enum class SourceType {
    /** Ссылка на Git-репозиторий (GitHub/GitLab) — нужно сделать клон. */
    GIT_URL,

    /** Загруженный архив проекта (zip, rar, tar, ...). */
    ARCHIVE,
}