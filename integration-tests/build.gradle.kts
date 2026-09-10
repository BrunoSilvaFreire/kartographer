plugins {
    id("org.jetbrains.kotlin.jvm")
    jacoco
}

repositories {
    mavenCentral()
    google()
}

dependencies {
    implementation(project(":core"))
    implementation(project(":cli"))
    // Pipeline loading and the project cache live in the daemon now.
    implementation(project(":daemon"))
    implementation(project(":tasks:go"))
    implementation(project(":tasks:proto"))
    implementation(project(":tasks:gradle"))
    implementation(project(":tasks:containers"))
    implementation(project(":tasks:python"))
    implementation(project(":tasks:flutter"))
    implementation(project(":tasks:pulumi"))
    implementation(project(":tasks:ansible"))
    implementation(project(":tasks:apt"))

    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.8.0")
    testImplementation("org.jetbrains.kotlin:kotlin-test")

    // Cucumber
    testImplementation("io.cucumber:cucumber-java:7.18.0")
    testImplementation("io.cucumber:cucumber-junit-platform-engine:7.18.0")
    testImplementation("org.junit.platform:junit-platform-suite:1.10.1")
    testImplementation(testFixtures(project(":core")))
    testImplementation("org.junit.jupiter:junit-jupiter:5.10.1")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test {
    useJUnitPlatform()
    // Show test output in console
    testLogging {
        events("passed", "skipped", "failed")
    }

    // Propagate Cucumber tag filter if provided via -Pcucumber.filter.tags="..." or system property
    val filterTags = project.findProperty("cucumber.filter.tags") as? String
        ?: System.getProperty("cucumber.filter.tags")
    if (!filterTags.isNullOrBlank()) {
        systemProperty("cucumber.filter.tags", filterTags)
    }

    finalizedBy(tasks.jacocoTestReport)
}

tasks.jacocoTestReport {
    val subprojects = listOf(
        project(":core"),
        project(":cli"),
        project(":tasks:go"),
        project(":tasks:proto"),
        project(":tasks:gradle"),
        project(":tasks:containers"),
        project(":tasks:python"),
        project(":tasks:flutter"),
        project(":tasks:pulumi"),
        project(":tasks:ansible"),
        project(":tasks:apt")
    )

    executionData.setFrom(fileTree(layout.buildDirectory) {
        include("jacoco/*.exec")
    })

    additionalSourceDirs.setFrom(files(subprojects.map { it.projectDir.resolve("src/main/kotlin") }))
    additionalClassDirs.setFrom(files(subprojects.map { it.layout.buildDirectory.dir("classes/kotlin/main") }))

    reports {
        html.required.set(true)
        xml.required.set(true)
        csv.required.set(false)
    }

    dependsOn(subprojects.map { it.tasks.named("compileKotlin") })
}
