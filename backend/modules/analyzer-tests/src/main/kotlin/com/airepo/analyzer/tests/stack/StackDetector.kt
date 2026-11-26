package com.airepo.analyzer.tests.stack

import com.airepo.analyzer.tests.stack.TestStackCatalog.NodePackageManager
import com.airepo.analyzer.tests.stack.TestStackCatalog.NodeTestFramework
import org.springframework.stereotype.Component
import java.nio.file.Files
import java.nio.file.Path
import java.util.Locale

/**
 * Параметры поиска проектов внутри репозитория.
 *
 * @param maxDepth    насколько глубоко смотреть (монорепозитории)
 * @param maxProjects предохранитель от обхода гигантских деревьев
 * @param maxPlans    максимум запускаемых стеков в одной проверке
 */
data class DetectionOptions(
    val maxDepth: Int = 3,
    val maxProjects: Int = 30,
    val maxPlans: Int = 8,
)

/**
 * Определяет технологические стеки проекта по файлам-маркерам.
 *
 * Сканирование ограничено по глубине и пропускает каталоги зависимостей
 * и сборки (`node_modules`, `build`, `target`, ...), поэтому не уходит
 * в бесконечный обход больших репозиториев.
 *
 * Один каталог может дать несколько планов: например, в корне монорепозитория
 * одновременно лежат `package.json` и `go.mod`.
 */
@Component
class StackDetector {

    fun detect(root: Path, options: DetectionOptions = DetectionOptions()): List<TestStackPlan> {
        if (!Files.isDirectory(root)) return emptyList()
        return findProjectDirectories(root, options)
            .flatMap { detectIn(it, root) }
            .distinctBy { it.id to it.workingDirectory }
            .take(options.maxPlans)
    }

    // -------------------------------------------------------------- scanning

    private fun findProjectDirectories(root: Path, options: DetectionOptions): List<Path> {
        if (options.maxProjects <= 0) return emptyList()
        val found = LinkedHashSet<Path>()
        val walkDepth = options.maxDepth.coerceAtLeast(0) + 1
        runCatching {
            Files.walk(root, walkDepth).use { paths ->
                paths.filter { Files.isRegularFile(it) }.forEach { file ->
                    val parent = file.parent ?: return@forEach
                    val relative = root.relativize(parent)
                    if (relative.any { it.toString() in IGNORED_DIRS }) return@forEach
                    val name = file.fileName.toString()
                    if (name in MARKER_FILES || name.endsWith(".csproj") || name.endsWith(".sln")) {
                        found.add(parent)
                    }
                }
            }
        }
        return found
            .sortedWith(compareBy({ root.relativize(it).nameCount }, { it.toString() }))
            .take(options.maxProjects)
    }

    private fun detectIn(projectDir: Path, root: Path): List<TestStackPlan> {
        val workingDirectory = root.relativize(projectDir)
            .toString()
            .replace('\\', '/')
            .ifBlank { "." }
        val plans = mutableListOf<TestStackPlan>()

        val gradleRoot = hasAny(projectDir, "settings.gradle", "settings.gradle.kts")
        if (hasAny(projectDir, "build.gradle", "build.gradle.kts", "settings.gradle", "settings.gradle.kts") &&
            (gradleRoot || !hasAncestorWith(projectDir, root) { hasAny(it, "settings.gradle", "settings.gradle.kts") })
        ) {
            val hasWrapper = Files.isRegularFile(projectDir.resolve("gradlew"))
            plans += TestStackCatalog.gradle(workingDirectory, hasWrapper)
        }

        if (Files.isRegularFile(projectDir.resolve("pom.xml")) && !isMavenSubmodule(projectDir, root)) {
            plans += TestStackCatalog.maven(workingDirectory)
        }
        if (Files.isRegularFile(projectDir.resolve("package.json")) && !isWorkspaceChild(projectDir, root)) {
            plans += nodePlan(projectDir, workingDirectory)
        }
        if (isPythonProject(projectDir)) {
            plans += TestStackCatalog.python(workingDirectory)
        }
        if (Files.isRegularFile(projectDir.resolve("go.mod"))) {
            plans += TestStackCatalog.go(workingDirectory)
        }
        if (isDotnetProject(projectDir) && !isSolutionChild(projectDir, root)) {
            plans += TestStackCatalog.dotnet(workingDirectory)
        }
        if (Files.isRegularFile(projectDir.resolve("composer.json"))) {
            val composer = readText(projectDir.resolve("composer.json"))
            plans += TestStackCatalog.php(
                workingDirectory,
                coverageCapable = composer.contains("xdebug", ignoreCase = true) ||
                    composer.contains("pcov", ignoreCase = true),
            )
        }
        if (Files.isRegularFile(projectDir.resolve("Gemfile"))) {
            plans += TestStackCatalog.ruby(workingDirectory)
        }
        if (Files.isRegularFile(projectDir.resolve("Cargo.toml")) && !isCargoWorkspaceChild(projectDir, root)) {
            plans += TestStackCatalog.rust(workingDirectory)
        }
        return plans
    }

    // ---------------------------------------------------- monorepo plumbing

    /**
     * Maven-модуль, перечисленный в родительском pom.xml, собирается реактором
     * родителя: отдельный запуск дублировал бы тесты.
     */
    private fun isMavenSubmodule(projectDir: Path, root: Path): Boolean =
        hasAncestorWith(projectDir, root) { ancestor ->
            val pom = ancestor.resolve("pom.xml")
            Files.isRegularFile(pom) && readText(pom).contains("<module", ignoreCase = true)
        }

    /** Вложенный package.json в workspace-монорепозитории тестируется корневым запуском. */
    private fun isWorkspaceChild(projectDir: Path, root: Path): Boolean =
        hasAncestorWith(projectDir, root) { ancestor ->
            val packageJson = ancestor.resolve("package.json")
            (Files.isRegularFile(packageJson) && readText(packageJson).contains("\"workspaces\"")) ||
                Files.isRegularFile(ancestor.resolve("pnpm-workspace.yaml")) ||
                Files.isRegularFile(ancestor.resolve("lerna.json"))
        }

    /** Проект, входящий в .sln, запускается один раз из корня решения. */
    private fun isSolutionChild(projectDir: Path, root: Path): Boolean =
        hasAncestorWith(projectDir, root) { ancestor ->
            runCatching {
                Files.list(ancestor).use { entries ->
                    entries.anyMatch { it.fileName.toString().endsWith(".sln") }
                }
            }.getOrDefault(false)
        }

    /** Подпроект cargo-workspace собирается корневой командой `cargo test --workspace`. */
    private fun isCargoWorkspaceChild(projectDir: Path, root: Path): Boolean =
        hasAncestorWith(projectDir, root) { ancestor ->
            val manifest = ancestor.resolve("Cargo.toml")
            Files.isRegularFile(manifest) && readText(manifest).contains("[workspace]")
        }

    private fun hasAncestorWith(projectDir: Path, root: Path, predicate: (Path) -> Boolean): Boolean {
        var current = projectDir.parent
        while (current != null && current.startsWith(root)) {
            if (predicate(current)) return true
            if (current == root) break
            current = current.parent
        }
        return false
    }

    // ------------------------------------------------------------------ node

    private fun nodePlan(projectDir: Path, workingDirectory: String): TestStackPlan {
        val packageJson = readText(projectDir.resolve("package.json")).lowercase(Locale.ROOT)
        val packageManager = when {
            Files.isRegularFile(projectDir.resolve("pnpm-lock.yaml")) -> NodePackageManager.PNPM
            Files.isRegularFile(projectDir.resolve("yarn.lock")) -> NodePackageManager.YARN
            else -> NodePackageManager.NPM
        }
        val framework = when {
            packageJson.contains("\"jest\"") || hasAny(
                projectDir,
                "jest.config.js", "jest.config.ts", "jest.config.mjs", "jest.config.cjs", "jest.config.json",
            ) -> NodeTestFramework.JEST

            packageJson.contains("\"vitest\"") || hasAny(
                projectDir,
                "vitest.config.ts", "vitest.config.js", "vitest.config.mts", "vitest.config.mjs",
            ) -> NodeTestFramework.VITEST

            packageJson.contains("\"mocha\"") || hasAny(
                projectDir,
                ".mocharc.json", ".mocharc.yml", ".mocharc.yaml", ".mocharc.js",
            ) -> NodeTestFramework.MOCHA

            else -> NodeTestFramework.UNKNOWN
        }
        val coveragePlugin = when (framework) {
            // Jest умеет собирать покрытие из коробки (istanbul внутри).
            NodeTestFramework.JEST -> true
            NodeTestFramework.VITEST ->
                packageJson.contains("@vitest/coverage-v8") || packageJson.contains("@vitest/coverage-istanbul")
            NodeTestFramework.MOCHA -> packageJson.contains("\"nyc\"")
            NodeTestFramework.UNKNOWN -> false
        }
        return TestStackCatalog.node(workingDirectory, packageManager, framework, coveragePlugin)
    }

    // --------------------------------------------------------------- helpers

    private fun isPythonProject(dir: Path): Boolean {
        if (hasAny(dir, "pyproject.toml", "setup.py", "setup.cfg", "requirements.txt", "pytest.ini", "tox.ini", "Pipfile")) {
            return true
        }
        // Одинокий .py-скрипт — ещё не Python-проект; ориентируемся на тесты/конфиг pytest.
        return runCatching {
            Files.list(dir).use { entries ->
                entries.anyMatch { path ->
                    val name = path.fileName.toString()
                    name == "conftest.py" || name.endsWith("_test.py") || name.matches(Regex("^test_.*\\.py$"))
                }
            }
        }.getOrDefault(false)
    }

    private fun isDotnetProject(dir: Path): Boolean = runCatching {
        Files.list(dir).use { entries ->
            entries.anyMatch { path ->
                val name = path.fileName.toString()
                name.endsWith(".csproj") || name.endsWith(".sln")
            }
        }
    }.getOrDefault(false)

    private fun hasAny(dir: Path, vararg names: String): Boolean =
        names.any { Files.isRegularFile(dir.resolve(it)) }

    private fun readText(path: Path): String =
        runCatching { Files.readString(path) }.getOrDefault("")

    private companion object {
        val MARKER_FILES = setOf(
            "build.gradle", "build.gradle.kts", "settings.gradle", "settings.gradle.kts",
            "pom.xml", "package.json",
            "pyproject.toml", "setup.py", "setup.cfg", "requirements.txt", "pytest.ini", "tox.ini",
            "go.mod", "Cargo.toml", "composer.json", "Gemfile",
        )

        val IGNORED_DIRS = setOf(
            "node_modules", "build", "target", ".git", ".gradle", "dist", "out", "bin",
            "vendor", ".venv", "venv", "env", "__pycache__", ".idea", ".mvn", ".next", ".nuxt",
            "coverage", ".tox", ".pytest_cache", "obj",
        )
    }
}
