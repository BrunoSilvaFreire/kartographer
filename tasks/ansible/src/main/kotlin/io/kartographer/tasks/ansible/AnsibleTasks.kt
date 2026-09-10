package io.kartographer.tasks.ansible

import io.kartographer.core.model.ExecutionContext
import io.kartographer.core.model.ExecutionResult
import io.kartographer.core.model.InputSet
import io.kartographer.core.model.NoOutputs
import io.kartographer.core.model.Task
import io.kartographer.core.model.executeBash

data class AnsibleInputs(
    var playbook: String,
    var inventory: String?,
    var ansibleBin: String,
    var extraVars: Map<String, String>,
    var tags: List<String>,
    var args: List<String>
) : InputSet()

open class AnsiblePlaybookRun(name: String) : Task<AnsibleInputs, NoOutputs>(name) {
    val playbook = property<String>()
    val inventory = property<String>()
    val ansibleBin = property<String> { set("ansible-playbook") }
    
    val extraVars = mutableMapOf<String, String>()
    val tags = mutableListOf<String>()
    
    val args = mutableListOf<String>()
    fun arg(vararg arguments: String) { args.addAll(arguments) }

    init {
        topology { requiresCapability("ansible") }
    }

    override suspend fun buildInputs(context: ExecutionContext) = AnsibleInputs(
        playbook.get(), inventory.orNull(), ansibleBin.get(), extraVars.toMap(), tags.toList(), args.toList()
    )
    override fun buildOutputs() = NoOutputs()

    override suspend fun execute(context: ExecutionContext, inputSet: AnsibleInputs): ExecutionResult<NoOutputs> {
        return context.executeBash {
            command(inputSet.ansibleBin)
            args(inputSet.playbook)
            
            if (inputSet.inventory != null) {
                args("-i", inputSet.inventory!!)
            }
            
            if (inputSet.tags.isNotEmpty()) {
                args("--tags", inputSet.tags.joinToString(","))
            }
            
            if (inputSet.extraVars.isNotEmpty()) {
                val varsString = inputSet.extraVars.map { "${it.key}=${it.value}" }.joinToString(" ")
                args("--extra-vars", varsString)
            }
            
            args(inputSet.args)
        }
    }
}
