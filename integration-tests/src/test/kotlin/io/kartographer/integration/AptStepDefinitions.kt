package io.kartographer.integration

import io.cucumber.java.en.Given
import io.cucumber.java.en.When
import io.cucumber.java.en.Then
import io.kartographer.core.model.*
import io.kartographer.tasks.apt.*
import io.kartographer.daemon.project.KtsPipelineLoader
import io.kartographer.core.scheduler.ConcurrentScheduler
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class AptStepDefinitions {
    private var projectDir: Path? = null
    private val tasks = mutableMapOf<TaskId, Node>()
    private val capturedCommands = mutableListOf<String>()
    private var executionResult: ExecutionResult<*>? = null

    private val mockExecutionEnv = object : ExecutionEnvironment {
        override suspend fun execute(command: String, workingDir: String, environment: Map<String, String>, requiresElevation: Boolean): Int {
            capturedCommands.add(command)
            return 0
        }
    }

    @Given("a temporary directory for packaging")
    fun given_temp_dir() {
        projectDir = Files.createTempDirectory("karto-apt-test")
    }

    @Given("a package file {string} with content {string}")
    fun given_file(pathStr: String, content: String) {
        val dir = projectDir ?: throw IllegalStateException("Temp dir not initialized")
        val file = dir.resolve(pathStr)
        Files.createDirectories(file.parent)
        Files.writeString(file, content)
    }

    @Given("we load apt script {string}")
    fun given_load_apt(scriptPath: String) {
        val loader = KtsPipelineLoader()
        tasks.putAll(loader.load(resolveScriptPath(scriptPath)))
    }

    @When("we dry-run the AptPackage task {string}")
    fun when_dry_run_pkg(name: String) = runBlocking {
        val task = tasks[TaskId(name)] as? AptPackage ?: throw IllegalArgumentException("Task $name not found")
        val context = ExecutionContext(
            projectDir = projectDir ?: Files.createTempDirectory("karto-apt-test"),
            worker = Worker(WorkerId("test-worker")),
            executionEnvironment = mockExecutionEnv
        )
        capturedCommands.clear()
        executionResult = ConcurrentScheduler(tasks).execute(task, context, checkTopology = { true })
    }

    @When("we dry-run the AptInstall task {string}")
    fun when_dry_run_install(name: String) = runBlocking {
        val task = tasks[TaskId(name)] as? AptInstall ?: throw IllegalArgumentException("Task $name not found")
        val context = ExecutionContext(
            projectDir = projectDir ?: Files.createTempDirectory("karto-apt-test"),
            worker = Worker(WorkerId("test-worker")),
            executionEnvironment = mockExecutionEnv
        )
        capturedCommands.clear()
        executionResult = ConcurrentScheduler(tasks).execute(task, context, checkTopology = { true })
    }

    @When("we dry-run the AptInstall task {string} and expect failure")
    fun when_dry_run_install_fail(name: String) = runBlocking {
        val task = tasks[TaskId(name)] as? AptInstall ?: throw IllegalArgumentException("Task $name not found")
        val context = ExecutionContext(
            projectDir = projectDir ?: Files.createTempDirectory("karto-apt-test"),
            worker = Worker(WorkerId("test-worker")),
            executionEnvironment = mockExecutionEnv
        )
        capturedCommands.clear()
        executionResult = ConcurrentScheduler(tasks).execute(task, context, checkTopology = { true })
    }

    @Then("the executed apt command should start with {string}")
    fun then_apt_command_starts(expectedStart: String) {
        val cmd = capturedCommands.firstOrNull()
        assertNotNull(cmd, "No command was generated")
        assertTrue(cmd.startsWith(expectedStart), "Command '$cmd' did not start with '$expectedStart'")
    }

    @Then("the executed apt command should end with {string}")
    fun then_apt_command_ends(expectedEnd: String) {
        assertTrue(capturedCommands.any { it.contains(expectedEnd) }, "No command contained '$expectedEnd': $capturedCommands")
    }

    @Then("the apt task execution should fail with IllegalArgumentException")
    fun then_apt_fails_illegal_arg() {
        val res = executionResult
        assertTrue(res is ExecutionResult.Failure<*>, "Expected failure but got $res")
        assertTrue(res.error is IllegalArgumentException, "Expected IllegalArgumentException but got ${res.error}")
    }
}
