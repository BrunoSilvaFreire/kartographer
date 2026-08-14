package io.kartographer.integration

import io.cucumber.java.en.Given
import io.cucumber.java.en.When
import io.cucumber.java.en.Then
import io.kartographer.core.model.*
import io.kartographer.tasks.containers.*
import io.kartographer.daemon.project.KtsPipelineLoader
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ContainerStepDefinitions {
    private val tasks = mutableMapOf<TaskId, Node>()
    private val capturedCommands = mutableListOf<String>()
    private var executionResult: ExecutionResult? = null
    private var projectDir: Path? = null

    private var mockExitCode = 0

    private val mockExecutionEnv = object : ExecutionEnvironment {
        override suspend fun execute(command: String, workingDir: String, environment: Map<String, String>, requiresElevation: Boolean): Int {
            capturedCommands.add(command)
            return mockExitCode
        }
    }

    @Given("we load container script {string}")
    fun given_load_container_script(scriptPath: String) {
        val loader = KtsPipelineLoader()
        tasks.putAll(loader.load(resolveScriptPath(scriptPath)))
        projectDir = Files.createTempDirectory("karto-container-test")
        mockExitCode = 0
    }

    @Given("we configure execution environment to fail on next commands")
    fun given_configure_fail() {
        mockExitCode = 1
    }

    @When("we dry-run the ContainerBuild task {string}")
    fun when_dry_run_build(name: String) = runBlocking {
        val task = tasks[TaskId(name)] as? ContainerBuild ?: throw IllegalArgumentException("Task $name not found")
        val context = ExecutionContext(
            projectDir = projectDir ?: Files.createTempDirectory("karto-container-test"),
            worker = Worker(WorkerId("test-worker")),
            executionEnvironment = mockExecutionEnv
        )
        capturedCommands.clear()
        executionResult = task.execute(context)
    }

    @When("we dry-run the ContainerPush task {string}")
    fun when_dry_run_push(name: String) = runBlocking {
        val task = tasks[TaskId(name)] as? ContainerPush ?: throw IllegalArgumentException("Task $name not found")
        val context = ExecutionContext(
            projectDir = projectDir ?: Files.createTempDirectory("karto-container-test"),
            worker = Worker(WorkerId("test-worker")),
            executionEnvironment = mockExecutionEnv
        )
        capturedCommands.clear()
        executionResult = task.execute(context)
    }

    @When("we dry-run the ContainerPush task {string} and expect failure")
    fun when_dry_run_push_fail(name: String) = runBlocking {
        val task = tasks[TaskId(name)] as? ContainerPush ?: throw IllegalArgumentException("Task $name not found")
        val context = ExecutionContext(
            projectDir = projectDir ?: Files.createTempDirectory("karto-container-test"),
            worker = Worker(WorkerId("test-worker")),
            executionEnvironment = mockExecutionEnv
        )
        capturedCommands.clear()
        executionResult = task.execute(context)
    }

    @Then("the executed container command should be {string}")
    fun then_container_command_is(expected: String) {
        val cmd = capturedCommands.firstOrNull()
        assertNotNull(cmd, "No command was generated")
        assertEquals(expected, cmd)
    }

    @Then("the executed container commands should be:")
    fun then_container_commands_are(expectedCmds: List<String>) {
        assertEquals(expectedCmds.size, capturedCommands.size)
        for (i in expectedCmds.indices) {
            assertEquals(expectedCmds[i], capturedCommands[i])
        }
    }

    @Then("the execution result should be failure")
    fun then_result_failure() {
        assertTrue(executionResult is ExecutionResult.Failure, "Expected Failure, got $executionResult")
    }

    @Then("the task {string} should require elevation")
    fun then_task_requires_elevation(name: String) {
        val task = tasks[TaskId(name)] as Task
        assertTrue(task.requiresElevation, "Expected task to require elevation")
    }

    @Then("the task {string} should not require elevation")
    fun then_task_not_requires_elevation(name: String) {
        val task = tasks[TaskId(name)] as Task
        kotlin.test.assertFalse(task.requiresElevation, "Expected task to not require elevation")
    }
}
