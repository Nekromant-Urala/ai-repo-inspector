package com.airepo.storage.entity

import com.airepo.domain.CheckStatus
import com.airepo.domain.SourceType
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant

/**
 * Запись о задаче проверки репозитория (одна таблица по ТЗ Димы).
 *
 * Поля: id, status, source_type, source_value, created_at.
 */
@Entity
@Table(name = "project_check")
class ProjectCheckEntity(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long = 0,

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    var status: CheckStatus,

    @Enumerated(EnumType.STRING)
    @Column(name = "source_type", nullable = false)
    val sourceType: SourceType,

    @Column(name = "source_value", nullable = false)
    val sourceValue: String,

    @Column(name = "created_at", nullable = false, updatable = false)
    val createdAt: Instant = Instant.now(),
)