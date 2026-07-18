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
    
    // Coroutines and gRPC
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.8.0")
    implementation("io.grpc:grpc-netty-shaded:1.60.0")
    implementation("io.grpc:grpc-protobuf:1.60.0")
    implementation("io.grpc:grpc-stub:1.60.0")
    implementation("io.grpc:grpc-kotlin-stub:1.4.1")
    implementation("com.google.protobuf:protobuf-kotlin:3.25.1")
    
    testImplementation("org.junit.jupiter:junit-jupiter:5.10.1")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

application {
    mainClass.set("io.kartographer.daemon.MainKt")
}

tasks.test {
    useJUnitPlatform()
}
