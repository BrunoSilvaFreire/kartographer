package io.kartographer.tasks.ansible

import io.kartographer.tasks.DefaultTask
import io.kartographer.core.model.ExecutionContext
import io.kartographer.core.model.ExecutionResult
import io.kartographer.core.model.executeBash

open class AnsiblePlaybookRun(name: String) : DefaultTask(name) {
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

    override suspend fun execute(context: ExecutionContext): ExecutionResult {
        return context.executeBash {
            command(ansibleBin.get())
            args(playbook.get())
            
            if (inventory.isPresent()) {
                args("-i", inventory.get())
            }
            
            if (tags.isNotEmpty()) {
                args("--tags", tags.joinToString(","))
            }
            
            if (extraVars.isNotEmpty()) {
                val varsString = extraVars.map { "${it.key}=${it.value}" }.joinToString(" ")
                args("--extra-vars", varsString)
            }
            
            args(this@AnsiblePlaybookRun.args)
        }
    }
}
