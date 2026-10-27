package com.airepo.web.service

import com.airepo.domain.AnalysisReport
import com.airepo.domain.CheckStatus
import com.airepo.domain.RepositorySource
import com.airepo.ingestion.ProjectLoader
import com.airepo.report.ReportService
import com.airepo.storage.entity.ProjectCheckEntity
import com.airepo.storage.service.ProjectCheckService
import com.airepo.web.dto.CheckResponse
import com.airepo.web.dto.CreateCheckRequest
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.web.server.ResponseStatusException

/**
 * Оркестрирует полный цикл проверки:
 * создание задачи -> загрузка проекта -> прогон критериев -> отчёт.
 */
@Service
class CheckOrchestrator(
    private val checkService: ProjectCheckService,
    private val loader: ProjectLoader,
    private val reportService: ReportService,
) {

    /** Создаёт задачу в статусе PENDING. */
    fun create(req: CreateCheckRequest): CheckResponse {
        val source = RepositorySource(type = req.type, value = req.value)
        return checkService.create(source).toResponse()
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

        checkService.updateStatus(id, CheckStatus.RUNNING)
        try {
            val source = RepositorySource(type = entity.sourceType, value = entity.sourceValue)
            val loaded = loader.load(source)
            val report = reportService.analyze(loaded, checkId = id)
            checkService.updateStatus(id, CheckStatus.DONE)
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