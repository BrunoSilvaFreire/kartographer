package io.kartographer.core.scheduler

import io.kartographer.core.model.Task
import io.kartographer.core.model.ExecutionContext
import java.nio.file.Files
import kotlin.streams.asSequence

fun interface UpToDateCheck {
    suspend fun isUpToDate(context: ExecutionContext, task: Task): Boolean
}

object UpToDateHelpers {

    fun propertiesMatch() = UpToDateCheck { context, task ->
        val oldState = TaskStateStore.loadState(context, task) ?: return@UpToDateCheck false
        val currentProps = task.registeredProperties.mapIndexed { index, prop ->
            "prop-$index" to if (prop.isPresent()) prop.get().toString() else ""
        }.toMap()
        oldState.properties == currentProps
    }

    fun inputsMatch() = UpToDateCheck { context, task ->
        val oldState = TaskStateStore.loadState(context, task) ?: return@UpToDateCheck false

        for (f in task.inputs.files) {
            val path = context.projectDir.resolve(f)
            val oldHash = oldState.inputFiles[f] ?: return@UpToDateCheck false
            if (TaskStateStore.calculateHash(path) != oldHash) return@UpToDateCheck false
        }
        for (d in task.inputs.dirs) {
            val path = context.projectDir.resolve(d)
            val oldHash = oldState.inputFiles[d] ?: return@UpToDateCheck false
            if (TaskStateStore.calculateHash(path) != oldHash) return@UpToDateCheck false
        }
        true
    }

    fun outputsMatch() = UpToDateCheck { context, task ->
        val oldState = TaskStateStore.loadState(context, task) ?: return@UpToDateCheck false

        if (oldState.outputFiles.isEmpty() && (task.outputs.files.isNotEmpty() || task.outputs.dirs.isNotEmpty())) {
            return@UpToDateCheck false
        }

        for (f in task.outputs.files) {
            val path = context.projectDir.resolve(f)
            if (!Files.exists(path)) return@UpToDateCheck false
            val oldHash = oldState.outputFiles[f] ?: return@UpToDateCheck false
            if (TaskStateStore.calculateHash(path) != oldHash) return@UpToDateCheck false
        }
        for (d in task.outputs.dirs) {
            val path = context.projectDir.resolve(d)
            if (!Files.exists(path)) return@UpToDateCheck false
            val oldHash = oldState.outputFiles[d] ?: return@UpToDateCheck false
            if (TaskStateStore.calculateHash(path) != oldHash) return@UpToDateCheck false
        }
        true
    }

    fun customPropertiesMatch() = UpToDateCheck { context, task ->
        val oldState = TaskStateStore.loadState(context, task) ?: return@UpToDateCheck false
        val currentProps = task.customPropertiesToTrack.mapValues { (_, provider) ->
            provider()?.toString() ?: ""
        }
        oldState.customProperties == currentProps
    }

    fun customFilesMatch() = UpToDateCheck { context, task ->
        val oldState = TaskStateStore.loadState(context, task) ?: return@UpToDateCheck false
        for (f in task.customFilesToTrack) {
            val path = context.projectDir.resolve(f)
            val oldHash = oldState.customFiles[f] ?: return@UpToDateCheck false
            if (TaskStateStore.calculateHash(path) != oldHash) return@UpToDateCheck false
        }
        true
    }

    fun inputsOlderThanOutputs() = UpToDateCheck { context, task ->
        val inputPaths = (task.inputs.files + task.inputs.dirs).map { context.projectDir.resolve(it) }
        val outputPathPaths = (task.outputs.files + task.outputs.dirs).map { context.projectDir.resolve(it) }

        if (inputPaths.isEmpty() || outputPathPaths.isEmpty()) return@UpToDateCheck false

        // Get max last modified time of inputs
        val maxInputTime = inputPaths.flatMap { path ->
            if (!Files.exists(path)) return@UpToDateCheck false
            if (Files.isDirectory(path)) {
                Files.walk(path).use { stream ->
                    stream.asSequence()
                        .filter { !Files.isDirectory(it) }
                        .map { Files.getLastModifiedTime(it).toMillis() }
                        .toList()
                }
            } else {
                listOf(Files.getLastModifiedTime(path).toMillis())
            }
        }.maxOrNull() ?: return@UpToDateCheck false

        // Get min last modified time of outputs, and check if all outputs exist
        val minOutputTime = outputPathPaths.flatMap { path ->
            if (!Files.exists(path)) return@UpToDateCheck false
            if (Files.isDirectory(path)) {
                Files.walk(path).use { stream ->
                    stream.asSequence()
                        .filter { !Files.isDirectory(it) }
                        .map { Files.getLastModifiedTime(it).toMillis() }
                        .toList()
                }
            } else {
                listOf(Files.getLastModifiedTime(path).toMillis())
            }
        }.minOrNull() ?: return@UpToDateCheck false

        maxInputTime <= minOutputTime
    }

    fun envVarsMatch(vararg names: String) = UpToDateCheck { context, task ->
        val oldState = TaskStateStore.loadState(context, task) ?: return@UpToDateCheck false
        names.all { name ->
            val oldVal = oldState.customProperties["env:$name"] ?: return@UpToDateCheck false
            val newVal = System.getenv(name) ?: ""
            oldVal == newVal
        }
    }

    fun systemPropertiesMatch(vararg names: String) = UpToDateCheck { context, task ->
        val oldState = TaskStateStore.loadState(context, task) ?: return@UpToDateCheck false
        names.all { name ->
            val oldVal = oldState.customProperties["sys:$name"] ?: return@UpToDateCheck false
            val newVal = System.getProperty(name) ?: ""
            oldVal == newVal
        }
    }
}
