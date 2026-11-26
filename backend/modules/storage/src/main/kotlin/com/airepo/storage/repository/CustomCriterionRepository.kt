package com.airepo.storage.repository

import com.airepo.storage.entity.CustomCriterionEntity
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Repository

/**
 * Репозиторий кастомных критериев проверки.
 */
@Repository
interface CustomCriterionRepository : JpaRepository<CustomCriterionEntity, Long> {

    fun findByProjectCheckId(checkId: Long): List<CustomCriterionEntity>
}