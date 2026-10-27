package com.airepo.web.controller

import com.airepo.domain.AnalysisReport
import com.airepo.web.dto.CheckResponse
import com.airepo.web.dto.CreateCheckRequest
import com.airepo.web.service.CheckOrchestrator
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException

/**
 * REST API сервиса.
 *
 * - `POST /api/checks`        — создать задачу проверки (вернёт id и статус PENDING)
 * - `POST /api/checks/{id}/run` — запустить анализ и получить отчёт
 * - `GET  /api/checks/{id}`   — узнать текущий статус (для страницы ожидания)
 */
@RestController
@RequestMapping("/api/checks")
class CheckController(
    private val orchestrator: CheckOrchestrator,
) {

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    fun create(@RequestBody request: CreateCheckRequest): CheckResponse =
        orchestrator.create(request)

    @PostMapping("/{id}/run")
    fun run(@PathVariable id: Long): AnalysisReport =
        orchestrator.run(id)

    @GetMapping("/{id}")
    fun status(@PathVariable id: Long): CheckResponse =
        orchestrator.status(id) ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "Check $id not found")
}