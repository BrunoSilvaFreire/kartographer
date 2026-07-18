package io.kartographer.core.scheduler

import io.kartographer.core.model.Task
import io.kartographer.core.model.ExecutionContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest

@Serializable
data class TaskState(
    val properties: Map<String, String> = emptyMap(),
    val inputFiles: Map<String, String> = emptyMap(),
    val outputFiles: Map<String, String> = emptyMap(),
    val customProperties: Map<String, String> = emptyMap(),
    val customFiles: Map<String, String> = emptyMap()
)

object TaskStateStore {
    private val json = Json { prettyPrint = true }

    fun getStateFile(context: ExecutionContext, task: Task): Path {
        // Replace spaces or special characters in task names to create valid file paths
        val safeName = task.name.replace(Regex("[^a-zA-Z0-9-_]"), "_")
        return context.projectDir.resolve(".kartographer/state/$safeName.json")
    }

    fun loadState(context: ExecutionContext, task: Task): TaskState? {
        val file = getStateFile(context, task)
        if (!Files.exists(file)) return null
        return try {
            val content = Files.readString(file)
            json.decodeFromString<TaskState>(content)
        } catch (e: Exception) {
            null
        }
    }

    fun saveState(context: ExecutionContext, task: Task) {
        val file = getStateFile(context, task)
        Files.createDirectories(file.parent)

        val properties = task.registeredProperties.mapIndexed { index, prop ->
            "prop-$index" to if (prop.isPresent()) prop.get().toString() else ""
        }.toMap()

        val inputFiles = mutableMapOf<String, String>()
        for (f in task.inputs.files) {
            val path = context.projectDir.resolve(f)
            inputFiles[f] = calculateHash(path)
        }
        for (d in task.inputs.dirs) {
            val path = context.projectDir.resolve(d)
            inputFiles[d] = calculateHash(path)
        }

        val outputFiles = mutableMapOf<String, String>()
        for (f in task.outputs.files) {
            val path = context.projectDir.resolve(f)
            outputFiles[f] = calculateHash(path)
        }
        for (d in task.outputs.dirs) {
            val path = context.projectDir.resolve(d)
            outputFiles[d] = calculateHash(path)
        }

        val customProperties = task.customPropertiesToTrack.mapValues { (_, provider) ->
            provider()?.toString() ?: ""
        }

        val customFiles = task.customFilesToTrack.associateWith { f ->
            calculateHash(context.projectDir.resolve(f))
        }

        val state = TaskState(properties, inputFiles, outputFiles, customProperties, customFiles)
        val content = json.encodeToString(TaskState.serializer(), state)
        Files.writeString(file, content)
    }

    fun calculateHash(path: Path): String {
        if (!Files.exists(path)) return ""
        return try {
            val digest = MessageDigest.getInstance("SHA-256")
            if (Files.isDirectory(path)) {
                Files.walk(path).use { stream ->
                    stream.sorted().forEach { p ->
                        if (!Files.isDirectory(p)) {
                            digest.update(path.relativize(p).toString().toByteArray())
                            digest.update(Files.readAllBytes(p))
                        }
                    }
                }
            } else {
                digest.update(Files.readAllBytes(path))
            }
            digest.digest().joinToString("") { "%02x".format(it) }
        } catch (e: Exception) {
            ""
        }
    }
}
