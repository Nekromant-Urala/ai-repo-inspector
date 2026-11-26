package com.airepo.storage.service

import com.airepo.domain.CheckStatus
import com.airepo.domain.CustomCriterion
import com.airepo.domain.RepositorySource
import com.airepo.storage.entity.CustomCriterionEntity
import com.airepo.storage.entity.ProjectCheckEntity
import com.airepo.storage.repository.CustomCriterionRepository
import com.airepo.storage.repository.ProjectCheckRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/**
 * Сервис для работы с задачами проверки.
 * Создаёт задачу (вместе с кастомными критериями), обновляет статус, отдаёт данные по id.
 */
@Service
class ProjectCheckService(
    private val repository: ProjectCheckRepository,
    private val customCriterionRepository: CustomCriterionRepository,
) {

    /**
     * Создаёт новую задачу в статусе PENDING вместе с переданными кастомными критериями.
     */
    @Transactional
    fun create(source: RepositorySource, customCriteria: List<CustomCriterion> = emptyList()): ProjectCheckEntity {
        val entity = ProjectCheckEntity(
            status = CheckStatus.PENDING,
            sourceType = source.type,
            sourceValue = source.value,
        )
        repository.save(entity)
        customCriteria.forEach { criterion ->
            customCriterionRepository.save(
                CustomCriterionEntity(
                    projectCheck = entity,
                    name = criterion.name,
                    scale = criterion.scale,
                    description = criterion.description,
                )
            )
        }
        return entity
    }

    /**
     * Возвращает кастомные критерии, добавленные к задаче проверки.
     */
    @Transactional(readOnly = true)
    fun findCustomCriteria(checkId: Long): List<CustomCriterion> =
        customCriterionRepository.findByProjectCheckId(checkId).map { it.toDomain() }

    /**
     * Переводит задачу в указанный статус. Используется по ходу анализа.
     */
    @Transactional
    fun updateStatus(id: Long, status: CheckStatus) {
        val entity = requireNotNull(repository.findById(id).orElse(null)) { "Check $id not found" }
        entity.status = status
        repository.save(entity)
    }

    @Transactional(readOnly = true)
    fun findById(id: Long): ProjectCheckEntity? =
        repository.findById(id).orElse(null)

    @Transactional(readOnly = true)
    fun findByStatus(status: CheckStatus): List<ProjectCheckEntity> =
        repository.findAll().filter { it.status == status }
}

private fun CustomCriterionEntity.toDomain() = CustomCriterion(
    name = name,
    scale = scale,
    description = description,
)