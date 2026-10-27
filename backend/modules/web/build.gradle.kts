plugins {
    kotlin("plugin.spring")
    id("org.springframework.boot")
}

dependencies {
    implementation(project(":modules:domain"))
    implementation(project(":modules:storage"))
    implementation(project(":modules:ingestion"))
    implementation(project(":modules:report"))

    // Реализации критериев — чтобы Spring нашёл их как бины при компонентном сканировании.
    implementation(project(":modules:analyzer-readme"))
    implementation(project(":modules:analyzer-code"))
    implementation(project(":modules:analyzer-tests"))
    implementation(project(":modules:analyzer-structure"))
    implementation(project(":modules:analyzer-git"))
    implementation(project(":modules:analyzer-security"))

    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-data-jpa")
    runtimeOnly("org.postgresql:postgresql")

    testImplementation(project(":modules:test-support"))
    testImplementation("org.springframework.boot:spring-boot-starter-test")
}

// Стабильное имя артефакта, чтобы Dockerfile не зависел от версии.
tasks.bootJar {
    archiveFileName.set("app.jar")
}