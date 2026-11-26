dependencies {
    implementation(project(":modules:analyzer-api"))
    implementation(project(":modules:domain"))
    implementation(project(":modules:common"))

    implementation("org.springframework:spring-context")
    // @ConfigurationProperties для настроек критерия.
    implementation("org.springframework.boot:spring-boot")
    implementation("org.slf4j:slf4j-api")

    // Разбор отчётов покрытия (Istanbul/SimpleCov) и упаковка копии проекта в tar.
    implementation("com.fasterxml.jackson.core:jackson-databind")
    implementation("org.apache.commons:commons-compress:1.27.1")

    testImplementation(project(":modules:test-support"))
    testImplementation("org.junit.jupiter:junit-jupiter-api")
    testRuntimeOnly("org.junit.jupiter:junit-jupiter-engine")
}
