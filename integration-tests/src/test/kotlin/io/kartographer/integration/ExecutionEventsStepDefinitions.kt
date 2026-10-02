package io.kartographer.integration

import io.cucumber.java.en.Given
import io.cucumber.java.en.When
import io.cucumber.java.en.Then
import io.kartographer.core.model.*
import io.kartographer.core.scheduler.ConcurrentScheduler
import io.kartographer.testing.ScriptedTask
import io.kartographer.daemon.project.KtsPipelineLoader
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ExecutionEventsStepDefinitions {
    private val tasks = mutableMapOf<TaskId, Node>()
    private val events = mutableListOf<String>()
    private var executionResult: ExecutionResult<*>? = null

    private val observer = object : ExecutionObserver {
        override fun onEvent(event: ExecutionEvent) {
            val eventName = when (event) {
                is ExecutionEvent.PipelineStarted -> "PipelineStarted"
                is ExecutionEvent.TaskQueued -> "TaskQueued"
                is ExecutionEvent.TaskStarted -> "TaskStarted"
                is ExecutionEvent.TaskReady -> "TaskReady"
                is ExecutionEvent.TaskStopped -> "TaskStopped"
                is ExecutionEvent.TaskOutput -> "TaskOutput"
                is ExecutionEvent.TaskOutputProduced -> "TaskOutputProduced"
                is ExecutionEvent.TaskCompleted -> "TaskCompleted"
                is ExecutionEvent.PipelineCompleted -> "PipelineCompleted"
                is ExecutionEvent.PromptRequested -> "PromptRequested"
                is ExecutionEvent.PromptCompleted -> "PromptCompleted"
            }
            events.add(eventName)
        }
    }

    @Given("we load events script {string}")
    fun given_load_events(scriptPath: String) {
        val loader = KtsPipelineLoader()
        tasks.putAll(loader.load(resolveScriptPath(scriptPath)))
    }

    @Given("an execution observer registered with the scheduler")
    fun given_observer() {
        events.clear()
    }

    @When("we execute the pipeline target {string} with the observer")
    fun when_execute_pipeline(targetName: String) = runBlocking {
        val target = tasks[TaskId(targetName)] ?: throw IllegalArgumentException("Task $targetName not found")
        val context = ExecutionContext(
            projectDir = Files.createTempDirectory("karto-event-test"),
            worker = Worker(WorkerId("test-worker")),
            observer = observer
        )
        val scheduler = ConcurrentScheduler(tasks)
        executionResult = scheduler.execute(target, context, { true })
    }

    @Then("the observer should receive the following events in order:")
    fun then_observer_events(expectedEvents: List<String>) {
        val actualSub = events.filter { it in expectedEvents }
        assertEquals(expectedEvents.size, actualSub.size, "Events mismatched. Actual: $events, Expected: $expectedEvents")
        for (i in expectedEvents.indices) {
            assertEquals(expectedEvents[i], actualSub[i])
        }
    }
}
