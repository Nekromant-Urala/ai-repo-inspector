package com.airepo.storage.repository

import com.airepo.storage.entity.ProjectCheckEntity
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Repository

/**
 * Репозиторий для задач проверки репозиториев.
 */
@Repository
interface ProjectCheckRepository : JpaRepository<ProjectCheckEntity, Long>