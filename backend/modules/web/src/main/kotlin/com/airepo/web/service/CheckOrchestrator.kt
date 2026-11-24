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
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
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

    /** Создаёт задачу в статусе PENDING. */
    fun create(req: CreateCheckRequest): CheckResponse {
        val source = RepositorySource(type = req.type, value = req.value)
        val uri = runCatching { URI(req.value) }.getOrNull()
        if (req.type != SourceType.GIT_URL || uri == null || uri.scheme != "https" ||
            uri.host?.lowercase()?.removePrefix("www.") !in setOf("github.com", "gitlab.com") ||
            uri.rawUserInfo != null || uri.port != -1 || uri.query != null || uri.fragment != null ||
            !Regex("/[A-Za-z0-9_.-]+(/[A-Za-z0-9_.-]+)+/?").matches(uri.rawPath) ||
            uri.rawPath.split('/').any { it == "." || it == ".." })
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Недопустимая ссылка")
        return checkService.create(source, criteriaJson(req.customCriteria)).toResponse()
    }

    private fun criteriaJson(criteria: List<CustomCriterion>): String {
        if (criteria.size > 20 || criteria.any {
            it.name.isBlank() || it.name.length > 120 || it.scale.isBlank() || it.scale.length > 160 ||
                it.description.isBlank() || it.description.length > 2000
        }) throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Проверьте дополнительные критерии")
        return json.writeValueAsString(criteria)
    }

    fun archive(file: MultipartFile, criteria: List<CustomCriterion>): CheckResponse {
        val name = file.originalFilename.orEmpty().substringAfterLast('/').substringAfterLast('\\').takeLast(250)
        if (file.size > 50L * 1024 * 1024) throw ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE)
        val bytes = file.bytes
        if (!name.endsWith(".zip", true) || !bytes.take(4).toByteArray().contentEquals(byteArrayOf(80, 75, 3, 4)))
            throw ResponseStatusException(HttpStatus.UNSUPPORTED_MEDIA_TYPE)
        return checkService.create(RepositorySource(SourceType.ARCHIVE, name), criteriaJson(criteria), bytes).toResponse()
    }

    fun report(id: Long): JsonNode {
        val check = checkService.findById(id) ?: throw ResponseStatusException(HttpStatus.NOT_FOUND)
        return json.readTree(check.reportJson ?: throw ResponseStatusException(HttpStatus.CONFLICT))
    }

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

    /** Возвращает текущий статус задачи. */
    fun status(id: Long): CheckResponse? =
        checkService.findById(id)?.toResponse()

    /**
     * Запускает анализ и возвращает готовый отчёт.
     *
     * TODO: вынести в фоновую задачу (например, @Async / корутины), чтобы фронт
     * мог показывать страницу ожидания, опрашивая GET /api/checks/{id} до DONE.
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
}

private fun ProjectCheckEntity.toResponse() = CheckResponse(
    id = id,
    status = status,
    sourceType = sourceType,
    sourceValue = sourceValue,
)
