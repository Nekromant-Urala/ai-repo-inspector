dependencies {
    implementation(project(":modules:analyzer-api"))
    implementation(project(":modules:domain"))
    implementation(project(":modules:ingestion"))
    implementation("org.springframework:spring-context")
    testImplementation(project(":modules:test-support"))
}