plugins {
    id("org.jetbrains.kotlin.jvm")
    id("com.jakewharton.mosaic") version "0.11.0"
    application
}

repositories {
    mavenCentral()
    google()
}

// The CLI is a thin gRPC client: it parses arguments, renders output, and forwards
// everything else to the daemon. It deliberately does NOT depend on the task modules or the
// Kotlin compiler — those live in :daemon now, and keeping them off this classpath is what
// makes `karto` start quickly.
dependencies {
    implementation(project(":core"))
    implementation("com.github.ajalt.clikt:clikt:4.4.0")
    implementation("com.jakewharton.mosaic:mosaic-runtime:0.11.0")

    // gRPC: core exposes the generated stubs, but its own gRPC dependencies are
    // `implementation`, so the client needs them declared here to compile against.
    implementation("io.grpc:grpc-netty-shaded:1.60.0")
    implementation("io.grpc:grpc-protobuf:1.60.0")
    implementation("io.grpc:grpc-stub:1.60.0")
    implementation("io.grpc:grpc-kotlin-stub:1.4.1")
    implementation("com.google.protobuf:protobuf-kotlin:3.25.1")

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
