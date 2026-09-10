package io.kartographer.plugin

import org.gradle.api.Project
import javax.inject.Inject

open class KartoDependenciesExtension @Inject constructor(private val project: Project) {
    private val version = "0.1.0-SNAPSHOT"

    fun python() {
        project.dependencies.add("implementation", "io.kartographer:python:$version")
    }

    fun apt() {
        project.dependencies.add("implementation", "io.kartographer:apt:$version")
    }

    fun containers() {
        project.dependencies.add("implementation", "io.kartographer:containers:$version")
    }

    fun go() {
        project.dependencies.add("implementation", "io.kartographer:go:$version")
    }

    fun npm() {
        project.dependencies.add("implementation", "io.kartographer:npm:$version")
    }

    fun gradle() {
        project.dependencies.add("implementation", "io.kartographer:gradle:$version")
    }

    fun proto() {
        project.dependencies.add("implementation", "io.kartographer:proto:$version")
    }

    fun flutter() {
        project.dependencies.add("implementation", "io.kartographer:flutter:$version")
    }

    fun dart() {
        project.dependencies.add("implementation", "io.kartographer:dart:$version")
    }

    fun kubernetes() {
        project.dependencies.add("implementation", "io.kartographer:kubernetes:$version")
    }

    fun pulumi() {
        project.dependencies.add("implementation", "io.kartographer:pulumi:$version")
    }

    fun ansible() {
        project.dependencies.add("implementation", "io.kartographer:ansible:$version")
    }

    fun api(dependencyNotation: String) {
        project.dependencies.add("implementation", dependencyNotation)
    }
}
