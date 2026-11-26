package com.airepo.storage.entity

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.FetchType
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.JoinColumn
import jakarta.persistence.ManyToOne
import jakarta.persistence.Table

/**
 * Пользовательский критерий, добавленный к задаче проверки.
 *
 * Хранится отдельной записью, связанной с [ProjectCheckEntity], чтобы его можно
 * было восстановить по id проверки (например, при повторном открытии отчёта).
 */
@Entity
@Table(name = "custom_criterion")
class CustomCriterionEntity(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long = 0,

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "check_id", nullable = false)
    val projectCheck: ProjectCheckEntity,

    @Column(name = "name", nullable = false)
    val name: String,

    @Column(name = "scale", nullable = false)
    val scale: String,

    @Column(name = "description", nullable = false)
    val description: String,
)