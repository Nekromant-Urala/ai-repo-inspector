package com.airepo.web

import com.airepo.testsupport.AbstractPostgresContainerTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test

/**
 * Демонстрация работы абстрактного базового класса `AbstractPostgresContainerTest`:
 * тест поднимает PostgreSQL в Docker-контейнере и выполняет простые запросы.
 *
 * Такие тесты помечены тэгом `container` и запускаются только с
 * `./gradlew test -PincludeContainers` (требуется запущенный Docker).
 */
@Tag("container")
class PostgresContainerTest : AbstractPostgresContainerTest() {

    @Test
    fun `соединение с контейнером работает`() {
        dataSource.connection.use { conn ->
            assertTrue(conn.isValid(1), "Соединение с PostgreSQL должно быть валидным")
        }
    }

    @Test
    fun `можно выполнять DDL и DML`() {
        dataSource.connection.use { conn ->
            conn.createStatement().use { st ->
                st.executeUpdate("CREATE TABLE demo (id INT PRIMARY KEY, name VARCHAR(50))")
                st.executeUpdate("INSERT INTO demo VALUES (1, 'airepo')")
                val rs = st.executeQuery("SELECT count(*) FROM demo")
                rs.next()
                assertEquals(1, rs.getInt(1))
            }
        }
    }
}