package com.airepo.storage.service

import com.airepo.domain.CheckStatus
import com.airepo.domain.RepositorySource
import com.airepo.storage.entity.ProjectCheckEntity
import com.airepo.storage.repository.ProjectCheckRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/**
 * Сервис для работы с задачами проверки.
 * Создаёт задачу, обновляет статус, отдаёт данные по id.
 */
@Service
class ProjectCheckService(
    private val repository: ProjectCheckRepository,
) {

    /**
     * Создаёт новую задачу в статусе PENDING.
     */
    @Transactional
    fun create(source: RepositorySource, criteriaJson: String? = null, archive: ByteArray? = null): ProjectCheckEntity {
        val entity = ProjectCheckEntity(
            status = CheckStatus.PENDING,
            sourceType = source.type, criteriaJson = criteriaJson, archive = archive,
            sourceValue = source.value,
        )
        return repository.save(entity)
    }

    /**
     * Переводит задачу в указанный статус. Используется по ходу анализа.
     */
    @Transactional
    fun updateStatus(id: Long, status: CheckStatus) {
        val entity = requireNotNull(repository.findById(id).orElse(null)) { "Check $id not found" }
        if (status == CheckStatus.FAILED) entity.archive = null
        entity.status = status
        repository.save(entity)
    }

    @Transactional
    fun start(id: Long) = repository.transition(id, CheckStatus.PENDING, CheckStatus.RUNNING) == 1

    @Transactional
    fun finish(id: Long, report: String) {
        val entity = repository.findById(id).orElseThrow()
        entity.reportJson = report
        entity.archive = null
        entity.status = CheckStatus.DONE
    }

    @Transactional(readOnly = true)
    fun findById(id: Long): ProjectCheckEntity? =
        repository.findById(id).orElse(null)

    @Transactional(readOnly = true)
    fun findByStatus(status: CheckStatus): List<ProjectCheckEntity> =
        repository.findAll().filter { it.status == status }
}