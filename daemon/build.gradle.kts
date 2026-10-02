import java.time.Duration

plugins {
    id("org.jetbrains.kotlin.jvm")
    `java-library`
    application
}

repositories {
    mavenCentral()
}

dependencies {
    implementation(project(":core"))

    // The daemon executes every task, so it needs every task type on its classpath. These
    // moved off the CLI, which now only forwards requests.
    implementation(project(":tasks:apt"))
    implementation(project(":tasks:containers"))
    implementation(project(":tasks:python"))
    implementation(project(":tasks:go"))
    implementation(project(":tasks:npm"))
    implementation(project(":tasks:gradle"))
    implementation(project(":tasks:proto"))
    implementation(project(":tasks:ansible"))
    implementation(project(":tasks:pulumi"))
    implementation(project(":tasks:flutter"))
    implementation(project(":tasks:dart"))
    implementation(project(":tasks:kubernetes"))
    implementation(project(":tasks:buf"))
    implementation(project(":tasks:github"))

    // Coroutines and gRPC
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.8.0")
    implementation("io.grpc:grpc-netty-shaded:1.60.0")
    implementation("io.grpc:grpc-protobuf:1.60.0")
    implementation("io.grpc:grpc-stub:1.60.0")
    implementation("io.grpc:grpc-kotlin-stub:1.4.1")
    implementation("com.google.protobuf:protobuf-kotlin:3.25.1")

    // Kotlin scripting — the daemon compiles pipelines now, so the 58 MB compiler lives here
    // and no longer reaches the CLI's runtime classpath. These must match the project's Kotlin
    // version: the embedded compiler reads the metadata of the task modules it compiles against.
    implementation("org.jetbrains.kotlin:kotlin-scripting-common:2.2.10")
    implementation("org.jetbrains.kotlin:kotlin-scripting-jvm:2.2.10")
    implementation("org.jetbrains.kotlin:kotlin-scripting-jvm-host:2.2.10")
    implementation("org.jetbrains.kotlin:kotlin-script-runtime:2.2.10")
    implementation("org.jetbrains.kotlin:kotlin-compiler-embeddable:2.2.10")
    implementation("org.jetbrains.kotlin:kotlin-scripting-compiler-embeddable:2.2.10")
    implementation("ch.qos.logback:logback-classic:1.5.3")

    // Native task-log sinks only. journald's structured fields need `sd_journal_sendv` (the
    // datagram protocol; JDK 21 only speaks stream unix sockets), and the Windows Event Log
    // needs RegisterEventSource/ReportEvent. Kept off the CLI, which only reads files.
    implementation("net.java.dev.jna:jna:5.17.0")
    implementation("net.java.dev.jna:jna-platform:5.17.0")

    testImplementation(testFixtures(project(":core")))
    testImplementation("org.junit.jupiter:junit-jupiter:5.10.1")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

application {
    mainClass.set("io.kartographer.daemon.MainKt")
}

tasks.test {
    useJUnitPlatform()
    // A hung receive loop (see the 111475b fallout) must fail the runner, not eat it.
    timeout.set(Duration.ofMinutes(10))
}
