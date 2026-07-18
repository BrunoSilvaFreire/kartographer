package io.kartographer.tasks.proto

import io.kartographer.tasks.DefaultTask
import io.kartographer.core.model.ExecutionContext
import io.kartographer.core.model.ExecutionResult
import io.kartographer.core.model.executeBash

open class ProtoTask(name: String) : DefaultTask(name) {
    init {
        topology {
            requiresCapability("protoc")
        }
    }
}

open class ProtoCompile(name: String) : ProtoTask(name) {
    var protoFiles = mutableListOf<String>()
    var protoPath: String? = null
    var goOut: String? = null
    var goGrpcOut: String? = null
    var pythonOut: String? = null
    var javaOut: String? = null
    val extraArgs = mutableListOf<String>()

    override suspend fun execute(context: ExecutionContext): ExecutionResult {
        if (protoFiles.isEmpty()) {
            return ExecutionResult.Failure(
                IllegalArgumentException("No proto files specified for ProtoCompile task '$name'")
            )
        }

        val args = mutableListOf<String>()
        protoPath?.let { args.add("--proto_path=$it") }
        goOut?.let { args.add("--go_out=$it") }
        goGrpcOut?.let { args.add("--go-grpc_out=$it") }
        pythonOut?.let { args.add("--python_out=$it") }
        javaOut?.let { args.add("--java_out=$it") }
        args.addAll(extraArgs)
        args.addAll(protoFiles)

        return context.executeBash {
            command("protoc")
            args(args)
        }
    }
}
