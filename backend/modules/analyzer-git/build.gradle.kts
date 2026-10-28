dependencies {
    implementation(project(":modules:analyzer-api"))
    implementation(project(":modules:domain"))
    implementation(project(":modules:common"))
    implementation("org.springframework:spring-context")
    implementation("org.eclipse.jgit:org.eclipse.jgit:6.9.0.202403050737-r")
    testImplementation("org.junit.jupiter:junit-jupiter-api:5.10.2")
    testRuntimeOnly("org.junit.jupiter:junit-jupiter-engine:5.10.2")
}

tasks.withType<Test> {
    useJUnitPlatform()
}

sourceSets {
    test {
        kotlin.srcDir("src/test/kotlin")
    }
}

