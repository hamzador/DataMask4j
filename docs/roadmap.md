# Roadmap

## M1 — Core, in progress
- Strict immutable string-map policy, explicit transformations, scoped HMAC with versioned tokens.
- Known-answer test, concurrency and negative tests; require successful CI before sign-off.
- Improve API documentation and coverage; add reviewed resource limits and structured error taxonomy where useful.

## M2 — Structured data
- Streaming CSV/JSON; explicit schema classification; safe YAML policy parser, no unsafe object deserialisation.
- Test malformed input, limits, encoding, duplicate keys, parser vulnerabilities and partial-output handling.

## M3 — JDBC
- Separate source and target connections, source read-only credentials, no updates to source.
- Explicit allowed destinations, dry run, fail closed on unmatched columns, PK/FK/unique/null semantics.
- PostgreSQL first; then evaluate MySQL and other engines. Validate actual database permissions, migrations and rollback/isolation with Testcontainers.

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
