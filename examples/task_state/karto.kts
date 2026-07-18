import io.kartographer.tasks.DefaultTask

task<DefaultTask>("state-task") {
    inputs.file("in.txt")
    outputs.file("out.txt")
}
