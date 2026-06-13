package io.kartographer

import io.kartographer.tasks.DefaultTask
import io.kartographer.tasks.ExecTask
import io.kartographer.core.ExecutionContext
import io.kartographer.core.ExecutionResult
import io.kartographer.core.TopologyRequirement
import io.kartographer.core.Worker
import io.kartographer.core.WorkerId
import io.kartographer.core.scheduler.CycleDetectedException
import io.kartographer.core.scheduler.DagScheduler
import io.kartographer.core.scheduler.MissingDependencyException
import io.kartographer.dsl.task
import io.kartographer.dsl.TaskRegistry
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.nio.file.Paths

class KartographerTest {

    @BeforeEach
    fun setup() {
        TaskRegistry.clear()
    }

    @Test
    fun testTopologicalOrdering() {
        val c = task<DefaultTask>("C") { }
        val b = task<DefaultTask>("B") {
            dependsOn(c)
        }
        val a = task<DefaultTask>("A") {
            dependsOn(b)
        }

        val scheduler = DagScheduler(TaskRegistry.tasks)
        val order = scheduler.getExecutionOrder()

        assertEquals(3, order.size)
        assertEquals(c.id, order[0].id)
        assertEquals(b.id, order[1].id)
        assertEquals(a.id, order[2].id)
    }

    @Test
    fun testCycleDetection() {
        val a = task<DefaultTask>("A") { }
        val b = task<DefaultTask>("B") {
            dependsOn(a)
        }
        // Force cycle B -> A -> B
        a.dependsOn(b)

        val scheduler = DagScheduler(TaskRegistry.tasks)
        val exception = assertThrows(CycleDetectedException::class.java) {
            scheduler.getExecutionOrder()
        }
        assertTrue(exception.message!!.contains("Cycle detected"))
    }

    @Test
    fun testMissingDependencyDetection() {
        task<DefaultTask>("A") {
            dependsOn("nonexistent")
        }

        val scheduler = DagScheduler(TaskRegistry.tasks)
        val exception = assertThrows(MissingDependencyException::class.java) {
            scheduler.getExecutionOrder()
        }
        assertTrue(exception.message!!.contains("nonexistent"))
    }

    @Test
    fun testLocalCommandSuccess() = runBlocking {
        val task = task<ExecTask>("successTask") {
            local {
                exec("echo 'Success'")
            }
        }

        val worker = Worker(WorkerId("test-worker"))
        val context = ExecutionContext(
            projectDir = Paths.get("").toAbsolutePath(),
            worker = worker
        )

        val result = task.execute(context)
        assertTrue(result is ExecutionResult.Success)
    }

    @Test
    fun testLocalCommandFailure() = runBlocking {
        val task = task<ExecTask>("failureTask") {
            local {
                exec("exit 42")
            }
        }

        val worker = Worker(WorkerId("test-worker"))
        val context = ExecutionContext(
            projectDir = Paths.get("").toAbsolutePath(),
            worker = worker
        )

        val result = task.execute(context)
        assertTrue(result is ExecutionResult.Failure)
        val failure = result as ExecutionResult.Failure
        assertEquals(42, failure.exitCode)
    }

    @Test
    fun testDSLBuildsExpectedTaskGraph() {
        val first = task<ExecTask>("first") {
            inputs.file("src/main.kt")
            outputs.dir("build/out")
        }

        val second = task<ExecTask>("second") {
            dependsOn(first)
            dependsOn("non-existent-yet")
        }

        assertEquals(1, second.dependencyObjects.size)
        assertTrue(second.dependencyObjects.contains(first))
        
        assertEquals(1, second.dependencyNames.size)
        assertTrue(second.dependencyNames.contains("non-existent-yet"))

        assertTrue(first.inputs.files.contains("src/main.kt"))
        assertTrue(first.outputs.dirs.contains("build/out"))
    }

    @Test
    fun testTopologyRequirementModelPreserved() {
        val t = task<DefaultTask>("topologyTask") {
            topology {
                localOnly()
                requires("arch", "amd64")
                prefers("region", "us-east")
                requiresCapability("docker")
            }
        }

        val reqs = t.topologyRequirements
        assertEquals(4, reqs.size)
        assertTrue(reqs[0] is TopologyRequirement.LocalOnly)
        assertEquals(TopologyRequirement.RequiresLabel("arch", "amd64"), reqs[1])
        assertEquals(TopologyRequirement.PrefersLabel("region", "us-east"), reqs[2])
        assertEquals(TopologyRequirement.RequiresCapability("docker"), reqs[3])
    }
}
