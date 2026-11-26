dependencies {
    implementation(project(":modules:analyzer-api"))
    implementation(project(":modules:domain"))
    implementation("org.springframework:spring-context")
    implementation("org.springframework.boot:spring-boot")
    implementation("com.fasterxml.jackson.core:jackson-databind")
    testImplementation(project(":modules:test-support"))
}