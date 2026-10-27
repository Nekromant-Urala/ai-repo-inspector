package com.airepo.common

import java.nio.file.Path
import java.util.Locale

/**
 * Общие утилиты, переиспользуемые во всех модулях бэкенда.
 * Здесь держим то, что не привязано к конкретному критерию аудита.
 */
object FileUtils {

    /**
     * Рекурсивно собирает все файлы в директории [root].
     * Пропускает скрытые файлы (например, `.git`), чтобы не мусорить в отчёте.
     */
    fun listFilesRecursively(root: Path): List<Path> {
        if (!java.nio.file.Files.exists(root)) return emptyList()
        return java.nio.file.Files.walk(root)
            .use { paths ->
                paths
                    .filter { java.nio.file.Files.isRegularFile(it) }
                    .filter { !it.fileName.toString().startsWith(".") }
                    .toList()
            }
    }

    /**
     * Возвращает расширение файла в нижнем регистре без точки,
     * например `build.gradle.kts` -> `kts`, `main.kt` -> `kt`.
     */
    fun extensionOf(path: Path): String =
        path.fileName.toString().substringAfterLast('.', "").lowercase(Locale.ROOT)

    /**
     * Читает содержимое текстового файла, ограничивая размер,
     * чтобы случайно не загрузить в память гигантский бинарник.
     */
    fun readTextSafely(path: Path, maxBytes: Int = 1_048_576): String? =
        runCatching {
            val size = java.nio.file.Files.size(path)
            if (size > maxBytes) return null
            java.nio.file.Files.readString(path)
        }.getOrNull()
}