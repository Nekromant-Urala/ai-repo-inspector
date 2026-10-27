package com.airepo.web

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication

/**
 * Точка входа в сервис AI-Repo Inspector.
 *
 * `@SpringBootApplication` в пакете `com.airepo.web`; компонентное сканирование
 * распространяется на все подпакеты `com.airepo.*`, поэтому бины из модулей
 * storage / report / analyzers подхватываются автоматически.
 */
@SpringBootApplication
class AiRepoApplication

fun main(args: Array<String>) {
    runApplication<AiRepoApplication>(*args)
}