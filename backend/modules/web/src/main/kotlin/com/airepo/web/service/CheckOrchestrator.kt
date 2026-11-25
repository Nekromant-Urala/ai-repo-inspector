package com.airepo.web.service

import com.airepo.domain.AnalysisReport
import com.airepo.domain.CheckStatus
import com.airepo.domain.CustomResult
import com.airepo.domain.RepositorySource
import com.airepo.domain.SourceType
import com.airepo.ingestion.ProjectLoader
import com.airepo.report.ReportService
import com.airepo.storage.entity.ProjectCheckEntity
import com.airepo.storage.service.ProjectCheckService
import com.airepo.web.dto.CheckResponse
import com.airepo.web.dto.CreateCheckRequest
import com.airepo.web.dto.CustomCriterion
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipInputStream
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.web.multipart.MultipartFile
import org.springframework.web.server.ResponseStatusException

/**
 * Оркестрирует полный цикл проверки:
 * создание задачи -> загрузка проекта -> прогон критериев -> отчёт.
 */
@Service
class CheckOrchestrator(
    private val checkService: ProjectCheckService,
    private val json: ObjectMapper,
    private val loader: ProjectLoader,
    private val reportService: ReportService,
) {

    /** Создаёт одну задачу в статусе PENDING. */
    fun create(req: CreateCheckRequest): CheckResponse {
        if (!isValidUrl(req.value)) throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Недопустимая ссылка")
        val source = RepositorySource(type = req.type, value = req.value)
        return checkService.create(source, criteriaJson(req.customCriteria)).toResponse()
    }

    /** Проверка валидности URL (вынесена отдельно, чтобы использовать и в списке). */
    private fun isValidUrl(url: String): Boolean {
        val uri = runCatching { URI(url) }.getOrNull() ?: return false
        return uri.scheme == "https" &&
                uri.host?.lowercase()?.removePrefix("www.") in setOf("github.com", "gitlab.com") &&
                uri.rawUserInfo == null && uri.port == -1 && uri.query == null && uri.fragment == null &&
                uri.rawPath?.let { Regex("/[A-Za-z0-9_.-]+(/[A-Za-z0-9_.-]+)+/?").matches(it) } == true &&
                uri.rawPath.split('/').none { it == "." || it == ".." }
    }

    private fun criteriaJson(criteria: List<CustomCriterion>): String {
        if (criteria.size > 20 || criteria.any {
            it.name.isBlank() || it.name.length > 120 || it.scale.isBlank() || it.scale.length > 160 ||
                it.description.isBlank() || it.description.length > 2000
        }) throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Проверьте дополнительные критерии")
        return json.writeValueAsString(criteria)
    }

    /**
     * Принимает файл (ZIP с txt внутри или просто TXT), извлекает список ссылок
     * и создаёт пачку задач проверки. Возвращает список созданных задач.
     */
    fun archive(file: MultipartFile, criteria: List<CustomCriterion>): List<CheckResponse> {
        val name = file.originalFilename.orEmpty().lowercase()
        
        if (file.size > 50L * 1024 * 1024) throw ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE)
        val bytes = file.bytes

        // 1. Извлекаем ссылки в зависимости от типа файла
        val urls = when {
            name.endsWith(".zip") -> {
                // Проверяем магические байты ZIP
                if (!bytes.take(4).toByteArray().contentEquals(byteArrayOf(80, 75, 3, 4))) {
                    throw ResponseStatusException(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "Файл поврежден или не является ZIP")
                }
                extractUrlsFromZip(bytes)
            }
            name.endsWith(".txt") -> parseTxt(bytes)
            else -> throw ResponseStatusException(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "Поддерживаются только ZIP или TXT")
        }

        // 2. Валидация списка
        if (urls.isEmpty()) throw ResponseStatusException(HttpStatus.BAD_REQUEST, "В файле не найдено ссылок")
        if (urls.size > 20) throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Максимум 20 репозиториев в списке")

        // 3. Создаём задачи
        val criteriaJson = criteriaJson(criteria)
        return urls.map { url ->
            if (!isValidUrl(url)) {
                throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Недопустимая ссылка в списке: $url")
            }
            val source = RepositorySource(type = SourceType.GIT_URL, value = url)
            checkService.create(source, criteriaJson, null).toResponse()
        }
    }

    private fun parseTxt(bytes: ByteArray): List<String> {
        return bytes.toString(Charsets.UTF_8)
            .lines()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") } // игнорируем пустые строки и комментарии
            .distinct() // убираем дубликаты
    }

    private fun extractUrlsFromZip(bytes: ByteArray): List<String> {
        ZipInputStream(bytes.inputStream()).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                if (!entry.isDirectory && entry.name.lowercase().endsWith(".txt")) {
                    // Читаем первый найденный txt и возвращаем результат
                    return parseTxt(zip.readAllBytes())
                }
                entry = zip.nextEntry
            }
        }
        return emptyList()
    }

    fun report(id: Long): JsonNode {
        val check = checkService.findById(id) ?: throw ResponseStatusException(HttpStatus.NOT_FOUND)
        return json.readTree(check.reportJson ?: throw ResponseStatusException(HttpStatus.CONFLICT))
    }

    /** Возвращает текущий статус задачи. */
    fun status(id: Long): CheckResponse? =
        checkService.findById(id)?.toResponse()

    /**
     * Запускает анализ синхронно и возвращает готовый отчёт.
     * (Этот метод остаётся для обратной совместимости одиночных проверок).
     */
    fun run(id: Long): AnalysisReport {
        val entity = checkService.findById(id)
            ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "Check $id not found")

        if (!checkService.start(id)) throw ResponseStatusException(HttpStatus.CONFLICT, "Проверка уже запускалась")
        try {
            val source = RepositorySource(type = entity.sourceType, value = entity.sourceValue)
            val loaded = loader.load(source, entity.archive)
            val report = try {
                val base = reportService.analyze(loaded, checkId = id)
                val custom = json.readTree(entity.criteriaJson ?: "[]").map { rule ->
                    val description = rule["description"].asText()
                    val score = fileScore(loaded.rootDir, description)
                    CustomResult(rule["name"].asText(), rule["scale"].asText(), description, score)
                }
                base.copy(customCriteria = custom)
            } finally { loaded.close() }
            checkService.finish(id, json.writeValueAsString(report))
            return report
        } catch (e: Exception) {
            checkService.updateStatus(id, CheckStatus.FAILED)
            throw e
        }
    }

    /** Вспомогательный метод для кастомных критериев (нужен и в run, и в фоне). */
    private fun fileScore(root: Path, description: String): Int? {
        if (!description.startsWith("Файл:")) return null
        val name = description.removePrefix("Файл:").trim()
        if (name.isEmpty()) return null
        val relative = runCatching { Path.of(name) }.getOrNull() ?: return null
        if (relative.isAbsolute || relative.any { it.toString() == ".." }) return null
        val path = root.resolve(relative)
        if (!Files.isRegularFile(path)) return 0
        return if (path.toRealPath().startsWith(root.toRealPath())) 100 else 0
    }
}

private fun ProjectCheckEntity.toResponse() = CheckResponse(
    id = id,
    status = status,
    sourceType = sourceType,
    sourceValue = sourceValue,
)
