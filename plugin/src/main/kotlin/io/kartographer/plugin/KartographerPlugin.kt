package io.kartographer.plugin

import org.gradle.api.Plugin
import org.gradle.api.Project

class KartographerPlugin : Plugin<Project> {
    override fun apply(project: Project) {
        project.pluginManager.apply("org.jetbrains.kotlin.jvm")
        
        project.repositories.add(project.repositories.mavenLocal())
        project.repositories.add(project.repositories.mavenCentral())

        // Core is always required for karto pipelines
        project.dependencies.add("implementation", "io.kartographer:core:0.1.0-SNAPSHOT")

        // Register the 'karto' DSL inside 'dependencies { ... }'
        project.dependencies.extensions.create("karto", KartoDependenciesExtension::class.java, project)
    }
}
