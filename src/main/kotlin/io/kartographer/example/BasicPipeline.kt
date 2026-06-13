package io.kartographer.example

import io.kartographer.core.ExecTask
import io.kartographer.dsl.task
import io.kartographer.dsl.TaskRegistry

class ProtoGen(name: String) : ExecTask(name)

class GoCompile(name: String) : ExecTask(name) {
    var path: String = ""
}

class BasicPipeline {
    init {
        TaskRegistry.clear()

        val codeGenTask = task<ProtoGen>("generate proto go files") {
            outputs.dir("build/generated")
            local {
                exec("mkdir -p build/generated && echo 'hello proto' > build/generated/hello.txt")
            }
        }

        task<GoCompile>("build tileserver-go") {
            path = "tileserver-go"
            dependsOn(codeGenTask)
            inputs.dir("src")
            local {
                exec("echo 'building tileserver-go' && cat build/generated/hello.txt")
            }
        }
    }
}
