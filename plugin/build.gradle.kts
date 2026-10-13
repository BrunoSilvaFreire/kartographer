plugins {
    `kotlin-dsl`
    id("maven-publish")
}

repositories {
    mavenCentral()
}

dependencies {
    implementation("org.jetbrains.kotlin:kotlin-gradle-plugin:2.2.10")
    implementation(project(":core"))
    implementation(project(":tasks:gradle"))
    implementation(project(":tasks:containers"))
    implementation(project(":tasks:python"))
    implementation(project(":tasks:apt"))
    implementation(project(":tasks:go"))
    implementation(project(":tasks:npm"))
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

tasks.named<Jar>("jar") {
    manifest.attributes["Implementation-Version"] = project.version.toString()
}

publishing {
    publications {
        create<MavenPublication>("pluginMaven") {
            groupId = "io.kartographer"
            artifactId = "kartographer-plugin"
            version = project.version.toString()
        }
    }
}
