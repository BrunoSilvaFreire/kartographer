plugins {
    `kotlin-dsl`
    id("maven-publish")
}

repositories {
    mavenCentral()
}

dependencies {
    implementation("org.jetbrains.kotlin:kotlin-gradle-plugin:1.9.20")
    implementation(project(":core"))
    implementation(project(":tasks:gradle"))
    implementation(project(":tasks:containers"))
    implementation(project(":tasks:python"))
    implementation(project(":tasks:apt"))
    implementation(project(":tasks:go"))
    implementation(project(":tasks:proto"))
}

gradlePlugin {
    plugins {
        create("kartographerPlugin") {
            id = "io.kartographer.plugin"
            implementationClass = "io.kartographer.plugin.KartographerPlugin"
        }
    }
}

publishing {
    publications {
        create<MavenPublication>("pluginMaven") {
            groupId = "io.kartographer"
            artifactId = "kartographer-plugin"
            version = "0.1.0-SNAPSHOT"
        }
    }
}
