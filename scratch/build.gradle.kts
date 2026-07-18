plugins {
    id("org.jetbrains.kotlin.jvm") version "1.9.22"
    application
}
repositories { mavenCentral() }
dependencies {
    implementation("com.github.ajalt.clikt:clikt:4.4.0")
}
application {
    mainClass.set("MainKt")
}
