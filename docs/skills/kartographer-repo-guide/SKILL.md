---
name: kartographer-repo-guide
description: Orientation and working rules for the Kartographer repository — module map, build/run/verify commands, the stale-artifact diagnosis playbook, and which docs are unreliable. Use when starting any task in Kartographer, building or running `karto`, or when a run fails with stale artifacts or "Unresolved reference" errors.
---

# Kartographer Repository Guide

Kartographer is a topology-aware DAG task orchestrator written in Kotlin. Projects declare tasks in
a `karto.kts` pipeline; the `karto` CLI builds the graph and executes it.
Read this to get oriented and to run the project. For *what things are*, read
`kartographer-concepts`; for *how to write tasks/pipelines*, read
`kartographer-task-best-practices`; for runtime/debugging, read `kartographer-daemon-and-execution`.

## Skill map

| Skill | Use it for |
|---|---|
| `kartographer-concepts` | Definitions and architecture of every first-class concept |
| `kartographer-task-best-practices` | Naming, groups, pipeline and task-type idioms, do/don't |
| `kartographer-repo-guide` | This file: build/run, stale-artifact playbook, unreliable docs |
| `kartographer-daemon-and-execution` | Runtime: daemon, signals, interactive tasks, logs, `karto ps` |
| `kartographer-testing-and-ci` | Tests, integration suites, Windows/CI portability |
| `kartographer-release-and-packaging` | dist bundle, installers, release workflow |

## Module map

Architecture (CLI thin client → daemon engine, shared core) is defined in the **Architecture** section
of `kartographer-concepts`. The directories:

| Path | What lives there |
|---|---|
| `core/` | Model, scheduler, properties/providers, plugins, logs, local protocol |
| `cli/` | Argument parsing, renderers (plain + Mosaic TUI), socket/gRPC client |
| `daemon/` | Script compilation, project cache, run sessions, native log sinks |
| `tasks/*/` | Bundled task types + `KartographerModule` service files |
| `dist/` | Self-contained zip/tar.gz + jpackage deb/rpm/msi bundle |
| `integration-tests/` | Cucumber `.feature` suites |
| `karto.kts` | Kartographer's own pipeline (`gherkin`, `test`, `coverage`) |
| `pilots/`, `coverage/`, `.kartographer/` | **gitignored** — never commit them |

## Build, bootstrap, run, verify

```bash
./gradlew build                       # compile + unit tests (all modules)
./gradlew :core:test                  # a single module
./gradlew :integration-tests:test     # Cucumber suite
./bootstrap.sh                        # installDist both apps + symlink karto/kartographer-daemon
./gradlew :cli:installDist :daemon:installDist   # rebuild without re-symlinking
```

Local pipelines: `karto tasks`, `karto graph`, `karto run <task>`, `karto ps [--json]`,
`karto logs [run] [--task]`. The daemon auto-starts; `-p/--pipeline` selects a `.kts` path or a
compiled class name, `-d/--project-dir` overrides the directory.

## Stale-artifact playbook (do this first)

Most "my change had no effect" reports in this repo's history were stale artifacts, not logic bugs.
Try these in order before reading source:

1. Changed `core/`, a `tasks:*` module, or the daemon? `./gradlew :cli:installDist :daemon:installDist`
   (or `./bootstrap.sh`) and restart the daemon. The daemon owns execution, so engine changes require
   a daemon rebuild. A stale CLI distribution once made a `buildArgs` property look "missing" when the
   logic was already correct.
2. Changed a project-local `karto/` plugin? The daemon recompiles it via fingerprint; confirm the
   output shows `Compiling local plugin:` / `Loading compiled local plugin jar:`. If Gradle was not
   found, it reuses a stale jar and warns that edits will not be picked up.
3. `rm -rf ~/.kartographer/script-cache` (compiled-script jars) and
   `rm ~/.kartographer/plugins/*.jar` (stale global plugins). `KARTOGRAPHER_PLUGINS_DIR` overrides the
   plugin directory.
4. Old daemon from a previous build: the CLI's `BuildStamp` check restarts a stale daemon, but if in
   doubt `pkill -f io.kartographer.daemon.MainKt` and let it respawn.

**"Unresolved reference" naming the pipeline's own symbols almost always means the local plugin
failed to compile or was not on the classpath** — read the *first* error, not the wall. Plugin and
script-loading mechanics are the **Plugins** and **Script loading** sections of
`kartographer-concepts`.

## Do-not-trust list (historical material that goes stale)

- `docs/investigation/*.md` are spike reports from an **older architecture**. Several link to
  `/home/brunorbsf/CityscapeProjects/kartographer` (the repo moved to `MiscProjects/`) and describe
  the CLI loading scripts and executing tasks locally. That is no longer true. Use them for
  background, then verify every claim against current source.
- The README "MVP CLI Usage" section still shows `./gradlew run --args=...` and claims pipelines must
  be compiled classes. Both predate `.kts` support and the daemon split. Prefer `karto ...`.
- A prior agent shipped a fix built on a stale distribution and only found it when the user retried.
  Always state which artifact you rebuilt.

## Repo etiquette

- Never commit `pilots/`, `coverage/`, `.kartographer/`, or `.zed/` — they are gitignored.
- Preserve uncommitted work; check `git status` before assuming a clean tree. In-flight work on a
  feature branch is common.
- Prefer a dedicated task type over ad-hoc shelling; see `kartographer-task-best-practices`.
