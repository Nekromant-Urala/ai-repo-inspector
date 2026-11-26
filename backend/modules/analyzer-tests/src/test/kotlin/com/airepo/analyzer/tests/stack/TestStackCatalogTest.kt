package com.airepo.analyzer.tests.stack

import com.airepo.analyzer.tests.stack.TestStackCatalog.NodePackageManager
import com.airepo.analyzer.tests.stack.TestStackCatalog.NodeTestFramework
import org.junit.jupiter.api.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TestStackCatalogTest {

    @Test
    fun `stacks with coverage declare artifacts and flags`() {
        val plans = listOf(
            TestStackCatalog.gradle(".", hasWrapper = true),
            TestStackCatalog.maven("."),
            TestStackCatalog.node(".", NodePackageManager.NPM, NodeTestFramework.JEST, coveragePlugin = true),
            TestStackCatalog.node(".", NodePackageManager.PNPM, NodeTestFramework.JEST, coveragePlugin = true),
            TestStackCatalog.python("."),
            TestStackCatalog.go("."),
            TestStackCatalog.dotnet("."),
            TestStackCatalog.ruby("."),
        )

        plans.forEach { plan ->
            assertTrue(plan.coverageArtifacts.isNotEmpty(), "${plan.id}: ожидались артефакты покрытия")
            assertFalse(plan.testCommand.isEmpty(), "${plan.id}: пустая команда тестов")
        }

        assertTrue(TestStackCatalog.maven(".").testCommand.contains("jacoco:report"))
        assertTrue(TestStackCatalog.python(".").testCommand.contains("--cov-report=xml:coverage.xml"))
        assertTrue(TestStackCatalog.go(".").testCommand.contains("-coverprofile=coverage.out"))
        assertTrue(TestStackCatalog.dotnet(".").testCommand.contains("--collect:XPlat Code Coverage"))
        assertTrue(
            TestStackCatalog.node(".", NodePackageManager.PNPM, NodeTestFramework.JEST, true)
                .testCommand.take(3) == listOf("corepack", "pnpm", "exec"),
        )
    }

    @Test
    fun `rust and php without a driver do not promise coverage`() {
        assertTrue(TestStackCatalog.rust(".").coverageArtifacts.isEmpty())
        assertTrue(TestStackCatalog.php(".", coverageCapable = false).coverageArtifacts.isEmpty())
        assertTrue(TestStackCatalog.php(".", coverageCapable = true).coverageArtifacts.isNotEmpty())
    }

    @Test
    fun `nested project artifacts are prefixed with the working directory`() {
        val plan = TestStackCatalog.gradle("backend", hasWrapper = false)

        assertTrue(plan.coverageArtifacts.all { it.glob.startsWith("backend/") })
        assertTrue(plan.injectedFiles.keys.all { !it.startsWith("/") })
    }
}
