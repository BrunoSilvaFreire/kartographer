# Spike: Add DB sample task type to kartographer plugin for the correlator

**Date:** 2026-07-13
**Session:** Write a new session (not resumed)

---

## 1. Key Architectural Facts (do not rediscover)

### Kartographer Framework

- **Kartographer** is a Kotlin/Gradle task orchestration tool. Plugin ID: `io.kartographer.plugin`. Source: `[build.gradle.kts:2](/home/brunorbsf/MiscProjects/kartographer/pilots/database/karto/build.gradle.kts#L2)`.
- **Task base class**: `Task(name: String)` is located in `[Node.kt:25](/home/brunorbsf/MiscProjects/kartographer/core/src/main/kotlin/io/kartographer/core/model/Node.kt#L25)`. It defines `inputs` (TaskInputs), `outputs` (TaskOutputs), `registeredProperties`, `topologyRequirements`, and the abstract `suspend fun execute(context: ExecutionContext): ExecutionResult`.
- **DefaultTask**: `DefaultTask(name: String)` is in `[Tasks.kt:9](/home/brunorbsf/MiscProjects/kartographer/core/src/main/kotlin/io/kartographer/tasks/Tasks.kt#L9)`. It extends `Task` and exposes action-block lambda DSLs.
- **Property system**: Configurable task properties are implemented via `Property<T>` in `[Properties.kt:10](/home/brunorbsf/MiscProjects/kartographer/core/src/main/kotlin/io/kartographer/core/Properties.kt#L10)`. Property instantiation uses the protected `property<T>()` helper in `[Node.kt:33](/home/brunorbsf/MiscProjects/kartographer/core/src/main/kotlin/io/kartographer/core/model/Node.kt#L33)`, supporting CLI flag binding through `.withCLI("name", "desc")`.
- **Execution system**: Tasks run shell processes via `context.executeBash` or `context.executeSpec` defined in `[Execution.kt:40](/home/brunorbsf/MiscProjects/kartographer/core/src/main/kotlin/io/kartographer/core/model/Execution.kt#L40)`.
- **Registry**: Tasks are registered in `TaskRegistry` in `[Registry.kt:9](/home/brunorbsf/MiscProjects/kartographer/core/src/main/kotlin/io/kartographer/dsl/Registry.kt#L9)` and created via `task<T>("name") { ... }`.
- **Script loading**: Evaluated by `KtsPipelineLoader` in `[KtsPipelineLoader.kt:14](/home/brunorbsf/MiscProjects/kartographer/cli/src/main/kotlin/io/kartographer/cli/KtsPipelineLoader.kt#L14)`.
- **Existing database pilot**: Located in `[karto.kts](/home/brunorbsf/MiscProjects/kartographer/pilots/database/karto.kts)`. It defines tasks under `db/` and `osm/` groups, utilizing task classes from the package `io.kartographer.tasks.database`.
- **Database pilot Config**: Defined in `[Config.kt:3](/home/brunorbsf/MiscProjects/kartographer/pilots/database/karto/src/main/kotlin/io/kartographer/tasks/database/Config.kt#L3)`. Includes local PostgreSQL credentials defaults (127.0.0.1:5432, user `cityscraper`), k3s secret names, and the list of database names (cityscape, geo, vivarealapi, imovelwebapi, chavesnamaoapi, googlemapsapi, osm, correlations).
- **Database pilot Helpers**: Located in `[Helpers.kt:10](/home/brunorbsf/MiscProjects/kartographer/pilots/database/karto/src/main/kotlin/io/kartographer/tasks/database/Helpers.kt#L10)`. Reuses `resolveLocalDbConfig()` to read k3s secrets (falling back to env vars or defaults) and `localDbConnectionString()` to construct JDBC connection URLs.

### Correlator Architecture

- **Correlator repo**: `/home/brunorbsf/CityscapeProjects/correlator/`.
- **Purpose**: ETL pipeline normalizing source listings (ImovelWeb, VivaReal, ChavesNaMao) and correlating them.
- **5 separate Postgres databases**:
  1. `imovelwebapi` (source)
  2. `vivarealapi` (source)
  3. `chavesnamaoapi` (source)
  4. `correlations` (output)
  5. `osm` (reference/geo input)
- **Local dev DB credentials**: Host `127.0.0.1`, user `cityscraper`, password fetched via `[local_db_password.sh](/home/brunorbsf/CityscapeProjects/correlator/scripts/local_db_password.sh)`.
- **Production credentials**: Retrieved using `[prod_db_credentials.py](/home/brunorbsf/CityscapeProjects/correlator/scripts/prod_db_credentials.py)`. RequiresPulumi support.

### Source & Output Schemas & Identifier Mappings

- **imovelwebapi**:
  - `listings` table contains `source_id TEXT UNIQUE`. Source: `[000005_create_listings_schema.up.sql:3](/home/brunorbsf/CityscapeProjects/database/imovelwebapi/migrations/000005_create_listings_schema.up.sql#L3)`.
  - Maps to `correlations.source_candidates.source_record_id` as the raw `listings.source_id` TEXT.
- **vivarealapi**:
  - `listings` table contains `id UUID PRIMARY KEY` and has no `source_id` column. Source: `[000003_create_listings_schema.up.sql:25](/home/brunorbsf/CityscapeProjects/database/vivarealapi/migrations/000003_create_listings_schema.up.sql#L25)`.
  - Maps to `correlations.source_candidates.source_record_id` as the stringified UUID `listings.id::text`.
- **chavesnamaoapi**:
  - `listings` table contains `id UUID PRIMARY KEY` and `source_id BIGINT UNIQUE`. Source: `[000005_create_listings_schema.up.sql:2-3](/home/brunorbsf/CityscapeProjects/database/chavesnamaoapi/migrations/000005_create_listings_schema.up.sql#L2-L3)`.
  - Maps to `correlations.source_candidates.source_record_id` as the stringified UUID `listings.id::text` (not `source_id`).
- **correlations**:
  - Contains `source_candidates` mapping (source, source_record_id). Source: `[000002_create_core_tables.up.sql:1](/home/brunorbsf/CityscapeProjects/database-correlator-rc/correlations/migrations/000002_create_core_tables.up.sql#L1)`.
  - Table differences exist across branches (see Known Traps).

---

## 2. The Problem

Developing and testing the correlator locally requires realistic data. A new task type is needed to copy a self-consistent database sample from production sources to the local PostgreSQL environment, respecting temporal representation, listing limits, and foreign key insertion order.

---

## 3. What Already Exists

- **Task orchestration setup**: The `pilots/database/` subproject runs migrations, drops, resets, and metrics.
- **Credential tools**: Local Kubernetes secret extraction is implemented in Kotlin (`Helpers.resolveLocalDbConfig()`), and production credentials can be resolved by invoking the Python script `/home/brunorbsf/CityscapeProjects/correlator/scripts/prod_db_credentials.py`.
- **Database venv**: The python environment at `/home/brunorbsf/CityscapeProjects/database/.venv/` has `psycopg2-binary` (version 2.9.11) installed, making it suitable for a companion Python sampling script.

---

## 4. User-Stated Requirements and Constraints

1. **New task type**: `DbSampleTask` in package `io.kartographer.tasks.database`, source file at `pilots/database/karto/src/main/kotlin/io/kartographer/tasks/database/DbSampleTask.kt`.
2. **Extends `DatabaseTask`** to inherit local DB credentials and target database configurations.
3. **Instantiated in `pilots/database/karto.kts`** under a new `sample` task group.
4. **Configurable properties**:
   - `--source`: source DB to sample (`imovelweb`, `vivareal`, `chavesnamao`, `all`)
   - `--mode`: selection mode (`random`, `latest`, `distributed`)
   - `--max-listings`: maximum listings to sample (default 500)
   - `--max-snapshots`: maximum snapshots per listing (default 10)
   - `--include-correlations`: whether to copy correlations DB data (default true)
   - `--prod-host` / `--prod-password` / `--prod-user`: production connection overrides
   - `--local-host` / `--local-password`: local connection overrides
   - `--truncate-first`: clean local tables before inserting (default false)
5. **Selection modes**:
   - `random`: uniformly random sample across listings.
   - `latest`: order by last seen (or freshness keyset) descending.
   - `distributed`: divide listings into temporal bins (e.g. weekly/monthly) and sample evenly across them.
6. **Self-consistent copy**: Must copy related rows across dependent tables:
   - Raw sources: listings, snapshots (respecting max-snapshots cap), sales/rentals history, gallery, publishers, locations, listing_statuses, reincidences, and amenities/specifications/proximities.
   - correlations (if enabled): source_candidates, candidate_blocks, edges (only when both sides are in the sample), identities, building entities, candidate zones, zone stats, zone unit stats, and all zone polygons.
7. **Foreign Key Integrity**: Insertion must occur in a dependency-respecting order.
8. **Idempotency**: Supports execution skipping or clean replacing via `--truncate-first`.
9. **Production Read-Only**: Enforce `default_transaction_read_only = on` on all production connection contexts.

---

## 5. Non-goals

- Copying the full production dataset (the output must remain a sample).
- Running database migrations (handled by `db/migrate_up` tasks).
- Sampling the `osm` database (copied in full or handled by `OsmRestoreInternalTask`).
- Writing/updating production database records.
- Executing the correlator ETL pipeline.

---

## 6. Evidence Gathered

- **Production DB Connectivity**: Verified by running a successful test connection to `db.vpn.lunari.studio` with user `cityscraper` yielding `1` (successful authentication).
- **Production Data Size**: The production `imovelwebapi.listings` database contains 835,989 rows (verified via live query).
- **Local DB Sizes**:
  - `imovelwebapi.listings`: 124,937 rows.
  - `vivarealapi.listings`: 122 rows.
  - `chavesnamaoapi.listings`: 252,594 rows.
  - `correlations.source_candidates`: 116,382 rows.
- **Python psycopg2 Availability**: Checked package status in `/home/brunorbsf/CityscapeProjects/database/.venv/`, confirming `psycopg2-binary` (2.9.11) is installed.
- **Daemon Status**: Verified port `50051` is currently idle.

---

## 7. Source Reference Index

- `[Node.kt:25](/home/brunorbsf/MiscProjects/kartographer/core/src/main/kotlin/io/kartographer/core/model/Node.kt#L25)`: `abstract class Task` definition.
- `[Tasks.kt:9](/home/brunorbsf/MiscProjects/kartographer/core/src/main/kotlin/io/kartographer/tasks/Tasks.kt#L9)`: `open class DefaultTask` definition.
- `[Properties.kt:10](/home/brunorbsf/MiscProjects/kartographer/core/src/main/kotlin/io/kartographer/core/Properties.kt#L10)`: `Property` class definition.
- `[Node.kt:33](/home/brunorbsf/MiscProjects/kartographer/core/src/main/kotlin/io/kartographer/core/model/Node.kt#L33)`: `property` helper function.
- `[karto.kts](/home/brunorbsf/MiscProjects/kartographer/pilots/database/karto.kts)`: Database tasks registration script.
- `[Helpers.kt:75](/home/brunorbsf/MiscProjects/kartographer/pilots/database/karto/src/main/kotlin/io/kartographer/tasks/database/Helpers.kt#L75)`: Local database config resolver.
- `[prod_db_credentials.py](/home/brunorbsf/CityscapeProjects/correlator/scripts/prod_db_credentials.py)`: Pulumi production credentials resolver.
- `[000005_create_listings_schema.up.sql](/home/brunorbsf/CityscapeProjects/database/imovelwebapi/migrations/000005_create_listings_schema.up.sql)`: ImovelWeb listings schema.
- `[000003_create_listings_schema.up.sql](/home/brunorbsf/CityscapeProjects/database/vivarealapi/migrations/000003_create_listings_schema.up.sql)`: VivaReal listings schema.
- `[000005_create_listings_schema.up.sql](/home/brunorbsf/CityscapeProjects/database/chavesnamaoapi/migrations/000005_create_listings_schema.up.sql)`: ChavesNaMao listings schema.
- `[000001_correlations_release_schema.up.sql](/home/brunorbsf/CityscapeProjects/database/correlations/migrations/000001_correlations_release_schema.up.sql)`: Correlations consolidated schema on branch `feat/location-listing-domain-split`.
- `[000002_create_core_tables.up.sql](/home/brunorbsf/CityscapeProjects/database-correlator-rc/correlations/migrations/000002_create_core_tables.up.sql)`: Correlations database tables on branch `codex/correlator-rc`.

---

## 8. Commands Run and Observed Outputs

```bash
# Querying production imovelwebapi listings count
PGPASSWORD=W10yCY0oUK2H7CYQpRUDVYNbQYMFkm4b psql -h db.vpn.lunari.studio -p 5432 -U cityscraper -d imovelwebapi -tAc "SELECT count(*) FROM listings"
→ 835989

# Listing local tables in the correlations schema
PGPASSWORD=U6HhipFEhz9zgUdu psql -h 127.0.0.1 -p 5432 -U cityscraper -d correlations -c "SELECT table_name FROM information_schema.tables WHERE table_schema='correlations';"
→ source_candidates, candidate_blocks, candidate_edges, candidate_edge_evidence, identity_entities, identity_links, building_entities, building_listings, zone_polygons, candidate_zones, zone_stats, zone_unit_stats, ...

# Testing prod credentials resolver
/home/brunorbsf/CityscapeProjects/database/.venv/bin/python /home/brunorbsf/CityscapeProjects/correlator/scripts/prod_db_credentials.py host
→ db.vpn.lunari.studio
```

---

## 9. Known Traps and Edge Cases

1. **Identity Table Discrepancy between Branches**:
   - The `database-correlator-rc` repository (currently checked out on branch `codex/correlator-rc`) defines `correlations.identity_links` with columns `identity_id` and `candidate_id` (`[000002_create_core_tables.up.sql:93](/home/brunorbsf/CityscapeProjects/database-correlator-rc/correlations/migrations/000002_create_core_tables.up.sql#L93)`).
   - The `database` repository (checked out on branch `feat/location-listing-domain-split`) defines `correlations.identity_memberships` (`[000001_correlations_release_schema.up.sql:165](/home/brunorbsf/CityscapeProjects/database/correlations/migrations/000001_correlations_release_schema.up.sql#L165)`).
   - *Implication*: A sampling tool must inspect the actual database schema at runtime or handle both formats to prevent table-not-found exceptions when copying identity associations.

2. **Absent correlations Tables in consolidated schema**:
   - On the `feat/location-listing-domain-split` branch in the `database` repo, tables like `candidate_edge_evidence` do not exist in the migrations file `[000001_correlations_release_schema.up.sql](/home/brunorbsf/CityscapeProjects/database/correlations/migrations/000001_correlations_release_schema.up.sql)`.
   - On the `codex/correlator-rc` branch, `candidate_edge_evidence` exists (`[000002_create_core_tables.up.sql:70](/home/brunorbsf/CityscapeProjects/database-correlator-rc/correlations/migrations/000002_create_core_tables.up.sql#L70)`).
   - *Implication*: The sampling tool must dynamically check if `candidate_edge_evidence` exists in the local database schema before attempting to write to it.

3. **ChavesNaMao Identifier Mapping**:
   - In `chavesnamaoapi.listings`, the PK is `id UUID` and the business identifier is `source_id BIGINT`.
   - In `correlations.source_candidates.source_record_id`, listings are mapped via the UUID `listings.id::text` (verified via query mapping `0000034e-91f3-5b36-acc6-20827a2c138f` to PK `id`).
   - *Implication*: Join matching must cast correctly and target `listings.id` for ChavesNaMao, rather than `listings.source_id`.

4. **Foreign Key Integrity for candidate_edges**:
   - `candidate_edges` links `left_candidate_id` and `right_candidate_id` referencing `source_candidates.id`.
   - *Implication*: If only one candidate of a pair is sampled, copying the edge will trigger a foreign key violation. The sampling script must filter edges to only copy those where both candidates exist in the sample set.

5. **Local Migration Tool Version Conflicts**:
   - The database pilot `karto.kts` defines a `rootDirectory` that resolves to `/home/brunorbsf/CityscapeProjects/database`.
   - If the local database was migrated using the `database-correlator-rc` repository (up to version 14), running `db/migrate_up` from `database` (which only contains a consolidated version 1 migration) fails because the `migrate` binary expects version 14 migrations to be present.

6. **CLI Parent Context Resolution Bug**:
   - The `karto` CLI does not pass the parent context's `-d/--project-dir` value down to subcommands like `run` unless `currentContext.parent?.obj` is explicitly queried or Clikt context propagation is configured.
   - *Implication*: The tool must be run from `/home/brunorbsf/MiscProjects/kartographer/pilots/database` to ensure correct relative path resolving for `rootDirectory`.
