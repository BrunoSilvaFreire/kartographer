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
    var jsonReport: Boolean,
    var generateJunit: Boolean,
    var reporter: String?,
    var ignoreFailures: Boolean,
    var isolateTargets: Boolean,
    var cacheable: Boolean,
    var generateHtml: Boolean = false,
    var args: List<String>
) : InputSet()

class FlutterTestOutputs : OutputSet() {
    val totalCount = output<Int>("totalCount")
    val passedCount = output<Int>("passedCount")
    val failedCount = output<Int>("failedCount")
    val skippedCount = output<Int>("skippedCount")
    val durationMs = output<Long>("durationMs")
    val success = output<Boolean>("success")
    val jsonReportPath = output<String>("jsonReportPath")
    val junitReportPath = output<String>("junitReportPath")
    val failureSummary = output<String>("failureSummary")
    val coveragePercent = output<Double>("coveragePercent")
    val coverageLcov = output<String>("coverageLcov")
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
    val branchCoverage = property<Boolean> { set(false) }
    val mergeCoverage = property<Boolean> { set(false) }
    val coveragePackage = property<String>()

    val reportDir = property<String> { set("build/test-results") }
    val jsonReport = property<Boolean> { set(true) }
    val generateJunit = property<Boolean> { set(true) }
    val reporter = property<String>()
        .withCLI("reporter", "Reporter to use") { completeWith("expanded", "compact", "failures-only", "github", "json", "silent") }
    val ignoreFailures = property<Boolean> { set(false) }
    val isolateTargets = property<Boolean> { set(false) }
    val cacheable = property<Boolean> { set(false) }
    val generateHtml = property<Boolean> { set(false) }
        .withCLI("html", "Generate HTML coverage report") { completeWith("true", "false") }

    val args = mutableListOf<String>()
    fun arg(vararg arguments: String) { args.addAll(arguments) }

    init {
        topology { requiresCapability("flutter") }
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
            jsonReport = jsonReport.get(),
            generateJunit = generateJunit.get(),
            reporter = reporter.orNull(),
            ignoreFailures = ignoreFailures.get(),
            isolateTargets = isolateTargets.get(),
            cacheable = cacheable.get(),
            generateHtml = generateHtml.get(),
            args = args.toList()
        )
    }

    override fun buildOutputs() = FlutterTestOutputs()

    override suspend fun execute(context: ExecutionContext, inputSet: FlutterTestInputs): ExecutionResult<FlutterTestOutputs> {
        val outputs = buildOutputs()
        val projectDir = context.projectDir.toFile()

        if (inputSet.targets.isNotEmpty()) {
            val hasTests = inputSet.targets.any { targetPath ->
                if (targetPath.endsWith(".dart")) {
                    true
                } else {
                    val dir = File(projectDir, targetPath)
                    dir.exists() && (dir.isFile || dir.walkTopDown().any { it.isFile && it.name.endsWith("_test.dart") })
                }
            }
            if (!hasTests) {
                val msg = "No tests found in ${inputSet.targets.joinToString(", ")}"
                context.observer?.onEvent(ExecutionEvent.TaskOutput(this, msg, isError = false))
                println(msg)
                return ExecutionResult.Success(outputs)
            }
        }

        val repDirName = inputSet.reportDir ?: "build/test-results"
        val resolvedReportDir = File(projectDir, repDirName).apply { mkdirs() }
        val mainJsonFile = File(resolvedReportDir, "$name.json")
        val junitFile = File(resolvedReportDir, "$name.xml")

        val configPath = inputSet.config?.let {
            if (it.endsWith(".yaml")) it else "config/$it.yaml"
        }

        if (inputSet.isolateTargets) {
            val testFiles = resolveTestFiles(projectDir, inputSet.targets)
            val summaries = mutableListOf<FlutterTestRunSummary>()
            val combinedJsonLines = mutableListOf<String>()
            var firstFailure: ExecutionResult.Failure<*>? = null

            for ((idx, testFile) in testFiles.withIndex()) {
                val relPath = testFile.relativeTo(projectDir).path
                val targetJson = File(resolvedReportDir, "${name}_${idx}_${testFile.nameWithoutExtension}.json")

                val result = context.executeBash {
                    command(inputSet.flutterBin)
                    args("test")
                    args(inputSet.args)
                    args(relPath)
                    if (inputSet.jsonReport) {
                        args("--file-reporter", "json:${targetJson.absolutePath}")
                    }
                    appendFlags(this, inputSet, configPath)
                }

                if (result is ExecutionResult.Failure && firstFailure == null) {
                    firstFailure = result
                }

                if (targetJson.exists()) {
                    combinedJsonLines.addAll(targetJson.readLines())
                    val sum = FlutterTestReportParser.parseJsonReport(targetJson)
                    summaries.add(sum)
                }
                if (inputSet.failFast && (summaries.lastOrNull()?.failedCount ?: 0) > 0) {
                    break
                }
            }

            if (combinedJsonLines.isNotEmpty()) {
                mainJsonFile.writeText(combinedJsonLines.joinToString("\n"))
            }

            val combinedSummary = FlutterTestReportParser.combineSummaries(summaries)
            if (inputSet.generateJunit) {
                FlutterTestReportParser.generateJUnitXml(combinedSummary, name, junitFile)
            }

            return populateOutputsAndReturn(
                context, outputs, combinedSummary, mainJsonFile, junitFile, inputSet, projectDir, firstFailure
            )
        } else {
            val result = context.executeBash {
                command(inputSet.flutterBin)
                args("test")
                args(inputSet.args)
                if (inputSet.targets.isNotEmpty()) {
                    args(inputSet.targets)
                }
                if (inputSet.jsonReport) {
                    args("--file-reporter", "json:${mainJsonFile.absolutePath}")
                }
                appendFlags(this, inputSet, configPath)

                onStdoutLine = { line ->
                    if (inputSet.startPaused) {
                        val endpoints = parseFlutterEndpoints(line)
                        endpoints.debugEndpoint?.let { context.emitOutput(outputs.debugEndpoint, it) }
                        endpoints.vmServiceUri?.let { context.emitOutput(outputs.vmServiceUri, it) }
                        endpoints.devToolsUri?.let { context.emitOutput(outputs.devToolsUri, it) }
                    }
                }
            }

            val summary = if (mainJsonFile.exists()) {
                FlutterTestReportParser.parseJsonReport(mainJsonFile)
            } else {
                FlutterTestRunSummary(
                    totalCount = 0,
                    passedCount = 0,
                    failedCount = if (result is ExecutionResult.Failure) 1 else 0,
                    skippedCount = 0,
                    durationMs = 0L,
                    success = result is ExecutionResult.Success,
                    testCases = emptyList()
                )
            }

            if (inputSet.generateJunit) {
                FlutterTestReportParser.generateJUnitXml(summary, name, junitFile)
            }

            return populateOutputsAndReturn(
                context, outputs, summary, mainJsonFile, junitFile, inputSet, projectDir, result as? ExecutionResult.Failure
            )
        }
    }

    private fun appendFlags(spec: BashSpec, inputSet: FlutterTestInputs, configPath: String?) {
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
        inputSet.coveragePath?.let { spec.args("--coverage-path", it) }
        if (inputSet.branchCoverage) spec.args("--branch-coverage")
        if (inputSet.mergeCoverage) spec.args("--merge-coverage")
        inputSet.coveragePackage?.let { spec.args("--coverage-package", it) }
    }

    private fun resolveTestFiles(baseDir: File, targets: List<String>): List<File> {
        if (targets.isEmpty()) {
            val defaultTestDir = File(baseDir, "test")
            return if (defaultTestDir.exists()) {
                defaultTestDir.walkTopDown().filter { it.isFile && it.name.endsWith("_test.dart") }.sortedBy { it.path }.toList()
            } else emptyList()
        }
        val result = mutableListOf<File>()
        for (target in targets) {
            val file = File(baseDir, target)
            if (file.isDirectory) {
                result.addAll(file.walkTopDown().filter { it.isFile && it.name.endsWith("_test.dart") }.sortedBy { it.path })
            } else if (file.isFile) {
                result.add(file)
            } else if (target.contains("*")) {
                val parts = target.split('/', '\\')
                val parentPath = parts.dropLast(1).joinToString("/")
                val pattern = parts.last()
                val parentDir = if (parentPath.isEmpty()) baseDir else File(baseDir, parentPath)
                if (parentDir.exists() && parentDir.isDirectory) {
                    val regex = Regex("^" + pattern.replace(".", "\\.").replace("*", ".*") + "$")
                    parentDir.listFiles { f -> f.isFile && regex.matches(f.name) }?.sortedBy { it.path }?.let { result.addAll(it) }
                }
            } else {
                result.add(file)
            }
        }
        return result.distinct()
    }

    private suspend fun populateOutputsAndReturn(
        context: ExecutionContext,
        outputs: FlutterTestOutputs,
        summary: FlutterTestRunSummary,
        mainJsonFile: File,
        junitFile: File,
        inputSet: FlutterTestInputs,
        projectDir: File,
        bashFailure: ExecutionResult.Failure<*>? = null
    ): ExecutionResult<FlutterTestOutputs> {
        context.emitOutput(outputs.totalCount, summary.totalCount)
        context.emitOutput(outputs.passedCount, summary.passedCount)
        context.emitOutput(outputs.failedCount, summary.failedCount)
        context.emitOutput(outputs.skippedCount, summary.skippedCount)
        context.emitOutput(outputs.durationMs, summary.durationMs)
        context.emitOutput(outputs.success, summary.success)

        if (mainJsonFile.exists()) {
            val relJson = mainJsonFile.relativeTo(projectDir).path
            context.emitOutput(outputs.jsonReportPath, relJson)
        }
        if (junitFile.exists()) {
            val relJunit = junitFile.relativeTo(projectDir).path
            context.emitOutput(outputs.junitReportPath, relJunit)
        }

        val failureSummary = FlutterTestReportParser.formatFailureSummary(summary)
        if (failureSummary.isNotBlank()) {
            context.emitOutput(outputs.failureSummary, failureSummary)
        }

        if (inputSet.coverage) {
            val lcovPath = inputSet.coveragePath ?: "coverage/lcov.info"
            val lcovFile = File(projectDir, lcovPath)
            val lcovSummary = FlutterTestReportParser.parseLcovCoverage(lcovFile)
            if (lcovSummary != null) {
                context.emitOutput(outputs.coveragePercent, lcovSummary.coveragePercent)
                context.emitOutput(outputs.coverageLcov, lcovFile.relativeTo(projectDir).path)
            }
        }

        val hasFailed = !summary.success || (bashFailure != null) || summary.failedCount > 0
        if (!hasFailed && inputSet.generateHtml) {
            val lcovPath = inputSet.coveragePath ?: "coverage/lcov.info"
            val lcovFile = File(projectDir, lcovPath)
            if (lcovFile.exists()) {
                val htmlDir = File(lcovFile.parentFile, "html")
                context.executeBash {
                    command("genhtml")
                    args(lcovFile.absolutePath, "-o", htmlDir.absolutePath)
                }
            }
        }

        if (hasFailed && !inputSet.ignoreFailures) {
            val msg = if (summary.failedCount > 0) {
                "Flutter test task '$name' failed: ${summary.failedCount} of ${summary.totalCount} tests failed."
            } else {
                bashFailure?.error?.message ?: "Flutter test task '$name' failed."
            }
            val exitCode = bashFailure?.exitCode ?: 1
            return ExecutionResult.Failure(RuntimeException(msg), exitCode, outputs)
        }

        return ExecutionResult.Success(outputs)
    }
}
