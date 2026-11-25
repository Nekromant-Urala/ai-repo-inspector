package com.airepo.web

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.task.TaskExecutor
import org.springframework.scheduling.annotation.EnableAsync
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor

@Configuration
@EnableAsync
class AsyncConfig {

    @Bean
    fun taskExecutor(): TaskExecutor {
        val executor = ThreadPoolTaskExecutor()
        // Максимум 4 задачи одновременно (чтобы не перегружать сеть и диск)
        executor.corePoolSize = 4
        executor.maxPoolSize = 4
        // Очередь на 50 задач (если ссылок больше 4, они будут ждать)
        executor.queueCapacity = 50
        executor.setThreadNamePrefix("check-runner-")
        executor.initialize()
        return executor
    }
}