package com.airepo.analyzer.tests.exec

import com.airepo.analyzer.tests.SandboxProperties
import com.airepo.analyzer.tests.stack.TestStackCatalog
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SandboxSupportTest {

    @Test
    fun `prepare workspace copies sources and skips build output`(@TempDir root: Path) {
        Files.writeString(root.resolve("build.gradle.kts"), "plugins { id(\"java\") }")
        val source = root.resolve("src/main/kotlin")
        Files.createDirectories(source)
        Files.writeString(source.resolve("App.kt"), "fun main() {}")

        val build = root.resolve("build/classes")
        Files.createDirectories(build)
        Files.writeString(build.resolve("App.class"), "binary")

        val nodeModules = root.resolve("node_modules/lib")
        Files.createDirectories(nodeModules)
        Files.writeString(nodeModules.resolve("index.js"), "")

        val workspace = Sandbox.prepareWorkspace(
            root,
            TestStackCatalog.gradle(".", hasWrapper = false),
            SandboxProperties(),
        )
        try {
            assertTrue(Files.isRegularFile(workspace.resolve("build.gradle.kts")))
            assertTrue(Files.isRegularFile(workspace.resolve("src/main/kotlin/App.kt")))
            assertFalse(Files.exists(workspace.resolve("build")))
            assertFalse(Files.exists(workspace.resolve("node_modules")))
            assertTrue(Files.isRegularFile(workspace.resolve(TestStackCatalog.JACOCO_INIT_SCRIPT_FILE)))
        } finally {
            Sandbox.deleteRecursively(workspace)
        }
    }

    @Test
    fun `workspace copy enforces file limits`(@TempDir root: Path) {
        Files.writeString(root.resolve("a.txt"), "1")
        Files.writeString(root.resolve("b.txt"), "2")
        val sandbox = SandboxProperties().apply { maxWorkspaceFiles = 1 }

        assertFailsWith<WorkspaceLimitException> {
            Sandbox.prepareWorkspace(root, TestStackCatalog.python("."), sandbox)
        }
    }

    @Test
    fun `injected files are placed into the project working directory`(@TempDir root: Path) {
        val module = root.resolve("backend")
        Files.createDirectories(module)
        Files.writeString(module.resolve("build.gradle.kts"), "")

        val workspace = Sandbox.prepareWorkspace(
            root,
            TestStackCatalog.gradle("backend", hasWrapper = false),
            SandboxProperties(),
        )
        try {
            assertTrue(Files.isRegularFile(workspace.resolve("backend/.airepo/jacoco-init.gradle")))
        } finally {
            Sandbox.deleteRecursively(workspace)
        }
    }

    @Test
    fun `tar roundtrip keeps structure and content`(@TempDir root: Path) {
        val source = root.resolve("source")
        val nested = source.resolve("coverage")
        Files.createDirectories(nested)
        Files.writeString(nested.resolve("lcov.info"), "LF:1\nLH:1\n")

        val tar = root.resolve("archive.tar")
        Sandbox.createTar(source, tar)
        assertTrue(Files.size(tar) > 0)

        val extracted = root.resolve("extracted")
        Sandbox.extractTar(tar, extracted)

        assertEquals("LF:1\nLH:1\n", Files.readString(extracted.resolve("coverage/lcov.info")))
    }

    @Test
    fun `tar extraction rejects path traversal`(@TempDir root: Path) {
        val tar = root.resolve("evil.tar")
        TarArchiveOutputStream(Files.newOutputStream(tar)).use { output ->
            val entry = TarArchiveEntry("../evil.txt")
            val data = "x".toByteArray()
            entry.size = data.size.toLong()
            output.putArchiveEntry(entry)
            output.write(data)
            output.closeArchiveEntry()
        }

        assertFailsWith<SecurityException> {
            Sandbox.extractTar(tar, root.resolve("target"))
        }
    }

    @Test
    fun `shell quoting handles spaces and single quotes`() {
        assertEquals("/workspace", Shell.quote("/workspace"))
        assertEquals("'a b'", Shell.quote("a b"))
        assertEquals("'it'\\''s'", Shell.quote("it's"))
        assertEquals("sh -c 'echo \"hi\"'", Shell.join(listOf("sh", "-c", "echo \"hi\"")))
    }

    @Test
    fun `output helpers strip ansi and keep the tail`() {
        assertEquals("red", Output.stripAnsi("\u001B[31mred\u001B[0m"))
        assertEquals("abcdef", Output.tail("abcdef", 10))
        assertEquals("…\ncdef", Output.tail("abcdef", 4))
    }
}
