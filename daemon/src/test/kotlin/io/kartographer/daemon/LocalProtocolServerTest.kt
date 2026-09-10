package io.kartographer.daemon

import io.kartographer.core.daemon.LocalProtocol
import io.kartographer.daemon.proto.LocalResponse
import io.kartographer.daemon.proto.RunServerMessage
import io.kartographer.daemon.proto.RunStatus
import io.kartographer.daemon.proto.completeRequest
import io.kartographer.daemon.proto.daemonInfoRequest
import io.kartographer.daemon.proto.describeProjectRequest
import io.kartographer.daemon.proto.localRequest
import io.kartographer.daemon.proto.projectContext
import io.kartographer.daemon.proto.runClientMessage
import io.kartographer.daemon.proto.runStart
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/**
 * Covers the transport the CLI actually uses. gRPC is exercised by [DaemonIntegrationTest],
 * but that is the remote-worker path — every local `karto` invocation goes through here.
 */
class LocalProtocolServerTest {

    private var server: LocalProtocolServer? = null

    @AfterEach
    fun tearDown() {
        server?.close()
        server = null
    }

    private fun startServer(socketDir: File): File {
        // Short path: Unix socket addresses are limited to ~108 bytes, and a long TempDir
        // plus a filename can exceed it.
        val socket = File(socketDir, "d.sock")
        server = LocalProtocolServer(socket, KartographerDaemonService()).also { it.start() }
        return socket
    }

    private fun writeProject(dir: File, vararg tasks: String) {
        File(dir, "karto.kts").writeText(
            tasks.joinToString("\n") { """task<ScriptedTask>("$it") { }""" }
        )
    }

    private fun contextFor(dir: File) = projectContext {
        projectDir = dir.absolutePath
        projectDirExplicit = true
    }

    @Test
    fun `reports daemon info and serves several requests on one connection`(@TempDir dir: File) {
        val socket = startServer(dir)
        writeProject(dir, "build")

        LocalProtocol.Client.connect(socket.toPath()).use { client ->
            val info = client.call(localRequest { daemonInfo = daemonInfoRequest { } })
            assertEquals(LocalResponse.ResponseCase.DAEMON_INFO, info.responseCase)
            assertTrue(info.daemonInfo.version.isNotBlank())

            // The CLI probes for liveness and then sends its real request on the same
            // connection; closing after the first answer would break every command.
            val described = client.call(
                localRequest { describeProject = describeProjectRequest { context = contextFor(dir) } }
            )
            assertEquals(LocalResponse.ResponseCase.DESCRIBE_PROJECT, described.responseCase)
            assertEquals(listOf("build"), described.describeProject.nodesList.map { it.name })
        }
    }

    @Test
    fun `completes task names from the cached pipeline`(@TempDir dir: File) {
        val socket = startServer(dir)
        writeProject(dir, "assemble", "audit", "build")

        LocalProtocol.Client.connect(socket.toPath()).use { client ->
            val response = client.call(
                localRequest {
                    complete = completeRequest {
                        context = contextFor(dir)
                        words.addAll(listOf("karto", "run", "a"))
                        cword = 2
                    }
                }
            )
            assertEquals(listOf("assemble", "audit"), response.complete.candidatesList)
        }
    }

    @Test
    fun `a failing pipeline reports its exit code`(@TempDir dir: File) {
        val socket = startServer(dir)
        File(dir, "karto.kts").writeText(
            """
            task<ScriptedTask>("boom") {
                action { context ->
                    context.executeBash { command("sh"); args("-c", "exit 7") }
                }
            }
            """.trimIndent()
        )

        LocalProtocol.Client.connect(socket.toPath()).use { client ->
            client.send(
                localRequest {
                    run = runClientMessage {
                        start = runStart {
                            context = contextFor(dir)
                            taskName = "boom"
                        }
                    }
                }
            )

            var completed: RunServerMessage? = null
            while (true) {
                val response = client.receive() ?: break
                if (response.responseCase != LocalResponse.ResponseCase.RUN) continue
                if (response.run.messageCase == RunServerMessage.MessageCase.PIPELINE_COMPLETED) {
                    completed = response.run
                    break
                }
            }

            assertNotNull(completed, "pipeline never reported completion")
            assertEquals(RunStatus.RUN_FAILURE, completed!!.pipelineCompleted.status)
            assertEquals(7, completed.pipelineCompleted.exitCode)
        }
    }

    @Test
    fun `process output is streamed as it happens`(@TempDir dir: File) {
        val socket = startServer(dir)
        File(dir, "karto.kts").writeText(
            """
            task<ScriptedTask>("talk") {
                action { context ->
                    context.executeBash { command("sh"); args("-c", "echo hello-from-task") }
                }
            }
            """.trimIndent()
        )

        LocalProtocol.Client.connect(socket.toPath()).use { client ->
            client.send(
                localRequest {
                    run = runClientMessage {
                        start = runStart {
                            context = contextFor(dir)
                            taskName = "talk"
                        }
                    }
                }
            )

            val output = mutableListOf<String>()
            while (true) {
                val response = client.receive() ?: break
                if (response.responseCase != LocalResponse.ResponseCase.RUN) continue
                val run = response.run
                if (run.messageCase == RunServerMessage.MessageCase.TASK_OUTPUT) {
                    output.add(run.taskOutput.line)
                }
                if (run.messageCase == RunServerMessage.MessageCase.PIPELINE_COMPLETED) break
            }

            // Execution happens on the daemon, so without this the user would see nothing at
            // all while a task runs.
            assertTrue(output.any { it.contains("hello-from-task") }, "no streamed output, got: $output")
        }
    }

    @Test
    fun `queryRuns returns tracked runs over local protocol`(@TempDir dir: File) {
        val socket = startServer(dir)
        writeProject(dir, "assemble")

        LocalProtocol.Client.connect(socket.toPath()).use { client ->
            val response = client.call(
                localRequest {
                    queryRuns = io.kartographer.daemon.proto.queryRunsRequest { activeOnly = false }
                }
            )
            assertEquals(LocalResponse.ResponseCase.QUERY_RUNS, response.responseCase)
            assertNotNull(response.queryRuns.runsList)
        }
    }

    @Test
    fun `interactive stdin is forwarded to running task`(@TempDir dir: File) {
        val socket = startServer(dir)
        File(dir, "karto.kts").writeText(
            """
            task<ScriptedTask>("interactive") {
                action { context ->
                    context.executeBash { command("sh"); args("-c", "read line; echo received-${'$'}{'$'}line") }
                }
            }
            """.trimIndent()
        )

        LocalProtocol.Client.connect(socket.toPath()).use { client ->
            client.send(
                localRequest {
                    run = runClientMessage {
                        start = runStart {
                            context = contextFor(dir)
                            taskName = "interactive"
                        }
                    }
                }
            )

            // Send stdin data to the running task
            Thread.sleep(200)
            client.send(
                localRequest {
                    run = runClientMessage {
                        stdinData = io.kartographer.daemon.proto.stdinData {
                            data = "foo\n"
                        }
                    }
                }
            )

            val output = mutableListOf<String>()
            while (true) {
                val response = client.receive() ?: break
                if (response.responseCase != LocalResponse.ResponseCase.RUN) continue
                val run = response.run
                if (run.messageCase == RunServerMessage.MessageCase.ERROR) {
                    error(run.error.message)
                }
                if (run.messageCase == RunServerMessage.MessageCase.TASK_OUTPUT) {
                    output.add(run.taskOutput.line)
                }
                if (run.messageCase == RunServerMessage.MessageCase.PIPELINE_COMPLETED) break
            }

            assertTrue(output.any { it.contains("received-foo") }, "stdin was not forwarded to task, got: $output")
        }
    }
}
