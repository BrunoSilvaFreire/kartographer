import io.kartographer.tasks.python.*

task<PythonExecTask>("py-script") {
    scriptPath.set("scripts/run.py")
}

task<PythonExecTask>("py-module") {
    moduleName.set("pytest")
    venv.set(".venv")
}

task<PythonExecTask>("py-complex") {
    scriptPath.set("scripts/run.py")
    environment["ENV"] = "test"
    arg("arg1", "arg2")
}

task<PythonExecTask>("py-empty") {
    // Empty task to test null moduleName and null scriptPath
}
