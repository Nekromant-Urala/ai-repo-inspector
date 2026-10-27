package com.airepo.testsupport

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import javax.sql.DataSource

/**
 * Базовый класс для интеграционных тестов, которым нужна настоящая БД.
 *
 * Поднимает PostgreSQL в Docker-контейнере (Testcontainers) и даёт наследникам
 * готовые параметры подключения. От этого класса следует наследоваться во всех
 * модулях, где тест должен ходить в БД, чтобы не дублировать настройку
 * контейнеров.
 *
 * Пример использования:
 * ```
 * class MyRepositoryTest : AbstractPostgresContainerTest() {
 *     @Test
 *     fun `соединение работает`() {
 *         dataSource.connection.use { conn ->
 *             assertTrue(conn.isValid(1))
 *         }
 *     }
 * }
 * ```
 *
 * Требование: на машине, где запускаются тесты, должен быть доступен Docker.
 */
@Testcontainers
abstract class AbstractPostgresContainerTest {

    @Container
    private val postgres: PostgreSQLContainer<*> = PostgreSQLContainer("postgres:16-alpine")

    /** Пул соединений к контейнеру. Можно использовать напрямую в тестах. */
    protected val dataSource: DataSource by lazy {
        val config = HikariConfig().apply {
            jdbcUrl = postgres.jdbcUrl
            username = postgres.username
            password = postgres.password
            driverClassName = postgres.driverClassName
            maximumPoolSize = 5
        }
        HikariDataSource(config)
    }

    protected val jdbcUrl: String get() = postgres.jdbcUrl
    protected val dbUsername: String get() = postgres.username
    protected val dbPassword: String get() = postgres.password
}