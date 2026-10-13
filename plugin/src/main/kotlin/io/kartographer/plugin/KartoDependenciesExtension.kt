package io.kartographer.plugin

import org.gradle.api.Project
import javax.inject.Inject

open class KartoDependenciesExtension @Inject constructor(
    private val project: Project,
    private val version: String
) {

    fun python() {
        project.dependencies.add("compileOnly", "io.kartographer:python:$version")
    }

    fun apt() {
        project.dependencies.add("compileOnly", "io.kartographer:apt:$version")
    }

    fun containers() {
        project.dependencies.add("compileOnly", "io.kartographer:containers:$version")
    }

    fun go() {
        project.dependencies.add("compileOnly", "io.kartographer:go:$version")
    }

    fun npm() {
        project.dependencies.add("compileOnly", "io.kartographer:npm:$version")
    }

    fun gradle() {
        project.dependencies.add("compileOnly", "io.kartographer:gradle:$version")
    }

    fun proto() {
        project.dependencies.add("compileOnly", "io.kartographer:proto:$version")
    }

    fun flutter() {
        project.dependencies.add("compileOnly", "io.kartographer:flutter:$version")
    }

    fun dart() {
        project.dependencies.add("compileOnly", "io.kartographer:dart:$version")
    }

    fun kubernetes() {
        project.dependencies.add("compileOnly", "io.kartographer:kubernetes:$version")
    }

    fun pulumi() {
        project.dependencies.add("compileOnly", "io.kartographer:pulumi:$version")
    }

    fun ansible() {
        project.dependencies.add("compileOnly", "io.kartographer:ansible:$version")
    }

    fun buf() {
        project.dependencies.add("compileOnly", "io.kartographer:buf:$version")
    }

    fun github() {
        project.dependencies.add("compileOnly", "io.kartographer:github:$version")
    }

    fun api(dependencyNotation: String) {
        project.dependencies.add("implementation", dependencyNotation)
    }
}
