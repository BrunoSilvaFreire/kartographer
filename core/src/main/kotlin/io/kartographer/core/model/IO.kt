package io.kartographer.core.model

abstract class TaskInput {
    abstract val path: String
}

class FileInput(override val path: String) : TaskInput()
class DirInput(override val path: String) : TaskInput()

abstract class TaskOutput {
    abstract val path: String
}

class FileOutput(override val path: String) : TaskOutput()
class DirOutput(override val path: String) : TaskOutput()

class TaskInputs {
    val all = mutableSetOf<TaskInput>()

    val files: Set<String>
        get() = all.filterIsInstance<FileInput>().map { it.path }.toSet()

    val dirs: Set<String>
        get() = all.filterIsInstance<DirInput>().map { it.path }.toSet()
}

class TaskOutputs {
    val all = mutableSetOf<TaskOutput>()

    val files: Set<String>
        get() = all.filterIsInstance<FileOutput>().map { it.path }.toSet()

    val dirs: Set<String>
        get() = all.filterIsInstance<DirOutput>().map { it.path }.toSet()
}

fun TaskInputs.file(path: String) {
    all.add(FileInput(path))
}

fun TaskInputs.dir(path: String) {
    all.add(DirInput(path))
}

fun TaskOutputs.file(path: String) {
    all.add(FileOutput(path))
}

fun TaskOutputs.dir(path: String) {
    all.add(DirOutput(path))
}
