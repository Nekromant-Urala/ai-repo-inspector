package com.airepo.web.service

import com.airepo.domain.CheckStatus
import com.airepo.domain.CustomResult
import com.airepo.domain.RepositorySource
import com.airepo.ingestion.ProjectLoader
import com.airepo.report.ReportService
import com.airepo.storage.service.ProjectCheckService
import com.fasterxml.jackson.databind.ObjectMapper
import java.nio.file.Files
import java.nio.file.Path
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Async
import org.springframework.stereotype.Service

/**
 * Сервис для фонового запуска проверок.
 * Позволяет не держать HTTP-соединение открытым, пока клонируются и анализируются репозитории.
 */
@Service
class CheckBackgroundRunner(
    private val checkService: ProjectCheckService,
    private val loader: ProjectLoader,
    private val reportService: ReportService,
    private val json: ObjectMapper,
) {
    private val log = LoggerFactory.getLogger(CheckBackgroundRunner::class.java)

    @Async
    fun runAsync(id: Long) {
        val entity = checkService.findById(id) ?: return
        if (!checkService.start(id)) return // если уже запущен или завершен — выходим

        try {
            val source = RepositorySource(type = entity.sourceType, value = entity.sourceValue)
            val loaded = loader.load(source, entity.archive)
            try {
                val base = reportService.analyze(loaded, checkId = id)
                val custom = json.readTree(entity.criteriaJson ?: "[]").map { rule ->
                    val description = rule["description"].asText()
                    val score = fileScore(loaded.rootDir, description)
                    CustomResult(rule["name"].asText(), rule["scale"].asText(), description, score)
                }
                val report = base.copy(customCriteria = custom)
                checkService.finish(id, json.writeValueAsString(report))
            } finally {
                loaded.close()
            }
        } catch (e: Exception) {
            log.error("Ошибка при фоновом анализе проверки $id", e)
            checkService.updateStatus(id, CheckStatus.FAILED)
        }
    }

    /**
     * Проверяет наличие файла, указанного в кастомном критерии (описание начинается с "Файл:").
     * Возвращает 0 или 100.
     */
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