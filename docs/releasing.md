# Releasing Kartographer

This page documents how releases are built and published, and how to exercise the release
pipeline safely before touching the public release line.

- [Artifacts](#artifacts)
- [Release procedure](#release-procedure)
- [Manual release (`workflow_dispatch`)](#manual-release-workflow_dispatch)
- [Private test repo: `kartographer-sandbox`](#private-test-repo-kartographer-sandbox)
- [Local installer tooling](#local-installer-tooling)
- [Troubleshooting](#troubleshooting)

## Artifacts

A release run produces, via `.github/workflows/release.yml`:

| Artifact | Job | Built by |
|---|---|---|
| `kartographer-<version>.zip` | linux | `:dist:distZip` |
| `kartographer-<version>.tar.gz` | linux | `:dist:distTar` |
| `kartographer_<version>_amd64.deb` | linux | `:dist:packageDeb` |
| `kartographer-<version>-1.x86_64.rpm` | linux | `:dist:packageRpm` |
| `kartographer-<version>.msi` | windows | `:dist:packageMsi` |

The `.deb`, `.rpm` and `.msi` are self-contained (jpackage bundles a trimmed Java runtime). The
`.zip`/`.tar.gz` archives require Java 21 on the target machine. See the README
[Installation](../README.md#installation) section for end-user install commands.

## Release procedure

The version is taken from the tag, so the tag **is** the version. For a tag `vX.Y.Z`,
`release.yml` builds with `-Pversion=X.Y.Z`.

1. Make sure `master` is green in CI (ubuntu + windows) and carries the release workflow
   (`.github/workflows/release.yml`), the `dist` module, and the install README.
2. Create and push an annotated tag on the commit to release:

   ```bash
   git checkout master
   git pull --ff-only
   git tag -a vX.Y.Z -m "vX.Y.Z"
   git push origin vX.Y.Z
   ```

3. Pushing the `v*` tag triggers `release.yml`:
   - the `linux` job builds the zip, tar.gz and deb on `ubuntu-latest`;
   - the `windows` job builds the msi on `windows-latest`;
   - the `release` job downloads both artifact sets and runs
     `gh release create vX.Y.Z artifacts/* --generate-notes` against the tag.
4. Confirm the GitHub Release exists with all four assets attached. `gh release view vX.Y.Z`
   lists them.

No repository secrets are required. The workflow uses the default `github.token` with
`permissions: contents: write`.

## Manual release (`workflow_dispatch`)

`release.yml` also declares:

```yaml
on:
  workflow_dispatch: {}
```

Use it to **re-run** a release for a tag that already exists (for example after a transient
network or runner failure) without deleting and re-pushing the tag.

- Open **Actions → Release → Run workflow** and choose the `v*` **tag** as the ref to run
  against (the ref dropdown accepts tags). A branch dispatch fails fast: both build jobs run a
  `Require a tag ref for manual runs` guard that errors unless `GITHUB_REF` is `refs/tags/v*`.
- The version is resolved the same way as a tag push — from `github.ref_name` (`vX.Y.Z` →
  `X.Y.Z`), so a manual run reproduces exactly what the tag push built.
- The final step is idempotent: if the release already exists it runs
  `gh release upload ... --clobber`, otherwise it creates the release. Re-dispatching therefore
  refreshes the assets instead of failing.

Because there are no dispatch inputs, the selected **tag ref is the whole version contract** —
dispatch against the tag you want, not `master`.

## Private test repo: `kartographer-sandbox`

`kartographer-sandbox` is a private mirror used to exercise `ci.yml` and `release.yml`
end-to-end without creating a public tag or release.

- Repository: `BrunoSilvaFreire/kartographer-sandbox` (private).
- Remote (already configured in this checkout):

  ```bash
  git remote add sandbox git@github.com:BrunoSilvaFreire/kartographer-sandbox.git   # once
  git remote -v                                                                       # sandbox is listed
  ```

- Purpose: validate the release pipeline. The `v0.1.0-rc1` tag was pushed here and the release
  workflow completed, proving the linux/windows/release jobs on the sandbox.

### Test-tag flow

Push a release-candidate tag to the sandbox remote. The tag must point at a commit whose tree
contains `.github/workflows/release.yml` (i.e. a release-ready commit):

```bash
git tag -a vX.Y.Z-rcN -m "vX.Y.Z-rcN"
git push sandbox vX.Y.Z-rcN
```

Then watch the sandbox run (the repository is private, so use the sandbox repo explicitly):

```bash
gh run list --repo BrunoSilvaFreire/kartographer-sandbox
gh run watch <run-id> --repo BrunoSilvaFreire/kartographer-sandbox
```

A green run here proves the zip/tar.gz/deb/msi build and the `gh release create` step before a
public `vX.Y.Z` is cut. Candidate tags use a `-rcN` suffix, which jpackage strips for the
numeric `--app-version` (`v0.1.0-rc2` → app version `0.1.0`).

> Do **not** create `kartographer-ci-test`; the sandbox already serves this purpose.

## Local installer tooling

Running the packaging tasks on a workstation needs the same tools the CI runners use:

| Task | Tool | Notes |
|---|---|---|
| `:dist:distZip`, `:dist:distTar` | JDK 21 | No extra tooling. |
| `:dist:packageDeb` | `jpackage` + `dpkg`/`fakeroot` | `jpackage` ships with JDK 21. Debian/Ubuntu. |
| `:dist:packageRpm` | `jpackage` + `rpm-build` | Fedora/RHEL; `rpmbuild` provides the RPM tooling. |
| `:dist:packageMsi` | `jpackage` + WiX Toolset | Windows only; jpackage drives WiX. |

The package tasks are host-gated in `dist/build.gradle.kts`
(`onlyIf { !isWindowsHost }` for deb/rpm, `onlyIf { isWindowsHost }` for msi), so calling the
wrong one on a host is skipped rather than failing. A local dry run:

```bash
./gradlew :dist:distZip :dist:distTar :dist:packageDeb :dist:packageRpm -Pversion=0.1.0
ls dist/build/distributions/
```

On Debian/Ubuntu, install the RPM tooling first: `sudo apt-get install -y rpm`. On Fedora/RHEL,
install `rpm-build` and `dpkg` (for the deb task) if you want to build both on one host.

## Troubleshooting

- **Release job fails because the release already exists** — that is the pre-`workflow_dispatch`
  behavior; re-dispatch now uses `gh release upload --clobber`.
- **Manual dispatch fails the tag-ref guard** — you picked a branch. Re-run against the `v*` tag.
- **`gh release create` can't find the tag** — the tag ref must exist on the remote before the
  workflow runs; push it first (`git push origin vX.Y.Z`).
