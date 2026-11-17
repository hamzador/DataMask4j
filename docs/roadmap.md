# Roadmap

## M1 — Core, initial CI passed
- Strict immutable string-map policy, explicit transformations, scoped HMAC with versioned tokens.
- Known-answer test, concurrency and negative tests; require successful CI before sign-off.
- Improve API documentation and coverage; add reviewed resource limits and structured error taxonomy where useful.

## M2 — Structured data, implementation in progress
- Implemented in snapshot: bounded streaming CSV/flat JSON, exact schema, restricted YAML policy AST. Initial CI passed (31 tests).
- Added malformed input, limits, duplicate keys, policy rejection and formula prefix tests. Added standalone CLI with private staging and atomic no-overwrite hard-link publication; CI passed (110 tests and CLI smoke test). Remaining: reusable file publication API, parser advisory scanning, more encoding and fuzz tests, XML.

## M3 — JDBC, read-only preview in progress
- First slice: PostgreSQL-only counts report, SELECT-only role check, read-only transaction, schema and type validation, bounded rows/values; no target writes. Unit CI passed (40 reactor tests).
- Separate source and target connections, source read-only credentials, no updates to source.
- Explicit allowed destinations, dry run, fail closed on unmatched columns, PK/FK/unique/null semantics.
- PostgreSQL first; then evaluate MySQL and other engines. Added a mandatory CI Testcontainers suite for actual PostgreSQL privileges, read-only transactions, source invariance and rejection paths. Integration CI passed (40 unit + 10 real PostgreSQL tests). Added experimental single-table INSERT-only export with strict target checks and rollback; CI passed (57 total, 17 PostgreSQL tests). Added ordered multi-table export in one transaction with restricted target FK checks, global row budget and repeatable-read snapshot. CI passed (63 total, 23 real PostgreSQL tests). Automatic planning remains.

## M4 — Java and Spring
- Added explicit annotation-driven public flat record masking with immutable copies, keyed and unkeyed strategies, and fail-closed validation. CI passed (80 total, 23 PostgreSQL integration tests). Added opt-in Spring Boot 4.0.9 starter with external key provider, startup validation and application bean override. CI passed (91 tests total). Added explicit Spring Batch 6.0.6 map and record processors with sanitized failure and deterministic reprocessing tests. Processor CI passed (99 total tests). Added actual Spring Batch JDBC job tests for chunk commit, rollback and restart with H2, CI passed (101 total tests). Wider restart/partitioning compatibility, nested transformations, Java beans and Spring version matrix remain.
- Independent consumers, version matrix and no framework dependency in core.

## M5 — Synthetic data and operational hardening
- Initial faker module: seven curated European locale variants, deterministic name and reserved test-domain email strategies. CI passed (71 tests total). Richer locale datasets, YAML integration and safe additional formats remain.
- Safe audit counts, optional synthetic-data generation, key scoping/rotation, performance and resource limits.
- SBOM, dependency/secret scanning, SpotBugs, mutation testing and misuse review; no raw data in reports.

## M5b — Grouped quality and documentation
- Prepared five static documentation pages, sitemap, canonical tags and offline validation; Pages activation/indexing not verified.
- One CI workflow now groups seven parallel jobs: full verification with JaCoCo baseline, PIT, SpotBugs, SBOM/OSV, Gitleaks, Trivy PostgreSQL image and documentation checks. New CI pending. Review reported findings, add justified coverage and mutation thresholds and complete build-plugin/helper-image scans before release.

## M6 — First public release
- Confirm ownership of requested Maven namespace io.github.hador.
- Verify docs, license, signatures and reproducibility evidence before owner-authorised publication.
- No tag, upload or deployment automatically.
