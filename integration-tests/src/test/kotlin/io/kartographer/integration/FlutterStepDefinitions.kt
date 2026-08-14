package io.kartographer.integration

import io.cucumber.java.en.Given
import io.cucumber.java.en.When
import io.cucumber.java.en.Then
import io.kartographer.core.model.*
import io.kartographer.tasks.flutter.*
import io.kartographer.daemon.project.KtsPipelineLoader
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class FlutterStepDefinitions {
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

    @Given("we load flutter script {string}")
    fun given_load_flutter(scriptPath: String) {
        val loader = KtsPipelineLoader()
        tasks.putAll(loader.load(resolveScriptPath(scriptPath)))
        projectDir = Files.createTempDirectory("karto-flutter-test")
    }

    @When("we dry-run the FlutterRun task {string}")
    fun when_dry_run_run(name: String) = runBlocking {
        val task = tasks[TaskId(name)] as? FlutterRun ?: throw IllegalArgumentException("Task $name not found")
        val context = ExecutionContext(
            projectDir = projectDir ?: Files.createTempDirectory("karto-flutter-test"),
            worker = Worker(WorkerId("test-worker"), capabilities = setOf("flutter")),
            executionEnvironment = mockExecutionEnv
        )
        capturedCommands.clear()
        executionResult = task.execute(context)
    }

    @When("we dry-run the FlutterBuild task {string}")
    fun when_dry_run_build(name: String) = runBlocking {
        val task = tasks[TaskId(name)] as? FlutterBuild ?: throw IllegalArgumentException("Task $name not found")
        val context = ExecutionContext(
            projectDir = projectDir ?: Files.createTempDirectory("karto-flutter-test"),
            worker = Worker(WorkerId("test-worker"), capabilities = setOf("flutter")),
            executionEnvironment = mockExecutionEnv
        )
        capturedCommands.clear()
        executionResult = task.execute(context)
    }

    @When("we dry-run the FlutterTest task {string}")
    fun when_dry_run_test(name: String) = runBlocking {
        val task = tasks[TaskId(name)] as? FlutterTest ?: throw IllegalArgumentException("Task $name not found")
        val context = ExecutionContext(
            projectDir = projectDir ?: Files.createTempDirectory("karto-flutter-test"),
            worker = Worker(WorkerId("test-worker"), capabilities = setOf("flutter")),
            executionEnvironment = mockExecutionEnv
        )
        capturedCommands.clear()
        executionResult = task.execute(context)
    }

    @Then("the executed flutter command should start with {string}")
    fun then_flutter_command_starts(expectedStart: String) {
        val cmd = capturedCommands.firstOrNull()
        assertNotNull(cmd, "No command was generated")
        assertTrue(cmd.startsWith(expectedStart), "Command '$cmd' did not start with '$expectedStart'")
    }
}
