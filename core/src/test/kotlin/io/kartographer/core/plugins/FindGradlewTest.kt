package io.kartographer.core.plugins

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import java.io.File
import java.nio.file.Files

/**
 * Covers the fallback chain a pipeline promoted into its own repo depends on: such a repo
 * usually has no wrapper anywhere above the script.
 */
class FindGradlewTest {

    @Test
    fun `finds a wrapper by walking up from the script directory`() {
        val root = tempDir()
        val wrapper = File(root, "gradlew").apply { writeText("#!/bin/sh\n") }
        val scriptDir = File(root, "karto").apply { mkdirs() }

        assertEquals(wrapper.absoluteFile, PluginLoader.findGradlew(scriptDir, pinnedGradle = null, path = null))
    }

    @Test
    fun `returns null when no wrapper is above and no gradle is on PATH`() {
        assertNull(PluginLoader.findGradlew(tempDir(), pinnedGradle = null, path = ""))
    }

    @Test
    fun `falls back to a gradle on PATH when nothing is above the script`() {
        val binDir = tempDir()
        val gradle = executable(File(binDir, "gradle"))

        val found = PluginLoader.findGradlew(tempDir(), pinnedGradle = null, path = binDir.absolutePath)

        assertEquals(gradle.absolutePath, found?.absolutePath)
    }

    @Test
    fun `a pinned gradle wins over a wrapper above the script`() {
        val root = tempDir()
        File(root, "gradlew").apply { writeText("#!/bin/sh\n") }
        val scriptDir = File(root, "karto").apply { mkdirs() }
        val pinned = executable(File(tempDir(), "gradle"))

        val found = PluginLoader.findGradlew(scriptDir, pinnedGradle = pinned.absolutePath, path = null)

        assertEquals(pinned.absolutePath, found?.absolutePath)
    }

    @Test
    fun `a non-executable pinned gradle is ignored in favour of the wrapper`() {
        val root = tempDir()
        val wrapper = File(root, "gradlew").apply { writeText("#!/bin/sh\n") }
        val scriptDir = File(root, "karto").apply { mkdirs() }
        val broken = File(tempDir(), "gradle").apply { writeText("#!/bin/sh\n") }

        val found = PluginLoader.findGradlew(scriptDir, pinnedGradle = broken.absolutePath, path = null)

        assertEquals(wrapper.absoluteFile, found)
    }

    private fun tempDir(): File =
        Files.createTempDirectory("findGradlew").toFile().apply { deleteOnExit() }

    private fun executable(file: File): File =
        file.apply {
            writeText("#!/bin/sh\n")
            setExecutable(true)
        }
}
