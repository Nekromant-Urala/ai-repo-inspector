package com.airepo.analyzer.tests.exec

import com.airepo.analyzer.tests.SandboxProperties
import com.airepo.analyzer.tests.stack.TestStackCatalog
import com.airepo.analyzer.tests.stack.TestStackCatalog.NodePackageManager
import com.airepo.analyzer.tests.stack.TestStackCatalog.NodeTestFramework
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DockerCommandBuilderTest {

    private val sandbox = SandboxProperties()

    @Test
    fun `test container is created with isolation flags`() {
        val arguments = DockerCommandBuilder.createArguments(
            containerName = "airepo-test-1",
            volumeName = "airepo-ws-1",
            image = "eclipse-temurin:21-jdk",
            plan = TestStackCatalog.gradle(".", hasWrapper = true),
            sandbox = sandbox,
        )

        assertTrue(arguments.take(2) == listOf("create", "--name"))
        assertTrue(arguments.contains("--read-only"))
        assertTrue(arguments.contains("--cap-drop"))
        assertTrue(arguments.contains("ALL"))
        assertTrue(arguments.contains("--security-opt"))
        assertTrue(arguments.contains("no-new-privileges"))
        assertTrue(arguments.contains("--pids-limit"))
        assertTrue(arguments.contains("--memory"))
        assertTrue(arguments.contains("--cpus"))
        assertTrue(arguments.contains("--init"))
        assertTrue(arguments.contains("airepo-ws-1:/workspace"))
        assertTrue(arguments.contains("--entrypoint"))
        assertEquals("eclipse-temurin:21-jdk", arguments[arguments.indexOf("--entrypoint") + 2])
    }

    @Test
    fun `network mode is configurable`() {
        val offline = SandboxProperties().apply { network = "none" }
        val arguments = DockerCommandBuilder.createArguments(
            "c", "vol", "img", TestStackCatalog.gradle(".", true), offline,
        )
        assertEquals("none", arguments[arguments.indexOf("--network") + 1])

        val online = SandboxProperties().apply { network = "bridge" }
        val onlineArguments = DockerCommandBuilder.createArguments(
            "c", "vol", "img", TestStackCatalog.gradle(".", true), online,
        )
        assertEquals("bridge", onlineArguments[onlineArguments.indexOf("--network") + 1])
    }

    @Test
    fun `container environment exposes caches in tmpfs`() {
        val arguments = DockerCommandBuilder.createArguments(
            "c", "vol", "node:20-alpine",
            TestStackCatalog.node(".", NodePackageManager.NPM, NodeTestFramework.JEST, true),
            sandbox,
        )
        val environment = arguments.filterIndexed { index, _ -> index > 0 && arguments[index - 1] == "-e" }

        assertTrue(environment.any { it.startsWith("HOME=/tmp/home") })
        assertTrue(environment.any { it.startsWith("CI=true") })
        assertTrue(environment.any { it.startsWith("npm_config_cache=/tmp/home/.npm") })
    }

    @Test
    fun `gradle script enables jacoco and collects xml report`() {
        val script = DockerCommandBuilder.script(TestStackCatalog.gradle(".", hasWrapper = true))

        assertTrue(script.contains("cd /workspace"))
        assertTrue(script.contains("sh gradlew test"))
        assertTrue(script.contains("--init-script .airepo/jacoco-init.gradle"))
        assertTrue(script.contains("jacocoTestReport.xml"))
        assertTrue(script.contains(DockerCommandBuilder.ARTIFACTS_TAR))
        assertTrue(script.contains("exit \"\$airepo_test_status\""))
    }

    @Test
    fun `node script passes coverage reporters`() {
        val plan = TestStackCatalog.node("frontend", NodePackageManager.NPM, NodeTestFramework.JEST, true)
        val script = DockerCommandBuilder.script(plan)

        assertTrue(script.contains("cd /workspace/frontend"))
        assertTrue(script.contains("--coverage"))
        assertTrue(script.contains("--coverageReporters=lcov"))
        assertTrue(script.contains("lcov.info"))
    }

    @Test
    fun `script without coverage artifacts skips artifact archive`() {
        val plan = TestStackCatalog.rust(".")
        val script = DockerCommandBuilder.script(plan)

        assertFalse(script.contains(DockerCommandBuilder.ARTIFACTS_TAR))
        assertTrue(script.contains("cargo test"))
    }

    @Test
    fun `helper containers run without network and share the volume`() {
        val upload = DockerCommandBuilder.uploadArguments("up", "vol", "node:20-alpine", sandbox)
        val download = DockerCommandBuilder.downloadArguments("down", "vol", "node:20-alpine", sandbox)

        listOf(upload, download).forEach { arguments ->
            assertTrue(arguments.take(2) == listOf("run", "--rm"))
            assertEquals("none", arguments[arguments.indexOf("--network") + 1])
            assertTrue(arguments.contains("vol:/workspace"))
            assertTrue(arguments.contains("--read-only"))
        }
        assertTrue(upload.contains("-i"))
        assertTrue(upload.contains("tar -xf - -C /workspace"))
        assertTrue(download.last().contains("cat"))
    }

    @Test
    fun `user can be pinned explicitly`() {
        val pinned = SandboxProperties().apply { user = "1000:1000" }
        val arguments = DockerCommandBuilder.createArguments(
            "c", "vol", "img", TestStackCatalog.gradle(".", true), pinned,
        )
        assertEquals("1000:1000", arguments[arguments.indexOf("--user") + 1])
    }

    @Test
    fun `resource names are docker friendly`() {
        assertEquals("airepo-test-abc123def456", DockerCommandBuilder.resourceName("airepo-test", "abc123-def456"))
        assertEquals("airepo-test-", DockerCommandBuilder.resourceName("airepo-test", "---"))
    }
}
