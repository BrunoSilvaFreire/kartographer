package io.kartographer.integration

import io.cucumber.java.en.Given
import io.cucumber.java.en.When
import io.cucumber.java.en.Then
import io.kartographer.core.model.*
import io.kartographer.tasks.pulumi.*
import io.kartographer.daemon.project.KtsPipelineLoader
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class PulumiStepDefinitions {
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

    @Given("we load pulumi script {string}")
    fun given_load_pulumi(scriptPath: String) {
        val loader = KtsPipelineLoader()
        tasks.putAll(loader.load(resolveScriptPath(scriptPath)))
        projectDir = Files.createTempDirectory("karto-pulumi-test")
    }

    @When("we dry-run the PulumiTask {string}")
    fun when_dry_run_pulumi_task(name: String) = runBlocking {
        val task = tasks[TaskId(name)] as? PulumiTask ?: throw IllegalArgumentException("Task $name not found")
        val context = ExecutionContext(
            projectDir = projectDir ?: Files.createTempDirectory("karto-pulumi-test"),
            worker = Worker(WorkerId("test-worker"), capabilities = setOf("pulumi")),
            executionEnvironment = mockExecutionEnv
        )
        capturedCommands.clear()
        executionResult = task.execute(context)
    }

    @Then("the executed pulumi command should be {string}")
    fun then_pulumi_command_is(expected: String) {
        val cmd = capturedCommands.firstOrNull()
        assertNotNull(cmd, "No command was generated")
        assertEquals(expected, cmd)
    }
}
