plugins {
    id("org.jetbrains.kotlin.jvm") version "1.9.22" apply false
    id("org.jetbrains.kotlin.plugin.serialization") version "1.9.22" apply false
    id("org.jetbrains.compose") version "1.5.11" apply false
    id("com.google.protobuf") version "0.9.4" apply false
    id("maven-publish")
}

subprojects {
    apply(plugin = "maven-publish")

    group = "io.kartographer"
    version = "0.1.0-SNAPSHOT"

    plugins.withType<org.jetbrains.kotlin.gradle.plugin.KotlinPluginWrapper> {
        publishing {
            publications {
                create<MavenPublication>("maven") {
                    from(components["java"])
                }
            }
        }
    }
}
