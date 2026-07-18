# Kartographer Project Context

## Current Status
- **Mission**: Exploration of the correlator repository at `/home/brunorbsf/CityscapeProjects/correlator` — **COMPLETE**.
- This was a read-only discovery task with no code changes.

## Summary of Completed Work
Thorough exploration of the correlator repository produced a comprehensive analysis covering:
1. **What correlator is**: CityScape durable listing correlation ETL pipeline (Go, PostgreSQL queues, bounded workers)
2. **Repository structure**: Full directory tree, all 16+ internal packages documented
3. **Kartographer integration**: Used only as build orchestration (`.kartographer/state/` has 7 cached task files for go build/test/tidy + container builds). No karto.kts files or plugin references exist.
4. **Database schema**: ~20 tables across `correlations` schema documented — queues, candidates, edges, identities, buildings, zones, reconciliation, backfill
5. **Key tables detailed**: source_candidates (central JSONB), incremental_listing_queue, candidate_edges, building_entities, identity_entities
6. **Production config**: 5 DB connections via env vars (IMOVELWEB_SOURCE_*, VIVAREAL_SOURCE_*, CHAVESNAMAO_SOURCE_*, CORR_DB_*, OSM_SOURCE_*), Kubernetes secrets, Pulumi, Argo Workflows
7. **Local dev**: k3s-based, no docker-compose, `scripts/local_db_password.sh`, `preflight_local_k3s.sh`, `test_run.sh`
8. **Sampling/seeding**: No seed/sampling infrastructure. Closest: `profile_and_sample.py` (eval samples from DB), `tuning_analysis.py`, `analysis/db.py`
9. **Architecture**: Pipeline: Discovery → Incremental → Block Repair → Identity Repair → Projection Repair → Reconciliation, all fenced by lease generations + SKIP LOCKED

## Pending Tasks
- None. This was a pure exploration task and is complete.

## Key Paths
- Correlator repo: `/home/brunorbsf/CityscapeProjects/correlator`
- Kartographer project: `/home/brunorbsf/MiscProjects/kartographer`
