@file:Repository("https://repo1.maven.org/maven2/")
@file:DependsOn("com.github.ajalt.clikt:clikt-jvm:4.4.0")

import com.github.ajalt.clikt.core.*
import com.github.ajalt.clikt.parameters.arguments.*
import com.github.ajalt.clikt.parameters.options.*

class RunCmd : CliktCommand(name = "run", treatUnknownOptionsAsArgs = true) {
    val taskName by argument()
    val unknownArgs by argument().multiple()
    override fun run() {
        println("Task: $taskName")
        println("Args: $unknownArgs")
    }
}
RunCmd().main(arrayOf("myTask", "--version", "1.0"))
