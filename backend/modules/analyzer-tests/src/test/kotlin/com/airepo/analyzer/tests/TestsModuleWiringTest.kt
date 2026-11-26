package com.airepo.analyzer.tests

import com.airepo.analyzer.tests.coverage.CoverageParser
import com.airepo.analyzer.tests.exec.DockerTestRunner
import com.airepo.analyzer.tests.exec.LocalTestRunner
import com.airepo.analyzer.tests.exec.TestRunner
import com.airepo.analyzer.tests.stack.StackDetector
import org.junit.jupiter.api.Test
import org.springframework.context.annotation.AnnotationConfigApplicationContext
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/**
 * Проверяет, что бин критерия и оба раннера собираются Spring'ом.
 *
 * Ошибка в конструкторе (например, неразрешимый параметр) проявилась бы
 * только при старте приложения — тест ловит это заранее.
 */
class TestsModuleWiringTest {

    @Test
    fun `spring wires the analyzer with docker and local runners`() {
        AnnotationConfigApplicationContext().use { context ->
            context.register(
                TestsModuleProperties::class.java,
                StackDetector::class.java,
                CoverageParser::class.java,
                DockerTestRunner::class.java,
                LocalTestRunner::class.java,
                TestsCoverageAnalyzer::class.java,
            )
            context.refresh()

            val analyzer = context.getBean(TestsCoverageAnalyzer::class.java)
            assertNotNull(analyzer)
            assertEquals("tests", analyzer.id)
            assertEquals(2, context.getBeansOfType(TestRunner::class.java).size)
        }
    }
}
