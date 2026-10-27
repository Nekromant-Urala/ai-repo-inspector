import io.spring.gradle.dependencymanagement.dsl.DependencyManagementExtension
import org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension

plugins {
    kotlin("jvm") version "2.3.21" apply false
    kotlin("plugin.spring") version "2.3.21" apply false
    kotlin("plugin.jpa") version "2.3.21" apply false
    id("org.springframework.boot") version "3.5.3" apply false
    id("io.spring.dependency-management") version "1.1.7" apply false
}

allprojects {
    group = "com.airepo"
    version = "0.1.0-SNAPSHOT"

    repositories {
        mavenCentral()
    }
}

subprojects {
    apply(plugin = "org.jetbrains.kotlin.jvm")
    apply(plugin = "io.spring.dependency-management")
    // Даёт конфигурацию `api` для всех модулей (нужна в test-support).
    apply(plugin = "java-library")

    extensions.configure<KotlinJvmProjectExtension> {
        jvmToolchain(24)
    }

    extensions.configure<DependencyManagementExtension> {
        imports {
            mavenBom("org.springframework.boot:spring-boot-dependencies:3.5.3")
        }
    }

    dependencies {
        add("testImplementation", "org.jetbrains.kotlin:kotlin-test:2.3.21")
    }

    tasks.withType<Test> {
        useJUnitPlatform {
            // Тесты с Docker-контейнерами (Testcontainers) по умолчанию пропускаем:
            // их запуск требует Docker. Включаются через `-PincludeContainers`.
            if (!project.hasProperty("includeContainers")) {
                excludeTags("container")
            }
        }
    }
}