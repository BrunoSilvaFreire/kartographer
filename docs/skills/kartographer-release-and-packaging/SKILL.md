---
name: kartographer-release-and-packaging
description: How Kartographer distributes releases — the merged dist bundle, jpackage installers, versioning, dependency pinning, and the release workflow. Use when changing `dist/`, CI/release workflows, installer packaging, dependency versions in the bundle, or the release procedure/sandbox repo.
---

# Release and Packaging

Verify a green baseline first (`kartographer-testing-and-ci`) and rebuild artifacts correctly
(`kartographer-repo-guide`); module/dist architecture is in `kartographer-concepts`.

## Artifacts

`dist/` produces one **merged bundle** shared by both launchers (CLI + daemon overlap heavily, so a
single `lib/` is smaller than two installs and keeps `kartographer` able to find
`kartographer-daemon` next to itself):

| Artifact | Task | Notes |
|---|---|---|
| `kartographer-<version>.zip` / `.tar.gz` | `:dist:distZip` / `:dist:distTar` | require Java 21 on the target |
| `kartographer_<version>_amd64.deb` | `:dist:packageDeb` | self-contained (jpackage trims a runtime) |
| `kartographer-<version>-1.x86_64.rpm` | `:dist:packageRpm` | needs `rpmbuild` (`rpm` on Debian CI) |
| `kartographer-<version>.msi` | `:dist:packageMsi` | needs WiX on Windows |

## Versioning

The **tag is the version**. `release.yml` extracts `X.Y.Z` from `vX.Y.Z` and builds with
`-Pversion=X.Y.Z`. Default local version is `0.1.0-SNAPSHOT`; jpackage strips the `-SNAPSHOT`
suffix for `--app-version`.

```bash
./gradlew :dist:distZip -Pversion=0.1.0
./gradlew :dist:packageDeb :dist:packageRpm   # Linux, needs jpackage + dpkg/rpmbuild
./gradlew :dist:packageMsi                    # Windows, needs WiX
```

## Dependency pinning is not optional

`dist/build.gradle.kts` force-resolves shared versions because the CLI and daemon resolve
transitively different versions otherwise, leaving a launcher pointing at a jar the shared `lib/`
never contains:

- `kotlinx-coroutines-core(-jvm)` -> `1.10.2`
- `net.java.dev.jna:jna` and `jna-platform` -> `5.17.0`

A real failure: unpinned JNA produced `NoClassDefFoundError: com/sun/jna/Platform` in the bundle.
**Any new shared dependency must be pinned here too.**

## Release workflow (`release.yml`)

- Triggers: push of a `v*` tag, or manual `workflow_dispatch`.
- **`workflow_dispatch` must be dispatched against an existing `v*` tag ref**, not a branch —
  the workflow uses `github.ref_name` and a "Require a tag ref" guard fails a branch dispatch.
- Jobs: `linux` (zip/tar.gz/deb/rpm), `windows` (msi), then `release` downloads both and runs
  `gh release create ... --generate-notes` or `gh release upload ... --clobber`. The clobber path
  makes a re-dispatch idempotent.
- `permissions: contents: write`; **no repository secrets** — it uses `github.token`.
- `release` checks out the repo because `--generate-notes` shells out to git.
- Download artifacts to a directory **outside** the `dist` project dir before uploading.

## Procedure and the sandbox

Full steps are in `docs/releasing.md`. Summary: ensure `master` is green on both OSes and carries
the release workflow + `dist` + install README; push an annotated tag; confirm the GitHub Release
lists every asset; then update the install docs. Before touching the public release line, exercise
the pipeline in the private test repo **`kartographer-sandbox`** (it has produced `v0.1.0-rc*`).
No public tag is cut until the sandbox run is green.

## Pitfalls

- A branch `workflow_dispatch` fails the tag-ref guard — dispatch against the tag.
- Forgetting to install `rpm` on the Linux runner breaks `:dist:packageRpm`.
- Adding a shared dependency without a `resolutionStrategy.force` pin silently breaks one launcher.
- Shipping the bundle without nesting it under a top-level `kartographer-<version>/` breaks
  unzip/install expectations.
- The install docs and README must move with a packaging change; Fedora/RHEL install + RPM were
  added alongside the workflow, and the README records the exact `dnf install`/`apt install` lines.
