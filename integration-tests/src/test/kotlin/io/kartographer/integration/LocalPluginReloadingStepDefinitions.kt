package io.kartographer.integration

import io.cucumber.java.After
import io.cucumber.java.en.Given
import io.cucumber.java.en.When
import io.cucumber.java.en.Then
import io.kartographer.core.model.Node
import io.kartographer.core.model.TaskId
import io.kartographer.core.plugins.PluginLoader
import io.kartographer.daemon.project.ProjectCache
import io.kartographer.core.Property
import io.kartographer.core.model.Task
import kotlinx.coroutines.runBlocking
import java.io.File
import java.nio.file.Files
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class LocalPluginReloadingStepDefinitions {
    private var tempProjDir: File? = null
    private var pipelineKtsFile: File? = null
    private var globalPluginsDir: File? = null

    // Loading goes through the cache rather than the loader directly, so these scenarios
    // double as the regression test for daemon cache invalidation: a plugin source edit has
    // to change the project fingerprint or the reload steps below silently see stale values.
    private val cache = ProjectCache()
    private var loadedNodes: Map<TaskId, Node> = emptyMap()

    @After
    fun restorePluginLoader() {
        if (globalPluginsDir != null) {
            PluginLoader.initialize()
            globalPluginsDir = null
        }
    }

    @When("the compiled local plugin jar is installed as a global plugin")
    fun when_install_jar_as_global_plugin() {
        val projDir = tempProjDir ?: error("Temp project directory not initialized")
        val libsDir = File(projDir, "karto/build/libs")
        val jar = libsDir.listFiles { _, name -> name.endsWith(".jar") }?.firstOrNull()
            ?: error("No compiled plugin jar found in ${libsDir.absolutePath}")

        val pluginsDir = Files.createTempDirectory(File("build/tmp").absoluteFile.toPath(), "global-plugins").toFile()
        jar.copyTo(File(pluginsDir, jar.name), overwrite = true)
        globalPluginsDir = pluginsDir
        // Re-initialize the loader against this plugins dir, simulating a stale
        // globally installed copy of the plugin that must not shadow local builds.
        PluginLoader.initialize(pluginsDir)
    }
    
    @Given("a temporary project directory")
    fun given_temp_project_dir() {
        val parent = File("build/tmp").absoluteFile
        parent.mkdirs()
        tempProjDir = Files.createTempDirectory(parent.toPath(), "local-plugin-test").toFile()
    }

    @Given("a custom local plugin with a task {string} that has property {string} defaulting to {int}")
    fun given_custom_local_plugin(taskName: String, propName: String, defaultValue: Int) {
        val projDir = tempProjDir ?: error("Temp project directory not initialized")
        val kartoDir = File(projDir, "karto")
        
        // Create directory structures
        val srcDir = File(kartoDir, "src/main/kotlin/io/kartographer/tasks/custom")
        srcDir.mkdirs()
        val resourcesDir = File(kartoDir, "src/main/resources/META-INF/services")
        resourcesDir.mkdirs()
        
        // Write settings.gradle.kts
        File(kartoDir, "settings.gradle.kts").writeText("""
            pluginManagement {
                repositories {
                    mavenLocal()
                    gradlePluginPortal()
                    mavenCentral()
                }
            }
            rootProject.name = "custom-plugin"
        """.trimIndent())
        
        // Write build.gradle.kts
        File(kartoDir, "build.gradle.kts").writeText("""
            plugins {
                id("io.kartographer.plugin") version "0.1.0-SNAPSHOT"
            }
        """.trimIndent())
        
        // Write Task class
        writeTaskClass(taskName, propName, defaultValue)
        
        // Write Module class
        File(srcDir, "CustomPluginModule.kt").writeText("""
            package io.kartographer.tasks.custom
            
            import io.kartographer.core.plugins.KartographerModule
            import io.kartographer.core.model.Task
            
            class CustomPluginModule : KartographerModule {
                override val id = "custom-plugin"
                override val taskTypes = listOf($taskName::class.java)
            }
        """.trimIndent())
        
        // Write ServiceLoader file
        File(resourcesDir, "io.kartographer.core.plugins.KartographerModule").writeText(
            "io.kartographer.tasks.custom.CustomPluginModule"
        )
    }

    private fun writeTaskClass(taskName: String, propName: String, defaultValue: Int) {
        val projDir = tempProjDir ?: error("Temp project directory not initialized")
        val kartoDir = File(projDir, "karto")
        val srcDir = File(kartoDir, "src/main/kotlin/io/kartographer/tasks/custom")
        
        File(srcDir, "$taskName.kt").writeText("""
            package io.kartographer.tasks.custom
            
            import io.kartographer.core.model.Task
            import io.kartographer.core.model.ExecutionContext
            import io.kartographer.core.model.ExecutionResult
            
            open class $taskName(name: String) : Task(name) {
                val $propName = property<Int> { set($defaultValue) }
                override suspend fun execute(context: ExecutionContext): ExecutionResult {
                    return ExecutionResult.Success
                }
            }
        """.trimIndent())
    }

    @When("we load and execute the pipeline script {string} defining:")
    fun when_load_and_execute_script(scriptName: String, scriptContent: String) {
        val projDir = tempProjDir ?: error("Temp project directory not initialized")
        val scriptFile = File(projDir, scriptName)
        scriptFile.writeText(scriptContent)
        pipelineKtsFile = scriptFile

        loadPipeline(scriptFile)
    }

    @Then("the task {string} should have property {string} with value {int}")
    fun then_task_has_property_value(taskName: String, propName: String, expectedValue: Int) {
        val node = loadedNodes[TaskId(taskName)]
        assertNotNull(node, "Task '$taskName' was not registered by the pipeline")
        val task = node as? Task ?: error("Node '$taskName' is not a Task")
        
        val propField = task::class.java.getDeclaredField(propName)
        propField.isAccessible = true
        val prop = propField.get(task) as Property<*>
        assertEquals(expectedValue, prop.get())
    }

    @When("we modify the custom local plugin task {string} property {string} default value to {int}")
    fun when_modify_custom_task_property(taskName: String, propName: String, newValue: Int) {
        writeTaskClass(taskName, propName, newValue)
    }

    @When("we load and execute the pipeline script {string} again")
    fun when_load_and_execute_again(scriptName: String) {
        val projDir = tempProjDir ?: error("Temp project directory not initialized")
        loadPipeline(File(projDir, scriptName))
    }

    private fun loadPipeline(scriptFile: File) {
        val projDir = tempProjDir ?: error("Temp project directory not initialized")
        loadedNodes = runBlocking {
            cache.use(projDir, scriptFile.absolutePath) { it.nodes.toMap() }
        }
    }
}
