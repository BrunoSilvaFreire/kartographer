---
name: kartographer-task-best-practices
description: Best practices for authoring Kartographer tasks and pipelines — naming conventions, group organization, how to write great karto.kts pipelines, task-type idioms, and what not to do. Use when creating or reviewing tasks, groups, or a karto.kts, or deciding how to structure a pipeline.
---

# Kartographer Task Best Practices

This skill is the *how*. For the meaning of `Node`, `Group`, `Task`, `Property`, `Provider`,
`OutputSet`, up-to-date checks, services, semaphores, topology, plugins, and script loading, read
`kartographer-concepts`. Do not re-derive a concept here — look it up there.

Sections: [Naming](#naming-conventions) · [Groups](#organizing-with-groups) · [Pipelines](#writing-a-great-pipeline) ·
[Task types](#authoring-a-task-type) · [Do/Don't](#do-and-dont)

---

## Naming conventions

**Groups are namespaces joined by `/`.** `group("a") { task<T>("b") }` registers `a/b`; nested groups
concatenate to `build/compile/correlator`. Refer to a node by its full slash path or by the object
`task<T>(...)` returns.

Rules, verified across every CityScape `karto.kts`:

- **Lowercase `snake_case`** for groups and task leaves: `sync_migrations`, `start_deps`,
  `worker_bootstrap`, `wa_bridge_stdout`. No uppercase, hyphens, or spaces.
- **Structure lives in the group path, never in the leaf name.** Write `local/db/reset/<db>`, not
  `db_reset_local_<db>`.
- **First group segment is the axis you switch on.** Environment matrices put the environment first:
  `dev/containers/...`, `prod/containers/...`. Then the domain: `containers`, `build`, `run`, `db`,
  `deploy`, `ops`, `review`, `lint`, `local`.
- **Group is the verb/noun, leaf is the subject or action.** `build/compile/correlator`,
  `local/db/migrate_up/<db>`, `ops/stage_status`, `containers/run`, `containers/shell`,
  `containers/clean`.
- **`legacy`** holds superseded tasks; **`all`** is an explicit aggregate when a group's default is
  not the whole story.

---

## Organizing with groups

**Fan out one leaf per item.** This is the dominant pattern: the group acts on all items, its leaves
on one each.

```kotlin
group("local") {
    group("db") {
        group("migrate_up") {
            for (db in Config.databases) {
                task<MigrateUpTask>(db) {
                    dbName.set(db)
                    dependsOn("local/db/create/$db")
                }
            }
        }
    }
}
// karto run local/db/migrate_up            -> every database
// karto run local/db/migrate_up/imovelwebapi -> one
```

**Protect dangerous or expensive defaults with `excludeTasksFromGroupDefault`.** A group depends on
all tasks created inside it, so `karto run <group>` triggers them. Remove deliberate steps and leave
them individually addressable:

```kotlin
val export  = task<SampleExportTask>("export") { ... }
val publish = task<SamplePublishTask>("publish") { ... }
excludeTasksFromGroupDefault(export, publish, fetch, restore)  // `karto run local/sample` won't publish
```

Real examples: `build/coverage_integration` excluded so `karto run build` needs no coverage
counters; scrape groups exclude every spider so `karto run scrape` does not hit live sites;
`dev` excludes `run_cover` so `karto run dev` is one server.

**Groups can declare descriptive CLI properties** that member tasks re-declare, so
`karto run <group> --flag=value` is discoverable. The value applies to the members, not the group.

**Duplicate ids are rejected.** `register` errors on an existing id, and `getOrRegisterGroup` errors
if a non-group owns the name. The same leaf under different groups is fine
(`review/candidates` and `review/all/candidates`); the same full path is not.

---

## Writing a great pipeline

- **Wire values, don't pass references.** `dependsOn(producer)` returns the producer's `OutputSet`;
  feed it to a consumer property with `setProvider`, which creates the implicit dependency. Prefer
  this to brittle string `dependsOn("...")` when you have the object.
- **Read external configuration through `Provider`s** (`fromEnvironment`, `fromFile`, `fromCommand`,
  `fromKubernetesSecret`, `fromPulumiConfig`, ...) and compose with `orElse`/`map`. Never hand-roll a
  `ProcessBuilder` provider. The idiomatic credential chain is exported env → `~/.cityscape` file →
  encrypted Pulumi config (see `backend/karto.kts`).
- **Mark secrets and state contribution deliberately.** A secret must carry `secret = true`; a value
  whose change should re-run consumers carries `contributesToState = true`.
- **Record files the script reads** with `projectFile`/`pipelineInputFile` so editing them reloads the
  pipeline. A bare `File("x")` resolves against the daemon's CWD and is invisible to invalidation.
- **Use a loop for matrices, not copy-paste.** `for ((env, namespace) in BUILD_ENVS) { group(env) { ... } }`
  keeps `dev` and `prod` structurally identical.
- **Keep the top level for genuinely global tasks** (`version`, `detect_changes`), everything else in
  a named group.
- **Exclude deliberate side effects** (`publish`, `fetch`, `deploy`) from their group default.

---

## Authoring a task type

Add a bundled type under the matching `tasks/<name>/` module; the daemon already depends on every
`tasks:*` module. A project-local plugin instead needs a `KartographerModule` service registration
and a Gradle build — see the Plugins concept.

- **Pick the narrowest base.** `Task<I, O>` with a real `InputSet`/`OutputSet`; avoid ad-hoc shelling
  where a typed property or file filter belongs. (`DefaultTask` was removed — use a dedicated type.)
- **Three phases:** resolve config and providers in `buildInputs(context)`, construct `O` in
  `buildOutputs()`, do the work in `execute(context, inputSet)` returning `success { }` /
  `failure(e, exitCode) { }`.
- **Declare real state** for up-to-date checks; use `alwaysRun()` only for genuinely non-cacheable
  work (spiders, deploy steps). Compose fine-grained `upToDateWhen*` instead of tracking a huge tree
  (directory hashing reads every byte).
- **Emit outputs by name** in `O`; use `context.emitOutput(output, value)` for values a consumer needs
  before the task finishes. Mark credentials `secret = true`.
- **Command shape:** pass flag and value as separate arguments; never a single `"-b x"` string
  (`BashSpec` single-quotes it). Prefer argument lists over `bash -c` so tasks work under `cmd.exe`.
- **A long-running process is a `service { readiness { ... } }`,** not a normal task that never
  returns.
- **Serialize shared resources with `acquires(...)`** and declare placement with `topology { ... }`.
- **Plugin jars must not bundle `core`,** and must instantiate drivers directly (not via
  `DriverManager`).

---

## Do and Don't

**Do**
- Put structure in the group path; use one leaf per item.
- Return the produced `OutputSet` from a dependency and consume it via a provider.
- Read external values through `Provider`s with `secret`/`contributesToState` set correctly.
- Exclude deliberate/expensive steps from group defaults.
- Use `projectFile`/`pipelineInputFile` for files the script reads.
- Give every concept its correct name in code and comments (`Property`, `Provider`, `Output`, `Group`,
  `local plugin`, `bundled plugin`).

**Don't**
- Don't encode hierarchy into a leaf name (`db_reset_local_x`).
- Don't hand-roll providers around `ProcessBuilder` or call `System.getenv` inside a task.
- Don't pass a flag and value as one string, or assume a shell on Windows.
- Don't let a secret reach a log, state file, or serialized property.
- Don't reuse a full task path, and don't name a group after one of its members.
- Don't put `legacy` tasks into active groups.
- Don't assume `karto run <group>` is harmless — check it does not fan out over a destructive or
  networked operation; use `karto graph` first.
