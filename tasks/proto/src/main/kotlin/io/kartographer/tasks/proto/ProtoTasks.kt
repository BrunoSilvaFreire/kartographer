package io.kartographer.tasks.proto

import io.kartographer.core.model.ExecutionContext
import io.kartographer.core.model.ExecutionResult
import io.kartographer.core.model.InputSet
import io.kartographer.core.model.NoOutputs
import io.kartographer.core.model.Task
import io.kartographer.core.model.executeBash

abstract class ProtoTask<I : InputSet>(name: String) : Task<I, NoOutputs>(name) {
    override fun buildOutputs() = NoOutputs()
    init {
        topology {
            requiresCapability("protoc")
        }
    }
}

data class ProtoCompileInputs(
    var protoFiles: List<String>,
    var protoPath: String?,
    var goOut: String?,
    var goGrpcOut: String?,
    var pythonOut: String?,
    var javaOut: String?,
    var extraArgs: List<String>
) : InputSet()

open class ProtoCompile(name: String) : ProtoTask<ProtoCompileInputs>(name) {
    var protoFiles = mutableListOf<String>()
    var protoPath: String? = null
    var goOut: String? = null
    var goGrpcOut: String? = null
    var pythonOut: String? = null
    var javaOut: String? = null
    val extraArgs = mutableListOf<String>()

    override suspend fun buildInputs(context: ExecutionContext) = ProtoCompileInputs(
        protoFiles.toList(), protoPath, goOut, goGrpcOut, pythonOut, javaOut, extraArgs.toList()
    )

    override suspend fun execute(context: ExecutionContext, inputSet: ProtoCompileInputs): ExecutionResult<NoOutputs> {
        if (inputSet.protoFiles.isEmpty()) {
            return failure(
                IllegalArgumentException("No proto files specified for ProtoCompile task '$name'")
            )
        }

        val args = mutableListOf<String>()
        inputSet.protoPath?.let { args.add("--proto_path=$it") }
        inputSet.goOut?.let { args.add("--go_out=$it") }
        inputSet.goGrpcOut?.let { args.add("--go-grpc_out=$it") }
        inputSet.pythonOut?.let { args.add("--python_out=$it") }
        inputSet.javaOut?.let { args.add("--java_out=$it") }
        args.addAll(inputSet.extraArgs)
        args.addAll(inputSet.protoFiles)

        return context.executeBash {
            command("protoc")
            args(args)
        }
    }
}
