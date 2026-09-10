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
    var workingDir: String?,
    var extraVars: Map<String, String>,
    var tags: List<String>,
    var args: List<String>
) : InputSet()

open class AnsiblePlaybookRun(name: String) : Task<AnsibleInputs, NoOutputs>(name) {
    val playbook = property<String>()
    val inventory = property<String>()
    val ansibleBin = property<String> { set("ansible-playbook") }
    val workingDir = property<String>()
    
    val extraVars = mutableMapOf<String, String>()
    val tags = mutableListOf<String>()
    
    val args = mutableListOf<String>()
    fun arg(vararg arguments: String) { args.addAll(arguments) }

    init {
        topology { requiresCapability("ansible") }
    }

    override suspend fun buildInputs(context: ExecutionContext) = AnsibleInputs(
        playbook.get(), inventory.orNull(), ansibleBin.get(), workingDir.orNull(),
        extraVars.toMap(), tags.toList(), args.toList()
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
            inputSet.workingDir?.let { workingDir = it }
        }
    }
}

data class AnsibleGalaxyInstallInputs(
    var ansibleGalaxyBin: String,
    var requirements: String,
    var workingDir: String?
) : InputSet()

class AnsibleGalaxyInstall(name: String) : Task<AnsibleGalaxyInstallInputs, NoOutputs>(name) {
    val ansibleGalaxyBin = property<String> { set("ansible-galaxy") }
    val requirements = property<String> { set("requirements.yml") }
    val workingDir = property<String>()

    init { topology { requiresCapability("ansible") } }

    override suspend fun buildInputs(context: ExecutionContext) =
        AnsibleGalaxyInstallInputs(ansibleGalaxyBin.get(), requirements.get(), workingDir.orNull())
    override fun buildOutputs() = NoOutputs()
    override suspend fun execute(context: ExecutionContext, inputSet: AnsibleGalaxyInstallInputs) =
        context.executeBash {
            command(inputSet.ansibleGalaxyBin)
            args("collection", "install", "-r", inputSet.requirements)
            inputSet.workingDir?.let { workingDir = it }
        }
}

data class AnsibleCommandInputs(var executable: String, var workingDir: String?, var args: List<String>) : InputSet()

abstract class AnsibleCommandTask(name: String) : Task<AnsibleCommandInputs, NoOutputs>(name) {
    val executable = property<String>()
    val workingDir = property<String>()
    val args = mutableListOf<String>()
    init { alwaysRun(); topology { requiresCapability("ansible") } }
    override suspend fun buildInputs(context: ExecutionContext) = AnsibleCommandInputs(executable.get(), workingDir.orNull(), args.toList())
    override fun buildOutputs() = NoOutputs()
    override suspend fun execute(context: ExecutionContext, inputSet: AnsibleCommandInputs) = context.executeBash {
        command(inputSet.executable); args(inputSet.args); inputSet.workingDir?.let { workingDir = it }
    }
}

class AnsibleInventory(name: String) : AnsibleCommandTask(name) {
    init { executable.set("ansible-inventory"); args.addAll(listOf("--list", "--yaml")) }
}

class AnsiblePing(name: String) : AnsibleCommandTask(name) {
    init { executable.set("ansible"); args.addAll(listOf("all", "-m", "ping")) }
}

data class AnsibleCheckInputs(var workingDir: String?) : InputSet()

class AnsibleCheck(name: String) : Task<AnsibleCheckInputs, NoOutputs>(name) {
    val workingDir = property<String>()
    init { alwaysRun(); topology { requiresCapability("ansible") } }
    override suspend fun buildInputs(context: ExecutionContext) = AnsibleCheckInputs(workingDir.orNull())
    override fun buildOutputs() = NoOutputs()
    override suspend fun execute(context: ExecutionContext, inputSet: AnsibleCheckInputs): ExecutionResult<NoOutputs> {
        val playbook = context.executeBash { command("ansible-playbook"); args("--version"); inputSet.workingDir?.let { workingDir = it } }
        if (playbook is ExecutionResult.Failure) return playbook
        return context.executeBash { command("ansible-galaxy"); args("collection", "list"); inputSet.workingDir?.let { workingDir = it } }
    }
}
