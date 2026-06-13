# Dynamic Kotlin Scripting (.kts) Roadmap

This document outlines the architecture, dependencies, and implementation strategy to migrate Kartographer from loading compiled pipeline classes to dynamically compiling and running `.kts` files at runtime.

---

## 1. Architectural Strategy

Instead of relying on JSR-223 (which is deprecated/obsolete), Kartographer will use the modern **Kotlin Scripting API** (`kotlinx.script`). 

In this architecture, the user defines a `kartographer.kts` file. When the CLI runs, it:
1. Reads `kartographer.kts`.
2. Compiles it using the `BasicJvmScriptingHost`.
3. Runs the compiled script bytecode under a custom classloader that has access to the Kartographer Core classes and the DSL.
4. Registers tasks inside the global `TaskRegistry` when the script executes.

---

## 2. Dependencies

To support dynamic scripting, the following dependencies must be added to `build.gradle.kts`:

```kotlin
dependencies {
    // Kotlin scripting compiler and JVM execution host
    implementation("org.jetbrains.kotlin:kotlin-scripting-jvm-host:1.9.20")
    implementation("org.jetbrains.kotlin:kotlin-scripting-compiler-embeddable:1.9.20")
}
```

---

## 3. Implementation Steps

### Step A: Define the Script Definition
A Script Definition defines the base class, compilation configurations, and implicit imports for the script.

```kotlin
package io.kartographer.dsl.scripting

import kotlin.script.experimental.annotations.KotlinScript
import kotlin.script.experimental.api.ScriptCompilationConfiguration
import kotlin.script.experimental.api.defaultImports
import kotlin.script.experimental.jvm.dependenciesFromCurrentContext
import kotlin.script.experimental.jvm.jvm

@KotlinScript(
    displayName = "Kartographer Pipeline Script",
    fileExtension = "kartographer.kts",
    compilationConfiguration = KartographerScriptCompilationConfiguration::class
)
abstract class KartographerScript

object KartographerScriptCompilationConfiguration : ScriptCompilationConfiguration({
    defaultImports(
        "io.kartographer.dsl.task",
        "io.kartographer.core.ExecTask",
        "io.kartographer.core.DefaultTask",
        "io.kartographer.core.TopologyRequirement"
    )
    jvm {
        // Automatically imports the current classpath so the script has access to Kartographer classes
        dependenciesFromCurrentContext(wholeClasspath = true)
    }
})
```

### Step B: Implement the Script Loader
The loader compiles and evaluates the script file using the scripting host.

```kotlin
package io.kartographer.cli

import io.kartographer.core.Task
import io.kartographer.core.TaskId
import io.kartographer.dsl.TaskRegistry
import io.kartographer.dsl.scripting.KartographerScript
import java.io.File
import kotlin.script.experimental.api.EvaluationResult
import kotlin.script.experimental.api.ResultWithDiagnostics
import kotlin.script.experimental.host.toScriptSource
import kotlin.script.experimental.jvmhost.BasicJvmScriptingHost
import kotlin.script.experimental.jvmhost.createJvmCompilationConfigurationFromTemplate

class KtsPipelineLoader : PipelineLoader {
    override fun load(sourcePath: String): Map<TaskId, Task> {
        val scriptFile = File(sourcePath)
        if (!scriptFile.exists()) {
            throw IllegalArgumentException("Script file not found: $sourcePath")
        }

        // Clear existing tasks
        TaskRegistry.clear()

        val compilationConfiguration = createJvmCompilationConfigurationFromTemplate<KartographerScript>()
        val scriptingHost = BasicJvmScriptingHost()

        val result = scriptingHost.eval(scriptFile.toScriptSource(), compilationConfiguration, null)

        when (result) {
            is ResultWithDiagnostics.Success<EvaluationResult> -> {
                // TaskRegistry is now populated
                return TaskRegistry.tasks
            }
            is ResultWithDiagnostics.Failure -> {
                val errors = result.reports.joinToString("\n") { it.render() }
                throw RuntimeException("Failed to compile or execute script:\n$errors")
            }
        }
    }
}
```

### Step C: Update CLI Integration
In `Main.kt`, we would update the pipeline class lookup to check if the path ends with `.kts`:

```kotlin
override fun run() {
    val tasks = if (pipelineClass.endsWith(".kts")) {
        KtsPipelineLoader().load(pipelineClass)
    } else {
        // Fallback to compiled class loading
        Class.forName(pipelineClass).getDeclaredConstructor().newInstance()
        TaskRegistry.tasks
    }
}
```

---

## 4. Execution Flow of a Scripted Build

With scripting in place, a user can write a `kartographer.kts` file:

```kotlin
// kartographer.kts
task<ExecTask>("compile") {
    inputs.dir("src")
    local {
        exec("kotlinc src/Main.kt -d build/")
    }
}
```

And execute it directly:

```bash
kartographer -p kartographer.kts run compile
```
