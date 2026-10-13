package io.kartographer.core.plugins

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledOnOs
import org.junit.jupiter.api.condition.OS
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
    @EnabledOnOs(OS.LINUX, OS.MAC)
    fun `a non-executable pinned gradle is ignored in favour of the wrapper`() {
        val root = tempDir()
        val wrapper = File(root, "gradlew").apply { writeText("#!/bin/sh\n") }
        val scriptDir = File(root, "karto").apply { mkdirs() }
        val broken = File(tempDir(), "gradle").apply { writeText("#!/bin/sh\n") }

        val found = PluginLoader.findGradlew(scriptDir, pinnedGradle = broken.absolutePath, path = null)

        assertEquals(wrapper.absoluteFile, found)
    }

    @Test
    fun `resolution init script maps the versionless plugin to the running bundle version`() {
        val libDir = File(tempDir(), "lib").apply { mkdirs() }
        val coreJar = File(libDir, "core-3.4.5.jar")
        JarFileFixture.write(coreJar, "3.4.5")
        val previousClasspath = System.getProperty("java.class.path")
        try {
            System.setProperty("java.class.path", coreJar.absolutePath)
            val script = PluginLoader.pluginResolutionInitScriptForTest() ?: error("No init script created")
            val text = script.readText()
            assertTrue(text.contains("useVersion(\"3.4.5\")"))
            assertTrue(text.contains("mavenLocal()"))
            assertTrue(text.contains("gradlePluginPortal()"))
            script.delete()
        } finally {
            if (previousClasspath == null) System.clearProperty("java.class.path")
            else System.setProperty("java.class.path", previousClasspath)
        }
    }

    @Test
    fun `resolution init script recovers the version from a release core jar name`() {
        // A release bundle's core jar carries no Implementation-Version; only its name does.
        val libDir = File(tempDir(), "lib").apply { mkdirs() }
        val coreJar = File(libDir, "core-0.1.0.jar")
        JarFileFixture.write(coreJar, version = null)
        val previousClasspath = System.getProperty("java.class.path")
        try {
            System.setProperty("java.class.path", coreJar.absolutePath)
            val script = PluginLoader.pluginResolutionInitScriptForTest() ?: error("No init script created")
            assertTrue(script.readText().contains("useVersion(\"0.1.0\")"))
            script.delete()
        } finally {
            if (previousClasspath == null) System.clearProperty("java.class.path")
            else System.setProperty("java.class.path", previousClasspath)
        }
    }

    private fun tempDir(): File =
        Files.createTempDirectory("findGradlew").toFile().apply { deleteOnExit() }

    private fun executable(file: File): File =
        file.apply {
            writeText("#!/bin/sh\n")
            setExecutable(true)
        }

    private object JarFileFixture {
        fun write(file: File, version: String?) {
            val manifest = java.util.jar.Manifest().apply {
                mainAttributes[java.util.jar.Attributes.Name.MANIFEST_VERSION] = "1.0"
                if (version != null) {
                    mainAttributes.putValue("Implementation-Version", version)
                }
            }
            java.util.jar.JarOutputStream(file.outputStream(), manifest).use { }
        }
    }
}
