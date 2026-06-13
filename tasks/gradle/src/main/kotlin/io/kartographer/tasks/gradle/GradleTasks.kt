package io.kartographer.tasks.gradle

import io.kartographer.tasks.ExecTask
import io.kartographer.core.ExecutionContext
import io.kartographer.core.ExecutionResult

open class GradleTask(name: String) : ExecTask(name) {
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
        buildFile?.let { args.add("-b $it") }
        projectDir?.let { args.add("-p $it") }
        args.addAll(extraArgs)
        args.addAll(tasks)

        commands.clear()
        exec("$gradleBin ${args.joinToString(" ")}")
        return super.execute(context)
    }
}
