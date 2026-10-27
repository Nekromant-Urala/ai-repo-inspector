package com.airepo.web

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.autoconfigure.domain.EntityScan
import org.springframework.boot.runApplication
import org.springframework.context.annotation.ComponentScan
import org.springframework.data.jpa.repository.config.EnableJpaRepositories

/**
 * Точка входа в сервис AI-Repo Inspector.
 *
 * `@SpringBootApplication` в пакете `com.airepo.web`; компонентное сканирование
 * распространяется на все подпакеты `com.airepo.*`, поэтому бины из модулей
 * storage / report / analyzers подхватываются автоматически.
 */
@SpringBootApplication
@ComponentScan(basePackages = ["com.airepo"])
@EnableJpaRepositories(basePackages = ["com.airepo"])
@EntityScan(basePackages = ["com.airepo"])
class AiRepoApplication

fun main(args: Array<String>) {
    runApplication<AiRepoApplication>(*args)
}