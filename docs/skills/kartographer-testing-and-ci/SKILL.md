---
name: kartographer-testing-and-ci
description: Testing and CI conventions for Kartographer, including Cucumber integration tests, Windows portability, and pre-existing failures. Use when adding or running tests, interpreting `./gradlew build` failures, touching POSIX/Windows-sensitive code, or changing the CI workflow.
---

# Testing and CI

Build commands and the stale-artifact diagnosis playbook live in `kartographer-repo-guide`; the
meaning of the units under test (scheduler, providers, plugins) is in `kartographer-concepts`.

## Layers

- **Unit tests** (JUnit 5) live in each module's `src/test/kotlin`. Infrastructure for scripted
  scheduling lives in `core` test fixtures (`io.kartographer.testing.ScriptedTask`), reusable via
  `testFixtures(project(":core"))`.
- **Integration tests** are **Cucumber JVM**, discovered by
  `io.kartographer.integration.CucumberTestRunner` from `.feature` files under
  `integration-tests/src/test/resources/io/kartographer/integration/`. Step definitions are in
  `integration-tests/src/test/kotlin/io/kartographer/integration/`.
- `daemon/` has its own JUnit integration coverage (`DaemonIntegrationTest`, `LocalProtocolServerTest`,
  `ProjectCacheTest`, `KtsPipelineLoaderTest`) and a **10-minute test timeout** so a hung receive
  loop fails the runner instead of eating it.

## Commands

```bash
./gradlew build                       # everything (compile + unit tests)
./gradlew :core:test                  # one module
./gradlew :core:test --tests "io.kartographer.LocalProcessExecutorTest"
./gradlew :integration-tests:test     # Cucumber suite
./gradlew :integration-tests:jacocoTestReport   # coverage HTML
# Kartographer's own pipeline:
karto run gherkin    # or  karto run test   /   karto run coverage
```

## Confirm the baseline before blaming your change

Some suites have failed for reasons unrelated to a change (a `ScriptedTask` resolution failure and a
hanging local-protocol test are the historical examples). Before debugging a broad `./gradlew build`,
run the affected module's tests and compare against the pre-change tree. If a failure reproduces
without your diff, note it as pre-existing rather than "fixing" it out of scope.

## Global state and isolation

- `NodeRegistry`/`TaskRegistry` are process-global. Clear between scenarios; sharing a registry
  across sequential Cucumber scenarios leaks task definitions.
- Cucumber tags are documentation-only unless `cucumber.filter.tags` is configured on the runner.
- Container tests execute real `docker`/`nerdctl`; gate or mock them rather than assuming a runtime.

## Windows portability (a repeated source of CI-only failures)

- CI runs `ubuntu-latest` **and** `windows-latest`. On Windows it builds/compiles plus a bundle
  smoke test and **skips `:daemon:test -x :integration-tests:test`** because those drive POSIX
  shells and signals.
- POSIX-bound unit tests use `assumeFalse(os contains "win")` (see `TaskLogObserverTest`).
- **Assert command pieces, not quoted strings.** A `BashSpec` that joins args will single-quote
  anything containing spaces; POSIX and Windows quoting differ. Tests were rewritten to assert the
  flag and value separately (docker, flutter, dart, kubernetes).
- **Compare paths with forward slashes** (`dart` packageDir) and do not relativize container build
  contexts by hand — ask `git rev-parse --show-prefix` (`fix(containers)`), which was the fix for a
  Windows-only `ContainerTasksTest` failure.
- **Pin shared dependencies in the distribution.** Unpinned JNA produced
  `NoClassDefFoundError: com/sun/jna/Platform`; the bundle force-resolves JNA and coroutines to one
  version so every launcher points at a jar that is actually in `lib/`.
- The daemon must launch/discover portably on Windows; do not hardcode POSIX shell behavior.

## CI shape

`.github/workflows/ci.yml` builds and assembles the dist on both OSes, installs `rpm` on Linux for
`:dist:packageRpm`, verifies the native installer exists, smoke-tests the bundle (launch + `tasks`
against a throwaway `karto.kts`), then stops the daemon. `release.yml` is separate — see the
release/packaging skill.

When you change CI, keep the Windows path compiling and the Linux path exercising the full suite;
do not silently drop a job. A merge to `master` is expected to be green on both.

## Test-writing notes

- Prefer targeted assertions on structured data (command pieces, output schema, event payloads) over
  asserting formatted/rendered text, which churns.
- For filesystem/process tests, use real temporary directories and real shells where the behavior is
  POSIX-specific, and assume-skip otherwise.
- Keep new step definitions grouped by feature area rather than growing one monolithic step file.
