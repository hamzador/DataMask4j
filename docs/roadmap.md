# Roadmap

## M1 — Core, initial CI passed
- Strict immutable string-map policy, explicit transformations, scoped HMAC with versioned tokens.
- Known-answer test, concurrency and negative tests; require successful CI before sign-off.
- Improve API documentation and coverage; add reviewed resource limits and structured error taxonomy where useful.

## M2 — Structured data, implementation in progress
- Implemented in snapshot: bounded streaming CSV/flat JSON, exact schema, restricted YAML policy AST. Initial CI passed (31 tests).
- Added malformed input, limits, duplicate keys, policy rejection and formula prefix tests. Remaining: atomic file publication, parser advisory scanning, more encoding and fuzz tests, XML.

## M3 — JDBC, read-only preview in progress
- First slice: PostgreSQL-only counts report, SELECT-only role check, read-only transaction, schema and type validation, bounded rows/values; no target writes. Unit CI passed (40 reactor tests).
- Separate source and target connections, source read-only credentials, no updates to source.
- Explicit allowed destinations, dry run, fail closed on unmatched columns, PK/FK/unique/null semantics.
- PostgreSQL first; then evaluate MySQL and other engines. Added a mandatory CI Testcontainers suite for actual PostgreSQL privileges, read-only transactions, source invariance and rejection paths. Integration CI passed (40 unit + 10 real PostgreSQL tests). Added experimental single-table INSERT-only export with strict target checks and rollback; CI passed (57 total, 17 PostgreSQL tests). Added ordered multi-table export in one transaction with restricted target FK checks, global row budget and repeatable-read snapshot. New CI pending. Automatic planning remains.

## M4 — Java and Spring
- Java records/annotations, immutable copies, explicit nested transformations; Spring Boot starter and Spring Batch adapters.
- Independent consumers, version matrix and no framework dependency in core.

## M5 — Operational hardening
- Safe audit counts, optional synthetic-data generation, key scoping/rotation, performance and resource limits.
- SBOM, dependency/secret scanning, SpotBugs, mutation testing and misuse review; no raw data in reports.

## M6 — First public release
- Confirm ownership of requested Maven namespace io.github.hador.
- Verify docs, license, signatures and reproducibility evidence before owner-authorised publication.
- No tag, upload or deployment automatically.
