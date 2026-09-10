import io.kartographer.testing.ScriptedTask

task<ScriptedTask>("state-task") {
    inputs.file("in.txt")
    trackedOutputs.file("out.txt")
}
