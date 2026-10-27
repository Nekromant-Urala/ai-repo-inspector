dependencies {
    implementation(project(":modules:analyzer-api"))
    implementation(project(":modules:domain"))
    implementation(project(":modules:common"))
    implementation("org.springframework:spring-context")
    testImplementation(project(":modules:test-support"))
}