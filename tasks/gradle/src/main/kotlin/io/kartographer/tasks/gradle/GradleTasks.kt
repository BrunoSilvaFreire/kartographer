package io.kartographer.tasks.gradle

import io.kartographer.tasks.DefaultTask
import io.kartographer.core.model.ExecutionContext
import io.kartographer.core.model.ExecutionResult
import io.kartographer.core.model.executeBash

open class GradleTask(name: String) : DefaultTask(name) {
    val tasks = mutableListOf<String>()
    var buildFile: String? = null
    var projectDir: String? = null
    var useWrapper: Boolean = true
    val extraArgs = mutableListOf<String>()

    override suspend fun execute(context: ExecutionContext): ExecutionResult {
        if (tasks.isEmpty()) {
            return ExecutionResult.Failure(
                IllegalArgumentException("No gradle tasks specified for GradleTask '$name'")
            )
        }

        val gradleBin = if (useWrapper) "./gradlew" else "gradle"
        val args = mutableListOf<String>()
        buildFile?.let { args.add("-b"); args.add(it) }
        projectDir?.let { args.add("-p"); args.add(it) }
        args.addAll(extraArgs)
        args.addAll(tasks)

        return context.executeBash {
            command(gradleBin)
            args(args)
        }
    }
}
