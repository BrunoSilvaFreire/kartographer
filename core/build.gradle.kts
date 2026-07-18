import com.google.protobuf.gradle.*

plugins {
    id("org.jetbrains.kotlin.jvm")
    id("org.jetbrains.kotlin.plugin.serialization")
    id("com.google.protobuf")
    `java-library`
}

repositories {
    mavenCentral()
}

dependencies {
    api("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.8.0")
    api("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.2")
    
    // Logging framework (SLF4J facade + Logback implementation)
    api("org.slf4j:slf4j-api:2.0.12")
    
    // Kotlin scripting compiler and JVM execution host
    implementation("org.jetbrains.kotlin:kotlin-scripting-common:1.9.20")
    implementation("org.jetbrains.kotlin:kotlin-scripting-jvm:1.9.20")
    implementation("org.jetbrains.kotlin:kotlin-scripting-jvm-host:1.9.20")
    implementation("org.jetbrains.kotlin:kotlin-scripting-compiler-embeddable:1.9.20")


    // Kotlin reflection for plugin serialization/deserialization
    implementation("org.jetbrains.kotlin:kotlin-reflect:1.9.20")

    // gRPC and Protobuf dependencies
    implementation("io.grpc:grpc-netty-shaded:1.60.0")
    implementation("io.grpc:grpc-protobuf:1.60.0")
    implementation("io.grpc:grpc-stub:1.60.0")
    implementation("io.grpc:grpc-kotlin-stub:1.4.1")
    implementation("com.google.protobuf:protobuf-kotlin:3.25.1")
    
    testImplementation("org.junit.jupiter:junit-jupiter:5.10.1")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

sourceSets {
    main {
        val proto = extensions.getByName("proto") as SourceDirectorySet
        proto.srcDir("../proto")
    }
}

protobuf {
    protoc {
        artifact = "com.google.protobuf:protoc:3.25.1"
    }
    plugins {
        create("grpc") {
            artifact = "io.grpc:protoc-gen-grpc-java:1.60.0"
        }
        create("grpckt") {
            artifact = "io.grpc:protoc-gen-grpc-kotlin:1.4.1:jdk8@jar"
        }
    }
    generateProtoTasks {
        all().forEach { task ->
            task.plugins {
                create("grpc")
                create("grpckt")
            }
            task.builtins {
                create("kotlin")
            }
        }
    }
}


tasks.test {
    useJUnitPlatform()
}
