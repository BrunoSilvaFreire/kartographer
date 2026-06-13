package io.kartographer

import io.kartographer.core.ExecutionContext
import io.kartographer.core.ExecutionResult
import io.kartographer.core.Worker
import io.kartographer.core.WorkerId
import io.kartographer.misc.FileFilter
import io.kartographer.misc.FilePaths
import io.kartographer.tasks.Copy
import io.kartographer.tasks.Delete
import io.kartographer.tasks.Move
import io.kartographer.tasks.Sync
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

class FilesystemTasksTest {

    @Test
    fun testFileFilter() {
        val filter = FileFilter(includes = listOf("src/**/*.kt"), excludes = listOf("**/Test.kt"))

        assertTrue(filter.matches(Paths.get("src/main/kotlin/io/kartographer/Main.kt")))
        assertFalse(filter.matches(Paths.get("src/main/kotlin/io/kartographer/Test.kt")))
        assertFalse(filter.matches(Paths.get("build/Main.class")))
    }

    @Test
    fun testCopyTask(@TempDir tempDir: Path) = runBlocking {
        val srcDir = Files.createDirectory(tempDir.resolve("src"))
        val destDir = tempDir.resolve("dest")

        Files.writeString(srcDir.resolve("hello.txt"), "hello world")

        val copyTask = Copy("test-copy").apply {
            sources = FilePaths(listOf(tempDir.relativize(srcDir).toString()))
            destination = tempDir.relativize(destDir).toString()
        }

        val worker = Worker(WorkerId("test-worker"))
        val context = ExecutionContext(
            projectDir = tempDir,
            worker = worker
        )

        val result = copyTask.execute(context)
        assertTrue(result is ExecutionResult.Success)

        val targetFile = destDir.resolve("hello.txt")
        assertTrue(Files.exists(targetFile))
        assertEquals("hello world", Files.readString(targetFile))
    }

    @Test
    fun testSyncTask(@TempDir tempDir: Path) = runBlocking {
        val srcDir = Files.createDirectory(tempDir.resolve("src"))
        val destDir = Files.createDirectory(tempDir.resolve("dest"))

        Files.writeString(srcDir.resolve("hello.txt"), "hello")
        Files.writeString(destDir.resolve("old.txt"), "old")

        val syncTask = Sync("test-sync").apply {
            sources = FilePaths(listOf(tempDir.relativize(srcDir).toString()))
            destination = tempDir.relativize(destDir).toString()
        }

        val worker = Worker(WorkerId("test-worker"))
        val context = ExecutionContext(
            projectDir = tempDir,
            worker = worker
        )

        val result = syncTask.execute(context)
        assertTrue(result is ExecutionResult.Success)

        assertTrue(Files.exists(destDir.resolve("hello.txt")))
        assertFalse(Files.exists(destDir.resolve("old.txt")))
    }

    @Test
    fun testMoveTask(@TempDir tempDir: Path) = runBlocking {
        val srcDir = Files.createDirectory(tempDir.resolve("src"))
        val destDir = tempDir.resolve("dest")

        Files.writeString(srcDir.resolve("hello.txt"), "hello")

        val moveTask = Move("test-move").apply {
            sources = FilePaths(listOf(tempDir.relativize(srcDir).toString()))
            destination = tempDir.relativize(destDir).toString()
        }

        val worker = Worker(WorkerId("test-worker"))
        val context = ExecutionContext(
            projectDir = tempDir,
            worker = worker
        )

        val result = moveTask.execute(context)
        assertTrue(result is ExecutionResult.Success)

        assertTrue(Files.exists(destDir.resolve("hello.txt")))
        assertFalse(Files.exists(srcDir.resolve("hello.txt")))
    }

    @Test
    fun testDeleteTask(@TempDir tempDir: Path) = runBlocking {
        val srcDir = Files.createDirectory(tempDir.resolve("src"))
        Files.writeString(srcDir.resolve("hello.txt"), "hello")

        val deleteTask = Delete("test-delete").apply {
            files = FilePaths(listOf(tempDir.relativize(srcDir).toString()))
        }

        val worker = Worker(WorkerId("test-worker"))
        val context = ExecutionContext(
            projectDir = tempDir,
            worker = worker
        )

        val result = deleteTask.execute(context)
        assertTrue(result is ExecutionResult.Success)
        assertFalse(Files.exists(srcDir))
    }
}
