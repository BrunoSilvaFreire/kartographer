---
name: kartographer-concepts
description: Canonical reference for Kartographer's first-class concepts and architecture — Node, Group, Task, InputSet, OutputSet, Property, Provider, up-to-date checks, services, semaphores, topology, scheduler, pipeline inputs, plugins, and script loading. Use when you need to understand what something is or how a Kartographer mechanism behaves.
---

# Kartographer Concepts

This is the canonical, definition-first reference for Kartographer's domain model and architecture.
Each concept below is self-contained. For *how to apply* these concepts, use
`kartographer-task-best-practices`; for *building and diagnosing the tool itself*, use
`kartographer-repo-guide` and `kartographer-daemon-and-execution`.

Read the section you need — do not skim the whole file if you know your question.

**Sections:** [Architecture](#architecture) · [Pipeline and registry](#pipeline-and-registry) ·
[Node](#node) · [Group](#group) · [Task](#task) · [Validation](#validation) · [InputSet](#inputset) ·
[OutputSet and Output](#outputset-and-output) · [Property](#property) · [Provider](#provider) ·
[Up-to-date checks](#up-to-date-checks) · [Lifecycle](#lifecycle-finite-and-service) ·
[Semaphores](#semaphores) · [Topology](#topology-requirements) · [Scheduler](#scheduler) ·
[Pipeline inputs](#pipeline-inputs-and-cache-invalidation) · [Plugins](#plugins) ·
[Script loading](#script-loading)

---

## Architecture

**Definition:** A Kartographer run is split across three modules with a strict division of labour.

- `cli/` — a thin client. Parses arguments, renders output, and forwards every request to the
  daemon over a framed local Unix-socket protocol. It deliberately does **not** depend on the task
  modules or the Kotlin compiler, which is what makes `karto` start fast.
- `daemon/` — the engine. Compiles pipelines, holds plugin classloaders and task classes warm,
  executes tasks, and streams events back. gRPC is used only for a genuinely remote worker.
- `core/` — the shared model used by both: `Node`/`Task`, `Property`/`Provider`, `InputSet`/
  `OutputSet`, the scheduler, `PluginLoader`/`PluginSet`, `LocalProcessExecutor`, `TaskLogs`.

Runtime behaviour (daemon lifecycle, signals, logs, privilege escalation) is documented in
`kartographer-daemon-and-execution`. Build/run/diagnose lives in `kartographer-repo-guide`.

---

## Pipeline and registry

**Definition:** A pipeline is the set of nodes a single `karto.kts` script (or compiled pipeline
class) registers into a `NodeRegistry`.

`TaskRegistry` is an `object` so `task("x") { }` needs no receiver in a script, but it writes to the
`NodeRegistry` bound to the current thread via `TaskRegistry.withRegistry`. A `NodeRegistry` is
**per project, never process-global** — the daemon holds one per cached project. `NodeRegistry`
also records `projectRoot` (from `project { rootDirectory = ... }`), `projectDirectory`, and
`pipelineInputFiles`.

`register` rejects a duplicate id. `getOrRegisterGroup` errors if a non-group node already owns the
name.

Related: the shape of pipelines (`Group`) and how to organize them is
`kartographer-task-best-practices`.

---

## Node

**Definition:** `Node(name)` is anything that can appear in the DAG. It carries an `id: TaskId`
(the name), dependencies, `registeredProperties`, and the node kinds below.

Dependencies come in several forms (`dependsOn(node|name|outputs)`, `dependsOnCompletion(...)`),
which is what makes a node's dependency on another *task invocation* explicit versus just on a name.
`TaskId` is a value class wrapping the string name; the string is the slash path (see Group).

---

## Group

**Definition:** `Group(name)` is a `Node` that does no work; it is a namespace and an aggregator.

`group("a") { task<T>("b") }` names the task `a/b` and makes group `a` depend on its members, so
`karto run a` runs the member subtree. Groups nest: `group("build") { group("compile") { ... } }`
yields `build/compile/...`. A group declares descriptive CLI properties that member tasks re-declare.

Naming conventions, group defaults, and `excludeTasksFromGroupDefault` are
`kartographer-task-best-practices`.

---

## Task

**Definition:** `Task<I : InputSet, O : OutputSet>(name)` is a node that performs work.

Its lifecycle is three phases:

1. `buildInputs(context): I` — resolve configuration and providers into a typed snapshot.
2. `buildOutputs(): O` — construct the typed, named output set.
3. `execute(context, inputSet): ExecutionResult<O>` — do the work; return `success { }` or
   `failure(error, exitCode) { }`.

A task declares typed outputs on `O`, filesystem inputs/outputs for up-to-date checks on
`inputs`/`trackedOutputs`, and configuration via `property<T>()`. Tasks are created with a public
`String` constructor and the `task<T>("name") { }` DSL.

Outputs are snapshotted by the scheduler **after** `execute()` (so a value set during execution is
visible); `context.emitOutput(output, value)` emits one *during* execution for a consumer that needs
it early.

---

## Validation

**Definition:** `Validation(name)` is a `Node` that participates in the DAG but does not mutate
state. It is a step whose value is its check, not its side effect.

---

## InputSet

**Definition:** An `InputSet` is the fully-resolved, typed configuration snapshot a task hands to
`execute()`.

It exists so the scheduler can hash exactly what a task ran with for up-to-date checks, and so a
task never reads mutable properties mid-execution. Build it in `buildInputs()`; that is where you
resolve providers (`provider.get()`) and environment.

---

## OutputSet and Output

**Definition:** An `OutputSet` declares what a task *produced*, by name and type; `Output<T>` is one
declared value.

```kotlin
class GoBuildOutputs : OutputSet() { val binary = output<String>("binary") }
```

- Outputs are set during `execute()` and collected into `ExecutionResult` after it returns.
- `output<T>("token", secret = true)` marks a credential: masked in rendered output, never written to
  a state file, never sent to a remote worker, and the task is never considered up-to-date.
- An `Output<T>` is a `TaskBoundProvider<T>` — see Provider.
- `rendered()` masks secrets; `schema()` reports name/type/secret for the CLI and serialization.

Distinguish this from `Property` (config in) and `InputSet` (resolved config): an Output is what came
out.

---

## Property

**Definition:** `Property<T>` is a task's (or group's) configuration value, holding either a direct
value or a `Provider` backing it.

- `property<T>()` registers it (so the CLI can expose it via `withCLI`/`completeWith`).
- `set(value)` stores a literal; `setProvider(provider)` delegates resolution.
- `isPresent()`, `get()`, `description`, `secret`, `contributesToState`, and `producingTask`
  (non-null only when the backing provider is task-bound).
- `renderForState()` decides what a state file records: a literal value, or the provider's
  `description` unless the provider opted into `contributesToState`.

A `Property` is itself a `Provider<T>`, which is how a property can be passed where a provider is
expected.

---

## Provider

**Definition:** `Provider<out T>` is a value resolved later, from wherever — the pipeline's way to
read external configuration, secrets, and command output.

```kotlin
interface Provider<out T> {
    suspend fun get(): T
    val description: String         // the SOURCE, shown in state/diffs/`karto tasks`
    val secret: Boolean             // never logged, persisted, or serialized to a worker
    val contributesToState: Boolean // resolve + hash into state so a change re-runs consumers
}
```

`get()` suspends because resolving one can run a subprocess; doing it on the calling thread would
block a scheduler worker.

### Sources

Built-ins in `io.kartographer.core.providers`:

| Helper | Meaning | Notable flags |
|---|---|---|
| `fromEnvironment(name)` | Value in the **run's** environment (the client's), not `System.getenv` | — |
| `fromFile(path)` | Trimmed file contents; empty if missing; resolved against the project dir | — |
| `fromCommand(executable, arguments, describedAs, ...)` | Run a command, keep stdout, `parse` it | `secret`, `contributesToState` |
| `providerOf(value, isSecret)` | A constant (test/default) | `isSecret` |

Task-module helpers, all built on `fromCommand`:

- `io.kartographer.tasks.kubernetes.fromKubernetesSecret(name, key, namespace?, kubeContext?, kubeconfig?)`
  — decodes base64 in Kotlin (never pipes through `base64 -d`); `secret = true` by default.
- `fromKubernetesConfigMap(...)` — `secret = false`, `contributesToState = true`.
- `io.kartographer.tasks.pulumi.fromPulumiOutput(cwd, stack, name, ...)` — `contributesToState = true`
  unless `secret`.
- `fromPulumiConfig(cwd, stack, key, ...)` — `secret = true` by default; pass
  `PULUMI_CONFIG_PASSPHRASE_FILE` via `environment`.

### Composition

- `a orElse b` — first non-null, non-blank, non-throwing value wins; the last link's failure surfaces.
- `a.map { transform }` — transform the resolved value; inherits `secret`/`contributesToState`.

### The three flags

- `secret` — governs masking and persistence. A secret never reaches a log, a state file, or a remote
  worker.
- `contributesToState` — resolve during up-to-date checking and hash the value, so a changed Pulumi
  output or ConfigMap invalidates the consumer. Off by default: it costs a subprocess and most
  external values are credentials.
- `description` — what is shown wherever the value would otherwise appear. Say the source
  (`<k8s-secret ns/name#key>`), never the value.

### Resolution scope and caching

Resolution runs inside a `ProviderResolution` coroutine-context element carrying the
`ExecutionContext`, the outputs produced so far, and a per-run cache (in `ProviderResolution.kt`).

- `get()` is legal only while a task builds inputs or executes. At a script's top level it throws
  `No provider resolution scope is active.`
- Each provider resolves **at most once per run**; the cache belongs to the run, so a long-lived
  daemon project cannot serve a stale value into the next run.
- A transient failure is not cached and can be retried within the run.
- `fromCommand` goes through the run's `ExecutionEnvironment`, so it is observable, does not block a
  scheduler thread, and **runs under `--dry-run`** (reading changes nothing).

### Task-bound providers and implicit dependencies

`TaskBoundProvider<out T>` is a provider whose value another task produces. `Output<T>` and a
`Property` backed by one implement it; `producingTask`/`producingInvocation` let the scheduler add the
implicit dependency, so `consumer.prop.setProvider(producer.outputs.ref)` wires an edge without an
explicit `dependsOn`. An external provider has no producing task and is not asked.

### Consuming a provider

`Property.setProvider(provider)`, or task-level injection such as `DartRun.environment("KEY", p)` or
`AptInstall.install(debProvider)`. Resolve with `get()` inside `buildInputs`/`execute`.

**Do not** hand-roll `object : Provider<String>` around a raw `ProcessBuilder`; a past version of
`backend/karto.kts` did, which made the command invisible to the run, ignored `--dry-run`, and
blocked the calling thread.

---

## Up-to-date checks

**Definition:** The mechanism that lets a task skip work when nothing that affects it changed.

A task is up-to-date only when it declares verifiable state (tracked inputs, outputs, files,
properties) and every check passes. `TaskStateStore` persists a SHA-256 hash under
`.kartographer/state/<task>.json`.

- Track with `inputs.files/dirs`, `trackedOutputs.files/dirs`, `trackFile`, `trackProperty`,
  `trackEnvVar`, `trackSystemProperty`, and compose `upToDateWhen { }`.
- `alwaysRun()` marks deliberately non-cacheable work (e.g. a spider): properties are excluded from
  state because an unchanged property map proves nothing about a crawl.
- `runAfterFailedDependencies()` lets a reporting task run after an upstream failure; it owns the
  final verdict and must return `Failure` itself.
- A task with **any secret output** is never up-to-date.
- A task whose dependency produced fresh results re-runs unless it has concrete inputs to re-verify.

---

## Lifecycle: finite and service

**Definition:** A task's `lifecycle` is `FINITE` (returns when done) by default, or `SERVICE` when
configured with `service { }`.

`service { readiness { ... } }` marks a long-running process, forces `alwaysRun()`, and constrains it
to `localOnly()`. A service that never reports ready hangs the run — see
`kartographer-daemon-and-execution` for diagnosing hangs.

---

## Semaphores

**Definition:** A named, counted resource a task must hold while it runs, used to serialise tasks
that cannot safely overlap (one Docker daemon, one port).

`acquires("docker")` or `acquires(name, permits)`. The scheduler acquires **by name**, so two tasks
wanting the same pair cannot deadlock. `semaphoresToAcquire(inputSet)` narrows the set for a task
that only sometimes needs the resource.

---

## Topology requirements

**Definition:** Placement constraints a task declares so the scheduler can match it to a compatible
worker.

`topology { localOnly(); requiresCapability("go"); prefers("region", "us-east") }`. Capabilities are
how a task says it needs `go`, `docker`, `flutter`, `gcloud`, etc. on the worker.

---

## Scheduler

**Definition:** `ConcurrentScheduler` executes the DAG in parallel, respecting dependencies, and
**cancels siblings on the first failure**.

It runs under a `supervisorScope` with cooperative, mutex-protected polling. The CLI/daemon observe
execution through `ExecutionObserver` events (`TaskQueued`, `TaskStarted`, `TaskCompleted`,
`PipelineCompleted`, `PromptRequested`, `TaskOutputProduced`). Cancelled siblings never reach a
`TaskCompleted` event, which is why they report no outputs.

---

## Pipeline inputs and cache invalidation

**Definition:** Files the pipeline reads while *evaluating the script* are pipeline inputs; changing
one must recompile the pipeline.

- `projectFile(path)` — resolve a path against the pipeline's project directory and record it as a
  pipeline input. A bare `File("x")` in a `.kts` resolves against the daemon's CWD, which is not the
  project, and is invisible to invalidation.
- `pipelineInputFile(path)` — record a file (e.g. a YAML list the script parses into tasks) so the
  daemon's `ProjectFingerprint` reloads when it changes.

`ProjectFingerprint` hashes the script, every source under `karto/` (excluding `build/.gradle/.git`),
all plugin jars (local and global), and these pipeline inputs. The daemon recomputes it on every
request and reloads on mismatch, so a cached project cannot go stale without a watcher.

---

## Plugins

**Definition:** Kartographer is extended with plugins — Kotlin jars that add task types and other
helpers. There are two kinds and the distinction matters.

- **Bundled plugins** live in this repo under `tasks/<name>/` and are on the daemon's classpath (the
  daemon depends on every `tasks:*` module). No service registration is needed.
- **Local plugins** live in a consumer project's `karto/` directory next to `karto.kts`, built by
  Gradle into `karto/build/libs/<name>-karto.jar`, and are private to that project. They register a
  `KartographerModule` via `ServiceLoader`.

`PluginSet` is a **class, not a singleton**, because one daemon serves many projects. A project set is
created with the global set's loader as parent, so global plugins are visible everywhere while local
jars stay private. `addPluginJar` prepends a fresh jar so it shadows a stale global copy of the same
name; rebuilding swaps in a new `URLClassLoader` and re-registers all modules (a module with a
duplicate `id` is skipped).

Classloading is **parent-first** for `java.`, `javax.`, `jdk.`, `sun.`, `kotlin.`, `kotlinx.`,
`org.slf4j.`, and `io.kartographer.core.`; anything else loads from the local jar first. This is why
a local plugin jar must never bundle `core` (it would shadow the daemon's runtime classes across the
boundary), and why `DriverManager` cannot see a plugin's JDBC driver — instantiate the driver
directly.

A `LoadedProject` is reference-counted: on reload the new entry is swapped in and the old one is
retired and its `PluginSet` closed only after the last in-flight run releases it. Never close a
project set while a run may still hold task instances from it.

Operational troubleshooting (stale jars, "Unresolved reference", cache clearing) is in
`kartographer-repo-guide`.

---

## Script loading

**Definition:** The daemon compiles a `.kts` pipeline (or loads a compiled class) into a
`NodeRegistry`, reusing work via a project cache and a compiled-script cache.

- Resolution: `-p/--pipeline` override, else `<projectDir>/karto.kts`. A non-`.kts` value is a
  compiled pipeline **class name**, not a path.
- `KtsPipelineLoader.compileLocalPluginIfPresent` runs `gradlew -p <karto> jar` before evaluating the
  script. `findGradlew` walks up for a wrapper, honours `KARTOGRAPHER_GRADLE`, then `gradle` on
  `PATH`; with no Gradle it reuses a previously built jar and **warns that source edits will not be
  picked up**. Failures are fatal (fail fast) rather than silently compiling without the plugin.
- `ProjectCache` keys on `(canonical projectDir, pipelinePath)` and recompiles only when the
  fingerprint changes, serialising one project's compile behind a mutex.
- The compiled-script jar cache lives in `~/.kartographer/script-cache`, keyed by script text plus
  the identity of every classpath/plugin jar.

Scripting/compiler API details historically captured in `docs/investigation/` are **stale** — verify
against source. See `kartographer-repo-guide` for the stale-artifact playbook.
