package io.kartographer.integration

import io.cucumber.java.en.Given
import io.cucumber.java.en.When
import io.cucumber.java.en.Then
import io.kartographer.core.model.*
import io.kartographer.daemon.project.KtsPipelineLoader
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FilesystemStepDefinitions {
    private var projectDir: Path? = null
    private val tasks = mutableMapOf<TaskId, Node>()
    private var executionResult: ExecutionResult? = null

    @Given("a temporary directory")
    fun given_temp_dir() {
        projectDir = Files.createTempDirectory("karto-fs-test")
    }

    @Given("a file {string} with content {string}")
    fun given_file_with_content(pathStr: String, content: String) {
        val dir = projectDir ?: throw IllegalStateException("Temp dir not initialized")
        val file = dir.resolve(pathStr)
        Files.createDirectories(file.parent)
        Files.writeString(file, content)
    }

    @Given("we load filesystem script {string}")
    fun given_load_fs_script(scriptPath: String) {
        val loader = KtsPipelineLoader()
        tasks.putAll(loader.load(resolveScriptPath(scriptPath)))
    }

    @When("we execute the filesystem task {string}")
    fun when_execute_fs_task(name: String) = runBlocking {
        val task = tasks[TaskId(name)] as? Task ?: throw IllegalArgumentException("Task $name not found")
        val dir = projectDir ?: throw IllegalStateException("Temp dir not initialized")
        val context = ExecutionContext(
            projectDir = dir,
            worker = Worker(WorkerId("test-worker"))
        )
        executionResult = task.execute(context)
    }

    @Then("the file {string} should exist with content {string}")
    fun then_file_exists_with_content(pathStr: String, content: String) {
        val dir = projectDir ?: throw IllegalStateException("Temp dir not initialized")
        val file = dir.resolve(pathStr)
        assertTrue(Files.exists(file), "File $pathStr does not exist")
        assertEquals(content, Files.readString(file).trim())
    }

    @Then("the file {string} should not exist")
    fun then_file_should_not_exist(pathStr: String) {
        val dir = projectDir ?: throw IllegalStateException("Temp dir not initialized")
        val file = dir.resolve(pathStr)
        assertFalse(Files.exists(file), "File $pathStr exists but should not")
    }

    @Then("the filesystem task execution should fail with IllegalArgumentException")
    fun then_fs_fails_illegal_arg() {
        val res = executionResult
        assertTrue(res is ExecutionResult.Failure, "Expected failure but got $res")
        assertTrue(res.error is IllegalArgumentException, "Expected IllegalArgumentException but got ${res.error}")
    }

    @Then("the filesystem pipeline execution should succeed")
    fun then_fs_pipeline_succeeds() {
        assertTrue(executionResult is ExecutionResult.Success, "Expected Success but got $executionResult")
    }

    @Given("a write-protected directory {string}")
    fun given_protected_dir(dirName: String) {
        val dir = projectDir ?: throw IllegalStateException("Temp dir not initialized")
        val protectedDir = dir.resolve(dirName)
        Files.createDirectories(protectedDir)
        protectedDir.toFile().setWritable(false)
    }

    @Then("the filesystem task execution should fail")
    fun then_fs_task_fails() {
        val res = executionResult
        projectDir?.toFile()?.walk()?.forEach { it.setWritable(true) }
        assertTrue(res is ExecutionResult.Failure, "Expected failure but got $res")
    }
}
