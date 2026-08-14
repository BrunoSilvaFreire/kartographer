# Kartographer Daemonization Investigation Handoff

Evidence-only report for an architect agent. No implementation decisions are made here.

## 1. Key Architectural Facts (do not rediscover)

- Repository: `/home/brunorbsf/CityscapeProjects/kartographer`; nested Git repository, branch `development`, clean worktree at investigation time. The latest commit is `211ed62 feat: checkpoint task outputs, renderer improvements, and scheduler concurrency` (2026-08-06). Command evidence is recorded in section 8.
- Kartographer is a Kotlin/JVM DAG task orchestrator. Its documented CLI commands are `tasks`, `graph`, and `run`; pipeline definitions are compiled Kotlin classes or `.kts` scripts. Source: [README.md:1-3](/home/brunorbsf/CityscapeProjects/kartographer/README.md:1), [README.md:25-51](/home/brunorbsf/CityscapeProjects/kartographer/README.md:25).
- The CLI is an application module with direct compile-time dependencies on `core` and eight task modules (`apt`, `containers`, `python`, `go`, `gradle`, `proto`, `ansible`, `pulumi`), plus Kotlin scripting compiler/host libraries and Clikt. Source: [cli/build.gradle.kts:12-35](/home/brunorbsf/CityscapeProjects/kartographer/cli/build.gradle.kts:12).
- The daemon is a separate application module depending on `core`, Kotlin coroutines, gRPC Netty/protobuf/stub/Kotlin stub, and protobuf Kotlin. Its entrypoint is `io.kartographer.daemon.MainKt`. Source: [daemon/build.gradle.kts:11-28](/home/brunorbsf/CityscapeProjects/kartographer/daemon/build.gradle.kts:11).
- The daemon transport is plaintext gRPC. The proto currently exposes only `GetWorkerInfo` and `ExecuteTask`; there are no project-loading, pipeline-query, completion, cache, invalidation, or shutdown RPCs. Source: [kartographer_daemon.proto:1-14](/home/brunorbsf/CityscapeProjects/kartographer/proto/kartographer_daemon.proto:1).
- `ExecuteTaskRequest` carries task type/name, run ID, serialized string properties, input/output paths, project directory, environment, and dry-run. `ExecuteTaskResponse` carries status, error message, exit code, and daemon-host log path. Source: [kartographer_daemon.proto:24-53](/home/brunorbsf/CityscapeProjects/kartographer/proto/kartographer_daemon.proto:24).
- Daemon startup accepts the first argument as an integer port and defaults to `50051`; it initializes the singleton `PluginLoader`, logs loaded modules, starts `DaemonServer`, and blocks. Source: [daemon/Main.kt:8-17](/home/brunorbsf/CityscapeProjects/kartographer/daemon/src/main/kotlin/io/kartographer/daemon/Main.kt:8).
- `DaemonServer` binds `ServerBuilder.forPort(port)` and installs one service. It has a shutdown hook and exposes the actual bound port for tests. Source: [DaemonServer.kt:16-45](/home/brunorbsf/CityscapeProjects/kartographer/daemon/src/main/kotlin/io/kartographer/daemon/DaemonServer.kt:16).
- Daemon task execution is synchronous within the RPC method: it creates a per-run log, optionally compiles/loads `<projectDir>/karto` local plugin code, resolves a task class, reflectively constructs it with one `String` constructor argument, deserializes properties, copies input/output declarations, creates an `ExecutionContext`, and invokes `taskInstance.execute`. Source: [DaemonServer.kt:61-146](/home/brunorbsf/CityscapeProjects/kartographer/daemon/src/main/kotlin/io/kartographer/daemon/DaemonServer.kt:61).
- Daemon local-plugin discovery requires `<projectDir>/karto/build.gradle.kts`; it first looks for a jar under `karto/build/libs`, otherwise invokes the nearest `gradlew` with `-p <localPluginDir> jar`, then adds the jar to the global singleton loader. Source: [DaemonServer.kt:79-93](/home/brunorbsf/CityscapeProjects/kartographer/daemon/src/main/kotlin/io/kartographer/daemon/DaemonServer.kt:79), [PluginLoader.kt:145-188](/home/brunorbsf/CityscapeProjects/kartographer/core/src/main/kotlin/io/kartographer/core/plugins/PluginLoader.kt:145).
- Daemon logs use `/var/log/kartographer/runs/<runId>/<task>.log` when writable and otherwise `$HOME/.kartographer/logs/runs/<runId>/<task>.log`; the response returns the absolute path on the daemon host. Source: [DaemonServer.kt:65-77](/home/brunorbsf/CityscapeProjects/kartographer/daemon/src/main/kotlin/io/kartographer/daemon/DaemonServer.kt:65), [DaemonServer.kt:180-195](/home/brunorbsf/CityscapeProjects/kartographer/daemon/src/main/kotlin/io/kartographer/daemon/DaemonServer.kt:180), [kartographer_daemon.proto:43-53](/home/brunorbsf/CityscapeProjects/kartographer/proto/kartographer_daemon.proto:43).
- `PluginLoader` is a process-global Kotlin object. Its eager initializer calls `initialize()`. Initialization clears module/type/jar state, closes the prior URL classloader, scans `$KARTOGRAPHER_PLUGINS_DIR` or `$HOME/.kartographer/plugins` for `.jar` files, creates a URL classloader, and registers `KartographerModule` implementations with `ServiceLoader`. Source: [PluginLoader.kt:9-63](/home/brunorbsf/CityscapeProjects/kartographer/core/src/main/kotlin/io/kartographer/core/plugins/PluginLoader.kt:9).
- `PluginLoader.addPluginJar` prepends a dynamic jar, closes/replaces the URL classloader, clears registrations, and re-runs `ServiceLoader`; local jars therefore mutate shared process state and supersede same-named global jars. Source: [PluginLoader.kt:65-105](/home/brunorbsf/CityscapeProjects/kartographer/core/src/main/kotlin/io/kartographer/core/plugins/PluginLoader.kt:65).
- The CLI parent command always resolves and loads the pipeline before subcommands run. It chooses `--pipeline` or `<projectDir>/karto.kts`; `.kts` calls `KtsPipelineLoader.load`, otherwise it reflectively instantiates the class. Source: [Main.kt:29-77](/home/brunorbsf/CityscapeProjects/kartographer/cli/src/main/kotlin/io/kartographer/cli/Main.kt:29).
- `KtsPipelineLoader` clears the process-global `TaskRegistry`, compiles/evaluates the script with `BasicJvmScriptingHost`, inherits the whole current classpath, and adds all `PluginLoader.getPluginJars()` to the script compilation classpath. Source: [KtsPipelineLoader.kt:25-85](/home/brunorbsf/CityscapeProjects/kartographer/cli/src/main/kotlin/io/kartographer/cli/KtsPipelineLoader.kt:25).
- Current script compilation has a disk cache at `$HOME/.kartographer/script-cache`. The cache key hashes script text plus every JVM classpath/plugin entry path, size, and mtime; an unwritable cache directory silently falls back to the default scripting configuration. Source: [KtsPipelineLoader.kt:87-118](/home/brunorbsf/CityscapeProjects/kartographer/cli/src/main/kotlin/io/kartographer/cli/KtsPipelineLoader.kt:87).
- The CLI `run` path executes locally through `ConcurrentScheduler`, creates a local `Worker` with capabilities including `local-exec`, `ansible`, `flutter`, `go`, `protoc`, `pulumi`, `python`, and `docker`, constructs a local `ExecutionContext`, handles property conversion, privilege prompting, rendering, and calls `scheduler.execute`. Source: [Main.kt:148-365](/home/brunorbsf/CityscapeProjects/kartographer/cli/src/main/kotlin/io/kartographer/cli/Main.kt:148).
- The CLI's existing remote client is not used by `RunCommand`; it creates a new plaintext gRPC channel per `RemoteWorkerClient` instance and exposes only `executeRemote(task, context)`, translating one task to one `ExecuteTask` RPC. Source: [RemoteWorkerClient.kt:15-63](/home/brunorbsf/CityscapeProjects/kartographer/core/src/main/kotlin/io/kartographer/core/scheduler/RemoteWorkerClient.kt:15).
- Completion is intercepted in the CLI `main` by finding an environment variable whose name ends in `_COMPLETE`, then calling `CustomAutocomplete.handle` before Clikt parsing. Source: [Main.kt:410-414](/home/brunorbsf/CityscapeProjects/kartographer/cli/src/main/kotlin/io/kartographer/cli/Main.kt:410).
- `CustomAutocomplete` currently parses `-d/--project-dir` and `-p/--pipeline`, loads the pipeline in-process, resolves the `run` target from `TaskRegistry`, traverses dependencies, and invokes a task property's suspend `completionProvider` in the CLI JVM. It prints matching values or flags and exits for those cases. Source: [CustomAutocomplete.kt:12-35](/home/brunorbsf/CityscapeProjects/kartographer/cli/src/main/kotlin/io/kartographer/cli/CustomAutocomplete.kt:12), [CustomAutocomplete.kt:37-113](/home/brunorbsf/CityscapeProjects/kartographer/cli/src/main/kotlin/io/kartographer/cli/CustomAutocomplete.kt:37).
- Properties can expose CLI names/descriptions and either literal or suspend dynamic completions. Completion providers are arbitrary Kotlin code held in the loaded task object. Source: [Properties.kt:10-17](/home/brunorbsf/CityscapeProjects/kartographer/core/src/main/kotlin/io/kartographer/core/Properties.kt:10), [Properties.kt:54-79](/home/brunorbsf/CityscapeProjects/kartographer/core/src/main/kotlin/io/kartographer/core/Properties.kt:54).
- No file watcher API, `WatchService`, watcher dependency, cache invalidation handler, or project-cache data structure was found in the kartographer source search. The only cache implementation found is the script jar cache in [KtsPipelineLoader.kt:95-107](/home/brunorbsf/CityscapeProjects/kartographer/cli/src/main/kotlin/io/kartographer/cli/KtsPipelineLoader.kt:95).
- The bootstrap script builds and symlinks separate CLI and daemon launchers as `karto`, `kartographer`, and `kartographer-daemon`; it does not start the daemon or configure a client endpoint. Source: [bootstrap.sh:1-23](/home/brunorbsf/CityscapeProjects/kartographer/bootstrap.sh:1).

## 2. The Problem

- User-stated direction: improve Kartographer by moving more work into the daemon so startup and invocation are very fast; locally remove execution of tasks through the CLI; all requests are to go to the daemon; the daemon is to maintain caches of projects and compiled plugins and know when to invalidate them using file watchers or equivalent; shell tab completion is also to go through the daemon, with the CLI forwarding completion requests and the daemon serving fast completion from cache. Source: the current user request.
- Current observable behavior: `karto run` loads the pipeline and task registry inside the short-lived CLI process and executes the DAG locally through `ConcurrentScheduler`; no CLI code routes `run`, `tasks`, `graph`, or completion to gRPC. Sources: [Main.kt:29-77](/home/brunorbsf/CityscapeProjects/kartographer/cli/src/main/kotlin/io/kartographer/cli/Main.kt:29), [Main.kt:225-365](/home/brunorbsf/CityscapeProjects/kartographer/cli/src/main/kotlin/io/kartographer/cli/Main.kt:225), [CustomAutocomplete.kt:65-109](/home/brunorbsf/CityscapeProjects/kartographer/cli/src/main/kotlin/io/kartographer/cli/CustomAutocomplete.kt:65).
- Current observable behavior: daemon task execution exists, but the daemon protocol only accepts already-resolved individual task data and has no request representing a pipeline command or completion query. Sources: [kartographer_daemon.proto:8-14](/home/brunorbsf/CityscapeProjects/kartographer/proto/kartographer_daemon.proto:8), [kartographer_daemon.proto:24-53](/home/brunorbsf/CityscapeProjects/kartographer/proto/kartographer_daemon.proto:24).
- Runtime state during this investigation included a daemon process listening on `*:50051`; this is runtime evidence, not source-controlled behavior. Command output is in section 8.

## 3. What Already Exists

- A gRPC daemon application and generated protobuf bindings exist. The daemon can report fixed worker metadata and execute one serialized task. Sources: [DaemonServer.kt:48-177](/home/brunorbsf/CityscapeProjects/kartographer/daemon/src/main/kotlin/io/kartographer/daemon/DaemonServer.kt:48), [kartographer_daemon.proto:8-53](/home/brunorbsf/CityscapeProjects/kartographer/proto/kartographer_daemon.proto:8).
- A client-side gRPC adapter exists for one task at a time, but the CLI scheduler does not instantiate or call it. Sources: [RemoteWorkerClient.kt:15-67](/home/brunorbsf/CityscapeProjects/kartographer/core/src/main/kotlin/io/kartographer/core/scheduler/RemoteWorkerClient.kt:15), [Main.kt:225-350](/home/brunorbsf/CityscapeProjects/kartographer/cli/src/main/kotlin/io/kartographer/cli/Main.kt:225).
- Pipeline scripts are evaluated through Kotlin scripting. Local `karto/` plugins may be compiled via Gradle before script evaluation, and compiled script jars are cached on disk using a content/metadata-derived key. Sources: [KtsPipelineLoader.kt:32-73](/home/brunorbsf/CityscapeProjects/kartographer/cli/src/main/kotlin/io/kartographer/cli/KtsPipelineLoader.kt:32), [KtsPipelineLoader.kt:87-118](/home/brunorbsf/CityscapeProjects/kartographer/cli/src/main/kotlin/io/kartographer/cli/KtsPipelineLoader.kt:87).
- Task execution supports stateful task inputs/outputs, up-to-date checks, properties, task output collection, topology requirements, and local process execution. Sources: [Node.kt:27-47](/home/brunorbsf/CityscapeProjects/kartographer/core/src/main/kotlin/io/kartographer/core/model/Node.kt:27), [Node.kt:131-160](/home/brunorbsf/CityscapeProjects/kartographer/core/src/main/kotlin/io/kartographer/core/model/Node.kt:131), [Execution.kt:18-30](/home/brunorbsf/CityscapeProjects/kartographer/core/src/main/kotlin/io/kartographer/core/model/Execution.kt:18).
- Daemon integration coverage starts an ephemeral gRPC server, calls `RemoteWorkerClient.executeRemote`, and verifies success plus daemon log contents. Source: [DaemonIntegrationTest.kt:23-65](/home/brunorbsf/CityscapeProjects/kartographer/daemon/src/test/kotlin/io/kartographer/daemon/DaemonIntegrationTest.kt:23).
- Script-loading coverage verifies one temporary `.kartographer.kts` file populates `TaskRegistry`. Source: [KtsPipelineLoaderTest.kt:12-55](/home/brunorbsf/CityscapeProjects/kartographer/cli/src/test/kotlin/io/kartographer/cli/KtsPipelineLoaderTest.kt:12).
- Local plugin integration coverage compiles a temporary plugin, tests global/local jar precedence, changes source, reloads the script, and verifies task properties. Sources: [LocalPluginReloadingStepDefinitions.kt:18-44](/home/brunorbsf/CityscapeProjects/kartographer/integration-tests/src/test/kotlin/io/kartographer/integration/LocalPluginReloadingStepDefinitions.kt:18), [LocalPluginReloadingStepDefinitions.kt:126-161](/home/brunorbsf/CityscapeProjects/kartographer/integration-tests/src/test/kotlin/io/kartographer/integration/LocalPluginReloadingStepDefinitions.kt:126).

## 4. User-Stated Requirements and Constraints

- Requests that currently execute tasks locally through the CLI are to be moved so requests go to the daemon. Source: current user request.
- The daemon is to maintain caches for projects and compiled plugins. Source: current user request.
- Cache invalidation is to use file watchers or other mechanisms that detect relevant changes. Source: current user request.
- Bash tab-completion requests received by the CLI are to be forwarded to the daemon, and the daemon is to provide very fast completion from cache. Source: current user request.
- The user framed this as a local startup/invocation performance improvement. Source: current user request.

## 5. Non-goals

- No explicit non-goals were stated in the current user request or the inspected repository documentation. The repository README documents current MVP limitations and roadmap items, but does not define non-goals for this daemonization request. Sources: [README.md:88-105](/home/brunorbsf/CityscapeProjects/kartographer/README.md:88), current user request.

## 6. Evidence Gathered

- Inspected the repository root, nested Git state, recent commits, Gradle module configuration, README, bootstrap script, daemon proto, daemon implementation, CLI entrypoint, completion handler, plugin loader, script loader, execution model, remote client, and relevant unit/integration tests. Source links are indexed in section 7.
- Traced current execution path: `MainKt.main` → `KartographerCommand.run` → `KtsPipelineLoader` or class reflection → `TaskRegistry` → `RunCommand` local `ConcurrentScheduler` → local `ExecutionEnvironment`. Sources: [Main.kt:410-449](/home/brunorbsf/CityscapeProjects/kartographer/cli/src/main/kotlin/io/kartographer/cli/Main.kt:410), [Main.kt:29-77](/home/brunorbsf/CityscapeProjects/kartographer/cli/src/main/kotlin/io/kartographer/cli/Main.kt:29), [Main.kt:225-365](/home/brunorbsf/CityscapeProjects/kartographer/cli/src/main/kotlin/io/kartographer/cli/Main.kt:225), [LocalExecution.kt:29-66](/home/brunorbsf/CityscapeProjects/kartographer/core/src/main/kotlin/io/kartographer/local/LocalExecution.kt:29).
- Traced current remote path: `RemoteWorkerClient.executeRemote` serializes one `Task`, calls `ExecuteTask`, and maps response status to `ExecutionResult`; no pipeline-level caller was found in CLI code. Sources: [RemoteWorkerClient.kt:27-63](/home/brunorbsf/CityscapeProjects/kartographer/core/src/main/kotlin/io/kartographer/core/scheduler/RemoteWorkerClient.kt:27), [Main.kt:225-350](/home/brunorbsf/CityscapeProjects/kartographer/cli/src/main/kotlin/io/kartographer/cli/Main.kt:225).
- Traced current completion path: shell environment detection → argument parsing → in-process pipeline load → target/dependency traversal → property completion provider or flag output. Sources: [Main.kt:410-414](/home/brunorbsf/CityscapeProjects/kartographer/cli/src/main/kotlin/io/kartographer/cli/Main.kt:410), [CustomAutocomplete.kt:37-113](/home/brunorbsf/CityscapeProjects/kartographer/cli/src/main/kotlin/io/kartographer/cli/CustomAutocomplete.kt:37).
- Inspected caches and invalidation evidence. The current source has only the Kotlin compiled-script cache; its key includes script text and classpath/plugin file metadata. No watcher or daemon cache implementation was found. Source: [KtsPipelineLoader.kt:87-118](/home/brunorbsf/CityscapeProjects/kartographer/cli/src/main/kotlin/io/kartographer/cli/KtsPipelineLoader.kt:87); search evidence is recorded in section 8.
- Inspected runtime state. At command time, a Java process from the daemon distribution listened on port 50051, and `$HOME/.kartographer/script-cache` contained one compiled script jar. Command output is in section 8.
- Inspected prior local investigation material. `docs/investigation/pipeline-startup-performance-spike.md` describes an earlier daemonization/performance context, but its source links point to `/home/brunorbsf/MiscProjects/kartographer` rather than the current repository path, and its claim that no script cache exists is inconsistent with current [KtsPipelineLoader.kt:95-107](/home/brunorbsf/CityscapeProjects/kartographer/cli/src/main/kotlin/io/kartographer/cli/KtsPipelineLoader.kt:95). The document remains historical evidence, not current source-of-truth. Source: [pipeline-startup-performance-spike.md:47-53](/home/brunorbsf/CityscapeProjects/kartographer/docs/investigation/pipeline-startup-performance-spike.md:47), [pipeline-startup-performance-spike.md:134-136](/home/brunorbsf/CityscapeProjects/kartographer/docs/investigation/pipeline-startup-performance-spike.md:134).
- Prior-session search was not completed: the required `agents sources` and `agents index` commands returned `/bin/bash: agents: command not found`. No historical session claims are therefore included as verified evidence.

## 7. Source Reference Index

| Source | Evidence |
|---|---|
| [kartographer_daemon.proto:8-53](/home/brunorbsf/CityscapeProjects/kartographer/proto/kartographer_daemon.proto:8) | Current gRPC service and request/response schema. |
| [DaemonServer.kt:16-197](/home/brunorbsf/CityscapeProjects/kartographer/daemon/src/main/kotlin/io/kartographer/daemon/DaemonServer.kt:16) | Server binding, task RPC, plugin loading, execution context, logs, and errors. |
| [Main.kt:29-77](/home/brunorbsf/CityscapeProjects/kartographer/cli/src/main/kotlin/io/kartographer/cli/Main.kt:29) | CLI pipeline discovery and in-process loading. |
| [Main.kt:148-365](/home/brunorbsf/CityscapeProjects/kartographer/cli/src/main/kotlin/io/kartographer/cli/Main.kt:148) | Local run command, property handling, renderer, scheduler, and local worker. |
| [Main.kt:410-449](/home/brunorbsf/CityscapeProjects/kartographer/cli/src/main/kotlin/io/kartographer/cli/Main.kt:410) | Completion interception and argument rewriting. |
| [CustomAutocomplete.kt:12-113](/home/brunorbsf/CityscapeProjects/kartographer/cli/src/main/kotlin/io/kartographer/cli/CustomAutocomplete.kt:12) | Current in-process completion implementation. |
| [KtsPipelineLoader.kt:25-118](/home/brunorbsf/CityscapeProjects/kartographer/cli/src/main/kotlin/io/kartographer/cli/KtsPipelineLoader.kt:25) | Script/plugin load path and compiled-script cache key/configuration. |
| [PluginLoader.kt:9-190](/home/brunorbsf/CityscapeProjects/kartographer/core/src/main/kotlin/io/kartographer/core/plugins/PluginLoader.kt:9) | Global plugin registry, classloader lifecycle, jar discovery, and local compilation. |
| [RemoteWorkerClient.kt:15-67](/home/brunorbsf/CityscapeProjects/kartographer/core/src/main/kotlin/io/kartographer/core/scheduler/RemoteWorkerClient.kt:15) | Existing per-task gRPC client and response mapping. |
| [DaemonIntegrationTest.kt:23-65](/home/brunorbsf/CityscapeProjects/kartographer/daemon/src/test/kotlin/io/kartographer/daemon/DaemonIntegrationTest.kt:23) | Existing daemon end-to-end test scope. |
| [KtsPipelineLoaderTest.kt:12-55](/home/brunorbsf/CityscapeProjects/kartographer/cli/src/test/kotlin/io/kartographer/cli/KtsPipelineLoaderTest.kt:12) | Existing script loader test scope. |
| [bootstrap.sh:1-23](/home/brunorbsf/CityscapeProjects/kartographer/bootstrap.sh:1) | Distribution and symlink setup; no daemon auto-start/client configuration. |
| [README.md:25-105](/home/brunorbsf/CityscapeProjects/kartographer/README.md:25) | Documented commands, limitations, and roadmap. |

## 8. Commands Run and Observed Outputs

- `pwd && ls -la` in `/home/brunorbsf/CityscapeProjects`: confirmed workspace and `kartographer/` directory.
- Repository discovery with `git` in `/home/brunorbsf/CityscapeProjects/kartographer`: `development`; latest commit `211ed62`; `git status --short` produced no output; remote is `git@github.com:BrunoSilvaFreire/kartographer.git`.
- `./gradlew :cli:test :daemon:test` in the kartographer repository: `BUILD SUCCESSFUL in 58s`; 40 actionable tasks, 15 executed, 25 up-to-date. Gradle emitted a warning that embedded Kotlin/kotlin-dsl use 1.9.20 while requested Kotlin plugin version is 1.9.22.
- `./gradlew :cli:installDist :daemon:installDist`: `BUILD SUCCESSFUL in 5s`; 42 actionable tasks, 11 executed, 31 up-to-date; the same Kotlin plugin-version warning appeared.
- `ps aux`: showed Java PID `22721` running the daemon distribution with `io.kartographer.daemon.MainKt --help` and Java PID `22999` running the Kotlin compile daemon. The process listing also showed unrelated Gradle/OpenCode processes. This was observational; no process was started or stopped by this spike.
- `ss -tlnp`: showed `LISTEN ... *:50051 ... users:(('java',pid=22721,fd=63))`. Other listeners were unrelated ports.
- `find "$HOME/.kartographer" -maxdepth 2 -type f ...`: observed `/home/brunorbsf/.kartographer/script-cache/karto-8c0ff1bc80bd100c1a67cee8454efc2f9d5fab25f1d7dfbd118b40bfd6de4e83.jar` at 15,730 bytes; directories observed were `.kartographer`, `logs`, `logs/runs`, and `script-cache`.
- `git ls-files | wc -l`: observed `140`; tracked investigation reports include `db-sample-task-type-spike.md`, `integration-tests-expansion-spike.md`, `pipeline-startup-performance-spike.md`, and `task-outputs-spike.md`.
- `agents sources && agents index`: could not run; observed `/bin/bash: line 1: agents: command not found`.
- `gh repo view --json name,description,url,defaultBranchRef,owner && gh issue list --limit 50 && gh pr list --limit 50` in the kartographer repository: repository `BrunoSilvaFreire/kartographer`, default branch `master`, URL `https://github.com/BrunoSilvaFreire/kartographer`; one open issue was listed, `#1 containers/prod/push omits non-host OCI platforms`; no open PR output was shown. No GitHub issue or PR was changed.
- Search for `daemon|completion|plugin|watch|cache|invoke|execute|project` and related exact terms across kartographer source found the files indexed in section 7; no watcher implementation or additional daemon RPC source was found. The shell `rg` executable was unavailable for direct command search (`/bin/bash: rg: command not found`); repository content search was performed with the workspace search tool.

## 9. Known Traps and Edge Cases

- The daemon's local plugin path can compile on an execution request, so a request that appears to be task execution can invoke an external `gradlew` process before task resolution. Sources: [DaemonServer.kt:79-93](/home/brunorbsf/CityscapeProjects/kartographer/daemon/src/main/kotlin/io/kartographer/daemon/DaemonServer.kt:79), [PluginLoader.kt:157-177](/home/brunorbsf/CityscapeProjects/kartographer/core/src/main/kotlin/io/kartographer/core/plugins/PluginLoader.kt:157).
- `PluginLoader.addPluginJar` replaces a shared classloader and re-registers all modules. Existing task classes from an older loader can coexist with or be invalidated by classloader replacement. The observed replacement behavior is proven by [PluginLoader.kt:83-105](/home/brunorbsf/CityscapeProjects/kartographer/core/src/main/kotlin/io/kartographer/core/plugins/PluginLoader.kt:83).
- The script cache key tracks plugin jar path, size, and mtime, not arbitrary source files inside a local plugin directory. Source: [KtsPipelineLoader.kt:110-118](/home/brunorbsf/CityscapeProjects/kartographer/cli/src/main/kotlin/io/kartographer/cli/KtsPipelineLoader.kt:110).
- A local plugin source change is followed by a fresh compile only when `KtsPipelineLoader.load` or daemon `executeTask` reaches the local-plugin compilation branch; there is no source watcher or background invalidation path in the current code. Sources: [KtsPipelineLoader.kt:32-45](/home/brunorbsf/CityscapeProjects/kartographer/cli/src/main/kotlin/io/kartographer/cli/KtsPipelineLoader.kt:32), [DaemonServer.kt:79-93](/home/brunorbsf/CityscapeProjects/kartographer/daemon/src/main/kotlin/io/kartographer/daemon/DaemonServer.kt:79).
- The daemon's `GetWorkerInfo` advertises capabilities `local-exec`, `go`, `gradle`, and `protoc`, while the CLI local worker advertises additional capabilities including `ansible`, `flutter`, `pulumi`, `python`, and `docker`. Sources: [DaemonServer.kt:50-58](/home/brunorbsf/CityscapeProjects/kartographer/daemon/src/main/kotlin/io/kartographer/daemon/DaemonServer.kt:50), [Main.kt:227-235](/home/brunorbsf/CityscapeProjects/kartographer/cli/src/main/kotlin/io/kartographer/cli/Main.kt:227).
- `ExecuteTaskResponse.log_path` is explicitly a path on the daemon host, so the client cannot assume the returned log path is local to the CLI process. Source: [kartographer_daemon.proto:43-53](/home/brunorbsf/CityscapeProjects/kartographer/proto/kartographer_daemon.proto:43).
- `RemoteWorkerClient` creates a new channel in each client instance and awaits shutdown for up to five seconds on close. Sources: [RemoteWorkerClient.kt:15-25](/home/brunorbsf/CityscapeProjects/kartographer/core/src/main/kotlin/io/kartographer/core/scheduler/RemoteWorkerClient.kt:15), [RemoteWorkerClient.kt:65-67](/home/brunorbsf/CityscapeProjects/kartographer/core/src/main/kotlin/io/kartographer/core/scheduler/RemoteWorkerClient.kt:65).
- Completion requests that do not contain `run` or do not yet contain a task name return from the custom handler and fall back to Clikt behavior; task-specific completion can therefore involve different code paths. Source: [CustomAutocomplete.kt:54-63](/home/brunorbsf/CityscapeProjects/kartographer/cli/src/main/kotlin/io/kartographer/cli/CustomAutocomplete.kt:54).
- Dynamic completion providers execute arbitrary suspend Kotlin code from the loaded pipeline/plugin process. Source: [CustomAutocomplete.kt:90-99](/home/brunorbsf/CityscapeProjects/kartographer/cli/src/main/kotlin/io/kartographer/cli/CustomAutocomplete.kt:90), [Properties.kt:16-17](/home/brunorbsf/CityscapeProjects/kartographer/core/src/main/kotlin/io/kartographer/core/Properties.kt:16).
- The daemon process observed on port 50051 was launched with `--help`, but `Main.kt` treats the first argument as a numeric port and otherwise defaults to 50051; source behavior and process command-line text do not establish whether that process was intentionally started for normal service use. Sources: [Main.kt:8-10](/home/brunorbsf/CityscapeProjects/kartographer/daemon/src/main/kotlin/io/kartographer/daemon/Main.kt:8), section 8 process output.
- The previous startup-performance report contains stale absolute links and historical runtime claims; current source and runtime evidence supersede those claims where they conflict. Sources: [pipeline-startup-performance-spike.md:7-10](/home/brunorbsf/CityscapeProjects/kartographer/docs/investigation/pipeline-startup-performance-spike.md:7), [KtsPipelineLoader.kt:87-118](/home/brunorbsf/CityscapeProjects/kartographer/cli/src/main/kotlin/io/kartographer/cli/KtsPipelineLoader.kt:87).

## Explicitly Unverified / Open Facts

- No prior Codex, Claude, OpenCode, or Antigravity session was verified because the `agents` executable is unavailable in this environment.
- No daemon RPC latency, CLI cold-start latency, cache hit rate, concurrent-request behavior, or watcher behavior was measured in this spike.
- No source-controlled daemon auto-start mechanism, daemon discovery mechanism, endpoint environment variable, Unix socket, PID file, or lifecycle protocol was found in the inspected kartographer files; absence is based on repository search, not a runtime proof of external tooling.

---

# Outcome — Daemonization Implemented (2026-08-08)

The spike above is the pre-implementation survey. This section records what was built and
what it measures, superseding the "Explicitly Unverified" list.

## Measured results

All figures from this machine, project = the kartographer repo's own `karto.kts`
(three `GradleTask`s), median of five runs.

| Invocation | Before | After |
|---|---|---|
| `karto tasks`, warm | ~1000–1100 ms | **~540 ms** |
| `karto graph`, warm | ~1000 ms | **~640–750 ms** |
| tab completion | ~1200–1500 ms | **~400–470 ms** |
| `karto tasks`, cold (no daemon, empty script cache) | ~5600 ms | ~5400 ms |
| CLI distribution | 102 MB / 72 jars | **40 MB / 54 jars** |
| Daemon distribution | 85 MB / 42 jars | 87 MB / 53 jars |

Daemon-side work for a cached project is **~11–23 ms**. Everything above that is CLI process
startup, which is now the whole cost.

## Why the CLI does not speak gRPC

The first implementation used gRPC over a Unix socket for the CLI too, and came out *slower*
than the baseline (~1190 ms). Instrumented breakdown (`KARTOGRAPHER_TIMING=1`):

| Segment | Cost |
|---|---|
| JVM start, Clikt, class loading | ~390 ms |
| `Epoll.isAvailable()` — Netty native extract + load | ~276 ms |
| gRPC channel construction | ~111 ms |
| First RPC — gRPC/protobuf class init | ~243 ms |
| `DescribeProject` round trip (the actual work) | **23 ms** |

gRPC costs ~630 ms to *initialise* in a process that lives one second. A plain JDK 21
`SocketChannel` over `StandardProtocolFamily.UNIX` costs ~60 ms for the same job, so the CLI
now speaks length-prefixed protobuf frames (`LocalProtocol`) over the socket. gRPC is retained
for the genuinely-remote worker path over TCP, where setup is amortised.

`KARTOGRAPHER_TIMING=1` remains available for re-measuring.

## What was built

- **Per-project isolation** (`core`): `NodeRegistry` extracted from `object TaskRegistry`,
  which is now a thread-local-bound facade; `PluginSet` extracted from `object PluginLoader`,
  giving each project its own classloader layered over the global plugins directory.
- **Daemon owns pipeline loading**: `KtsPipelineLoader` moved `cli` → `daemon`, plus a
  `ProjectCache` that validates a stat fingerprint (script, `karto/` sources, plugin jars) on
  every request and reloads when it differs. Correct by construction — no watcher needed. A
  `WatchService` pre-warm can be layered on later without changing the contract.
- **Protocol**: `GetDaemonInfo`, `DescribeProject`, `Complete`, `RunPipeline` (bidirectional),
  `Shutdown`, plus the `LocalRequest`/`LocalResponse` envelope for the local transport.
- **Transports**: Unix socket at `$XDG_RUNTIME_DIR/kartographer/daemon.sock`, created `0600`
  in a `0700` directory (verified `srw-------`). TCP+gRPC is opt-in via `--listen-tcp`.
- **CLI**: thin client. Dropped all eight task modules and the Kotlin scripting/compiler
  dependencies; `core` no longer leaks `kotlin-compiler-embeddable` onto consumers' runtime
  classpaths. Auto-starts the daemon, and restarts one whose jar signature (`BuildStamp`)
  differs from the installed distribution. Completion never auto-starts a daemon.
- **Live output**: `ExecutionEvent.TaskOutput` was declared but never emitted anywhere.
  It is now, so `run` streams process output as it happens — previously output only reached a
  logger or a redirect file.

## Test status

`:core:test` 36 passed, `:daemon:test` 10 passed (including 4 new local-protocol tests
covering multi-request connections, completion, exit-code propagation and output streaming).

`:integration-tests:test` — 84 tests, 2 failed. Both are the local-plugin scenarios, and both
**fail identically on unmodified HEAD** (verified in a clean worktree at `211ed62`): the test
fixture builds a plugin against `id("io.kartographer.plugin") version "0.1.0-SNAPSHOT"` from
`mavenLocal()`, and that artifact is not published on this machine. Pre-existing and
environmental, not a regression.

## Known remaining work

- `karto tasks` (~540 ms) costs ~200 ms more than tab completion (~400 ms) because completion
  bypasses Clikt entirely. Clikt command construction is now the largest single remaining
  cost after JVM startup.
- AppCDS would attack the remaining ~310 ms of JVM/class-loading; not attempted.
- Kotlin version skew persists: `core` still pins `kotlin-reflect` at 1.9.20 while the build
  uses 1.9.22. Scripting dependencies were aligned to 1.9.22 as part of this work.
- The daemon idle-shutdown default is 180 minutes (`--idle-timeout`, 0 disables).
