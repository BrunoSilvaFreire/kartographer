# Spike: Expand Kartographer Gherkin Integration Test Suite with Coverage Tracking

**Date:** 2026-07-13
**Session:** `hrc` session `019f5d6b` (latest active session on this machine)
**Branch:** `master`

## 1. Key Architectural Facts (do not rediscover)

- Kartographer is a Kotlin build orchestration tool with a DAG-based task execution model using coroutines.
- The project has 12 sub-modules: `core`, `daemon`, `cli`, `plugin`, `integration-tests`, and 9 task library modules.
- `core` contains the foundation: `Node`/`Task`/`Validation` DAG model, `ConcurrentScheduler` (parallel execution), `TaskStateStore` (SHA-256-based incremental build state), `Property<T>` system, `ExecutionSpec`/`BashSpec` command generation, `UpToDateCheck` predicates.
- `Task` is the abstract base; `DefaultTask` (in `core/src/main/kotlin/io/kartographer/tasks/Tasks.kt`) adds an `action {}` block for ad-hoc tasks.
- All 9 task library modules depend only on `:core` and each provides a single Kotlin source file defining task subclasses.
- Integration tests use **Cucumber JVM 7.18.0** with JUnit Platform Suite engine.
- The `CucumberTestRunner` uses `@SelectClasspathResource("io/kartographer/integration")` — all `.feature` files under that path are discovered.
- Step definitions are in a single monolithic class: `StepDefinitions.kt` (401 lines).
- No code coverage tool (JaCoCo, Kover) is configured anywhere in the project.
- The `GradleTask` integration test is **deterministically broken** — it generates `./gradlew '-b build.gradle.kts' '-p subproject' clean test` with quoted args instead of `./gradlew -b build.gradle.kts -p subproject clean test`.
- 7 core unit tests exist (`KartographerTest`, `ConcurrentSchedulerTest`, `FilesystemTasksTest`, `UpToDateTest`, `PropertiesTest`, `TaskSerializerTest`, `LocalProcessExecutorTest`).
- Among task libraries, only `tasks:go` has unit tests (`GoTasksTest.kt`); `tasks:containers` has JUnit 5 dependency but **no test file**.
- The daemon module has its own `DaemonIntegrationTest` (JUnit 5, not Gherkin) that starts a real gRPC server, dispatches a task via `RemoteWorkerClient`, and verifies log output.
- The `DaemonServer` uses gRPC with protobuf definitions at `proto/kartographer_daemon.proto`.
- Real-world usage patterns exist in `pilots/*/karto.kts` files — these are Kotlin script pipelines exercising the DSL (task creation, group nesting, property wiring, cross-task dependencies).

### Task Library Modules (all 9)

| Module | File | Task Classes | Requires Capability |
|--------|------|-------------|---------------------|
| `tasks:go` | `GoTasks.kt` | `GoBuild`, `GoTest`, `GoMod` + `GoSpec` exec spec | `go` |
| `tasks:proto` | `ProtoTasks.kt` | `ProtoCompile` | `protoc` |
| `tasks:gradle` | `GradleTasks.kt` | `GradleTask` | (none) |
| `tasks:containers` | `ContainerTasks.kt` | `ContainerBuild`, `ContainerPush` | (none) |
| `tasks:flutter` | `FlutterTasks.kt` | `FlutterRun`, `FlutterBuild`, `FlutterTest` | `flutter` |
| `tasks:python` | `PythonTasks.kt` | `PythonExecTask` + `PythonSpec` exec spec | `python` |
| `tasks:apt` | `AptTasks.kt` | `AptPackage`, `AptInstall` | (none) |
| `tasks:pulumi` | `PulumiTasks.kt` | `PulumiUp`, `PulumiPreview`, `PulumiRefresh`, `PulumiDestroy` | `pulumi` |
| `tasks:ansible` | `AnsibleTasks.kt` | `AnsiblePlaybookRun` | `ansible` |
| `core` | `FilesystemTasks.kt` | `Copy`, `Sync`, `Move`, `Delete` | (none) |

### Execution Model

- `ExecutionContext` holds: project dir, environment vars, worker info, shared variables (ConcurrentHashMap), observer, dryRun flag, runId, I/O redirects, prompter, `ExecutionEnvironment`.
- `ExecutionEnvironment` is an interface with `suspend fun execute(command, workingDir, environment, requiresElevation): Int`.
- The scheduler uses `supervisorScope` + cooperative `Mutex`-protected polling (`delay(10)`).
- The `ConcurrentScheduler` takes `checkTopology: (Node) -> Boolean` as a parameter — integration tests inject their own validation.
- `TaskStateStore` persists state to `.kartographer/state/<safe-name>.json` using `kotlinx.serialization`.
- `ExecutionResult` is a sealed interface: `Success`, `UpToDate`, `Failure(error, exitCode)`.
- `UpToDateHelpers` provides 8 composable checks: `propertiesMatch`, `inputsMatch`, `outputsMatch`, `customPropertiesMatch`, `customFilesMatch`, `inputsOlderThanOutputs`, `envVarsMatch`, `systemPropertiesMatch`.

### Scripting/DSL Layer

- `TaskRegistry` is a global singleton (`object`) backed by `ConcurrentHashMap<TaskId, Node>`.
- Key DSL functions: `task<T>()`, `group()`, `project {}`.
- `Group` is literally `class Group(name: String) : Node(name)` — just a namespace node.
- `Property<T>` supports direct values, lazily-resolved `Provider<T>`, CLI integration via `withCLI()`, and auto-completion via `completeWith()`.
- `TaskOutputProvider` wraps output resolution from one task, enabling cross-task property wiring via `dependsOn`.

### Daemon (gRPC-based remote execution)

- `DaemonServer` starts a gRPC server on a configurable port.
- `KartographerDaemonService` implements `executeTask()`: loads task class via `PluginLoader`, deserializes properties via `TaskSerializer`, builds `ExecutionContext` with `ProcessBuilder.Redirect.appendTo(logFile)`, executes synchronously.
- `RemoteWorkerClient` is the client-side stub.
- Log fallback logic: tries `/var/log/kartographer/runs/` first, then `~/.kartographer/logs/runs/`.

### Test Execution Commands

- Build: `./gradlew build` from project root.
- Integration tests only: `./gradlew :integration-tests:test`.
- All tests: `./gradlew test`.
- Cucumber engine auto-discovers all `.feature` files under `io/kartographer/integration`.

## 2. The Problem

The integration test suite has only 4 feature files covering a narrow subset of the system:
- Pipeline scheduling (3 scenarios — transitive deps, parallel execution, failure propagation)
- Topology requirements (4 scenarios — capabilities and labels)
- Up-to-date system (4 scenarios — file hashing, timestamps, property tracking)
- Task libraries (3 scenarios — only GoBuild, ProtoCompile, GradleTask dry-runs)

**What's missing:**
- 1 of 14 scenarios is deterministically broken (GradleTask command generation bug).
- No integration test invokes real process execution — all tests use a mock `ExecutionEnvironment` that returns 0.
- No integration test exercises the `DaemonServer` + `RemoteWorkerClient` end-to-end path in Gherkin (only a JUnit test exists).
- No integration test exercises cross-task property wiring (task A produces a value consumed by task B).
- No integration test exercises filesystem tasks (`Copy`, `Sync`, `Move`, `Delete`) with real file I/O.
- No integration test exercises the `ExecSpec`/`BashSpec` system (command escaping, environment merging, requiresElevation).
- No integration test exercises the `TaskStateStore` persistence (save/load cycle).
- No integration test exercises the `ExecutionObserver` / `ExecutionEvent` event stream.
- No tests for `ContainerBuild`, `ContainerPush`, `AptPackage`, `AptInstall`, `FlutterTasks`, `PythonExecTask`, `PulumiTask`, `AnsiblePlaybookRun`.
- No coverage tooling exists — impossible to know current coverage percentage or track improvements.
- No test report generation (HTML, JSON, or XML aggregation beyond Gradle defaults).
- No tag-based filtering for Cucumber scenarios (e.g., `@smoke`, `@slow`, `@flakey`).

## 3. What Already Exists

### Integration test infrastructure (working)
- Cucumber JVM 7.18.0 with JUnit Platform Suite.
- Single `CucumberTestRunner` class with `@Suite` annotation.
- Monolithic `StepDefinitions.kt` with all step definitions (401 lines).
- Mock `ExecutionEnvironment` that captures commands via `capturedCommands`.
- `TestDefaultTask`, `TestCopyTask`, `TestPropTask` inner classes for test fixture tasks.
- 13 passing scenarios, 1 failing (GradleTask bug).

### Unit tests (working)
- `ConcurrentSchedulerTest.kt` — tests scheduler behavior.
- `FilesystemTasksTest.kt` — tests Copy, Sync, Move, Delete with real file I/O.
- `UpToDateTest.kt` — tests hash-based and timestamp-based up-to-date logic.
- `PropertiesTest.kt` — tests Property<T> system.
- `KartographerTest.kt` — general core tests.
- `TaskSerializerTest.kt` — tests property serialization.
- `LocalProcessExecutorTest.kt` — tests real process execution.
- `GoTasksTest.kt` — tests GoBuild and GoTest command generation.

### Prior Gherkin integration pattern (from correlator project)
- A correlator project (Go) has a full godog/Gherkin integration test suite with: tag filtering (`@integration && ~@vpn`), strict mode, VPN-conditional scenarios.
- That project serves as a proven pattern for meaningful integration tests that invoke real infrastructure.

### Real pilot scripts (demonstrating actual use cases)
- `pilots/app/karto.kts` — FlutterRun/FlutterBuild/ContainerBuild/ContainerPush pipeline.
- `pilots/backend/karto.kts` — PythonExecTask/ContainerBuild/ContainerPush pipeline.
- `pilots/database/karto.kts` — AnsiblePlaybookRun + custom database tasks (CreateDB, MigrateUp, Drop, Reset, Dump, Metrics).
- `pilots/scraping/karto.kts` — custom scraping tasks + container builds.
- `pilots/infra/karto.kts`, `pilots/correlator/karto.kts`, `pilots/tileserver-go/karto.kts` — more pipelines.

## 4. Requirements / Acceptance Criteria

1. **Expanded feature coverage**: Add Gherkin scenarios for at least 6 of the 9 task libraries that currently lack integration tests (Flutter, Python, Containers, Apt, Pulumi, Ansible, Filesystem tasks).

2. **Real flow coverage**: Scenarios must exercise cross-task dependency wiring, property resolution, and the full DAG execution path — not just dry-run command generation.

3. **BashSpec / ExecSpec validation**: Tests that verify command escaping behavior (spaces, quotes, special chars in arguments), environment variable merging, and the `requiresElevation` flag.

4. **TaskStateStore persistence**: Tests that verify save/load round-trip correctness.

5. **ExecutionObserver event stream**: Tests that verify correct event ordering (Queued → Started → Completed/PipelineCompleted).

6. **Fix the GradleTask bug**: The deterministic integration test failure must be resolved.

7. **Code coverage tracking**: Integrate a coverage tool (Kover or JaCoCo) into the integration-tests module.

8. **Coverage reports**: Generate HTML and/or console coverage reports that can be consumed by CI or dev workflow.

9. **Cucumber tag filtering**: Add `@smoke`, `@integration`, `@slow` tags to scenarios and support running by tag via `cucumber.filter.tags` configuration parameter.

10. **Report generation**: Produce a test execution report (at minimum, build on Gradle's HTML report; optionally generate a JSON report for CI consumption).

11. **Meaningful assertions**: Tests must verify observable behavior — file contents after copy, exit codes from failed commands, topology rejection reasons, property value propagation — not just "command starts with expected string."

## 5. Non-goals

- Do NOT rewrite existing unit tests.
- Do NOT modify the `daemon` module's JUnit integration test.
- Do NOT add integration tests that require real external infrastructure (real Docker daemon, real protoc, real Flutter SDK).
- Do NOT implement a new test framework — stay within Cucumber JVM + Gradle.
- Do NOT create tests for the `cli` module (it is still stabilizing).
- Do NOT change the scheduling algorithm or DAG resolution logic.

## 6. Evidence Gathered

- Read `integration-tests/build.gradle.kts` (full file) → confirms Cucumber JVM 7.18.0 + JUnit Platform Suite, no coverage tool configured.
- Read `CucumberTestRunner.kt` (full file) → confirms `@SelectClasspathResource("io/kartographer/integration")`, no tag filtering.
- Read `StepDefinitions.kt` (full 401 lines) → confirms monolithic step definitions, mock `ExecutionEnvironment` pattern, inner test task classes.
- Read all 4 `.feature` files → confirms 14 scenarios across 4 feature areas.
- Ran `./gradlew :integration-tests:test` → **14 tests, 1 failed**: GradleTask command generation produces shell-quoted args `'./gradlew '-b build.gradle.kts' '-p subproject' clean test'` instead of unquoted `'./gradlew -b build.gradle.kts -p subproject clean test'`.
- Grepped for `jacoco|kover|coverage` across all `build.gradle.kts` files → **zero matches** — no coverage tool configured anywhere.
- Read all 9 task library source files → only `GoBuild`, `ProtoCompile`, `GradleTask` have Gherkin integration steps. 6 task libraries have zero integration tests.
- Read `core/src/main/kotlin/io/kartographer/core/model/Execution.kt` → confirms `BashSpec` performs single-quote escaping with `'\\''` pattern for args containing spaces/quotes. The bug is in `GradleTask` which passes `-b $it` and `-p $it` as **separate string args** that get individually quoted.
- Read `core/src/main/kotlin/io/kartographer/core/model/IO.kt` → confirms `TaskInputs.file()`/`.dir()` and `TaskOutputs.file()`/`.dir()` extension functions.
- Read `core/src/main/kotlin/io/kartographer/tasks/FilesystemTasks.kt` → `Copy`, `Sync`, `Move`, `Delete` are real filesystem-operating tasks using `Files.walk()`, `Files.copy()`, with `FileFilter` support.
- Read `core/src/main/kotlin/io/kartographer/core/Properties.kt` → confirms `Property<T>` with `setProvider()`, `orNull`, `withCLI()`, `completeWith()`.
- Read `core/src/main/kotlin/io/kartographer/core/scheduler/TaskState.kt` → confirms `TaskStateStore` persists JSON to `.kartographer/state/` with SHA-256 hashes.
- Read `core/src/main/kotlin/io/kartographer/core/model/ExecutionEvent.kt` → confirms 8 event types: `PipelineStarted`, `TaskQueued`, `TaskStarted`, `TaskOutput`, `TaskCompleted`, `PipelineCompleted`, `PromptRequested`, `PromptCompleted`.
- Read `daemon/src/main/kotlin/io/kartographer/daemon/DaemonServer.kt` → confirms gRPC-based task execution with `PluginLoader`, `TaskSerializer`, log file redirection, graceful shutdown hook.
- Read `pilots/app/karto.kts`, `pilots/backend/karto.kts`, `pilots/database/karto.kts` → confirms real-world use of `ContainerBuild → ContainerPush` dependency chain, `FlutterRun` with device/config/mode matrix, database lifecycle tasks.
- Searched `hrc` for "kartographer" → no results (this repo wasn't scraped by hrc previously).
- Searched `hrc` for "integration test gherkin cucumber" → found correlator project with godog/Gherkin reference showing tag-filtered integration tests as a proven pattern.
- Searched Antigravity brain artifacts → found `065090d5` ("Kartographer Core Hardening and APT MVP Tasks") with implementation plan for core model refactoring and APT tasks.

## 7. Source Reference Index

- `[integration-tests/build.gradle.kts:1-32](integration-tests/build.gradle.kts)` — Integration test Gradle config with Cucumber/JUnit Platform dependencies.
- `[integration-tests/src/test/kotlin/io/kartographer/integration/CucumberTestRunner.kt:1-12](integration-tests/src/test/kotlin/io/kartographer/integration/CucumberTestRunner.kt)` — Cucumber suite runner with classpath resource scanning.
- `[integration-tests/src/test/kotlin/io/kartographer/integration/StepDefinitions.kt:1-401](integration-tests/src/test/kotlin/io/kartographer/integration/StepDefinitions.kt)` — All step definitions (monolithic, mock-based).
- `[integration-tests/src/test/resources/io/kartographer/integration/pipeline_execution.feature:1-28](integration-tests/src/test/resources/io/kartographer/integration/pipeline_execution.feature)` — Pipeline execution scenarios.
- `[integration-tests/src/test/resources/io/kartographer/integration/topology_requirements.feature:1-28](integration-tests/src/test/resources/io/kartographer/integration/topology_requirements.feature)` — Topology requirement scenarios.
- `[integration-tests/src/test/resources/io/kartographer/integration/uptodate_system.feature:1-47](integration-tests/src/test/resources/io/kartographer/integration/uptodate_system.feature)` — Up-to-date system scenarios.
- `[integration-tests/src/test/resources/io/kartographer/integration/task_libraries.feature:1-19](integration-tests/src/test/resources/io/kartographer/integration/task_libraries.feature)` — Task library dry-run scenarios (broken GradleTest).
- `[core/src/main/kotlin/io/kartographer/core/model/Node.kt:1-174](core/src/main/kotlin/io/kartographer/core/model/Node.kt)` — `Task` class with inputs/outputs, properties, topology, up-to-date checks.
- `[core/src/main/kotlin/io/kartographer/core/model/Execution.kt:1-109](core/src/main/kotlin/io/kartographer/core/model/Execution.kt)` — `ExecutionContext`, `ExecutionResult`, `BashSpec`, `executeSpec()`, `executeBash()`.
- `[core/src/main/kotlin/io/kartographer/core/model/IO.kt:1-55](core/src/main/kotlin/io/kartographer/core/model/IO.kt)` — `TaskInput`/`TaskOutput` abstract classes with file/dir variants and extension DSL.
- `[core/src/main/kotlin/io/kartographer/core/model/Topology.kt:1-44](core/src/main/kotlin/io/kartographer/core/model/Topology.kt)` — `TopologyRequirement` sealed interface, `Worker` data class, `TopologyBuilder`.
- `[core/src/main/kotlin/io/kartographer/core/Properties.kt:1-108](core/src/main/kotlin/io/kartographer/core/Properties.kt)` — `Property<T>` with CLI support, `Provider<T>`, `TaskOutputProvider`.
- `[core/src/main/kotlin/io/kartographer/core/scheduler/ConcurrentScheduler.kt:1-202](core/src/main/kotlin/io/kartographer/core/scheduler/ConcurrentScheduler.kt)` — Parallel DAG scheduler with coroutine-based execution.
- `[core/src/main/kotlin/io/kartographer/core/scheduler/UpToDateCheck.kt:1-134](core/src/main/kotlin/io/kartographer/core/scheduler/UpToDateCheck.kt)` — `UpToDateHelpers` with 8 composable predicates.
- `[core/src/main/kotlin/io/kartographer/core/scheduler/TaskState.kt:1-97](core/src/main/kotlin/io/kartographer/core/scheduler/TaskState.kt)` — `TaskStateStore`: JSON-based state persistence with SHA-256 hashing.
- `[core/src/main/kotlin/io/kartographer/tasks/FilesystemTasks.kt:1-188](core/src/main/kotlin/io/kartographer/tasks/FilesystemTasks.kt)` — `Copy`, `Sync`, `Move`, `Delete` tasks with real filesystem operations.
- `[core/src/main/kotlin/io/kartographer/tasks/Tasks.kt:1-26](core/src/main/kotlin/io/kartographer/tasks/Tasks.kt)` — `DefaultTask` with `action {}` block.
- `[core/src/main/kotlin/io/kartographer/dsl/Registry.kt:1-82](core/src/main/kotlin/io/kartographer/dsl/Registry.kt)` — `TaskRegistry` singleton, `task<T>()`, `group()`, `project {}` DSL.
- `[tasks/go/src/main/kotlin/io/kartographer/tasks/go/GoTasks.kt:1-80](tasks/go/src/main/kotlin/io/kartographer/tasks/go/GoTasks.kt)` — `GoBuild`, `GoTest`, `GoMod` + `GoSpec`.
- `[tasks/proto/src/main/kotlin/io/kartographer/tasks/proto/ProtoTasks.kt:1-46](tasks/proto/src/main/kotlin/io/kartographer/tasks/proto/ProtoTasks.kt)` — `ProtoCompile`.
- `[tasks/gradle/src/main/kotlin/io/kartographer/tasks/gradle/GradleTasks.kt:1-34](tasks/gradle/src/main/kotlin/io/kartographer/tasks/gradle/GradleTasks.kt)` — `GradleTask` (bug source: individually-quoted `-b`/`-p` args).
- `[tasks/containers/src/main/kotlin/io/kartographer/tasks/containers/ContainerTasks.kt:1-76](tasks/containers/src/main/kotlin/io/kartographer/tasks/containers/ContainerTasks.kt)` — `ContainerBuild`, `ContainerPush` with `ContainerClient` enum.
- `[tasks/flutter/src/main/kotlin/io/kartographer/tasks/flutter/FlutterTasks.kt:1-89](tasks/flutter/src/main/kotlin/io/kartographer/tasks/flutter/FlutterTasks.kt)` — `FlutterRun`, `FlutterBuild`, `FlutterTest`.
- `[tasks/python/src/main/kotlin/io/kartographer/tasks/python/PythonTasks.kt:1-75](tasks/python/src/main/kotlin/io/kartographer/tasks/python/PythonTasks.kt)` — `PythonExecTask` + `PythonSpec` with venv resolution.
- `[tasks/apt/src/main/kotlin/io/kartographer/tasks/apt/AptTasks.kt:1-127](tasks/apt/src/main/kotlin/io/kartographer/tasks/apt/AptTasks.kt)` — `AptPackage` (creates .deb) + `AptInstall` (apt-get install).
- `[tasks/pulumi/src/main/kotlin/io/kartographer/tasks/pulumi/PulumiTasks.kt:1-61](tasks/pulumi/src/main/kotlin/io/kartographer/tasks/pulumi/PulumiTasks.kt)` — `PulumiUp`, `PulumiPreview`, `PulumiRefresh`, `PulumiDestroy`.
- `[tasks/ansible/src/main/kotlin/io/kartographer/tasks/ansible/AnsibleTasks.kt:1-44](tasks/ansible/src/main/kotlin/io/kartographer/tasks/ansible/AnsibleTasks.kt)` — `AnsiblePlaybookRun`.
- `[daemon/src/main/kotlin/io/kartographer/daemon/DaemonServer.kt:1-181](daemon/src/main/kotlin/io/kartographer/daemon/DaemonServer.kt)` — gRPC daemon with `executeTask` RPC.
- `[daemon/src/test/kotlin/io/kartographer/daemon/DaemonIntegrationTest.kt:1-67](daemon/src/test/kotlin/io/kartographer/daemon/DaemonIntegrationTest.kt)` — JUnit test that starts daemon, dispatches task, verifies log.
- `[pilots/app/karto.kts:1-62](pilots/app/karto.kts)` — Real pipeline: FlutterRun matrix + ContainerBuild/Push chain.
- `[pilots/database/karto.kts:1-84](pilots/database/karto.kts)` — Real pipeline: Ansible + database lifecycle tasks.
- `[build.gradle.kts:1-24](build.gradle.kts)` — Root build file: Kotlin + protobuf + compose plugins, `maven-publish` for all subprojects.

## 8. Commands Run and Observed Outputs

### Build and Test Execution

```bash
# Full integration test run
./gradlew :integration-tests:test
→ 14 tests completed, 1 failed
→ PASSED: 13 scenarios (pipeline execution 3/3, topology 4/4, up-to-date 4/4, task libraries 2/3)
→ FAILED: "Execute a GradleTask and check command generation"
→ Error: Command './gradlew '-b build.gradle.kts' '-p subproject' clean test' did not start with './gradlew -b build.gradle.kts -p subproject clean test'
→ Root cause: BashSpec quotes args containing dashes as '-b build.gradle.kts' (single shell token) instead of -b build.gradle.kts (two tokens)

# Dependency tree check
./gradlew :integration-tests:dependencies --configuration testRuntimeClasspath
→ cucumber-java 7.18.0, cucumber-junit-platform-engine 7.18.0, junit-platform-suite 1.10.1, junit-jupiter 5.10.2
```

### Coverage Tool Search

```bash
grep -r "jacoco\|kover\|coverage" build.gradle.kts */build.gradle.kts
→ (no output — no coverage tool configured anywhere)
```

### Module Structure Verification

```bash
# Task library modules
ls -d tasks/*/
→ tasks/ansible tasks/apt tasks/containers tasks/flutter tasks/go tasks/gradle tasks/proto tasks/pulumi tasks/python

# Unit test files
find . -path "*/src/test/kotlin/*.kt" | sort
→ ./core/src/test/kotlin/io/kartographer/ConcurrentSchedulerTest.kt
→ ./core/src/test/kotlin/io/kartographer/FilesystemTasksTest.kt
→ ./core/src/test/kotlin/io/kartographer/KartographerTest.kt
→ ./core/src/test/kotlin/io/kartographer/LocalProcessExecutorTest.kt
→ ./core/src/test/kotlin/io/kartographer/PropertiesTest.kt
→ ./core/src/test/kotlin/io/kartographer/TaskSerializerTest.kt
→ ./core/src/test/kotlin/io/kartographer/UpToDateTest.kt
→ ./daemon/src/test/kotlin/io/kartographer/daemon/DaemonIntegrationTest.kt
→ ./tasks/go/src/test/kotlin/io/kartographer/tasks/go/GoTasksTest.kt
```

### Feature File Inventory

```bash
find integration-tests/ -name "*.feature"
→ integration-tests/src/test/resources/io/kartographer/integration/pipeline_execution.feature
→ integration-tests/src/test/resources/io/kartographer/integration/topology_requirements.feature
→ integration-tests/src/test/resources/io/kartographer/integration/uptodate_system.feature
→ integration-tests/src/test/resources/io/kartographer/integration/task_libraries.feature
```

### Prior Session Search

```bash
hrc search "kartographer" → (no results — repo not previously indexed)
hrc search "integration test gherkin cucumber" → found correlator Go godog session (fbece922) with proven Gherkin integration pattern
```

## 9. Known Traps and Edge Cases

- **GradleTask bug**: The `GradleTask` at `tasks/gradle/src/main/kotlin/io/kartographer/tasks/gradle/GradleTasks.kt:24-25` passes `-b $it` and `-p $it` as **single string arguments** to `args()`. The `BashSpec` then wraps them in single quotes because they contain spaces, producing `'./gradlew '-b build.gradle.kts' '-p subproject' clean test'`. Compare with `ProtoCompile` which correctly uses `--proto_path=$it` (no space between flag and value). This is a real implementation bug, not just a test bug.
- **Mock-only testing**: All 13 passing integration tests use a mock `ExecutionEnvironment` that returns 0. No scenario ever invokes an actual process. This means the executor layer (`LocalProcessExecutor`, `HostExecutionEnvironment`) is **untested by integration tests**.
- **No coverage baseline**: Without JaCoCo or Kover, there is zero visibility into which code paths are exercised. Adding coverage tooling after expanding tests will give the first baseline.
- **Monolithic step definitions**: All step definitions live in a single 401-line `StepDefinitions.kt`. Adding more feature files to this class will compound the maintainability problem. The architect should consider splitting step definitions by feature area.
- **Global mutable state in DSL**: `TaskRegistry` is a singleton `object` with a `ConcurrentHashMap`. Multiple scenarios in sequence share the same registry unless `clear()` is called between tests. This is not done in the current step definitions.
- **Cucumber tag configuration**: The `CucumberTestRunner` currently has no `@ConfigurationParameter(key = "cucumber.filter.tags")`. Adding tags without configuring the filter will cause all scenarios to run regardless of tags — tags become documentation-only.
- **container tests require Docker**: `ContainerBuild` and `ContainerPush` execute real `docker`/`nerdctl` commands. Testing these requires either a mock, a Docker socket check, or skipping via tags.
- **`maven-publish` in root build**: The root `build.gradle.kts` applies `maven-publish` with an empty `from(components["java"])` on all Kotlin subprojects. Some subprojects may fail to publish if they lack a `java` component.
