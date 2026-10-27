dependencies {
    implementation(project(":modules:domain"))
    implementation(project(":modules:common"))
    implementation("org.springframework:spring-context")
    implementation("org.slf4j:slf4j-api")
    testImplementation(project(":modules:test-support"))
}