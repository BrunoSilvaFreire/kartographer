---
name: kartographer-daemon-and-execution
description: How the Kartographer daemon runs and how to debug execution, interactive tasks, Ctrl-C/SIGINT, privilege escalation, live run outputs, and task logs. Use when `karto run` hangs, a Ctrl-C leaves a process alive, an interactive task (e.g. flutter run) gets no stdin, sudo is required, or you need `karto ps`/`karto logs` to inspect runs.
---

# Daemon and Execution

Definitions of the scheduler, providers, services, and other first-class concepts are in
`kartographer-concepts`; build commands and the stale-artifact playbook are in
`kartographer-repo-guide`.

## Why a daemon

The daemon is the engine (see **Architecture** in `kartographer-concepts`): the CLI forwards every
request to it over a Unix socket, and the daemon keeps compiled pipelines and plugin classloaders
warm. The CLI dials the socket; if none answers, it launches `kartographer-daemon` from `bin/` next
to itself.

## Lifecycle

- Auto-started by the CLI on first use; the CLI dials the socket, and if none answers it launches
  `kartographer-daemon` from `bin/` next to itself.
- Socket: `$KARTOGRAPHER_DAEMON_SOCKET` if set, else `$XDG_RUNTIME_DIR/kartographer/daemon.sock`,
  else `~/.kartographer/daemon.sock`. A Unix socket, not a port, because the daemon runs arbitrary
  task code as the user: file permissions are the access boundary. `--listen-tcp <port>` is
  **off by default** and is reachable by every process on the machine.
- `--idle-timeout <mins>` (default 180; `0` disables) exits an idle daemon so warm JVMs do not
  accumulate. The CLI restarts one next time.
- **Build stamp**: the daemon reports the `BuildStamp` of its classpath; the CLI computes the stamp
  of the distribution it would launch. A mismatch means a rebuilt installation is talking to an old
  daemon, and the CLI restarts it. If you bypass the CLI, kill the daemon yourself:
  `pkill -f io.kartographer.daemon.MainKt`. (This mechanism exists because a version string cannot
  detect it — every dev build is `0.1.0-SNAPSHOT`.)

## Running and inspecting

```bash
karto run <task> [-P prop=value] [--ui plain|tui] [--dry-run]
karto ps [--json] [-a]        # active/recent runs, task states, live outputs   (alias: status)
karto logs [runId-prefix] [--task <name>] [--list]
```

`karto ps --json` is the machine-readable hook for an LLM/automation to discover a run's live
outputs — for example a Flutter debug WebSocket endpoint to attach an MCP server to. Prefer it over
scraping human output.

## Interactive tasks and cancellation

- **Stdin is forwarded** from the CLI to the task over the run stream (`StdinData`), so `flutter run`
  reload/restart/quit keys work. A task that needs interaction must be started through `karto run`,
  not a detached process.
- **Ctrl-C must reach the process tree.** `LocalProcessExecutor.terminateProcessTree` sends `SIGINT`
  (signal 2) to deepest descendants first, then the root, so servers under `go run` run graceful
  shutdown hooks and release ports; after `defaultGracePeriodMillis` (~2.5s) it escalates to
  `SIGKILL`. On Windows it uses `Process.destroy()` for the tree. A JVM shutdown hook force-kills
  tracked children if the daemon JVM exits.
- A historical bug: a coroutine scope waited on blocking children before running process destruction,
  so Ctrl-C left the task alive. The fix installs `job.invokeOnCompletion(onCancelling = true)`.
  Do not reintroduce blocking waits before cleanup, and never close a project's classloader while a
  run is in flight.
- A client disconnect should unwind in-flight runs; the daemon terminates them rather than leaking.

## Privilege escalation

Tasks with `requiresElevation = true` are collected before the run. The daemon sends
`PromptRequested { elevatedTasks }`; the CLI/TUI shows a modal and asks for the sudo password; the
answer is returned over the stream. If not authorized, the run fails fast.

For local agents, privileged commands must go through the operator-approved `llm-sudo` workflow, not
a raw `sudo`. A reviewer must treat a privileged/interactive precondition as an **unactionable
blocker**: escalate to the Architect instead of looping `request_changes` on something the
implementer cannot fix. (This rule comes from a real review loop in this repo.)

## Task logs

- Every run writes one gzip-compressed file per run under `<projectDir>/.kartographer/logs/runs/`,
  named with a sortable UTC timestamp and run id. Format:
  `ts run=<id> task=<name> inv=<id> stream=<out|err|meta> <text>`; `stream=meta` carries `start` and
  `end status=<SUCCESS|UP_TO_DATE|FAILED> exit=<code|-> duration_ms=<n>`.
- Config via `KARTO_LOG_*` env vars (`BACKEND`, `MAX_RUNS`, `MAX_AGE_DAYS`, `MAX_TOTAL_MB`,
  `COMPRESS`). Rotation runs at run start (age, then count, then total size; current run never
  pruned). Read with `karto logs`.
- Native sinks: journald on Linux and the Windows Event Log, identical message text. **journald
  structured fields must be separated by `\n`, not NUL** — `sd_journal_sendv` is the datagram
  protocol; JDK 21 only speaks stream unix sockets, which is why JNA is used. A NUL-separated field
  bug was caught only by a live smoke test.
- The file store is written for every backend except `off`, because `karto logs` reads it. An
  explicitly requested unavailable backend logs one warning and falls back to file.

## Debug order for a stuck/failed run

1. `karto ps -a` and `karto logs --list`, then `karto logs <id> --task <name>`.
2. Check whether the daemon is stale (rebuild + restart) — see the repo-guide skill.
3. Reproduce with `--ui plain` to rule out the TUI renderer.
4. For hangs, confirm the task is not a `service` that never reports ready; check readiness config.
5. For leftover processes, verify SIGINT escalation is intact in `LocalProcessExecutor`.
