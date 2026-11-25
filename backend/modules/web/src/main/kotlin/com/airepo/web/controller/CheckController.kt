package com.airepo.web.controller

import com.airepo.domain.AnalysisReport
import com.airepo.web.dto.CheckResponse
import com.airepo.web.dto.CreateCheckRequest
import com.airepo.web.dto.CustomCriterion
import com.airepo.web.service.CheckBackgroundRunner
import com.airepo.web.service.CheckOrchestrator
import com.fasterxml.jackson.databind.JsonNode
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestPart
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.multipart.MultipartFile
import org.springframework.web.server.ResponseStatusException

/**
 * REST API сервиса.
 *
 * - `POST /api/checks`          — создать одну задачу проверки (вернёт id и статус PENDING)
 * - `POST /api/checks/archive`  — загрузить ZIP/TXT со списком ссылок и создать пачку задач
 * - `POST /api/checks/{id}/run` — запустить анализ и получить отчёт (синхронно)
 * - `GET  /api/checks/{id}`     — узнать текущий статус (для страницы ожидания)
 * - `GET  /api/checks/{id}/report` — получить готовый отчёт
 */
@RestController
@RequestMapping("/api/checks")
class CheckController(
    private val orchestrator: CheckOrchestrator,
    private val backgroundRunner: CheckBackgroundRunner, // Добавили фоновый сервис
) {

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    fun create(@RequestBody request: CreateCheckRequest): CheckResponse =
        orchestrator.create(request)

    @PostMapping("/archive", consumes = ["multipart/form-data"])
    @ResponseStatus(HttpStatus.CREATED)
    fun archive(
        @RequestPart file: MultipartFile,
        @RequestPart(required = false) customCriteria: List<CustomCriterion>?,
    ): List<CheckResponse> {
        // 1. Оркестратор парсит файл и создаёт пачку задач в БД (статус PENDING)
        val results = orchestrator.archive(file, customCriteria.orEmpty())
        
        // 2. Запускаем каждую задачу в фоне через @Async
        results.forEach { backgroundRunner.runAsync(it.id) }
        
        // 3. Мгновенно возвращаем фронту список созданных задач
        return results
    }

    @GetMapping("/{id}/report")
    fun report(@PathVariable id: Long): JsonNode = orchestrator.report(id)

    @PostMapping("/{id}/run")
    fun run(@PathVariable id: Long): AnalysisReport =
        orchestrator.run(id)

    @GetMapping("/{id}")
    fun status(@PathVariable id: Long): CheckResponse =
        orchestrator.status(id) ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "Check $id not found")
}
