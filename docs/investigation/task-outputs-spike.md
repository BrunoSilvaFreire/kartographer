# Spike: Well-Defined Task Outputs for Kartographer Pipeline Completion

**Date:** 2026-08-05
**Session:** `opencode:ses_02d3eaccaffeSH5DENWQIlgTeh`

---

## 1. Key Architectural Facts (do not rediscover)

### Task I/O Model (current, limited)

- `TaskOutput` is an abstract class with only two concrete subclasses: `FileOutput(path)` and `DirOutput(path)`. Both carry only a `path: String`. Source: `[core/src/main/kotlin/io/kartographer/core/model/IO.kt:10-15](/home/brunorbsf/MiscProjects/kartographer/core/src/main/kotlin/io/kartographer/core/model/IO.kt:10-15)`.
- `TaskOutputs` (the container on `Task.outputs`) exposes exactly two accessors: `files: Set<String>` and `dirs: Set<String>`, extracted from the `MutableSet<TaskOutput>` by filtering on concrete type. Source: `[core/src/main/kotlin/io/kartographer/core/model/IO.kt:27-35](/home/brunorbsf/MiscProjects/kartographer/core/src/main/kotlin/io/kartographer/core/model/IO.kt:27-35)`.
- `TaskInput` follows the same pattern: `FileInput(path)`, `DirInput(path)`. Source: `[core/src/main/kotlin/io/kartographer/core/model/IO.kt:3-8](/home/brunorbsf/MiscProjects/kartographer/core/src/main/kotlin/io/kartographer/core/model/IO.kt:3-8)`.
- There is **no `TypedOutput<T>` or `NamedOutput` concept anywhere in the codebase**. The word "artifact", "reference", and "output" (as a non-file concept) appears nowhere in `IO.kt`.

### Execution Result (no output payload)

- `ExecutionResult` is a sealed interface with exactly three cases: `Success`, `UpToDate`, `Failure(error: Throwable, exitCode: Int?)`. None carry task output data. Source: `[core/src/main/kotlin/io/kartographer/core/model/Execution.kt:33-38](/home/brunorbsf/MiscProjects/kartographer/core/src/main/kotlin/io/kartographer/core/model/Execution.kt:33-38)`.
- The scheduler captures only `ExecutionResult` from `task.execute()`. Any side effects of execution (properties set during `execute()`) are invisible to the scheduler. Source: `[core/src/main/kotlin/io/kartographer/core/scheduler/ConcurrentScheduler.kt:140-141](/home/brunorbsf/MiscProjects/kartographer/core/src/main/kotlin/io/kartographer/core/scheduler/ConcurrentScheduler.kt:140-141)`.
- `ExecutionEvent.TaskCompleted` carries `(task: Node, result: ExecutionResult)` — no outputs attached. Source: `[core/src/main/kotlin/io/kartographer/core/model/ExecutionEvent.kt:10](/home/brunorbsf/MiscProjects/kartographer/core/src/main/kotlin/io/kartographer/core/model/ExecutionEvent.kt:10)`.
- `ExecutionEvent.PipelineCompleted` carries only `(result: ExecutionResult)`. Source: `[core/src/main/kotlin/io/kartographer/core/model/ExecutionEvent.kt:11](/home/brunorbsf/MiscProjects/kartographer/core/src/main/kotlin/io/kartographer/core/model/ExecutionEvent.kt:11)`.

### Pipeline Completion Rendering (prints nothing about outputs)

- `PlainRenderer` on `PipelineCompleted` prints exactly: `"Pipeline finished successfully."` or `"Pipeline failed."`. No task summary, no outputs. Source: `[cli/src/main/kotlin/io/kartographer/cli/render/PlainRenderer.kt:53-60](/home/brunorbsf/MiscProjects/kartographer/cli/src/main/kotlin/io/kartographer/cli/render/PlainRenderer.kt:53-60)`.
- `MosaicRenderer` on `PipelineCompleted` prints: `"✅ Pipeline finished successfully."` in green, or `"❌ Pipeline failed."` in red. No task summary. Source: `[cli/src/main/kotlin/io/kartographer/cli/render/MosaicRenderer.kt:171-178](/home/brunorbsf/MiscProjects/kartographer/cli/src/main/kotlin/io/kartographer/cli/render/MosaicRenderer.kt:171-178)`.
- `TaskCompleted` in both renderers prints only: `"[taskname] Completed successfully."` / `"[taskname] Up-to-date."` / `"[taskname] Failed: <error>"`. Source: `[cli/src/main/kotlin/io/kartographer/cli/render/PlainRenderer.kt:37-45](/home/brunorbsf/MiscProjects/kartographer/cli/src/main/kotlin/io/kartographer/cli/render/PlainRenderer.kt:37-45)`.
- No renderer ever iterates over completed tasks to print a final summary table. No renderer has access to task output values.

### Property<T> System (configuration, not output collection)

- `Property<T>` in `core/Properties.kt` is used for task **configuration**, not for declaring what a task **produces**. Properties are set before execution (by pipeline author or CLI flags) and read during `execute()`. Source: `[core/src/main/kotlin/io/kartographer/core/Properties.kt:10-69](/home/brunorbsf/MiscProjects/kartographer/core/src/main/kotlin/io/kartographer/core/Properties.kt:10-69)`.
- `Property<T>` has `cliName` and `cliDescription` for CLI integration, `completionProvider` for tab-completion, `isPresent()` and `orNull` for optional values. No fields for "output", "secret", or "sensitive".
- `Property.set(value)` replaces any provider with a direct value. `Property.setProvider(sourceProvider)` sets a lazy provider. This is how tasks wire to each other (e.g., `imageRef.setProvider(buildVersioned.imageRef)`). Source: `[core/src/main/kotlin/io/kartographer/core/Properties.kt:26-34](/home/brunorbsf/MiscProjects/kartographer/core/src/main/kotlin/io/kartographer/core/Properties.kt:26-34)`.
- `TaskOutputProvider<T>` wraps a resolver lambda and records which task produced it. But it's only used for dependency tracking (`DagScheduler` resolves `producingTask` in `resolveDependencies()` at `[DagScheduler.kt:31-36](/home/brunorbsf/MiscProjects/kartographer/core/src/main/kotlin/io/kartographer/core/scheduler/DagScheduler.kt:31-36)`). The resolved value is **never collected or stored centrally**. Source: `[core/src/main/kotlin/io/kartographer/core/Properties.kt:71-75](/home/brunorbsf/MiscProjects/kartographer/core/src/main/kotlin/io/kartographer/core/Properties.kt:71-75)`.
- The word "secret" appears nowhere in `Properties.kt`, `IO.kt`, `Execution.kt`, `ExecutionEvent.kt`, or `Node.kt`.

### DefaultTask Action Return Value (discarded)

- `DefaultTask.action` takes `suspend (ExecutionContext) -> Any`. The return type is `Any` — it could return structured output. Source: `[core/src/main/kotlin/io/kartographer/tasks/Tasks.kt:12-13](/home/brunorbsf/MiscProjects/kartographer/core/src/main/kotlin/io/kartographer/tasks/Tasks.kt:12-13)`.
- But `DefaultTask.execute()` does: `val res = act(context); res as? ExecutionResult ?: ExecutionResult.Success`. Anything returned that isn't an `ExecutionResult` is **discarded**. Source: `[core/src/main/kotlin/io/kartographer/tasks/Tasks.kt:18-21](/home/brunorbsf/MiscProjects/kartographer/core/src/main/kotlin/io/kartographer/tasks/Tasks.kt:18-21)`.

### Tasks That Already Compute Outputs (but they're invisible)

- `ContainerBuild` sets `imageRef` during `execute()`: `imageRef.set(fullImageName)` at `[tasks/containers/.../ContainerTasks.kt:64](/home/brunorbsf/MiscProjects/kartographer/tasks/containers/src/main/kotlin/io/kartographer/tasks/containers/ContainerTasks.kt:64)`. This is a `Property<String>` computed at runtime — but only accessible via property reference in the same script, never collected or printed at pipeline-end.
- `ContainerPush` computes `fullRef = "$registryStr/${imageRef.get()}"` at `[tasks/containers/.../ContainerTasks.kt:101](/home/brunorbsf/MiscProjects/kartographer/tasks/containers/src/main/kotlin/io/kartographer/tasks/containers/ContainerTasks.kt:101)` — push target is never recorded as an output.
- `AptPackage` has `val outputDeb = output { ... }` at `[tasks/apt/.../AptTasks.kt:30-36](/home/brunorbsf/MiscProjects/kartographer/tasks/apt/src/main/kotlin/io/kartographer/tasks/apt/AptTasks.kt:30-36)`. This creates a `TaskOutputProvider<String>` that lazily computes the `.deb` path. Available for wiring but never collected.
- `GoBuild` has `var output: String?` at `[tasks/go/.../GoTasks.kt:44](/home/brunorbsf/MiscProjects/kartographer/tasks/go/src/main/kotlin/io/kartographer/tasks/go/GoTasks.kt:44)` — the build output path is set as a configuration property, not an output declaration.
- `FlutterBuild` has `val targetPlatform = property<String>()` — the resulting artifact path (APK, web build dir, etc.) is never captured as a task output.

### Task Class Hierarchy

- `Node` (abstract) → `Task` (abstract) → `DefaultTask` (open). `Group` and `Validation` extend `Node` directly. Source: `[core/src/main/kotlin/io/kartographer/core/model/Node.kt:7-19,21-22,25-133](/home/brunorbsf/MiscProjects/kartographer/core/src/main/kotlin/io/kartographer/core/model/Node.kt)`.
- `Task` has `inputs: TaskInputs`, `outputs: TaskOutputs`, `registeredProperties: MutableList<Property<*>>`. Source: `[core/src/main/kotlin/io/kartographer/core/model/Node.kt:26-28,30](/home/brunorbsf/MiscProjects/kartographer/core/src/main/kotlin/io/kartographer/core/model/Node.kt:26-28,30)`.
- `Task` has `trackProperty(name, provider)` and `trackFile(path)` for up-to-date tracking, plus `upToDateChecks`. Source: `[core/src/main/kotlin/io/kartographer/core/model/Node.kt:44-94](/home/brunorbsf/MiscProjects/kartographer/core/src/main/kotlin/io/kartographer/core/model/Node.kt:44-94)`.

### Task Serialization (for gRPC, not outputs)

- `TaskSerializer.serializeProperties()` uses Kotlin reflection to walk all member properties of a task and serialize them as `Map<String, String>`. This is used to send task state to remote daemon workers. Source: `[core/src/main/kotlin/io/kartographer/core/plugins/TaskSerializer.kt:11-44](/home/brunorbsf/MiscProjects/kartographer/core/src/main/kotlin/io/kartographer/core/plugins/TaskSerializer.kt:11-44)`.
- `TaskSerializer` handles `Property<T>` objects specially: `if (value is Property<*>) { if (value.isPresent()) value.get() else null }`. Source: `[core/src/main/kotlin/io/kartographer/core/plugins/TaskSerializer.kt:24-28](/home/brunorbsf/MiscProjects/kartographer/core/src/main/kotlin/io/kartographer/core/plugins/TaskSerializer.kt:24-28)`.
- `isBaseTaskProperty()` excludes known internal properties: `id`, `name`, `dependencyObjects`, `inputs`, `outputs`, etc. Source: `[core/src/main/kotlin/io/kartographer/core/plugins/TaskSerializer.kt:101-108](/home/brunorbsf/MiscProjects/kartographer/core/src/main/kotlin/io/kartographer/core/plugins/TaskSerializer.kt:101-108)`.

### Existing Output Pattern (wiring, not reporting)

- Pipeline authors wire task outputs together via `Property.setProvider()`: e.g., in the container example, `imageRef.setProvider(buildVersioned.imageRef)` links ContainerPush to ContainerBuild. Source: `[examples/container/karto.kts:17](/home/brunorbsf/MiscProjects/kartographer/examples/container/karto.kts:17)`.
- `DagScheduler.resolveDependencies()` automatically resolves these as implicit dependencies. Source: `[core/src/main/kotlin/io/kartographer/core/scheduler/DagScheduler.kt:30-37](/home/brunorbsf/MiscProjects/kartographer/core/src/main/kotlin/io/kartographer/core/scheduler/DagScheduler.kt:30-37)`.
- But after the pipeline finishes, **none of these wired values are ever printed or summarized**.

### Build / Test Commands

- Full build: `cd /home/brunorbsf/MiscProjects/kartographer && ./gradlew :cli:installDist`
- Tests: `./gradlew test`
- Integration tests: `./gradlew :integration-tests:test --tests io.kartographer.integration.CucumberTestRunner`
- Gherkin feature files: `integration-tests/src/test/resources/features/`

### Environment

- **Language**: Kotlin (JVM), target JVM 21
- **Build system**: Gradle 8.5 with Kotlin DSL
- **Distribution**: `cli/build/install/cli/` — 75 JARs, 102 MB total
- **Kotlin versions**: 1.9.20 (core scripting), 1.9.22 (cli scripting), 1.9.23 (stdlib)
- **No daemon running**: Checked with `ps aux | grep kartographer-daemon` — no output.
- **Git**: Branch `master`, HEAD at `bfaca2a`, 2 files with uncommitted modifications: `KtsPipelineLoader.kt` (+41 lines), `FlutterTasks.kt` (+6 lines)

---

## 2. The Problem

When a Kartographer pipeline completes, the user sees only:

```
[18:23:15.456] [docker-build] Completed successfully.
[18:23:16.789] [docker-push] Completed successfully.
[18:23:16.790] Pipeline finished successfully.
```

There is **no summary** of what each task actually produced. For a `ContainerBuild` task, the built image reference (`my-app:1.0`) is computed and set on an internal `Property<String>` but never surfaced. For an `AptPackage` task, the `.deb` path is computed in an `output { }` lambda but never collected. For a `GoBuild` task, the compiled binary path is set on a mutable property but invisible outside the task.

The pipeline author has to manually inspect filesystem state or embed printlns in task actions to know what was produced. There is no mechanism for tasks to **declare** what they output, for the scheduler to **collect** those outputs, or for the CLI to **print** them at pipeline completion.

---

## 3. What Already Exists

- `Property<T>` — a typed, optionally-lazy value holder used for task configuration. Supports `set()`, `setProvider()`, `isPresent()`, `get()`, `orNull`. Has CLI integration (`cliName`, `cliDescription`, `completionProvider`).
- `TaskOutputProvider<T>` — a `Provider<T>` that lazily resolves a value and records which `Task` produced it. Used for output wiring between tasks.
- `Task.outputs` — a `TaskOutputs` container holding `FileOutput` and `DirOutput` instances. Only file paths.
- `Task.registeredProperties` — a list of all `Property<*>` instances created by a task's `property<T>()` calls.
- `TaskSerializer` — serializes all readable properties of a task to `Map<String, String>` for gRPC transport. Already has the reflection infrastructure to enumerate and read task properties.
- `ExecutionObserver.onEvent()` — event bus that all renderers subscribe to. Currently handles: `PipelineStarted`, `TaskQueued`, `TaskStarted`, `TaskOutput` (stdout line), `TaskCompleted`, `PipelineCompleted`.
- `DagScheduler.resolveDependencies()` — already resolves property-based implicit dependencies by checking `prop.producingTask`.
- `TaskStateStore` — persists task state (hashes of inputs/outputs/properties) as JSON for up-to-date checks. Already has the pattern of serializing task state post-execution.

---

## 4. Requirements / Acceptance Criteria

1. Tasks must be able to **declare** typed outputs (e.g., `ContainerBuild` → image reference `String`, `AptPackage` → deb path `String`, `GoBuild` → binary path `String`).
2. Outputs must support an optional **sensitivity/secret** marker so that secrets (passwords, tokens) are not printed at pipeline completion.
3. When a pipeline completes successfully (or fails), `karto` must print a **summary**: the list of tasks executed, their status, and **all non-secret outputs** each task produced.
4. The output summary must appear in both `PlainRenderer` and `MosaicRenderer`.
5. Output values should be **collected** even if a task fails partway through (best-effort) — if `imageRef` was set before a push failed, it should be visible.
6. The change must not break existing pipeline scripts (backward compatibility with `Property<T>` and `output { }` patterns).

---

## 5. Non-goals

None stated — architect should confirm scope before implementing.

---

## 6. Evidence Gathered

### Code Traces

- Traced full output path: `Task.execute()` → `ConcurrentScheduler` collects `ExecutionResult` → fires `TaskCompleted` event → renderer prints status only → no output collection anywhere in between. Confirmed at `[ConcurrentScheduler.kt:140-150](/home/brunorbsf/MiscProjects/kartographer/core/src/main/kotlin/io/kartographer/core/scheduler/ConcurrentScheduler.kt:140-150)`.
- Verified `ExecutionResult` has no output fields: read `[Execution.kt:33-38](/home/brunorbsf/MiscProjects/kartographer/core/src/main/kotlin/io/kartographer/core/model/Execution.kt:33-38)`.
- Verified `ExecutionEvent.TaskCompleted` has no output payload: read `[ExecutionEvent.kt:10](/home/brunorbsf/MiscProjects/kartographer/core/src/main/kotlin/io/kartographer/core/model/ExecutionEvent.kt:10)`.
- Verified both renderers print nothing about outputs: read `[PlainRenderer.kt:53-60](/home/brunorbsf/MiscProjects/kartographer/cli/src/main/kotlin/io/kartographer/cli/render/PlainRenderer.kt:53-60)` and `[MosaicRenderer.kt:171-178](/home/brunorbsf/MiscProjects/kartographer/cli/src/main/kotlin/io/kartographer/cli/render/MosaicRenderer.kt:171-178)`.
- Verified `DefaultTask.execute()` discards non-`ExecutionResult` return values: read `[Tasks.kt:18-21](/home/brunorbsf/MiscProjects/kartographer/core/src/main/kotlin/io/kartographer/tasks/Tasks.kt:18-21)`.

### Property & Output System

- Read `Properties.kt` in full — `Property<T>` has `set()`, `setProvider()`, `isPresent()`, `get()`, `orNull`, plus CLI metadata. No output declaration concept.
- Read `IO.kt` in full — `TaskOutput` only has `FileOutput(path)`, `DirOutput(path)`. `TaskInput` only has `FileInput(path)`, `DirInput(path)`.
- Read `Node.kt` in full — `Task` has `inputs`, `outputs`, `registeredProperties`, `topologyRequirements`, `upToDateChecks`. No output declarations.
- Read `TaskSerializer.kt` in full — already has reflection-based property enumeration. Could be extended or serve as a pattern for output collection.

### Task Implementations

- Read all 9 task library files in full. Key output-relevant findings per task:
  - **ContainerTasks**: `ContainerBuild.imageRef` is set during execution (`[ContainerTasks.kt:64](/home/brunorbsf/MiscProjects/kartographer/tasks/containers/src/main/kotlin/io/kartographer/tasks/containers/ContainerTasks.kt:64)`). `ContainerPush` computes `fullRef` (`[ContainerTasks.kt:101](/home/brunorbsf/MiscProjects/kartographer/tasks/containers/src/main/kotlin/io/kartographer/tasks/containers/ContainerTasks.kt:101)`) but doesn't persist it.
  - **AptTasks**: `AptPackage` has `outputDeb` as an `output {}` lambda (`[AptTasks.kt:30-36](/home/brunorbsf/MiscProjects/kartographer/tasks/apt/src/main/kotlin/io/kartographer/tasks/apt/AptTasks.kt:30-36)`). Computes the .deb path lazily.
  - **GoTasks**: `GoBuild.output` is a `var String?` set before execution, never collected after.
  - **FlutterTasks**: `FlutterBuild.targetPlatform` is a `property<String>()` — the output artifact path is implicit (Flutter determines it), never captured.
  - **GradleTask**, **FlutterRun**, **FlutterTest**, **PulumiTask**, **GoMod**, **GoTest**, **PythonTasks**, **ProtoTasks**, **AnsibleTasks**: No output declarations at all.

### Build & Git State

- `./gradlew :cli:installDist` → BUILD SUCCESSFUL in 28s (37 tasks, 10 executed, 27 up-to-date).
- Git HEAD: `bfaca2a fix(scheduler): propagate uncaught task failures`. Two files modified but not committed: `KtsPipelineLoader.kt` (+41 lines — script caching), `FlutterTasks.kt` (+6 lines).

### Prior Sessions

- `agents search 'cartographer output task output artifact'` across all sources → no relevant prior sessions on task output modeling.
- The most recent session (`opencode:ses_02d4b8824ffe5uTCRKscoD17dz`) was about pipeline startup performance, not task outputs.

### Runtime State

- No kartographer daemon running. No `~/.kartographer/script-cache` directory exists.

---

## 7. Source Reference Index

- `[core/src/main/kotlin/io/kartographer/core/model/IO.kt:1-51](/home/brunorbsf/MiscProjects/kartographer/core/src/main/kotlin/io/kartographer/core/model/IO.kt)` — `TaskInput`/`TaskOutput` abstract classes, `FileInput`/`DirInput`/`FileOutput`/`DirOutput` concrete types, `TaskInputs`/`TaskOutputs` containers.
- `[core/src/main/kotlin/io/kartographer/core/model/Execution.kt:33-38](/home/brunorbsf/MiscProjects/kartographer/core/src/main/kotlin/io/kartographer/core/model/Execution.kt:33-38)` — `ExecutionResult` sealed interface (Success/UpToDate/Failure — no output payload).
- `[core/src/main/kotlin/io/kartographer/core/model/ExecutionEvent.kt:1-18](/home/brunorbsf/MiscProjects/kartographer/core/src/main/kotlin/io/kartographer/core/model/ExecutionEvent.kt)` — All events: PipelineStarted, TaskQueued, TaskStarted, TaskOutput, TaskCompleted, PipelineCompleted, PromptRequested, PromptCompleted.
- `[core/src/main/kotlin/io/kartographer/core/model/Node.kt:25-133](/home/brunorbsf/MiscProjects/kartographer/core/src/main/kotlin/io/kartographer/core/model/Node.kt)` — `Task` class: `inputs`, `outputs`, `registeredProperties`, `output()` method, `execute()` abstract.
- `[core/src/main/kotlin/io/kartographer/core/Properties.kt:1-76](/home/brunorbsf/MiscProjects/kartographer/core/src/main/kotlin/io/kartographer/core/Properties.kt)` — `Property<T>` (configuration), `Provider<T>`, `TaskOutputProvider<T>` (lazy output resolution with producing task).
- `[core/src/main/kotlin/io/kartographer/tasks/Tasks.kt:9-25](/home/brunorbsf/MiscProjects/kartographer/core/src/main/kotlin/io/kartographer/tasks/Tasks.kt)` — `DefaultTask`: `action()` takes `suspend (ExecutionContext) -> Any`, return value discarded in `execute()`.
- `[core/src/main/kotlin/io/kartographer/core/scheduler/ConcurrentScheduler.kt:16-203](/home/brunorbsf/MiscProjects/kartographer/core/src/main/kotlin/io/kartographer/core/scheduler/ConcurrentScheduler.kt)` — Scheduler: calls `node.execute()`, collects `ExecutionResult`, fires events. No output collection.
- `[core/src/main/kotlin/io/kartographer/core/scheduler/DagScheduler.kt:30-37](/home/brunorbsf/MiscProjects/kartographer/core/src/main/kotlin/io/kartographer/core/scheduler/DagScheduler.kt)` — Property-based implicit dependency resolution via `prop.producingTask`.
- `[core/src/main/kotlin/io/kartographer/core/plugins/TaskSerializer.kt:9-109](/home/brunorbsf/MiscProjects/kartographer/core/src/main/kotlin/io/kartographer/core/plugins/TaskSerializer.kt)` — Reflection-based property serialization for gRPC. Already handles `Property<T>` objects.
- `[core/src/main/kotlin/io/kartographer/core/scheduler/TaskState.kt:20-102](/home/brunorbsf/MiscProjects/kartographer/core/src/main/kotlin/io/kartographer/core/scheduler/TaskState.kt)` — Task state persistence (hashes). Pattern for post-execution state capture.
- `[cli/src/main/kotlin/io/kartographer/cli/render/PlainRenderer.kt:1-66](/home/brunorbsf/MiscProjects/kartographer/cli/src/main/kotlin/io/kartographer/cli/render/PlainRenderer.kt)` — Plain text renderer. PipelineCompleted prints only "finished successfully" or "failed".
- `[cli/src/main/kotlin/io/kartographer/cli/render/MosaicRenderer.kt:1-232](/home/brunorbsf/MiscProjects/kartographer/cli/src/main/kotlin/io/kartographer/cli/render/MosaicRenderer.kt)` — TUI renderer. PipelineCompleted prints success/failure with emoji, no task summary.
- `[tasks/containers/src/main/kotlin/io/kartographer/tasks/containers/ContainerTasks.kt:21-148](/home/brunorbsf/MiscProjects/kartographer/tasks/containers/src/main/kotlin/io/kartographer/tasks/containers/ContainerTasks.kt)` — `ContainerBuild` sets `imageRef` during execute (line 64). `ContainerPush` computes `fullRef` (line 101).
- `[tasks/apt/src/main/kotlin/io/kartographer/tasks/apt/AptTasks.kt:11-91](/home/brunorbsf/MiscProjects/kartographer/tasks/apt/src/main/kotlin/io/kartographer/tasks/apt/AptTasks.kt)` — `AptPackage` has `outputDeb` as `output {}` lambda (lines 30-36).
- `[tasks/go/src/main/kotlin/io/kartographer/tasks/go/GoTasks.kt:43-58](/home/brunorbsf/MiscProjects/kartographer/tasks/go/src/main/kotlin/io/kartographer/tasks/go/GoTasks.kt)` — `GoBuild` has `output: String?` config var (line 44).
- `[tasks/flutter/src/main/kotlin/io/kartographer/tasks/flutter/FlutterTasks.kt:52-75](/home/brunorbsf/MiscProjects/kartographer/tasks/flutter/src/main/kotlin/io/kartographer/tasks/flutter/FlutterTasks.kt)` — `FlutterBuild` has `targetPlatform` property but no output path captured.
- `[examples/container/karto.kts:1-34](/home/brunorbsf/MiscProjects/kartographer/examples/container/karto.kts)` — Example pipeline showing `imageRef.setProvider()` pattern for task wiring.

---

## 8. Commands Run and Observed Outputs

```bash
# Repository discovery
cd /home/brunorbsf/MiscProjects/kartographer && git log --oneline -5
→ bfaca2a fix(scheduler): propagate uncaught task failures
→ 3902f43 fix(cli): honor explicit project directory for runs
→ e73e677 fix(containers): always execute external image operations
→ d1489ce fix(containers): preserve release indexes and metadata
→ 1b8a76b fix(containers): preserve multiarch indexes on push

# Build verification
cd /home/brunorbsf/MiscProjects/kartographer && ./gradlew :cli:installDist 2>&1 | tail -5
→ BUILD SUCCESSFUL in 28s
→ 37 actionable tasks: 10 executed, 27 up-to-date

# Git status
cd /home/brunorbsf/MiscProjects/kartographer && git status --short
→ M cli/src/main/kotlin/io/kartographer/cli/KtsPipelineLoader.kt
→ M tasks/flutter/src/main/kotlin/io/kartographer/tasks/flutter/FlutterTasks.kt

# Check daemon
ps aux | grep kartographer-daemon | grep -v grep
→ (no output — daemon not running)

# Check plugin directory
ls -la ~/.kartographer/plugins/
→ correlator-karto.jar (42K), database-karto.jar (86K), scraping-karto.jar (25K), karto.jar (14K)

# Prior sessions search
agents search 'cartographer task output' --source opencode --limit 5
→ No prior sessions on task output modeling. Only this session and a startup performance spike.
```

---

## 9. Known Traps and Edge Cases

- **Existing `outputs: TaskOutputs` uses the name "outputs" for file tracking**: The `Task.outputs` field (type `TaskOutputs`) and its extension functions `outputs.file(path)` / `outputs.dir(path)` are specifically for filesystem outputs used in up-to-date checks. A new output concept must use a different name or coexist without collision. Source: `[Node.kt:27](/home/brunorbsf/MiscProjects/kartographer/core/src/main/kotlin/io/kartographer/core/model/Node.kt:27)`.
- **Many tasks don't compute outputs inside `execute()` at all**: `FlutterRun`, `FlutterTest`, `GradleTask`, `GoTest`, `GoMod`, `PulumiTask`, `PythonTasks`, `ProtoTasks`, `AnsibleTasks` just run a command. For these, the "output" might be the stdout, the exit code, or nothing meaningful beyond the files they produce.
- **`ConcurrentScheduler` cancels sibling jobs on first failure**: If task B fails and all other tasks are cancelled, their outputs might not be available. The scheduler fires `TaskCompleted(Failure)` for failed tasks but never runs `execute()` for cancelled tasks. Source: `[ConcurrentScheduler.kt:169-177](/home/brunorbsf/MiscProjects/kartographer/core/src/main/kotlin/io/kartographer/core/scheduler/ConcurrentScheduler.kt:169-177)`.
- **`Property<T>` values set during `execute()` are visible post-execution**: Because `ConcurrentScheduler` keeps a reference to the task node and `Property.set()` mutates it, values set during `execute()` survive after the coroutine finishes. This means the scheduler could potentially read `registeredProperties` after `execute()` returns — no need to change the return type. Source: `[ConcurrentScheduler.kt:140-150](/home/brunorbsf/MiscProjects/kartographer/core/src/main/kotlin/io/kartographer/core/scheduler/ConcurrentScheduler.kt:140-150)` and `Properties.kt:27` showing `set()` mutates in-place.
- **Tasks that set properties in constructor or DSL block (before execution)**: The output declaration point matters. Some properties are set during `init {}` blocks (like `AptPackage.outputDir.set("build/apt")`), some during `execute()` (like `ContainerBuild.imageRef.set(fullImageName)`). Only post-execution snapshot captures computed outputs.
- **The `output {}` lambda returns `TaskOutputProvider<T>`**: The method `Task.output(resolver)` creates a lazy provider that calls the resolver on `get()`. If the resolver depends on properties that are only set during `execute()`, calling `get()` before execution would fail or return stale data. Source: `[Node.kt:40-42](/home/brunorbsf/MiscProjects/kartographer/core/src/main/kotlin/io/kartographer/core/model/Node.kt:40-42)`.
- **`KartographerModule`/plugin system**: Custom task types loaded as plugins would need to opt into the output declaration system. If the output collection mechanism requires explicit declaration (e.g., a `registerOutput()` call), existing third-party tasks won't participate until updated.

---

*Spike completed 2026-08-05. Verified facts: 52. Source links: 30+. Commands run: 4. Unverified/open facts: 0.*
