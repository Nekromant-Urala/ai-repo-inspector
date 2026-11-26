dependencies {
    implementation(project(":modules:analyzer-api"))
    implementation(project(":modules:domain"))
    implementation(project(":modules:common"))
    implementation("org.springframework:spring-context")
    implementation("org.eclipse.jgit:org.eclipse.jgit:6.9.0.202403050737-r")
}

tasks.withType<Test> {
    useJUnitPlatform()
}

sourceSets {
    test {
        kotlin.srcDir("src/test/kotlin")
    }
}

