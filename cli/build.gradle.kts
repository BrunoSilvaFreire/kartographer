plugins {
    id("org.jetbrains.kotlin.jvm")
    id("com.jakewharton.mosaic") version "0.11.0"
    application
}

repositories {
    mavenCentral()
    google()
}

dependencies {
    implementation(project(":core"))
    implementation(project(":tasks:apt"))
    implementation(project(":tasks:containers"))
    implementation(project(":tasks:python"))
    implementation(project(":tasks:go"))
    implementation(project(":tasks:gradle"))
    implementation(project(":tasks:proto"))
    implementation(project(":tasks:ansible"))
    implementation("com.github.ajalt.clikt:clikt:4.4.0")
    implementation("com.jakewharton.mosaic:mosaic-runtime:0.11.0")
    implementation("com.fasterxml.jackson.core:jackson-databind:2.15.2")
    implementation("com.fasterxml.jackson.module:jackson-module-kotlin:2.15.2")
    
    // Kotlin scripting
    implementation("org.jetbrains.kotlin:kotlin-scripting-common:1.9.22")
    implementation("org.jetbrains.kotlin:kotlin-scripting-jvm:1.9.22")
    implementation("org.jetbrains.kotlin:kotlin-scripting-jvm-host:1.9.22")
    implementation("org.jetbrains.kotlin:kotlin-script-runtime:1.9.22")
    implementation("org.jetbrains.kotlin:kotlin-compiler-embeddable:1.9.22")
    implementation("org.jetbrains.kotlin:kotlin-scripting-compiler-embeddable:1.9.22")
    implementation("org.slf4j:slf4j-api:2.0.12")
    implementation("ch.qos.logback:logback-classic:1.5.3")
    
    testImplementation("org.junit.jupiter:junit-jupiter:5.10.1")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

application {
    mainClass.set("io.kartographer.cli.MainKt")
}

tasks.test {
    useJUnitPlatform()
}
