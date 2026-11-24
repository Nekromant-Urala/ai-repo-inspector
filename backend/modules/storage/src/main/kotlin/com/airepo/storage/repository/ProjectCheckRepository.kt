package com.airepo.storage.repository

import com.airepo.domain.CheckStatus
import com.airepo.storage.entity.ProjectCheckEntity
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.stereotype.Repository

/**
 * Репозиторий для задач проверки репозиториев.
 */
@Repository
interface ProjectCheckRepository : JpaRepository<ProjectCheckEntity, Long> {
    @Modifying
    @Query("update ProjectCheckEntity c set c.status = :next where c.id = :id and c.status = :expected")
    fun transition(id: Long, expected: CheckStatus, next: CheckStatus): Int
}
