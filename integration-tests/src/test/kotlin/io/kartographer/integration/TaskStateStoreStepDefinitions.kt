package io.kartographer.integration

import kotlinx.coroutines.runBlocking
import io.cucumber.java.en.Given
import io.cucumber.java.en.When
import io.cucumber.java.en.Then
import io.kartographer.core.model.*
import io.kartographer.core.scheduler.TaskStateStore
import io.kartographer.testing.ScriptedTask
import io.kartographer.daemon.project.KtsPipelineLoader
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class TaskStateStoreStepDefinitions {
    private var projectDir: Path? = null
    private val tasks = mutableMapOf<TaskId, Node>()
    private var executionContext: ExecutionContext? = null
    private var loadedState: io.kartographer.core.scheduler.TaskState? = null

    @Given("a temporary workspace directory")
    fun given_temp_workspace() {
        projectDir = Files.createTempDirectory("karto-state-test")
        executionContext = ExecutionContext(
            projectDir = projectDir!!,
            worker = Worker(WorkerId("test-worker"))
        )
    }

    @Given("we load task state script {string}")
    fun given_load_task_state(scriptPath: String) {
        val loader = KtsPipelineLoader()
        tasks.putAll(loader.load(resolveScriptPath(scriptPath)))
    }

    @Given("the input file {string} exists with content {string}")
    fun given_in_file(path: String, content: String) {
        val dir = projectDir ?: throw IllegalStateException("Workspace not initialized")
        val file = dir.resolve(path)
        Files.createDirectories(file.parent)
        Files.writeString(file, content)
    }

    @Given("the output file {string} exists with content {string}")
    fun given_out_file(path: String, content: String) {
        val dir = projectDir ?: throw IllegalStateException("Workspace not initialized")
        val file = dir.resolve(path)
        Files.createDirectories(file.parent)
        Files.writeString(file, content)
    }

    @When("we save the state of task {string}")
    fun when_save_state(name: String) {
        val task = tasks[TaskId(name)] as? Task<*, *> ?: throw IllegalArgumentException("Task $name not found")
        val context = executionContext ?: throw IllegalStateException("Context not initialized")
        runBlocking { TaskStateStore.saveState(context, task) }
    }

    @Then("the task state file should exist on disk")
    fun then_state_file_exists() {
        val context = executionContext ?: throw IllegalStateException("Context not initialized")
        val task = tasks.values.first() as Task<*, *>
        val stateFile = TaskStateStore.getStateFile(context, task)
        assertTrue(Files.exists(stateFile), "State file does not exist at $stateFile")
    }

    @Then("we can load the task state of task {string}")
    fun then_load_state(name: String) {
        val task = tasks[TaskId(name)] as? Task<*, *> ?: throw IllegalArgumentException("Task $name not found")
        val context = executionContext ?: throw IllegalStateException("Context not initialized")
        loadedState = TaskStateStore.loadState(context, task)
        assertNotNull(loadedState, "Loaded state was null")
    }

    @Then("the loaded task state input file {string} hash should match the current hash of {string}")
    fun then_in_hash_matches(fileStr: String, fileStr2: String) {
        val context = executionContext ?: throw IllegalStateException("Context not initialized")
        val state = loadedState ?: throw IllegalStateException("No loaded state")
        val currentHash = TaskStateStore.calculateHash(context.projectDir.resolve(fileStr2))
        assertEquals(currentHash, state.inputFiles[fileStr])
    }

    @Then("the loaded task state output file {string} hash should match the current hash of {string}")
    fun then_out_hash_matches(fileStr: String, fileStr2: String) {
        val context = executionContext ?: throw IllegalStateException("Context not initialized")
        val state = loadedState ?: throw IllegalStateException("No loaded state")
        val currentHash = TaskStateStore.calculateHash(context.projectDir.resolve(fileStr2))
        assertEquals(currentHash, state.outputFiles[fileStr])
    }
}
