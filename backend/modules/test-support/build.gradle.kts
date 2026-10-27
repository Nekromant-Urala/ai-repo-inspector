// Java-library и kotlin jvm применяются из корневого build.gradle.kts (subprojects).
dependencies {
    api("org.testcontainers:testcontainers")
    api("org.testcontainers:junit-jupiter")
    api("org.testcontainers:postgresql")
    api("org.postgresql:postgresql")
    api("com.zaxxer:HikariCP")
}