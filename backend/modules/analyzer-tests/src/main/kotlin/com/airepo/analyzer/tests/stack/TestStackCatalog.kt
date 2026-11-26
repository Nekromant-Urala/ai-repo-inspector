package com.airepo.analyzer.tests.stack

/**
 * Каталог поддерживаемых технологических стеков.
 *
 * Для каждого стека описан полностью декларативный план: Docker-образ,
 * команды установки зависимостей и запуска тестов с флагами сбора покрытия,
 * а также файлы покрытия, которые нужно забрать после прогона.
 *
 * Добавить новый стек = добавить функцию в этот объект и правило детекта
 * в [StackDetector]. Раннеры об этом ничего не знают.
 */
object TestStackCatalog {

    /** Пакетный менеджер Node.js. */
    enum class NodePackageManager { NPM, PNPM, YARN }

    /** Тестовый фреймворк Node.js. */
    enum class NodeTestFramework { JEST, VITEST, MOCHA, UNKNOWN }

    // ------------------------------------------------------------------ JVM

    fun gradle(workingDirectory: String, hasWrapper: Boolean): TestStackPlan {
        val commandPrefix = if (hasWrapper) listOf("sh", "gradlew") else listOf("gradle")
        val testCommand = commandPrefix + listOf(
            "test",
            "--no-daemon",
            "--console=plain",
            "--init-script",
            JACOCO_INIT_SCRIPT_FILE,
        )
        return TestStackPlan(
            id = "gradle",
            displayName = "Gradle (JVM)",
            image = if (hasWrapper) "eclipse-temurin:21-jdk" else "gradle:8.10-jdk21",
            workingDirectory = workingDirectory,
            testCommand = testCommand,
            coverageArtifacts = listOf(
                artifact(workingDirectory, "**/build/reports/jacoco/test/jacocoTestReport.xml", CoverageFormat.JACOCO_XML),
                artifact(workingDirectory, "**/build/reports/jacoco/**/*.xml", CoverageFormat.JACOCO_XML),
            ),
            injectedFiles = mapOf(JACOCO_INIT_SCRIPT_FILE to JACOCO_INIT_SCRIPT),
            environment = mapOf(
                "GRADLE_USER_HOME" to "/tmp/home/.gradle",
                "GRADLE_OPTS" to "-Dorg.gradle.daemon=false -Dorg.gradle.parallel=false",
            ),
            notes = listOf("JaCoCo подключается init-скриптом, менять build.gradle проекта не нужно."),
        )
    }

    fun maven(workingDirectory: String): TestStackPlan = TestStackPlan(
        id = "maven",
        displayName = "Maven (JVM)",
        image = "maven:3.9-eclipse-temurin-21",
        workingDirectory = workingDirectory,
        testCommand = listOf(
            "mvn", "-B", "-ntp",
            "-Dmaven.repo.local=/tmp/home/.m2/repository",
            "jacoco:prepare-agent",
            "test",
            "jacoco:report",
        ),
        coverageArtifacts = listOf(
            artifact(workingDirectory, "**/target/site/jacoco/jacoco.xml", CoverageFormat.JACOCO_XML),
        ),
        environment = mapOf(
            "MAVEN_OPTS" to "-Dmaven.repo.local=/tmp/home/.m2/repository",
        ),
        notes = listOf("jacoco-maven-plugin вызывается целями prepare-agent/report, pom.xml не модифицируется."),
    )

    // ---------------------------------------------------------------- Node.js

    fun node(
        workingDirectory: String,
        packageManager: NodePackageManager,
        framework: NodeTestFramework,
        coveragePlugin: Boolean,
    ): TestStackPlan {
        val runner = when (packageManager) {
            NodePackageManager.NPM -> listOf("npx", "--no-install")
            NodePackageManager.PNPM -> listOf("corepack", "pnpm", "exec")
            NodePackageManager.YARN -> listOf("corepack", "yarn", "run")
        }
        val installCommand = when (packageManager) {
            NodePackageManager.NPM -> listOf(
                "sh", "-c",
                "if [ -f package-lock.json ]; then npm ci --no-audit --no-fund; " +
                    "else npm install --no-audit --no-fund; fi",
            )
            NodePackageManager.PNPM -> listOf(
                "sh", "-c",
                "if [ -f pnpm-lock.yaml ]; then corepack pnpm install --frozen-lockfile; else corepack pnpm install; fi",
            )
            NodePackageManager.YARN -> listOf(
                "sh", "-c",
                "if [ -f yarn.lock ]; then corepack yarn install --frozen-lockfile; else corepack yarn install; fi",
            )
        }

        val testCommand = when (framework) {
            NodeTestFramework.JEST -> runner + listOf(
                "jest",
                "--coverage",
                "--coverageReporters=lcov",
                "--coverageReporters=json-summary",
                "--ci",
            )
            NodeTestFramework.VITEST -> if (coveragePlugin) {
                runner + listOf(
                    "vitest", "run",
                    "--coverage",
                    "--coverage.reporter=lcov",
                    "--coverage.reporter=json-summary",
                )
            } else {
                runner + listOf("vitest", "run")
            }
            NodeTestFramework.MOCHA -> if (coveragePlugin) {
                runner + listOf("nyc", "--reporter=lcov", "--reporter=json-summary", "mocha")
            } else {
                runner + listOf("mocha")
            }
            NodeTestFramework.UNKNOWN -> when (packageManager) {
                NodePackageManager.NPM -> listOf("npm", "test", "--silent")
                NodePackageManager.PNPM -> listOf("pnpm", "test")
                NodePackageManager.YARN -> listOf("yarn", "test")
            }
        }

        return TestStackPlan(
            id = "node",
            displayName = "Node.js",
            image = "node:20-alpine",
            workingDirectory = workingDirectory,
            bootstrapCommands = listOf(installCommand),
            testCommand = testCommand,
            coverageArtifacts = listOf(
                artifact(workingDirectory, "coverage/lcov.info", CoverageFormat.LCOV),
                artifact(workingDirectory, "coverage/coverage-summary.json", CoverageFormat.ISTANBUL_JSON),
            ),
            environment = mapOf(
                "CI" to "true",
                "NODE_ENV" to "test",
                "npm_config_cache" to "/tmp/home/.npm",
                "npm_config_update_notifier" to "false",
                "COREPACK_HOME" to "/tmp/home/.corepack",
                "COREPACK_ENABLE_DOWNLOAD_PROMPT" to "0",
            ),
            notes = when (framework) {
                NodeTestFramework.UNKNOWN -> listOf("Тестовый фреймворк не распознан: запускается npm-скрипт test без флагов покрытия.")
                NodeTestFramework.VITEST -> if (!coveragePlugin) {
                    listOf("В зависимостях нет @vitest/coverage-v8/@vitest/coverage-istanbul: покрытие не собирается.")
                } else {
                    emptyList()
                }
                else -> emptyList()
            },
        )
    }

    // ---------------------------------------------------------------- Python

    fun python(workingDirectory: String): TestStackPlan {
        val venvPython = "/tmp/home/venv/bin/python"
        val bootstrap = mutableListOf<List<String>>()
        // Virtualenv живёт в tmpfs: rootfs контейнера read-only, system site-packages недоступны.
        bootstrap += listOf("python", "-m", "venv", "/tmp/home/venv")
        bootstrap += listOf(
            "sh", "-c",
            "if [ -f requirements.txt ]; then " +
                "$venvPython -m pip install --no-cache-dir --quiet -r requirements.txt; fi",
        )
        bootstrap += listOf(
            venvPython, "-m", "pip", "install", "--no-cache-dir", "--quiet", "pytest", "pytest-cov",
        )
        return TestStackPlan(
            id = "python",
            displayName = "Python",
            image = "python:3.12-slim",
            workingDirectory = workingDirectory,
            bootstrapCommands = bootstrap,
            testCommand = listOf(
                venvPython, "-m", "pytest", "-q",
                "--cov=.",
                "--cov-report=xml:coverage.xml",
                "--cov-report=lcov:lcov.info",
            ),
            coverageArtifacts = listOf(
                artifact(workingDirectory, "coverage.xml", CoverageFormat.COBERTURA_XML),
                artifact(workingDirectory, "lcov.info", CoverageFormat.LCOV),
            ),
            environment = mapOf(
                "PIP_CACHE_DIR" to "/tmp/home/.cache/pip",
                "PYTHONDONTWRITEBYTECODE" to "1",
                "PYTHONUNBUFFERED" to "1",
            ),
            notes = listOf("Покрытие собирается pytest-cov; зависимости и pytest ставятся в venv внутри контейнера."),
        )
    }

    // -------------------------------------------------------------------- Go

    fun go(workingDirectory: String): TestStackPlan = TestStackPlan(
        id = "go",
        displayName = "Go",
        image = "golang:1.23-alpine",
        workingDirectory = workingDirectory,
        bootstrapCommands = listOf(
            listOf("go", "mod", "download"),
        ),
        testCommand = listOf("go", "test", "-coverprofile=coverage.out", "./..."),
        coverageArtifacts = listOf(
            artifact(workingDirectory, "coverage.out", CoverageFormat.GO_COVER),
        ),
        environment = mapOf(
            "GOCACHE" to "/tmp/home/.cache/go-build",
            "GOMODCACHE" to "/tmp/home/go/pkg/mod",
            "CGO_ENABLED" to "0",
        ),
        notes = listOf("Покрытие встроенным флагом -coverprofile."),
    )

    // ------------------------------------------------------------------- .NET

    fun dotnet(workingDirectory: String): TestStackPlan = TestStackPlan(
        id = "dotnet",
        displayName = ".NET",
        image = "mcr.microsoft.com/dotnet/sdk:8.0",
        workingDirectory = workingDirectory,
        bootstrapCommands = listOf(
            listOf("dotnet", "restore", "--nologo"),
        ),
        testCommand = listOf(
            "dotnet", "test", "--no-restore", "--nologo",
            "--collect:XPlat Code Coverage",
            "--results-directory", "TestResults",
        ),
        coverageArtifacts = listOf(
            artifact(workingDirectory, "**/TestResults/**/coverage.cobertura.xml", CoverageFormat.COBERTURA_XML),
        ),
        environment = mapOf(
            "DOTNET_CLI_TELEMETRY_OPTOUT" to "1",
            "DOTNET_NOLOGO" to "1",
            "DOTNET_CLI_HOME" to "/tmp/home",
            "NUGET_PACKAGES" to "/tmp/home/.nuget/packages",
        ),
        notes = listOf("Покрытие собирается встроенным коллектором XPlat Code Coverage (Cobertura)."),
    )

    // -------------------------------------------------------------------- PHP

    fun php(workingDirectory: String, coverageCapable: Boolean): TestStackPlan = TestStackPlan(
        id = "php",
        displayName = "PHP",
        image = "composer:2",
        workingDirectory = workingDirectory,
        bootstrapCommands = listOf(
            listOf("composer", "install", "--no-interaction", "--no-progress", "--prefer-dist"),
        ),
        testCommand = if (coverageCapable) {
            listOf("vendor/bin/phpunit", "--coverage-clover", "clover.xml")
        } else {
            listOf("vendor/bin/phpunit")
        },
        coverageArtifacts = if (coverageCapable) {
            listOf(artifact(workingDirectory, "clover.xml", CoverageFormat.CLOVER_XML))
        } else {
            emptyList()
        },
        environment = mapOf("COMPOSER_HOME" to "/tmp/home/.composer"),
        notes = if (coverageCapable) {
            emptyList()
        } else {
            listOf("Для покрытия PHPUnit нужен драйвер xdebug/pcov; он не заявлен в composer.json, покрытие не собирается.")
        },
    )

    // ------------------------------------------------------------------- Ruby

    fun ruby(workingDirectory: String): TestStackPlan = TestStackPlan(
        id = "ruby",
        displayName = "Ruby",
        image = "ruby:3.3",
        workingDirectory = workingDirectory,
        bootstrapCommands = listOf(
            listOf("sh", "-c", "bundle check >/dev/null 2>&1 || bundle install --jobs 4 --retry 2"),
        ),
        testCommand = listOf("bundle", "exec", "rspec"),
        coverageArtifacts = listOf(
            artifact(workingDirectory, "coverage/.resultset.json", CoverageFormat.SIMPLECOV_JSON),
        ),
        environment = mapOf(
            "BUNDLE_PATH" to "/tmp/home/bundle",
            "GEM_HOME" to "/tmp/home/gems",
        ),
        notes = listOf("Покрытие читается из SimpleCov, если он подключён в spec_helper."),
    )

    // ------------------------------------------------------------------- Rust

    fun rust(workingDirectory: String): TestStackPlan = TestStackPlan(
        id = "rust",
        displayName = "Rust",
        image = "rust:1.83-slim",
        workingDirectory = workingDirectory,
        bootstrapCommands = listOf(
            listOf("cargo", "fetch"),
        ),
        testCommand = listOf("cargo", "test", "--workspace", "--all-targets"),
        environment = mapOf(
            "CARGO_HOME" to "/tmp/home/.cargo",
            "CARGO_TARGET_DIR" to "/tmp/home/target",
        ),
        notes = listOf("Тесты запускаются, но покрытие не собирается: нужен дополнительный инструмент (cargo-llvm-cov/llvm-tarpaulin)."),
    )

    // --------------------------------------------------------------- helpers

    private fun artifact(workingDirectory: String, path: String, format: CoverageFormat): CoverageArtifact {
        val prefix = workingDirectory.trim().trim('/')
        val fullPath = if (prefix.isEmpty() || prefix == ".") path else "$prefix/$path"
        return CoverageArtifact(fullPath, format)
    }

    /** Файл init-скрипта JaCoCo, который подкладывается в копию проекта. */
    const val JACOCO_INIT_SCRIPT_FILE: String = ".airepo/jacoco-init.gradle"

    /**
     * Init-скрипт Gradle: подключает JaCoCo и XML-отчёт ко всем модулям.
     *
     * Проект не модифицируется: скрипт передаётся флагом `--init-script`.
     * `jacocoTestReport` добавляется как finalizer к каждой задаче `test`.
     */
    val JACOCO_INIT_SCRIPT: String = """
        import org.gradle.api.tasks.testing.Test

        allprojects { p ->
            try {
                p.pluginManager.apply('jacoco')
            } catch (Exception ignored) {
                // Проект без JVM-плагина — JaCoCo ему не нужен.
            }

            p.tasks.withType(Test).configureEach { t ->
                def reportName = 'jacoco' + t.name.substring(0, 1).toUpperCase() + t.name.substring(1) + 'Report'
                def reportTask = p.tasks.findByName(reportName)
                if (reportTask != null) {
                    reportTask.reports.xml.required = true
                    t.finalizedBy(reportTask)
                }
            }

            p.tasks.matching { it.name.startsWith('jacoco') && it.name.endsWith('Report') }.configureEach { r ->
                r.reports.xml.required = true
            }
        }
    """.trimIndent()
}
