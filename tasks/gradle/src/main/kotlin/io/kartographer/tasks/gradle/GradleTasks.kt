package io.kartographer.tasks.gradle

import io.kartographer.core.model.ExecutionContext
import io.kartographer.core.model.ExecutionResult
import io.kartographer.core.model.InputSet
import io.kartographer.core.model.NoOutputs
import io.kartographer.core.model.Task
import io.kartographer.core.model.executeBash

data class GradleInputs(
    var tasks: List<String>,
    var buildFile: String?,
    var projectDir: String?,
    var useWrapper: Boolean,
    var extraArgs: List<String>
) : InputSet()

open class GradleTask(name: String) : Task<GradleInputs, NoOutputs>(name) {
    val tasks = mutableListOf<String>()
    var buildFile: String? = null
    var projectDir: String? = null
    var useWrapper: Boolean = true
    val extraArgs = mutableListOf<String>()

    override suspend fun buildInputs(context: ExecutionContext) = GradleInputs(
        tasks.toList(), buildFile, projectDir, useWrapper, extraArgs.toList()
    )
    override fun buildOutputs() = NoOutputs()

    override suspend fun execute(context: ExecutionContext, inputSet: GradleInputs): ExecutionResult<NoOutputs> {
        if (inputSet.tasks.isEmpty()) {
            return failure(
                IllegalArgumentException("No gradle tasks specified for GradleTask '$name'")
            )
        }

        val gradleBin = if (inputSet.useWrapper) "./gradlew" else "gradle"
        val args = mutableListOf<String>()
        inputSet.buildFile?.let { args.add("-b"); args.add(it) }
        inputSet.projectDir?.let { args.add("-p"); args.add(it) }
        args.addAll(inputSet.extraArgs)
        args.addAll(inputSet.tasks)

        return context.executeBash {
            command(gradleBin)
            args(args)
        }
    }
}
