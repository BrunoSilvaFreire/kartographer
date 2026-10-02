package io.kartographer.tasks.flutter

import io.kartographer.core.model.BashSpec
import io.kartographer.core.model.ExecutionContext
import io.kartographer.core.model.ExecutionEvent
import io.kartographer.core.model.ExecutionResult
import io.kartographer.core.model.InputSet
import io.kartographer.core.model.NoOutputs
import io.kartographer.core.model.OutputSet
import io.kartographer.core.model.Task
import io.kartographer.core.model.dir
import io.kartographer.core.model.file
import io.kartographer.core.model.executeBash
import io.kartographer.core.model.emitOutput
import io.kartographer.core.model.SemaphoreHandle
import io.kartographer.core.model.releaseSemaphore
import io.kartographer.tasks.dart.TestOutputs
import io.kartographer.tasks.dart.TestPublishOptions
import io.kartographer.tasks.dart.TestReportPaths
import io.kartographer.tasks.dart.TestTaskSupport
import java.io.File

data class FlutterRunInputs(
    var device: String,
    var config: String,
    var mode: String,
    var wasm: String,
    var target: String,
    var webPort: String?,
    var webHostname: String?,
    var flutterBin: String,
    var args: List<String>
) : InputSet()

class FlutterRunOutputs : OutputSet() {
    val debugEndpoint = output<String>("debugEndpoint")
    val vmServiceUri = output<String>("vmServiceUri")
    val devToolsUri = output<String>("devToolsUri")
}

open class FlutterRun(name: String) : Task<FlutterRunInputs, FlutterRunOutputs>(name) {
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
        // `flutter run` launches an app; it produces no cacheable output.
        alwaysRun()
    }

    override suspend fun buildInputs(context: ExecutionContext) = FlutterRunInputs(
        device.get(), config.get(), mode.get(), wasm.get(), target.get(), webPort.orNull(),
        webHostname.orNull(), flutterBin.get(), args.toList()
    )
    override fun buildOutputs() = FlutterRunOutputs()

    override suspend fun execute(context: ExecutionContext, inputSet: FlutterRunInputs): ExecutionResult<FlutterRunOutputs> {
        val configPath = if (inputSet.config.endsWith(".yaml")) inputSet.config else "config/${inputSet.config}.yaml"
        val outputs = buildOutputs()

        val result = context.executeBash {
            command(inputSet.flutterBin)
            args("run", "-d", inputSet.device)
            
            if (inputSet.mode == "profile") args("--profile")
            if (inputSet.mode == "release") args("--release")
            
            args("-t", inputSet.target)
            args("--dart-define=CITYSCAPE_CONFIG=$configPath")
            
            if (inputSet.device in listOf("chrome", "web-server")) {
                inputSet.webPort?.let { args("--web-port", it) }
                inputSet.webHostname?.let { args("--web-hostname", it) }
                if (inputSet.wasm.equals("true", ignoreCase = true)) args("--wasm")
            }

            args(inputSet.args)
            environment("CITYSCAPE_CONFIG", configPath)

            onStdoutLine = { line ->
                val endpoints = parseFlutterEndpoints(line)
                endpoints.debugEndpoint?.let {
                    context.emitOutput(outputs.debugEndpoint, it)
                }
                endpoints.vmServiceUri?.let {
                    context.emitOutput(outputs.vmServiceUri, it)
                }
                endpoints.devToolsUri?.let {
                    context.emitOutput(outputs.devToolsUri, it)
                }
            }
        }
        return when (result) {
            is ExecutionResult.Failure -> ExecutionResult.Failure(result.error, result.exitCode, outputs)
            else -> ExecutionResult.Success(outputs)
        }
    }
}

data class FlutterEndpoints(
    val debugEndpoint: String? = null,
    val vmServiceUri: String? = null,
    val devToolsUri: String? = null
)

fun parseFlutterEndpoints(line: String): FlutterEndpoints {
    val devToolsMatch = Regex("""(?:Flutter DevTools debugger and profiler.*?is available at:\s*)(https?://\S+)""", RegexOption.IGNORE_CASE).find(line)
    val devTools = devToolsMatch?.groupValues?.get(1)

    val vmMatch = Regex("""(?:Dart VM Service|Observatory debugger|Dart VM service).*?is available at:\s*(https?://\S+|ws://\S+)""", RegexOption.IGNORE_CASE).find(line)
        ?: Regex("""(?:Connecting to VM Service at|Debug service listening on)\s*(ws://\S+|https?://\S+)""", RegexOption.IGNORE_CASE).find(line)

    val vmService = vmMatch?.groupValues?.get(1)
    val debugEndpoint = vmService?.let { toWebSocketEndpoint(it) }

    return FlutterEndpoints(
        debugEndpoint = debugEndpoint,
        vmServiceUri = vmService,
        devToolsUri = devTools
    )
}

private fun toWebSocketEndpoint(uri: String): String {
    if (uri.startsWith("ws://", ignoreCase = true) || uri.startsWith("wss://", ignoreCase = true)) {
        return if (uri.endsWith("/ws")) uri else "${uri.trimEnd('/')}/ws"
    }
    val wsUri = uri.replaceFirst(Regex("^http://", RegexOption.IGNORE_CASE), "ws://")
        .replaceFirst(Regex("^https://", RegexOption.IGNORE_CASE), "wss://")
    return if (wsUri.endsWith("/ws")) wsUri else "${wsUri.trimEnd('/')}/ws"
}

data class FlutterBuildInputs(var targetPlatform: String, var config: String, var flutterBin: String, var args: List<String>) : InputSet()

open class FlutterBuild(name: String) : Task<FlutterBuildInputs, NoOutputs>(name) {
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

    override suspend fun buildInputs(context: ExecutionContext) = FlutterBuildInputs(
        targetPlatform.get(), config.get(), flutterBin.get(), args.toList()
    )
    override fun buildOutputs() = NoOutputs()

    override suspend fun execute(context: ExecutionContext, inputSet: FlutterBuildInputs): ExecutionResult<NoOutputs> {
        val configPath = if (inputSet.config.endsWith(".yaml")) inputSet.config else "config/${inputSet.config}.yaml"
        
        return context.executeBash {
            command(inputSet.flutterBin)
            args("build", inputSet.targetPlatform)
            args("--dart-define=CITYSCAPE_CONFIG=$configPath")
            args(inputSet.args)
            environment("CITYSCAPE_CONFIG", configPath)
        }
    }
}

data class FlutterTestInputs(
    var flutterBin: String,
    var targets: List<String>,
    var name: String?,
    var plainName: String?,
    var tags: String?,
    var excludeTags: String?,
    var device: String?,
    var config: String?,
    var dartDefines: Map<String, String>,
    var dartDefineFromFile: String?,
    var flavor: String?,
    var wasm: Boolean,
    var failFast: Boolean,
    var runSkipped: Boolean,
    var concurrency: Int?,
    var timeout: String?,
    var ignoreTimeouts: Boolean,
    var randomizeOrderingSeed: String?,
    var totalShards: Int?,
    var shardIndex: Int?,
    var testAssets: Boolean,
    var updateGoldens: Boolean,
    var startPaused: Boolean,
    var coverage: Boolean,
    var coveragePath: String?,
    var branchCoverage: Boolean,
    var mergeCoverage: Boolean,
    var coveragePackage: String?,
    var reportDir: String?,
    var reportName: String? = null,
    var jsonReport: Boolean,
    var generateJunit: Boolean,
    var reporter: String?,
    var ignoreFailures: Boolean,
    var isolateTargets: Boolean,
    var cacheable: Boolean,
    var generateHtml: Boolean = true,
    var logFile: Boolean = true,
    var coverageExclude: List<String> = emptyList(),
    var minCoverage: Double? = null,
    var args: List<String>
) : InputSet()

class FlutterTestOutputs : TestOutputs() {
    val debugEndpoint = output<String>("debugEndpoint")
    val vmServiceUri = output<String>("vmServiceUri")
    val devToolsUri = output<String>("devToolsUri")
}

open class FlutterTest(name: String) : Task<FlutterTestInputs, FlutterTestOutputs>(name) {
    val flutterBin = property<String> { set("flutter") }

    val target = property<String>()
        .withCLI("target", "Specific test file or directory to run")
    val targets = property<List<String>> { set(emptyList()) }
    fun target(vararg paths: String) {
        val current = targets.directOrNull() ?: emptyList()
        targets.set(current + paths)
    }

    val namePattern = property<String>()
        .withCLI("name", "Regexp matching test names")
    val plainName = property<String>()
        .withCLI("plain-name", "Substring matching test names")
    val tags = property<String>()
        .withCLI("tags", "Run tests with tags")
    val excludeTags = property<String>()
        .withCLI("exclude-tags", "Run tests without tags")
    val device = property<String>()
        .withCLI("device", "Device to run tests on")
    val config = property<String>()
        .withCLI("config", "Config profile, a name under config/ or a path")

    val dartDefines = mutableMapOf<String, String>()
    fun dartDefine(key: String, value: String) { dartDefines[key] = value }

    val dartDefineFromFile = property<String>()
    val flavor = property<String>()
    val wasm = property<Boolean> { set(false) }

    val failFast = property<Boolean> { set(false) }
        .withCLI("fail-fast", "Stop running tests after the first failure") { completeWith("true", "false") }
    val runSkipped = property<Boolean> { set(false) }
    val concurrency = property<Int>()
    val timeout = property<String>()
        .withCLI("timeout", "Test timeout (e.g. 60s, 2x, none)")
    val ignoreTimeouts = property<Boolean> { set(false) }
    val randomizeOrderingSeed = property<String>()
    val totalShards = property<Int>()
    val shardIndex = property<Int>()
    val testAssets = property<Boolean> { set(true) }
    val updateGoldens = property<Boolean> { set(false) }
        .withCLI("update-goldens", "Update golden test files") { completeWith("true", "false") }
    val startPaused = property<Boolean> { set(false) }
        .withCLI("start-paused", "Start paused and wait for debugger") { completeWith("true", "false") }

    val coverage = property<Boolean> { set(false) }
        .withCLI("coverage", "Collect coverage information") { completeWith("true", "false") }
    val coveragePath = property<String>()

    /** Globs whose files leave the coverage denominator — generated code, mostly. */
    val coverageExclude = property<List<String>> { set(emptyList()) }

    /** Fails the task when coverage drops below this percentage. */
    val minCoverage = property<Double>()
        .withCLI("min-coverage", "Fail when line coverage falls below this percentage")
    val branchCoverage = property<Boolean> { set(false) }
    val mergeCoverage = property<Boolean> { set(false) }
    val coveragePackage = property<String>()

    val reportDir = property<String> { set("build/test-results") }
    val reportName = property<String>()
    val jsonReport = property<Boolean> { set(true) }
    val generateJunit = property<Boolean> { set(true) }
    val reporter = property<String>()
        .withCLI("reporter", "Reporter to use") { completeWith("expanded", "compact", "failures-only", "github", "json", "silent") }
    val ignoreFailures = property<Boolean> { set(false) }
    val isolateTargets = property<Boolean> { set(false) }
    val cacheable = property<Boolean> { set(false) }
    val generateHtml = property<Boolean> { set(false) }
        .withCLI("html", "Generate HTML test report") { completeWith("true", "false") }
    val logFile = property<Boolean> { set(false) }

    val args = mutableListOf<String>()
    fun arg(vararg arguments: String) { args.addAll(arguments) }

    init {
        topology { requiresCapability("flutter") }
        // Declared unconditionally so the run can size the semaphore; only actually
        // acquired by invocations that build the bundle (see semaphoresToAcquire).
        acquires(TEST_ASSETS)
    }

    /**
     * A `--no-test-assets` run queues for nothing the bundle is concerned with. Everything
     * else the pipeline declared with `acquires(...)` still applies: returning a fixed list
     * here would silently drop a semaphore the task was told to hold.
     */
    override fun semaphoresToAcquire(inputSet: FlutterTestInputs): Collection<SemaphoreHandle> =
        if (inputSet.testAssets) semaphores else semaphores - TEST_ASSETS

    companion object {
        /**
         * `flutter test` bundles assets into `<project>/build/unit_test_assets`, one shared
         * directory per project with no flag to move it. Two concurrent runs race there:
         * one cleans the directory while the other is still compiling shaders into it, and
         * the loser dies with a ShaderCompilerException that reads like a code problem.
         */
        val TEST_ASSETS = SemaphoreHandle("flutter-test-assets")

        /**
         * The first sign that the bundle is written and the permit can go back.
         *
         * The reporter prints `00:00 +0: loading <file>` once it starts a suite, which is
         * after the asset bundle is built. Reading the tool's own output is indirect, but
         * `flutter test` offers no other signal, and the fallback when it never matches is
         * simply holding the permit for the whole run, as before.
         */
        val BUNDLE_DONE = Regex("^\\d\\d:\\d\\d \\+\\d|\"type\":\"testStart\"")
    }

    fun enableCaching() {
        cacheable.set(true)
        trackFile("pubspec.yaml")
        trackFile("pubspec.lock")
        inputs.dir("lib")
        inputs.dir("test")
        reportDir.directOrNull()?.let { d: String -> trackedOutputs.dir(d) }
    }

    override suspend fun buildInputs(context: ExecutionContext): FlutterTestInputs {
        val resolvedTargets = buildList {
            target.orNull()?.let { add(it) }
            if (isEmpty()) {
                addAll(targets.orNull() ?: emptyList())
            }
        }
        return FlutterTestInputs(
            flutterBin = flutterBin.get(),
            targets = resolvedTargets,
            name = namePattern.orNull(),
            plainName = plainName.orNull(),
            tags = tags.orNull(),
            excludeTags = excludeTags.orNull(),
            device = device.orNull(),
            config = config.orNull(),
            dartDefines = dartDefines.toMap(),
            dartDefineFromFile = dartDefineFromFile.orNull(),
            flavor = flavor.orNull(),
            wasm = wasm.get(),
            failFast = failFast.get(),
            runSkipped = runSkipped.get(),
            concurrency = concurrency.orNull(),
            timeout = timeout.orNull(),
            ignoreTimeouts = ignoreTimeouts.get(),
            randomizeOrderingSeed = randomizeOrderingSeed.orNull(),
            totalShards = totalShards.orNull(),
            shardIndex = shardIndex.orNull(),
            testAssets = testAssets.get(),
            updateGoldens = updateGoldens.get(),
            startPaused = startPaused.get(),
            coverage = coverage.get(),
            coveragePath = coveragePath.orNull(),
            branchCoverage = branchCoverage.get(),
            mergeCoverage = mergeCoverage.get(),
            coveragePackage = coveragePackage.orNull(),
            reportDir = reportDir.orNull(),
            reportName = reportName.orNull(),
            jsonReport = jsonReport.get(),
            generateJunit = generateJunit.get(),
            reporter = reporter.orNull(),
            ignoreFailures = ignoreFailures.get(),
            isolateTargets = isolateTargets.get(),
            cacheable = cacheable.get(),
            generateHtml = generateHtml.get(),
            logFile = logFile.get(),
            coverageExclude = coverageExclude.get(),
            minCoverage = minCoverage.orNull(),
            args = args.toList()
        )
    }

    override fun buildOutputs() = FlutterTestOutputs()

    override suspend fun execute(context: ExecutionContext, inputSet: FlutterTestInputs): ExecutionResult<FlutterTestOutputs> {
        val outputs = buildOutputs()
        val projectDir = context.projectDir.toFile()

        if (!TestTaskSupport.hasTests(projectDir, inputSet.targets)) {
            val msg = "No tests found in ${inputSet.targets.joinToString(", ")}"
            context.observer?.onEvent(ExecutionEvent.TaskOutput(this, msg, isError = false))
            println(msg)
            return ExecutionResult.Success(outputs)
        }

        val paths = TestReportPaths.resolve(
            projectDir = projectDir,
            reportDir = inputSet.reportDir,
            baseName = inputSet.reportName ?: name.substringAfterLast('/'),
            coveragePath = inputSet.coveragePath
        )
        val logWriter = TestTaskSupport.logWriter(paths, inputSet.logFile)

        val configPath = inputSet.config?.let {
            if (it.endsWith(".yaml")) it else "config/$it.yaml"
        }

        // `flutter test` writes the asset bundle before it loads the first suite, and every
        // run in the project writes it to the same build/unit_test_assets. Once the first
        // suite is loading the bundle is done with, so the permit goes back then rather
        // than at the end of the run — the tests themselves do not conflict.
        val assetsReleased = java.util.concurrent.atomic.AtomicBoolean(false)
        fun releaseAssetsOnce(line: String) {
            if (!assetsReleased.get() && BUNDLE_DONE.containsMatchIn(line)) {
                if (assetsReleased.compareAndSet(false, true)) {
                    context.releaseSemaphore(TEST_ASSETS)
                }
            }
        }

        try {
            val jsonFiles = mutableListOf<File>()
            var firstFailure: ExecutionResult.Failure<*>? = null

            if (inputSet.isolateTargets) {
                val testFiles = TestTaskSupport.findTestFiles(projectDir, inputSet.targets)
                val combinedJsonLines = mutableListOf<String>()
                val coverageParts = mutableListOf<File>()

                for ((idx, testFile) in testFiles.withIndex()) {
                    val relPath = testFile.relativeTo(projectDir).path
                    val targetJson = paths.isolatedJson(idx, testFile)
                    // Each invocation writes its own lcov; they are merged below. Sharing one
                    // --coverage-path would leave only the last test file's coverage.
                    val targetLcov = paths.isolatedLcov(idx, testFile).takeIf { inputSet.coverage }
                    targetLcov?.let { coverageParts.add(it) }

                    val result = context.executeBash {
                        command(inputSet.flutterBin)
                        args("test")
                        args(inputSet.args)
                        args(relPath)
                        if (inputSet.jsonReport) {
                            args("--file-reporter", "json:${targetJson.absolutePath}")
                        }
                        appendFlags(this, inputSet, configPath, coveragePath = targetLcov?.absolutePath)
                        onStdoutLine = { line -> logWriter?.println(line); releaseAssetsOnce(line) }
                        onStderrLine = { line -> logWriter?.println(line) }
                    }

                    if (result is ExecutionResult.Failure && firstFailure == null) {
                        firstFailure = result
                    }

                    if (targetJson.exists()) {
                        jsonFiles.add(targetJson)
                        combinedJsonLines.addAll(targetJson.readLines())
                    }
                    if (inputSet.failFast && result is ExecutionResult.Failure) break
                }

                if (combinedJsonLines.isNotEmpty()) {
                    paths.json.writeText(combinedJsonLines.joinToString("\n"))
                }
                if (inputSet.coverage) {
                    TestTaskSupport.mergeCoverage(coverageParts, paths.lcov)
                }
            } else {
                val result = context.executeBash {
                    command(inputSet.flutterBin)
                    args("test")
                    args(inputSet.args)
                    if (inputSet.targets.isNotEmpty()) {
                        args(inputSet.targets)
                    }
                    if (inputSet.jsonReport) {
                        args("--file-reporter", "json:${paths.json.absolutePath}")
                    }
                    appendFlags(this, inputSet, configPath)

                    onStdoutLine = { line ->
                        logWriter?.println(line)
                        releaseAssetsOnce(line)
                        if (inputSet.startPaused) {
                            val endpoints = parseFlutterEndpoints(line)
                            endpoints.debugEndpoint?.let { context.emitOutput(outputs.debugEndpoint, it) }
                            endpoints.vmServiceUri?.let { context.emitOutput(outputs.vmServiceUri, it) }
                            endpoints.devToolsUri?.let { context.emitOutput(outputs.devToolsUri, it) }
                        }
                    }
                    onStderrLine = { line ->
                        logWriter?.println(line)
                    }
                }
                if (result is ExecutionResult.Failure) firstFailure = result
                jsonFiles.add(paths.json)
            }

            return TestTaskSupport.publish(
                context = context,
                outputs = outputs,
                summary = TestTaskSupport.summarize(jsonFiles, processFailed = firstFailure != null),
                paths = paths,
                options = TestPublishOptions(
                    taskName = name,
                    generateJunit = inputSet.generateJunit,
                    generateHtml = inputSet.generateHtml,
                    coverage = inputSet.coverage,
                    coverageExclude = inputSet.coverageExclude,
                    minCoverage = inputSet.minCoverage,
                    ignoreFailures = inputSet.ignoreFailures
                ),
                processFailure = firstFailure
            )
        } finally {
            logWriter?.flush()
            logWriter?.close()
        }
    }

    private fun appendFlags(
        spec: BashSpec,
        inputSet: FlutterTestInputs,
        configPath: String?,
        coveragePath: String? = inputSet.coveragePath,
    ) {
        inputSet.reporter?.let { spec.args("-r", it) }
        inputSet.device?.let { spec.args("-d", it) }

        if (configPath != null) {
            spec.args("--dart-define=CITYSCAPE_CONFIG=$configPath")
            spec.environment("CITYSCAPE_CONFIG", configPath)
        }

        for ((k, v) in inputSet.dartDefines) {
            spec.args("--dart-define=$k=$v")
        }

        inputSet.dartDefineFromFile?.let { spec.args("--dart-define-from-file=$it") }
        inputSet.flavor?.let { spec.args("--flavor", it) }
        if (inputSet.wasm) spec.args("--wasm")
        inputSet.name?.let { spec.args("--name", it) }
        inputSet.plainName?.let { spec.args("--plain-name", it) }
        inputSet.tags?.let { spec.args("--tags", it) }
        inputSet.excludeTags?.let { spec.args("--exclude-tags", it) }
        if (inputSet.failFast && "--fail-fast" !in inputSet.args) spec.args("--fail-fast")
        if (inputSet.runSkipped) spec.args("--run-skipped")
        inputSet.concurrency?.let { spec.args("-j", it.toString()) }
        inputSet.timeout?.let { spec.args("--timeout", it) }
        if (inputSet.ignoreTimeouts) spec.args("--ignore-timeouts")
        inputSet.randomizeOrderingSeed?.let { spec.args("--test-randomize-ordering-seed=$it") }
        inputSet.totalShards?.let { spec.args("--total-shards", it.toString()) }
        inputSet.shardIndex?.let { spec.args("--shard-index", it.toString()) }
        if (!inputSet.testAssets) spec.args("--no-test-assets")
        if (inputSet.updateGoldens && "--update-goldens" !in inputSet.args) spec.args("--update-goldens")
        if (inputSet.startPaused && "--start-paused" !in inputSet.args) spec.args("--start-paused")

        if (inputSet.coverage && "--coverage" !in inputSet.args) spec.args("--coverage")
        coveragePath?.let { spec.args("--coverage-path", it) }
        if (inputSet.branchCoverage) spec.args("--branch-coverage")
        if (inputSet.mergeCoverage) spec.args("--merge-coverage")
        inputSet.coveragePackage?.let { spec.args("--coverage-package", it) }
    }

}
