import io.kartographer.tasks.DefaultTask

task<DefaultTask>("state-task") {
    inputs.file("in.txt")
    trackedOutputs.file("out.txt")
}
