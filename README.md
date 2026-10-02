# Kartographer

Kartographer is a topology-aware Directed Acyclic Graph (DAG) task orchestrator. It allows you to define pipelines using a clean, type-safe Kotlin DSL, validate dependencies, and execute tasks locally while targeting specific hardware, operating system, or environment requirements.

## Why Topology-Aware DAG Orchestration?

Modern build and workflow orchestrators often treat target executors as uniform or rely entirely on container isolation. However, in heterogeneous hybrid or edge environments, certain tasks require specific placement:
- Building an ARM64 binary requires an ARM64 runner or emulator.
- Compiling code on bare metal for speed.
- Execution bound to a specific region or security zone.

Kartographer models these constraints as first-class concepts via **Topology Requirements** (e.g. `localOnly`, `requiresCapability("docker")`, `prefers("region", "us-east")`), allowing the scheduler to place tasks onto matching workers.

---

## Core Concepts

- **DAG Dependency Graph**: Tasks specify direct dependencies, forming an acyclic dependency graph that the scheduler validates (detecting missing dependencies and cycles) and executes in topological order.
- **Typed Tasks**: Tasks are defined as typed Kotlin objects subclassing `Task`, promoting configuration safety and IDE autocompletion.
- **Topology-Aware Placement**: Tasks declare placement constraints that are matched against worker capabilities before execution.
- **Local Process Execution**: Tasks can run shell commands natively, capturing stderr/stdout, custom working directories, and custom environment variables.

---

## MVP CLI Usage

Kartographer CLI runs compiled Kotlin pipelines (default: `io.kartographer.example.BasicPipeline`).

### 1. List Registered Tasks
Lists all tasks declared in the pipeline along with their class types.
```bash
./gradlew run --args="tasks"
```

### 2. View the Dependency Graph
Validates and prints the dependency structure of the pipeline.
```bash
./gradlew run --args="graph"
```

### 3. Run a Task
Resolves dependencies, sorts them topologically, checks worker compatibility, and runs the target task and its upstream dependencies sequentially.
```bash
./gradlew run --args="run \"build tileserver-go\""
```

### Specifying a Custom Pipeline Class
You can load another compiled pipeline class on the classpath via the `--pipeline` option:
```bash
./gradlew run --args="-p io.kartographer.example.BasicPipeline run \"build tileserver-go\""
```

---

## Example Pipeline Definition

```kotlin
package io.kartographer.example

import io.kartographer.core.ExecTask
import io.kartographer.dsl.task

class ProtoGen(name: String) : ExecTask(name)
class GoCompile(name: String) : ExecTask(name)

class BasicPipeline {
    init {
        val codeGenTask = task<ProtoGen>("generate proto go files") {
            outputs.dir("build/generated")
            local {
                exec("mkdir -p build/generated && echo 'hello proto' > build/generated/hello.txt")
            }
        }

        task<GoCompile>("build tileserver-go") {
            dependsOn(codeGenTask)
            inputs.dir("src")
            local {
                exec("echo 'building tileserver-go' && cat build/generated/hello.txt")
            }
        }
    }
}
```

---

## MVP Limitations

1. **Sequential Execution**: Execution is currently sequential (single-threaded).
2. **Compiled DSL**: Pipelines must be compiled Kotlin classes on the classpath rather than dynamic `.kts` scripts compiled at runtime.
3. **Local Process Only**: Execution only supports running processes on the host machine without containerization.

---

## Task Logs

Every local `karto run` captures each task's stdout, stderr, exit status and duration to
`<projectDir>/.kartographer/logs/runs/` (the same `.kartographer/` directory the up-to-date
state uses). One file per run, named with a sortable UTC timestamp and the run id:

```
.kartographer/logs/runs/20260929T101530Z-3f2a...c1.log.gz
```

When the host supports it, the same records are also forwarded to the systemd journal (Linux) or
the Windows Event Log, with structured fields. The message text is identical on every backend.
Each line looks like:

```
2026-09-29T10:15:30.123Z run=<runId> task=<taskName> inv=<invocationId> stream=<out|err|meta> <text>
```

`stream=meta` carries `start` and, at the end of a task, `end status=<SUCCESS|UP_TO_DATE|FAILED>
exit=<code|-> duration_ms=<n>`.

### Configuration

Set these environment variables in the shell that launches `karto` (they are read per run, so the
daemon inherits the client's values):

| Variable | Default | Meaning |
|---|---|---|
| `KARTO_LOG_BACKEND` | `auto` | `auto` (file + native if available), `file`, `journald`, `eventlog`, or `off` |
| `KARTO_LOG_MAX_RUNS` | `50` | keep at most N run logs |
| `KARTO_LOG_MAX_AGE_DAYS` | `14` | delete run logs older than this |
| `KARTO_LOG_MAX_TOTAL_MB` | `500` | delete the oldest runs until the total is under this size |
| `KARTO_LOG_COMPRESS` | `true` | gzip a run's log when the run finishes |

The file store is written for every backend except `off`, because `karto logs` reads it. If an
explicit `journald` or `eventlog` backend is not available on the host, one warning is logged and
the run continues with file logs only. Rotation runs when a run starts: by age, then by count,
then by total size, oldest first; the current run is never pruned.

### Inspecting past runs

```bash
karto logs                      # the latest run
karto logs <runId-prefix>       # a specific run, by run id or timestamp prefix
karto logs --task <name>        # only that task's lines
karto logs --list               # stored runs with their sizes
```

Native backends are queried with the host's own tools. Structured fields let you filter without
grep:

```bash
# Linux
journalctl -t karto KARTO_RUN_ID=<id>
journalctl -t karto KARTO_TASK=<name> -o verbose
journalctl -t karto -n 20 -o verbose

# Windows PowerShell
Get-WinEvent -FilterHashtable @{LogName='Application'; ProviderName='Kartographer'}
```

When the daemon runs inside your login session (rather than as a system service), records land in
the per-user journal, so add `--user` (or point `--file` at the user journal file):

```bash
journalctl --user -t karto KARTO_RUN_ID=<id> -o verbose
```

> The Windows Event Log source is not registered (no message DLL), so Windows shows a
> "description not found" preamble; the message text is still present.

---

## Roadmap

1. **Parallel Local Execution**: Use coroutines to execute independent DAG branches concurrently.
2. **Dynamic Kotlin `.kts` Script Loading**: Integrate the Kotlin Scripting compiler host to compile and execute `.kts` files at runtime. See [docs/scripting_roadmap.md](file:///home/brunorbsf/MiscProjects/kartographer/docs/scripting_roadmap.md) for the detailed design.
3. **Containerized Execution**: Implement a Docker/Podman executor to run tasks inside specified containers.
4. **Remote Worker Protocol**: Establish a worker agent daemon communicating via gRPC/WebSockets to execute tasks on remote servers.
5. **Distributed Scheduler**: Distribute task scheduling and placement decisions across multiple remote workers based on capability matching.
6. **Content-Addressed Caching**: Implement input/output hashing for task caching and reproducible build execution.
7. **Artifact Storage**: Store and share intermediate outputs between tasks and workers.
8. **Kubernetes Integration**: Provision worker pods dynamically inside Kubernetes clusters.
