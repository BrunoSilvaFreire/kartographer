package io.kartographer.dsl.scripting

import kotlin.script.experimental.annotations.KotlinScript
import kotlin.script.experimental.api.ScriptCompilationConfiguration
import kotlin.script.experimental.api.compilerOptions
import kotlin.script.experimental.api.defaultImports
import kotlin.script.experimental.jvm.dependenciesFromCurrentContext
import kotlin.script.experimental.jvm.jvm

@KotlinScript(
    displayName = "Kartographer Pipeline Script",
    fileExtension = "kartographer.kts",
    filePathPattern = ".*(?:/|^)karto(?:/.*)?\\.kts|.*\\.kartographer\\.kts",
    compilationConfiguration = KartographerScriptCompilationConfiguration::class
)
abstract class KartographerScript

object KartographerScriptCompilationConfiguration : ScriptCompilationConfiguration({
    defaultImports(
        "io.kartographer.dsl.*",
        "io.kartographer.tasks.*",
        "io.kartographer.core.*",
        "io.kartographer.core.model.*"
    )
    jvm {
        // Automatically imports the current classpath so the script has access to Kartographer classes
        dependenciesFromCurrentContext(wholeClasspath = true)
    }
    compilerOptions("-jvm-target", System.getProperty("java.specification.version") ?: "11")
})
