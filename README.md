# Kartographer

Kartographer is a topology-aware Directed Acyclic Graph (DAG) task orchestrator. It allows you to define pipelines using a clean, type-safe Kotlin DSL, validate dependencies, and execute tasks locally while targeting specific hardware, operating system, or environment requirements.

## Installation

The only runtime requirement is **Java 21** — the bundle ships its own libraries and Kotlin
compiler, so no Gradle or Kotlin installation is needed.

### Linux / macOS

```bash
unzip kartographer-0.1.0.zip
export PATH="$PWD/kartographer-0.1.0/bin:$PATH"
kartographer --help
```

### Windows (PowerShell)

```powershell
Expand-Archive kartographer-0.1.0.zip -DestinationPath .
$env:PATH += ";$PWD\kartographer-0.1.0\bin"
kartographer.bat --help
```

The bundle contains both launchers in `bin/`: `kartographer` (CLI) and `kartographer-daemon`
(the long-lived process the CLI starts on demand). Keep them in the same `bin/` directory, and
keep `bin/` next to `lib/`.

### From source

```bash
./gradlew :dist:distZip          # default version 0.1.0-SNAPSHOT
./gradlew :dist:distZip -Pversion=0.1.0
```

`./bootstrap.sh` remains the developer path: it builds `installDist` and symlinks `kartographer`
and `kartographer-daemon` into `~/.local/bin`.


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

## Roadmap

1. **Parallel Local Execution**: Use coroutines to execute independent DAG branches concurrently.
2. **Dynamic Kotlin `.kts` Script Loading**: Integrate the Kotlin Scripting compiler host to compile and execute `.kts` files at runtime. See [docs/scripting_roadmap.md](file:///home/brunorbsf/MiscProjects/kartographer/docs/scripting_roadmap.md) for the detailed design.
3. **Containerized Execution**: Implement a Docker/Podman executor to run tasks inside specified containers.
4. **Remote Worker Protocol**: Establish a worker agent daemon communicating via gRPC/WebSockets to execute tasks on remote servers.
5. **Distributed Scheduler**: Distribute task scheduling and placement decisions across multiple remote workers based on capability matching.
6. **Content-Addressed Caching**: Implement input/output hashing for task caching and reproducible build execution.
7. **Artifact Storage**: Store and share intermediate outputs between tasks and workers.
8. **Kubernetes Integration**: Provision worker pods dynamically inside Kubernetes clusters.
