package io.kartographer.integration

import io.cucumber.java.en.Given
import io.cucumber.java.en.When
import io.cucumber.java.en.Then
import io.kartographer.core.model.*
import io.kartographer.testing.ScriptedTask
import io.kartographer.daemon.project.KtsPipelineLoader
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ExecSpecValidationStepDefinitions {
    private val tasks = mutableMapOf<TaskId, Node>()
    private val capturedCommands = mutableListOf<String>()
    private val capturedEnv = mutableMapOf<String, String>()
    private var capturedElevation: Boolean = false
    private var executionResult: ExecutionResult<*>? = null
    private val contextEnv = mutableMapOf<String, String>()
    private var projectDir: Path? = null

    private val mockExecutionEnv = object : ExecutionEnvironment {
        override suspend fun execute(command: String, workingDir: String, environment: Map<String, String>, requiresElevation: Boolean): Int {
            capturedCommands.add(command)
            capturedEnv.clear()
            capturedEnv.putAll(environment)
            capturedElevation = requiresElevation
            return 0
        }
    }

    @Given("we load escaping script {string}")
    fun given_load_escaping(scriptPath: String) {
        val loader = KtsPipelineLoader()
        tasks.putAll(loader.load(resolveScriptPath(scriptPath)))
        projectDir = Files.createTempDirectory("karto-escaping-test")
    }

    @Given("we configure task {string} with args:")
    fun given_configure_task_args(name: String, argsTable: List<String>) {
        val task = tasks[TaskId(name)] as? ScriptedTask ?: throw IllegalArgumentException("Task $name not found")
        task.action { ctx ->
            ctx.executeBash {
                command("echo")
                args(argsTable)
            }
        }
    }

    @Given("we configure task {string} with env {string} set to {string}")
    fun given_configure_task_env(name: String, key: String, value: String) {
        val task = tasks[TaskId(name)] as? ScriptedTask ?: throw IllegalArgumentException("Task $name not found")
        task.action { ctx ->
            ctx.executeBash {
                command("echo")
                environment(key, value)
            }
        }
    }

    @Given("we configure task {string} to require elevation")
    fun given_configure_task_elevation(name: String) {
        val task = tasks[TaskId(name)] as? ScriptedTask ?: throw IllegalArgumentException("Task $name not found")
        task.action { ctx ->
            ctx.executeBash {
                requiresElevation = true
                command("apt-get update")
            }
        }
    }

    @Given("the execution context env has {string} set to {string}")
    fun given_context_env(key: String, value: String) {
        contextEnv[key] = value
    }

    @When("we dry-run the custom task {string}")
    fun when_dry_run_custom(name: String) = runBlocking {
        val task = tasks[TaskId(name)] as? ScriptedTask ?: throw IllegalArgumentException("Task $name not found")
        val context = ExecutionContext(
            projectDir = projectDir ?: Files.createTempDirectory("karto-escaping-test"),
            worker = Worker(WorkerId("test-worker")),
            environment = contextEnv,
            executionEnvironment = mockExecutionEnv
        )
        capturedCommands.clear()
        executionResult = task.execute(context)
    }

    @Then("the executed command line should be:")
    fun then_command_is(expected: String) {
        val cmd = capturedCommands.firstOrNull()
        assertNotNull(cmd, "No command was generated")
        assertEquals(expected.trim(), cmd.trim())
    }

    @Then("the command execution environment should contain {string} with {string}")
    fun then_env_contains(key: String, value: String) {
        assertEquals(value, capturedEnv[key])
    }

    @Then("the task execution should request elevation")
    fun then_requires_elevation() {
        assertTrue(capturedElevation, "Expected elevation to be requested")
    }

    @Given("we do not configure any action for task {string}")
    fun given_do_not_configure_action(name: String) {
        // By default tasks from escaping/karto.kts have no action configured in script.
    }

    @Given("we configure task {string} to throw an exception")
    fun given_configure_task_throws(name: String) {
        val task = tasks[TaskId(name)] as? ScriptedTask ?: throw IllegalArgumentException("Task $name not found")
        task.action {
            throw RuntimeException("Simulated exception")
        }
    }

    @Then("the task execution should succeed")
    fun then_task_succeeds() {
        assertTrue(executionResult is ExecutionResult.Success<*>, "Expected Success but got $executionResult")
    }

    @Then("the custom task execution should fail with RuntimeException")
    fun then_custom_task_fails_runtime() {
        val res = executionResult
        assertTrue(res is ExecutionResult.Failure<*>, "Expected failure but got $res")
        assertTrue(res.error is RuntimeException, "Expected RuntimeException but got ${res.error}")
        assertEquals("Simulated exception", res.error.message)
    }
}
