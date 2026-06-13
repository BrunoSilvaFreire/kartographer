package io.kartographer.integration

import io.cucumber.java.en.Given
import io.cucumber.java.en.When
import io.cucumber.java.en.Then
import io.kartographer.core.*
import io.kartographer.core.scheduler.ConcurrentScheduler
import io.kartographer.tasks.go.GoBuild
import io.kartographer.tasks.proto.ProtoCompile
import io.kartographer.tasks.gradle.GradleTask
import io.kartographer.tasks.ExecTask
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.FileTime
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class StepDefinitions {
    private val actionLog = mutableListOf<String>()
    private val tasks = mutableMapOf<String, Task>()
    private var executionResult: ExecutionResult? = null
    private var worker: Worker? = null
    private var projectDir: Path? = null
    private var executionTimeMs: Long = 0
    private val customPropertyValues = mutableMapOf<String, String>()

    // Mock Tasks for execution simulations
    inner class TestDefaultTask(
        name: String,
        private val delayMs: Long = 0,
        private val fail: Boolean = false
    ) : Task(name) {
        override suspend fun execute(context: ExecutionContext): ExecutionResult {
            if (fail) {
                return ExecutionResult.Failure(RuntimeException("Task '$name' failed by request"))
            }
            if (delayMs > 0) {
                kotlinx.coroutines.delay(delayMs)
            }
            actionLog.add(name)
            return ExecutionResult.Success
        }
    }

    inner class TestCopyTask(name: String, val src: String, val dest: String) : Task(name) {
        override suspend fun execute(context: ExecutionContext): ExecutionResult {
            actionLog.add(name)
            val srcFile = context.projectDir.resolve(src)
            val destFile = context.projectDir.resolve(dest)
            Files.createDirectories(destFile.parent)
            Files.writeString(destFile, Files.readString(srcFile))
            return ExecutionResult.Success
        }
    }

    inner class TestPropTask(name: String, val propName: String) : Task(name) {
        init {
            trackProperty(propName) { customPropertyValues[propName] ?: "" }
        }
        override suspend fun execute(context: ExecutionContext): ExecutionResult {
            actionLog.add(name)
            return ExecutionResult.Success
        }
    }

    // Step Definitions for pipeline_execution.feature
    @Given("a task named {string} that performs no-op")
    fun given_task_noop(name: String) {
        tasks[name] = TestDefaultTask(name)
    }

    @Given("a task named {string} that depends on {string}")
    fun given_task_depends(name: String, dependency: String) {
        val task = tasks.getOrPut(name) { TestDefaultTask(name) }
        val depTask = tasks.getOrPut(dependency) { TestDefaultTask(dependency) }
        task.dependsOn(depTask)
    }

    @Given("a task named {string} that depends on {string} and {string}")
    fun given_task_depends_two(name: String, dep1: String, dep2: String) {
        val task = tasks.getOrPut(name) { TestDefaultTask(name) }
        val d1 = tasks.getOrPut(dep1) { TestDefaultTask(dep1) }
        val d2 = tasks.getOrPut(dep2) { TestDefaultTask(dep2) }
        task.dependsOn(d1, d2)
    }

    @Given("a task named {string} that takes {int} milliseconds")
    fun given_task_delay(name: String, delay: Int) {
        tasks[name] = TestDefaultTask(name, delayMs = delay.toLong())
    }

    @Given("a task named {string} that fails with an error")
    fun given_task_fails(name: String) {
        tasks[name] = TestDefaultTask(name, fail = true)
    }

    @When("we execute the pipeline target {string}")
    fun when_execute_pipeline(targetName: String) = runBlocking {
        actionLog.clear()
        val target = tasks[targetName] ?: throw IllegalArgumentException("Task $targetName not found")
        val activeWorker = worker ?: Worker(WorkerId("default-worker"))
        val activeProjDir = projectDir ?: Files.createTempDirectory("kartographer-cucumber")
        
        val context = ExecutionContext(
            projectDir = activeProjDir,
            worker = activeWorker
        )

        val scheduler = ConcurrentScheduler(tasks.mapKeys { it.value.id })
        val start = System.currentTimeMillis()
        executionResult = scheduler.execute(
            targetTask = target,
            context = context,
            checkTopology = { task ->
                task.topologyRequirements.all { req ->
                    when (req) {
                        is TopologyRequirement.LocalOnly -> true
                        is TopologyRequirement.RequiresLabel -> activeWorker.labels[req.key] == req.value
                        is TopologyRequirement.RequiresCapability -> activeWorker.capabilities.contains(req.capability)
                        is TopologyRequirement.PrefersLabel -> true
                    }
                }
            },
            onTaskStart = {},
            onTaskSuccess = {},
            onTaskFailure = { _, _, _ -> }
        )
        executionTimeMs = System.currentTimeMillis() - start
    }

    @Then("the task {string} should be executed before {string}")
    fun then_executed_before(first: String, second: String) {
        val idx1 = actionLog.indexOf(first)
        val idx2 = actionLog.indexOf(second)
        assertTrue(idx1 >= 0, "$first was not executed")
        assertTrue(idx2 >= 0, "$second was not executed")
        assertTrue(idx1 < idx2, "$first was not executed before $second")
    }

    @Then("the pipeline execution should succeed")
    fun then_pipeline_succeeds() {
        assertTrue(executionResult is ExecutionResult.Success, "Expected pipeline success but got $executionResult")
    }

    @Then("the execution should take less than {int} milliseconds")
    fun then_execution_time_limit(limit: Int) {
        assertTrue(executionTimeMs < limit, "Execution took $executionTimeMs ms, which is not less than $limit ms")
    }

    @Then("all tasks should complete successfully")
    fun then_all_tasks_succeed() {
        assertTrue(executionResult is ExecutionResult.Success)
    }

    @Then("the pipeline execution should fail")
    fun then_pipeline_fails() {
        assertTrue(executionResult is ExecutionResult.Failure, "Expected pipeline failure but got $executionResult")
    }

    @Then("the task {string} should report a failure")
    fun then_task_reports_failure(name: String) {
        val res = executionResult
        assertTrue(res is ExecutionResult.Failure, "Result is not Failure for task $name")
    }

    // Step Definitions for uptodate_system.feature
    @Given("a temporary test directory")
    fun given_temp_directory() {
        projectDir = Files.createTempDirectory("kartographer-cucumber-io")
    }

    @Given("an input file {string} with content {string}")
    fun given_input_file(fileName: String, content: String) {
        val dir = projectDir ?: throw IllegalStateException("Temp dir not initialized")
        val file = dir.resolve(fileName)
        Files.createDirectories(file.parent)
        Files.writeString(file, content)
    }

    @Given("an output file {string} with content {string}")
    fun given_output_file(fileName: String, content: String) {
        val dir = projectDir ?: throw IllegalStateException("Temp dir not initialized")
        val file = dir.resolve(fileName)
        Files.createDirectories(file.parent)
        Files.writeString(file, content)
    }

    @Given("a task named {string} that copies {string} to {string}")
    fun given_task_copies(name: String, src: String, dest: String) {
        tasks[name] = TestCopyTask(name, src, dest)
    }

    @Given("a task named {string} that copy-syncs {string} to {string}")
    fun given_task_copy_sync(name: String, src: String, dest: String) {
        tasks[name] = TestCopyTask(name, src, dest)
    }

    @Given("the task {string} defines {string} as input and {string} as output")
    fun given_task_defines_in_out(name: String, input: String, output: String) {
        val task = tasks[name] ?: throw IllegalArgumentException("Task $name not found")
        task.inputs.file(input)
        task.outputs.file(output)
    }

    @Given("the task {string} has input {string} and output {string}")
    fun given_task_has_in_out(name: String, input: String, output: String) {
        val task = tasks[name] ?: throw IllegalArgumentException("Task $name not found")
        task.inputs.file(input)
        task.outputs.file(output)
    }

    @Given("the task {string} uses the inputs newer than outputs check")
    fun given_task_uses_timestamp_check(name: String) {
        val task = tasks[name] ?: throw IllegalArgumentException("Task $name not found")
        task.upToDateWhenInputsNewerThanOutputs()
    }

    @When("we execute the pipeline target {string} again")
    fun when_execute_again(name: String) {
        // Run execution again
        when_execute_pipeline(name)
    }

    @Then("the task {string} should be executed")
    fun then_task_executed(name: String) {
        assertTrue(actionLog.contains(name), "Task $name was not executed")
    }

    @Then("the task {string} should be skipped as up-to-date")
    fun then_task_skipped(name: String) {
        assertTrue(!actionLog.contains(name), "Task $name was executed but should have been skipped")
    }

    @When("we modify the input file {string} to {string}")
    fun when_modify_input_file(fileName: String, content: String) {
        val dir = projectDir ?: throw IllegalStateException("Temp dir not initialized")
        Files.writeString(dir.resolve(fileName), content)
    }

    @When("the input file {string} modification time is set to {long}")
    fun when_set_input_time(fileName: String, millis: Long) {
        val dir = projectDir ?: throw IllegalStateException("Temp dir not initialized")
        Files.setLastModifiedTime(dir.resolve(fileName), FileTime.fromMillis(millis))
    }

    @When("the output file {string} modification time is set to {long}")
    fun when_set_output_time(fileName: String, millis: Long) {
        val dir = projectDir ?: throw IllegalStateException("Temp dir not initialized")
        Files.setLastModifiedTime(dir.resolve(fileName), FileTime.fromMillis(millis))
    }

    @Given("a task named {string} that tracks a custom property {string} with value {string}")
    fun given_task_tracks_prop(name: String, propName: String, value: String) {
        customPropertyValues[propName] = value
        tasks[name] = TestPropTask(name, propName)
    }

    @When("we change the tracked custom property {string} value to {string}")
    fun when_change_prop_value(propName: String, value: String) {
        customPropertyValues[propName] = value
    }

    // Step Definitions for topology_requirements.feature
    @Given("a worker with capabilities {string}")
    fun given_worker_capabilities(caps: String) {
        val capabilities = caps.split(",").map { it.trim() }.toSet()
        worker = Worker(WorkerId("custom-worker"), capabilities = capabilities)
    }

    @Given("a task named {string} requiring capability {string}")
    fun given_task_requires_capability(name: String, cap: String) {
        val task = tasks.getOrPut(name) { TestDefaultTask(name) }
        task.topology {
            requiresCapability(cap)
        }
    }

    @When("we execute the pipeline target {string} with the worker")
    fun when_execute_with_worker(name: String) {
        when_execute_pipeline(name)
    }

    @Then("the task {string} should be executed successfully")
    fun then_task_executed_success(name: String) {
        assertTrue(actionLog.contains(name))
        assertTrue(executionResult is ExecutionResult.Success)
    }

    @Then("the task {string} should report a capability failure")
    fun then_task_reports_capability_failure(name: String) {
        val res = executionResult
        assertTrue(res is ExecutionResult.Failure, "Expected failure for task $name")
        assertTrue(res.error.message?.contains("topology requirements not satisfied") == true, "Expected topology failure for task $name but got: ${res.error.message}")
    }

    @Given("a worker with labels {string}")
    fun given_worker_labels(labelsStr: String) {
        val labels = labelsStr.split(",").map { it.trim() }.associate {
            val parts = it.split(":")
            parts[0] to parts[1]
        }
        worker = Worker(WorkerId("custom-worker"), labels = labels)
    }

    @Given("a task named {string} requiring label {string} with value {string}")
    fun given_task_requires_label(name: String, key: String, value: String) {
        val task = tasks.getOrPut(name) { TestDefaultTask(name) }
        task.topology {
            requires(key, value)
        }
    }

    // Step Definitions for task_libraries.feature
    @Given("a GoBuild task named {string} with output {string} and mainFile {string}")
    fun given_gobuild_task(name: String, output: String, mainFile: String) {
        val task = GoBuild(name)
        task.output = output
        task.mainFile = mainFile
        tasks[name] = task
    }

    @When("we dry-run the GoBuild task {string}")
    fun when_dry_run_gobuild(name: String) = runBlocking {
        val task = tasks[name] as GoBuild
        val context = ExecutionContext(
            projectDir = projectDir ?: Files.createTempDirectory("kartographer-cucumber-dry"),
            worker = Worker(WorkerId("test-worker"), capabilities = setOf("go")),
            dryRun = true
        )
        executionResult = task.execute(context)
    }

    @Then("the executed command should start with {string}")
    fun then_command_starts_with(expectedStart: String) {
        val result = executionResult
        assertTrue(result is ExecutionResult.Success)
        val task = tasks.values.first() as ExecTask
        val cmd = task.commands.firstOrNull()
        assertNotNull(cmd, "No command was generated")
        assertTrue(cmd.startsWith(expectedStart), "Command '$cmd' did not start with '$expectedStart'")
    }

    @Given("a ProtoCompile task named {string} with protoPath {string}, goOut {string}, and files {string}")
    fun given_protocompile_task(name: String, protoPath: String, goOut: String, files: String) {
        val task = ProtoCompile(name)
        task.protoPath = protoPath
        task.goOut = goOut
        task.protoFiles.addAll(files.split(",").map { it.trim() })
        tasks[name] = task
    }

    @When("we dry-run the ProtoCompile task {string}")
    fun when_dry_run_proto(name: String) = runBlocking {
        val task = tasks[name] as ProtoCompile
        val context = ExecutionContext(
            projectDir = projectDir ?: Files.createTempDirectory("kartographer-cucumber-dry"),
            worker = Worker(WorkerId("test-worker"), capabilities = setOf("protoc")),
            dryRun = true
        )
        executionResult = task.execute(context)
    }

    @Then("the executed command should end with {string}")
    fun then_command_ends_with(expectedEnd: String) {
        val task = tasks.values.first() as ExecTask
        val cmd = task.commands.firstOrNull()
        assertNotNull(cmd, "No command was generated")
        assertTrue(cmd.endsWith(expectedEnd), "Command '$cmd' did not end with '$expectedEnd'")
    }

    @Given("a GradleTask named {string} with buildFile {string}, projectDir {string}, using wrapper, and tasks {string}")
    fun given_gradletask(name: String, buildFile: String, projectDirName: String, tasksStr: String) {
        val task = GradleTask(name)
        task.buildFile = buildFile
        task.projectDir = projectDirName
        task.useWrapper = true
        task.tasks.addAll(tasksStr.split(",").map { it.trim() })
        tasks[name] = task
    }

    @When("we dry-run the GradleTask {string}")
    fun when_dry_run_gradle(name: String) = runBlocking {
        val task = tasks[name] as GradleTask
        val context = ExecutionContext(
            projectDir = projectDir ?: Files.createTempDirectory("kartographer-cucumber-dry"),
            worker = Worker(WorkerId("test-worker")),
            dryRun = true
        )
        executionResult = task.execute(context)
    }
}
