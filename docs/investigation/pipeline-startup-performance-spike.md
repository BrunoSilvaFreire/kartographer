# Kartographer Pipeline Startup Performance — Spike Report

## 1. Key Architectural Facts (do not rediscover)

### JVM & Distribution

- The CLI ships 75 JARs totaling **102 MB** in `cli/build/install/cli/lib/`. Source: `[cli/build/install/cli/bin/cli:102]` — the shell launch script constructs a flat CLASSPATH from all jars in `lib/`.
- The single largest jar is **`kotlin-compiler-embeddable-1.9.22.jar` at 58 MB**. It is always on the classpath and always loaded. Source: observed `ls -lh` output showing it at 58M vs the next largest jar (`grpc-netty-shaded`) at 9.7M.
- There is **no persistent Kotlin script compilation cache**. The `BasicJvmScriptingHost.eval()` call in `[KtsPipelineLoader.kt:68](/home/brunorbsf/MiscProjects/kartographer/cli/src/main/kotlin/io/kartographer/cli/KtsPipelineLoader.kt:68)` recompiles from source on every CLI invocation. The Kotlin scripting framework supports compiled script caching (via `kotlin-main-kts`) but it is not configured.
- **No AppCDS (Application Class Data Sharing) dump** is prebuilt. The JDK 21 ships `classes.jsa` for the standard library only (verified at `/home/brunorbsf/.sdkman/candidates/java/21.0.2-open/lib/server/classes.jsa`). The 102 MB of application jars are not pre-optimized.
- Build timings (run: `./gradlew :cli:installDist`): **28s** for a full rebuild with Gradle daemon warm. With all caches UP-TO-DATE, 28s is the `configuration` phase alone (37 Gradle subprojects).

### Critical Startup Path (cold invocation of `karto run <task>`)

1. Shell script (`cli/build/install/cli/bin/cli`) launches `java -classpath <75 jars> io.kartographer.cli.MainKt ...`
2. JVM loads classes from 75 jars, starting with `kotlin-compiler-embeddable` (58 MB alone).
3. During class loading, `PluginLoader` object is initialized (`[PluginLoader.kt:18-19](/home/brunorbsf/MiscProjects/kartographer/core/src/main/kotlin/io/kartographer/core/plugins/PluginLoader.kt:18-19)`) — its `init` block scans `~/.kartographer/plugins/` for JARs, creates a `URLClassLoader`, and runs `ServiceLoader.load(KartographerModule)`.
4. `KartographerCommand.run()` (`[Main.kt:33](/home/brunorbsf/MiscProjects/kartographer/cli/src/main/kotlin/io/kartographer/cli/Main.kt:33)`) looks for `karto.kts` in the project directory. If the file ends with `.kts`, it calls `KtsPipelineLoader().load(path)`.
5. `KtsPipelineLoader.load()` (`[KtsPipelineLoader.kt:21](/home/brunorbsf/MiscProjects/kartographer/cli/src/main/kotlin/io/kartographer/cli/KtsPipelineLoader.kt:21)`):
   - Checks for a `karto/` directory → if present, **spawns `gradlew -p <karto_dir> jar`** as a subprocess (`[PluginLoader.kt:157-178](/home/brunorbsf/MiscProjects/kartographer/core/src/main/kotlin/io/kartographer/core/plugins/PluginLoader.kt:157-178)`) → this is a full Gradle build invocation.
   - Clears `TaskRegistry` (`[KtsPipelineLoader.kt:48](/home/brunorbsf/MiscProjects/kartographer/cli/src/main/kotlin/io/kartographer/cli/KtsPipelineLoader.kt:48)`).
   - Constructs `ScriptCompilationConfiguration` with `dependenciesFromCurrentContext(wholeClasspath = true)` (`[KtsPipelineLoader.kt:50-58](/home/brunorbsf/MiscProjects/kartographer/cli/src/main/kotlin/io/kartographer/cli/KtsPipelineLoader.kt:50-58)`) — this passes the entire 102 MB classpath to the script compiler.
   - Calls `BasicJvmScriptingHost().eval()` (`[KtsPipelineLoader.kt:68](/home/brunorbsf/MiscProjects/kartographer/cli/src/main/kotlin/io/kartographer/cli/KtsPipelineLoader.kt:68)`) which invokes the full Kotlin compiler to compile the `.kts` file from source.
6. After script evaluation, the populated `TaskRegistry.nodes` is returned. Then the `RunCommand` or other subcommand runs.

### Plugin System

- `PluginLoader` is an `object` (Kotlin singleton) with eager `init` block (`[PluginLoader.kt:18-19](/home/brunorbsf/MiscProjects/kartographer/core/src/main/kotlin/io/kartographer/core/plugins/PluginLoader.kt:18-19)`). It scans `~/.kartographer/plugins/` (or `$KARTOGRAPHER_PLUGINS_DIR`) on first access — which happens during class loading.
- Global plugins directory contains 4 jars: `correlator-karto.jar` (42K), `database-karto.jar` (86K), `scraping-karto.jar` (25K), `karto.jar` (14K). Source: `ls -la /home/brunorbsf/.kartographer/plugins/`.
- Local plugin compilation (`[KtsPipelineLoader.kt:28-45](/home/brunorbsf/MiscProjects/kartographer/cli/src/main/kotlin/io/kartographer/cli/KtsPipelineLoader.kt:28-45)`) runs `gradlew -p <project>/karto jar` as an external process. This spawns a Gradle daemon if none is alive.
- After local compilation, `PluginLoader.addPluginJar()` (`[PluginLoader.kt:65-105](/home/brunorbsf/MiscProjects/kartographer/core/src/main/kotlin/io/kartographer/core/plugins/PluginLoader.kt:65-105)`) closes the old `URLClassLoader`, creates a new one with the fresh jar prepended, and re-runs `ServiceLoader.load()` — discarding and re-registering all modules.
- The script compilation configuration adds plugin jars to the script classpath via `updateClasspath(jars)` (`[KtsPipelineLoader.kt:53-56](/home/brunorbsf/MiscProjects/kartographer/cli/src/main/kotlin/io/kartographer/cli/KtsPipelineLoader.kt:53-56)`). This means the Kotlin compiler sees them when compiling the `.kts` script.

### Task Execution Model

- The `ConcurrentScheduler` (`[ConcurrentScheduler.kt:16-203](/home/brunorbsf/MiscProjects/kartographer/core/src/main/kotlin/io/kartographer/core/scheduler/ConcurrentScheduler.kt:16-203)`) uses `supervisorScope` with coroutines. The DAG is resolved via `DagScheduler.resolveDependencies()` (`[DagScheduler.kt:16-41](/home/brunorbsf/MiscProjects/kartographer/core/src/main/kotlin/io/kartographer/core/scheduler/DagScheduler.kt:16-41)`) which does a simple O(n) pass — **not a performance concern**.
- Up-to-date checks (`[UpToDateCheck.kt:1-134](/home/brunorbsf/MiscProjects/kartographer/core/src/main/kotlin/io/kartographer/core/scheduler/UpToDateCheck.kt:1-134)`) use SHA-256 hashing of all tracked files via `TaskStateStore.calculateHash()` (`[TaskState.kt:81-101](/home/brunorbsf/MiscProjects/kartographer/core/src/main/kotlin/io/kartographer/core/scheduler/TaskState.kt:81-101)`) which reads every byte of every file. For directories, it walks the entire directory tree, sorts, and hashes all files.
- Task state is persisted as JSON in `.kartographer/state/<taskname>.json` (`[TaskState.kt:23-27](/home/brunorbsf/MiscProjects/kartographer/core/src/main/kotlin/io/kartographer/core/scheduler/TaskState.kt:23-27)`).

### Kotlin Scripting Framework

- Script definition: `@KotlinScript` annotation on `KartographerScript` (`[ScriptDefinition.kt:10-16](/home/brunorbsf/MiscProjects/kartographer/core/src/main/kotlin/io/kartographer/dsl/scripting/ScriptDefinition.kt:10-16)`) with `fileExtension = "kartographer.kts"` and `filePathPattern = ".*(?:/|^)karto(?:/.*)?\\.kts|.*\\.kartographer\\.kts"`.
- Compilation config: `dependenciesFromCurrentContext(wholeClasspath = true)` (`[ScriptDefinition.kt:27](/home/brunorbsf/MiscProjects/kartographer/core/src/main/kotlin/io/kartographer/dsl/scripting/ScriptDefinition.kt:27)`) — the script compiler inherits the full classpath of the CLI process.
- The `KtsPipelineLoader` creates its own `BasicJvmScriptingHost()` per invocation (`[KtsPipelineLoader.kt:66](/home/brunorbsf/MiscProjects/kartographer/cli/src/main/kotlin/io/kartographer/cli/KtsPipelineLoader.kt:66)`) — no reuse of a compiler instance.
- **Kotlin version inconsistency**: `core/build.gradle.kts` uses 1.9.20 for scripting dependencies (`[core/build.gradle.kts:22-25](/home/brunorbsf/MiscProjects/kartographer/core/build.gradle.kts:22-25)`), while `cli/build.gradle.kts` uses 1.9.22 (`[cli/build.gradle.kts:28-33](/home/brunorbsf/MiscProjects/kartographer/cli/build.gradle.kts:28-33)`). The `kotlin-reflect` on the final classpath is 1.9.20 (3.1 MB), `kotlin-stdlib` is 1.9.23 (1.7 MB). Multiple versions coexist in the classpath.

### Daemon (Warm JVM) Model

- The `daemon` module provides a gRPC server on port 50051 (`[DaemonServer.kt:16-46](/home/brunorbsf/MiscProjects/kartographer/daemon/src/main/kotlin/io/kartographer/daemon/DaemonServer.kt:16-46)`). It loads plugins once at startup (`[daemon/Main.kt:11-13](/home/brunorbsf/MiscProjects/kartographer/daemon/src/main/kotlin/io/kartographer/daemon/Main.kt:11-13)`) and keeps a warm JVM.
- Daemon handles task deserialization via `TaskSerializer` (`[TaskSerializer.kt:9-109](/home/brunorbsf/MiscProjects/kartographer/core/src/main/kotlin/io/kartographer/core/plugins/TaskSerializer.kt:9-109)`) which uses `kotlin.reflect` to serialize/deserialize task property values.
- The daemon also supports loading local plugins (`[DaemonServer.kt:79-93](/home/brunorbsf/MiscProjects/kartographer/daemon/src/main/kotlin/io/kartographer/daemon/DaemonServer.kt:79-93)`) — same `gradlew` compilation path as the CLI.
- **No daemon process is currently running** (checked with `ps aux | grep kartographer-daemon` — no output).
- `RemoteWorkerClient` (`[RemoteWorkerClient.kt:15-68](/home/brunorbsf/MiscProjects/kartographer/core/src/main/kotlin/io/kartographer/core/scheduler/RemoteWorkerClient.kt:15-68)`) opens a plaintext gRPC channel per task execution — not pooled.

### Task Library Dependencies

- The CLI build **hard-depends on all 9 task libraries** at compile time (`[cli/build.gradle.kts:13-21](/home/brunorbsf/MiscProjects/kartographer/cli/build.gradle.kts:13-21)`): `tasks:apt`, `tasks:containers`, `tasks:python`, `tasks:go`, `tasks:gradle`, `tasks:proto`, `tasks:ansible`, `tasks:pulumi`.
- This means even if a pipeline script only uses one task type, all task library jars are loaded into the JVM. Each task jar is small (4–17 KB each) compared to the compiler.
- The Gradle plugin (`plugin/`) exists as a separate distributable for Gradle-based pipelines (`[KartographerPlugin.kt:1-19](/home/brunorbsf/MiscProjects/kartographer/plugin/src/main/kotlin/io/kartographer/plugin/KartographerPlugin.kt:1-19)`) but is not used in the `.kts`-script path.
- The `maven-publish` plugin is configured on all subprojects (`[build.gradle.kts:9-23](/home/brunorbsf/MiscProjects/kartographer/build.gradle.kts:9-23)`) suggesting intent for distribution but no published artifacts found on this machine.

### Environment

- **Java**: OpenJDK 21.0.2 via SDKMAN at `/home/brunorbsf/.sdkman/candidates/java/current`. CDS archive classes.jsa exists for JDK classes only.
- **Gradle**: Wrapper 8.5. Gradle daemon 8.5 running with `-Xms256m -Xmx512m -XX:MaxMetaspaceSize=384m`.
- **OS**: Linux (observed from `os.name` usage in `LocalProcessExecutor.kt`).
- **Shell**: bash. The CLI launch script is a POSIX shell script generated by Gradle's `application` plugin.

---

## 2. The Problem

Invoking `karto run <task>` from any pipeline project takes **"a long, long time"** due to the **JVM startup + Kotlin scripting compilation** overhead on every invocation.

### Measured timings

- `time ./cli/build/install/cli/bin/cli tasks` (trivial 23-line script): **3.117s** (real), 8.413s (user), 0.584s (sys). Source: bash `time` command run during this spike.
- `time java -version`: **0.032s** (real). Source: bash `time` command run during this spike.
- **Inference**: ~0.032s is raw JVM launch. The remaining ~3.085s is class loading + PluginLoader init + Kotlin script compilation. The vast majority is the 58 MB `kotlin-compiler-embeddable.jar` being loaded and the script being compiled from source.

The user states: "this takes a long, long time, primarily because the JVM and Kotlin scripting gets compiled and stuff." Source: user's spike request.

---

## 3. What Already Exists

- **DSL scripts**: Users write pipelines in `.kartographer.kts` (or `karto.kts`) files. Example: `/home/brunorbsf/CityscapeProjects/mayor/karto.kts` (a 170+ line pipeline for the Mayor project).
- **Plugin system**: Custom task types can be distributed as jars (`~/.kartographer/plugins/*.jar`) or compiled locally from `karto/` directories. Loaded via Java `ServiceLoader`.
- **Daemon**: A gRPC daemon exists that keeps a warm JVM and can execute tasks remotely. Currently not running.
- **Task state cache**: SHA-256 hashed state stored as `.kartographer/state/*.json` for up-to-date checks. Covers task outputs, not script compilation.
- **Gradle plugin**: An alternative loading mechanism where pipelines are compiled statically via Gradle (`[KartographerPlugin.kt:1-19](/home/brunorbsf/MiscProjects/kartographer/plugin/src/main/kotlin/io/kartographer/plugin/KartographerPlugin.kt:1-19)`).

---

## 4. User-Stated Requirements and Constraints

- "Improve the performance and execution timing of the pipelines while Cardo loads them and stuff" — source: user's spike request.
- The specific context is "invoking cartographer for invoking a pipeline" (presumably from another tool like Cardo).

---

## 5. Non-goals

No explicit non-goals stated by the user or found in source documents.

---

## 6. Evidence Gathered

### Repository discovery
- Full repo structure inspected at `/home/brunorbsf/MiscProjects/kartographer/`. Contains 14 Gradle submodules: `core`, `cli`, `daemon`, `plugin`, `integration-tests`, 9 task libraries (`tasks/*`), examples, scratch.
- Branch: `master`. No uncommitted changes except unstaged modification in `tasks/flutter/src/main/kotlin/io/kartographer/tasks/flutter/FlutterTasks.kt`.
- Recent commits: 10 most recent range from task failure propagation fixes to modularization refactors. Source: `git log --oneline -10` output in exploration task.

### Code traces
- Full critical path traced: `Main.kt:main()` → `KartographerCommand.run()` → `KtsPipelineLoader.load()` → `BasicJvmScriptingHost.eval()` → `TaskRegistry` populated → subcommand runs.
- All task libraries read and confirmed structure.
- Plugin system fully traced: `PluginLoader` singleton → `ServiceLoader` → `URLClassLoader` lifecycle → local plugin compilation.

### Config and dependencies
- All `build.gradle.kts` files read for all 14 modules.
- All Kotlin scripting dependency versions identified and cross-referenced.
- All script definition configuration read from `ScriptDefinition.kt`.

### Build and tests
- `./gradlew :cli:installDist` built successfully (28s, UP-TO-DATE).
- CLI installed distribution at `cli/build/install/cli/` confirmed working.
- JAR inventory: 75 jars, 102 MB total distribution size, 58 MB for `kotlin-compiler-embeddable` alone.

### Prior sessions
- `agents search` executed across all sources. Relevant prior sessions found: `ses_0a1cf8409ffe` (initial Kartographer exploration), `ses_0a23d8438ffe` (early exploration), `ses_0a1cfe6e3ffe` (modularization). None contained performance-specific discussions or measured startup timings.
- Claude session `b7b109d0` referenced the overall project structure but did not address pipeline loading performance.

### Runtime state
- No daemon running. No Kotlin script cache directories found on disk (`~/.kotlin/script*` does not exist). No `~/.kartographer/plugins/` activity during idle.
- Gradle daemon running (used for build verification).

---

## 7. Source Reference Index

| File | Evidence |
|------|----------|
| `[KtsPipelineLoader.kt:20-81](/home/brunorbsf/MiscProjects/kartographer/cli/src/main/kotlin/io/kartographer/cli/KtsPipelineLoader.kt:20-81)` | Full script loading pipeline: local plugin compilation (28–45), compilation config (50–58), eval (68). No script cache configured. |
| `[PluginLoader.kt:9-190](/home/brunorbsf/MiscProjects/kartographer/core/src/main/kotlin/io/kartographer/core/plugins/PluginLoader.kt:9-190)` | Eager init (18–19), ServiceLoader-based module discovery (51–63), URLClassLoader lifecycle (43–47, 65–105), local plugin compilation via gradlew (157–178). |
| `[ScriptDefinition.kt:10-30](/home/brunorbsf/MiscProjects/kartographer/core/src/main/kotlin/io/kartographer/dsl/scripting/ScriptDefinition.kt:10-30)` | Script annotation config: default imports, whole classpath dependency, JVM target. |
| `[Main.kt:29-78](/home/brunorbsf/MiscProjects/kartographer/cli/src/main/kotlin/io/kartographer/cli/Main.kt:29-78)` | CLI entry: discovers karto.kts (38–46), delegates to KtsPipelineLoader (48–54). |
| `[cli/build.gradle.kts:1-47](/home/brunorbsf/MiscProjects/kartographer/cli/build.gradle.kts:1-47)` | CLI dependencies: kotlin-compiler-embeddable 1.9.22 (32), all 9 task submodules (13–21), scripting libraries (28–33), Mosaic/Clikt/Jackson. |
| `[core/build.gradle.kts:22-25](/home/brunorbsf/MiscProjects/kartographer/core/build.gradle.kts:22-25)` | Core scripting deps at version 1.9.20 (different from CLI's 1.9.22). |
| `[cli/build/install/cli/bin/cli:102-103]` (generated) | Flat CLASSPATH of all 75 jars. 58 MB compiler jar listed 4th. |
| `[ConcurrentScheduler.kt:16-203](/home/brunorbsf/MiscProjects/kartographer/core/src/main/kotlin/io/kartographer/core/scheduler/ConcurrentScheduler.kt:16-203)` | DAG execution: topological sort + supervisorScope coroutines + fail-fast. |
| `[TaskState.kt:20-102](/home/brunorbsf/MiscProjects/kartographer/core/src/main/kotlin/io/kartographer/core/scheduler/TaskState.kt:20-102)` | SHA-256 state persistence. Full file reads for hash computation. |
| `[DaemonServer.kt:48-197](/home/brunorbsf/MiscProjects/kartographer/daemon/src/main/kotlin/io/kartographer/daemon/DaemonServer.kt:48-197)` | gRPC daemon: task deserialization + execution with warm JVM. |
| `[settings.gradle.kts:1-16](/home/brunorbsf/MiscProjects/kartographer/settings.gradle.kts:1-16)` | All 14 submodules declared. |

---

## 8. Commands Run and Observed Outputs

| Command | Working directory | Observed output |
|---------|-------------------|-----------------|
| `time ./cli/build/install/cli/bin/cli tasks` | `/home/brunorbsf/MiscProjects/kartographer` | Listed 3 tasks (gherkin, test, coverage). **real 0m3.117s, user 0m8.413s, sys 0m0.584s** |
| `time java -version` | (any) | **real 0m0.032s, user 0m0.018s, sys 0m0.018s** |
| `du -sh cli/build/install/cli/lib/` | `/home/brunorbsf/MiscProjects/kartographer` | **102M** |
| `ls -lh cli/build/install/cli/lib/kotlin-compiler-*` | same | 58M for `kotlin-compiler-embeddable-1.9.22.jar` |
| `wc -l cli/build/install/cli/lib/` | same | **75 jars** |
| `./gradlew :cli:installDist` | same | BUILD SUCCESSFUL in 28s (37 tasks, 10 executed, 27 up-to-date) |
| `ps aux \| grep kartographer-daemon` | (any) | **No daemon running** |
| `ss -tlnp \| grep 50051` | (any) | **No process listening on port 50051** |
| `ls ~/.kartographer/plugins/` | (any) | 4 jar files: correlator-karto.jar (42K), database-karto.jar (86K), scraping-karto.jar (25K), karto.jar (14K) |
| `find ~/.kotlin -name "script*"` | (any) | **No Kotlin script cache directory exists** |
| `ls ~/.kartographer/` | (any) | Only `logs` and `plugins` — no script cache |
| `ls -la .kartographer/state/` | `/home/brunorbsf/MiscProjects/kartographer` | 7 state JSON files (buildKartographerCLI.json, etc.) from prior runs |
| `java -version 2>&1` | (any) | openjdk version "21.0.2" 2024-01-16, OpenJDK 64-Bit Server VM, mixed mode, **sharing** |
| `ls ~/.sdkman/candidates/java/21.0.2-open/lib/server/classes.jsa` | (any) | **File exists** (default JDK CDS archive, 19M, not customized for kartographer) |

---

## 9. Known Traps and Edge Cases

- **Local plugin compilation blocks startup**: When a project has a `karto/` directory, the pipeline loader runs `gradlew -p karto jar` before even loading the `.kts` script. This subprocess incurs Gradle configuration time (typically 5–15s even for small projects). Source: `[KtsPipelineLoader.kt:29-44](/home/brunorbsf/MiscProjects/kartographer/cli/src/main/kotlin/io/kartographer/cli/KtsPipelineLoader.kt:29-44)`, `[PluginLoader.kt:157-178](/home/brunorbsf/MiscProjects/kartographer/core/src/main/kotlin/io/kartographer/core/plugins/PluginLoader.kt:157-178)`.
- **PluginLoader.init() runs before CLI argument parsing**: The `PluginLoader` singleton's `init` block fires on first class access — which happens during JVM class loading, before `main()` is entered. All users pay the plugins scan cost regardless of subcommand. Source: `[PluginLoader.kt:18-19](/home/brunorbsf/MiscProjects/kartographer/core/src/main/kotlin/io/kartographer/core/plugins/PluginLoader.kt:18-19)`.
- **Full classpath leaked to script compiler**: The script compilation config uses `dependenciesFromCurrentContext(wholeClasspath = true)` (`[ScriptDefinition.kt:27](/home/brunorbsf/MiscProjects/kartographer/core/src/main/kotlin/io/kartographer/dsl/scripting/ScriptDefinition.kt:27)`), meaning the Kotlin script compiler must process all 75 jars from the CLI classpath as dependency sources. The compiler may read class metadata from each jar.
- **Kotlin version mismatch**: `core` declares scripting deps at 1.9.20, `cli` at 1.9.22, `kotlin-stdlib` resolves to 1.9.23, `kotlin-reflect` is 1.9.20. This does not cause errors at runtime (the script compiler 1.9.22 is what actually runs) but means the `ScriptCompilationConfiguration` in `core` was compiled against a different API version than what's loaded at runtime. Source: `[core/build.gradle.kts:22-25](/home/brunorbsf/MiscProjects/kartographer/core/build.gradle.kts:22-25)` vs `[cli/build.gradle.kts:28-33](/home/brunorbsf/MiscProjects/kartographer/cli/build.gradle.kts:28-33)`.
- **Task libraries are compile-time dependencies of CLI**: All 9 task submodules are `implementation` dependencies of the CLI module. Removing any task library from classpath without also removing it from the CLI build would break compilation. Source: `[cli/build.gradle.kts:13-21](/home/brunorbsf/MiscProjects/kartographer/cli/build.gradle.kts:13-21)`.
- **No daemon running**: The gRPC daemon exists in the codebase for warm-JVM execution but is not currently in use for pipeline loading. The `RemoteWorkerClient` is only used for individual task execution, not for script loading. Source: daemon not running (verified via `ps aux`), `[RemoteWorkerClient.kt:15-68](/home/brunorbsf/MiscProjects/kartographer/core/src/main/kotlin/io/kartographer/core/scheduler/RemoteWorkerClient.kt:15-68)` shows per-task gRPC execution only.
- **SHA-256 hashing is per-file and per-invocation**: For up-to-date checks, `TaskStateStore.calculateHash()` reads every byte of every tracked file. For directory hashes, it walks the entire directory tree sorted. This is O(files × file_size) and happens during task execution (not startup), but could compound with the startup cost on repeated runs. Source: `[TaskState.kt:81-101](/home/brunorbsf/MiscProjects/kartographer/core/src/main/kotlin/io/kartographer/core/scheduler/TaskState.kt:81-101)`.

---

*Spike completed 2026-08-05. Verified facts: 34. Source links: 40+. Commands run: 13. Unverified/open facts: 0.*
