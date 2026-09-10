package io.kartographer.daemon.run

import io.kartographer.daemon.proto.queryRunsRequest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class RunTrackerTest {

    @Test
    fun `tracks active run lifecycle, tasks and live outputs`() {
        val tracker = RunTracker()

        val run = tracker.onRunStarted("run-1", "/test/project", "flutter:run")
        assertEquals("RUNNING", run.state)

        tracker.onTaskQueued("run-1", "flutter:run", "flutter:run")
        tracker.onTaskStarted("run-1", "flutter:run", "flutter:run")
        tracker.onTaskOutputProduced("run-1", "flutter:run", "flutter:run", "debugEndpoint", "ws://127.0.0.1:41234/ws", isSecret = false)
        tracker.onTaskOutputProduced("run-1", "flutter:run", "flutter:run", "token", "secret123", isSecret = true)

        val activeQuery = tracker.queryRuns(queryRunsRequest { activeOnly = true })
        assertEquals(1, activeQuery.runsCount)
        val activeRun = activeQuery.getRuns(0)
        assertEquals("run-1", activeRun.runId)
        assertEquals("flutter:run", activeRun.targetTask)
        assertEquals("RUNNING", activeRun.state)
        assertEquals(1, activeRun.tasksCount)

        val taskInfo = activeRun.getTasks(0)
        assertEquals("flutter:run", taskInfo.taskName)
        assertEquals("RUNNING", taskInfo.state)
        assertEquals("ws://127.0.0.1:41234/ws", taskInfo.outputsMap["debugEndpoint"])
        assertEquals("***", taskInfo.outputsMap["token"])

        // Complete task and run
        tracker.onTaskCompleted("run-1", "flutter:run", "flutter:run", "COMPLETED", mapOf("debugEndpoint" to "ws://127.0.0.1:41234/ws"))
        tracker.onRunCompleted("run-1", "COMPLETED")

        val afterActiveQuery = tracker.queryRuns(queryRunsRequest { activeOnly = true })
        assertEquals(0, afterActiveQuery.runsCount)

        val allQuery = tracker.queryRuns(queryRunsRequest { activeOnly = false })
        assertEquals(1, allQuery.runsCount)
        assertEquals("COMPLETED", allQuery.getRuns(0).state)
    }

    @Test
    fun `filters runs by project directory`() {
        val tracker = RunTracker()
        tracker.onRunStarted("run-1", "/project/a", "build")
        tracker.onRunStarted("run-2", "/project/b", "test")

        val queryA = tracker.queryRuns(queryRunsRequest { projectDir = "/project/a" })
        assertEquals(1, queryA.runsCount)
        assertEquals("run-1", queryA.getRuns(0).runId)

        val queryB = tracker.queryRuns(queryRunsRequest { projectDir = "/project/b" })
        assertEquals(1, queryB.runsCount)
        assertEquals("run-2", queryB.getRuns(0).runId)
    }
}
