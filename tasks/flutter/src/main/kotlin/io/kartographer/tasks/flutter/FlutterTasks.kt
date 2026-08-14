package io.kartographer.tasks.flutter

import io.kartographer.tasks.DefaultTask
import io.kartographer.core.model.ExecutionContext
import io.kartographer.core.model.ExecutionResult
import io.kartographer.core.model.executeBash

open class FlutterRun(name: String) : DefaultTask(name) {
    val device = property<String> { set("linux") }
        .withCLI("device", "Device to run on")
    val config = property<String> { set("dev") }
        .withCLI("config", "Config profile, a name under config/ or a path")
    // Exposed rather than expanded into the task tree: a run task per build mode would triple
    // the matrix for the axis that varies least.
    val mode = property<String> { set("debug") }
        .withCLI("mode", "Build mode") { completeWith("debug", "profile", "release") }
    val wasm = property<String> { set("false") }
        .withCLI("wasm", "Compile to WebAssembly (web devices only)") { completeWith("true", "false") }
    val target = property<String> { set("lib/main.dart") }
    val webPort = property<String>()
    val webHostname = property<String>()
    val flutterBin = property<String> { set("flutter") }

    val args = mutableListOf<String>()
    fun arg(vararg arguments: String) { args.addAll(arguments) }

    init {
        topology { requiresCapability("flutter") }
        // `flutter run` launches an app; it produces no cacheable output. The
        // default property-based up-to-date check would otherwise skip it after
        // the first run (properties unchanged, no inputs/outputs declared).
        upToDateWhen { _, _ -> false }
    }

    override suspend fun execute(context: ExecutionContext): ExecutionResult {
        val configPath = if (config.get().endsWith(".yaml")) config.get() else "config/${config.get()}.yaml"

        return context.executeBash {
            command(flutterBin.get())
            args("run", "-d", device.get())
            
            if (mode.get() == "profile") args("--profile")
            if (mode.get() == "release") args("--release")
            
            args("-t", target.get())
            args("--dart-define=CITYSCAPE_CONFIG=$configPath")
            
            if (device.get() in listOf("chrome", "web-server")) {
                if (webPort.isPresent()) args("--web-port", webPort.get())
                if (webHostname.isPresent()) args("--web-hostname", webHostname.get())
                if (wasm.get().equals("true", ignoreCase = true)) args("--wasm")
            }

            args(this@FlutterRun.args)
            environment("CITYSCAPE_CONFIG", configPath)
        }
    }
}

open class FlutterBuild(name: String) : DefaultTask(name) {
    val targetPlatform = property<String>() // e.g., apk, appbundle, web, linux
        .withCLI("target_platform", "Platform to build") {
            completeWith("apk", "appbundle", "web", "linux", "macos", "windows", "ios")
        }
    val config = property<String> { set("prod") }
        .withCLI("config", "Config profile, a name under config/ or a path")
    val flutterBin = property<String> { set("flutter") }

    val args = mutableListOf<String>()
    fun arg(vararg arguments: String) { args.addAll(arguments) }

    init {
        topology { requiresCapability("flutter") }
    }

    override suspend fun execute(context: ExecutionContext): ExecutionResult {
        val configPath = if (config.get().endsWith(".yaml")) config.get() else "config/${config.get()}.yaml"
        
        return context.executeBash {
            command(flutterBin.get())
            args("build", targetPlatform.get())
            args("--dart-define=CITYSCAPE_CONFIG=$configPath")
            args(this@FlutterBuild.args)
            environment("CITYSCAPE_CONFIG", configPath)
        }
    }
}

open class FlutterTest(name: String) : DefaultTask(name) {
    val flutterBin = property<String> { set("flutter") }
    val args = mutableListOf<String>()
    fun arg(vararg arguments: String) { args.addAll(arguments) }

    init {
        topology { requiresCapability("flutter") }
    }

    override suspend fun execute(context: ExecutionContext): ExecutionResult {
        return context.executeBash {
            command(flutterBin.get())
            args("test")
            args(this@FlutterTest.args)
        }
    }
}
