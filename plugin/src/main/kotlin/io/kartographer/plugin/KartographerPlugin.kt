package io.kartographer.plugin

import org.gradle.api.Plugin
import org.gradle.api.Project

class KartographerPlugin : Plugin<Project> {
    private val kartographerVersion: String =
        javaClass.`package`?.implementationVersion ?: "0.1.0-SNAPSHOT"

    override fun apply(project: Project) {
        project.pluginManager.apply("org.jetbrains.kotlin.jvm")
        
        project.repositories.add(project.repositories.mavenLocal())
        project.repositories.add(project.repositories.mavenCentral())

        // The daemon supplies core at runtime (PluginClassLoader delegates this package to
        // its parent), but local plugin sources need its API while compiling.
        project.dependencies.add("compileOnly", "io.kartographer:core:$kartographerVersion")

        // Register the 'karto' DSL inside 'dependencies { ... }'
        project.dependencies.extensions.create(
            "karto",
            KartoDependenciesExtension::class.java,
            project,
            kartographerVersion
        )
    }
}
