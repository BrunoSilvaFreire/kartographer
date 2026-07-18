package io.kartographer.integration

import io.cucumber.java.en.Given
import io.cucumber.java.en.When
import io.cucumber.java.en.Then
import io.kartographer.core.model.*
import io.kartographer.tasks.python.PythonExecTask
import io.kartographer.cli.KtsPipelineLoader
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class PythonStepDefinitions {
    private val tasks = mutableMapOf<TaskId, Node>()
    private val capturedCommands = mutableListOf<String>()
    private var executionResult: ExecutionResult? = null
    private var projectDir: Path? = null

    private val mockExecutionEnv = object : ExecutionEnvironment {
        override suspend fun execute(command: String, workingDir: String, environment: Map<String, String>, requiresElevation: Boolean): Int {
            capturedCommands.add(command)
            return 0
        }
    }

    @Given("we load python script {string}")
    fun given_load_py_script(scriptPath: String) {
        val loader = KtsPipelineLoader()
        tasks.putAll(loader.load(resolveScriptPath(scriptPath)))
        projectDir = Files.createTempDirectory("karto-py-test")
    }

    @Given("we set the python arguments to {string}")
    fun given_py_args(argsStr: String) {
        // Set args for both PythonExecTasks
        for (node in tasks.values) {
            if (node is PythonExecTask) {
                node.args.clear()
                node.args.addAll(argsStr.split(",").map { it.trim() })
            }
        }
    }

    @When("we dry-run the PythonExecTask {string}")
    fun when_dry_run_py(name: String) = runBlocking {
        val task = tasks[TaskId(name)] as? PythonExecTask ?: throw IllegalArgumentException("Task $name not found")
        val context = ExecutionContext(
            projectDir = projectDir ?: Files.createTempDirectory("karto-py-test"),
            worker = Worker(WorkerId("test-worker"), capabilities = setOf("python")),
            executionEnvironment = mockExecutionEnv
        )
        capturedCommands.clear()
        executionResult = task.execute(context)
    }

    @Then("the executed python command should start with {string}")
    fun then_command_starts_with(expectedStart: String) {
        val cmd = capturedCommands.firstOrNull()
        assertNotNull(cmd, "No command was generated")
        assertTrue(cmd.startsWith(expectedStart), "Command '$cmd' did not start with '$expectedStart'")
    }
}
