# Threat model — initial core

## Scope and trust boundaries
A trusted Java application supplies string records, immutable field policies and, where required, a random secret key. The core performs no network calls, autonomous discovery, persistence or logging. Untrusted plugins and application access control are not supported. JDBC preview generates narrowly scoped SQL using validated identifiers and parameter-bound catalog queries; credentials are externally supplied. Optional file adapters parse bounded CSV/JSON and restricted YAML policy ASTs.

## Security properties and limitations
| Threat | Initial mitigation | Residual risk |
| --- | --- | --- |
| Unknown sensitive field | Reject nonmatching field sets before executing strategies | Incorrect classification or explicit KEEP |
| Accidental log disclosure | Generic transformation failures without raw cause | Caller/custom strategy logging, JVM crash dumps |
| Cross-dataset linkage | HMAC domain label and length-framed explicit scope | Equal values link within a scope; frequency and auxiliary data may identify people |
| Guessing identifiers | Keyed HMAC with >=32 byte key | Low-entropy key, key compromise or accessible oracle |
| Ambiguous encoding | Strict UTF-8; no silent replacement; framed byte strings | Different canonicalisation across sources |
| Key mutation | SecretKeySpec copies caller array; fresh Mac per invocation | Java/JCA key copies remain in memory; no guaranteed zeroisation |
| Data loss or write to production | Engine does not mutate input or write to databases | Future adapters need explicit destination controls and transactional tests |
| Resource exhaustion | No external runtime dependencies | In-memory maps and strings are not bounded; callers limit sizes, concurrency and access |

Use distinct random keys per purpose/environment, retain keys separately, restrict tokenisation access, and classify all attributes including indirect identifiers. Nulls and equality may leak information. Tokens are pseudonyms, not proof of anonymity. No claim of collision-free output or relational constraints. Do not truncate tokens without risk analysis. Use synthetic data in tests.

## File and policy adapters

- Malformed input, duplicate headers/keys, unknown fields, non-string JSON, YAML anchors/aliases/merges/tags and excess limits fail with generic exceptions. The engine does not instantiate arbitrary YAML objects.
- CSV rejects common spreadsheet formula prefixes in emitted fields and headers; not a universal spreadsheet security guarantee.
- Streaming output can contain partial masked records after a failure; callers must discard the entire output and use isolated staging. Caller-supplied streams can access external systems; core does not control destinations.
- Input/output size limits count UTF-16 code units; parser allocations and caller-supplied transformation memory are not fully bounded. Policy AST parsing has independent limits.
- The optional file module introduces external parser dependencies. Review transitive dependencies, advisories and parser misuse cases before release.

## JDBC preview
- The initial module obtains a fresh connection, checks PostgreSQL, sets a read-only transaction, checks database identity and direct table/column write grants, accepts only ordinary non-RLS non-partition tables and returns counts. It issues no DML or DDL.
- Database permissions, network isolation, datasource trust, resource limits and custom strategy safety are caller responsibilities. A database marked read-only is not proof of least privilege everywhere. Avoid superuser and shared production credentials.
- Only text/varchar columns are supported; row/value caps are checked after driver allocation. Counts may themselves be sensitive. The read-only preview does not export records. A separate experimental single-table exporter exists; no automatic relational integrity validation.

The real PostgreSQL integration suite checks SELECT-only and write-capable roles, read-only JDBC session behaviour and unchanged source data. It cannot prove the safety of a malicious datasource, custom strategy, concurrent privileged DDL or untested database configurations. Integration test dependencies and container images require security review.

## Experimental PostgreSQL export
- Distinct configured database names and datasources, dm_test_ target naming convention, explicit expected writer role, no privileged target role or table ownership, strict INSERT/SELECT grants and empty table checks.
- Source is read-only and rolled back/closed before target commit. Target uses SERIALIZABLE, parameter-bound INSERT and explicit rollback. Exact retained columns, ordinary tables and restricted metadata are required.
- This assumes trusted provisioning, stable schema/roles and no concurrent privileged DDL. Naming and grants are not proof of non-production; deployment isolation and credentials are required. No cross-database atomic transaction, multi-table planning, foreign keys or arbitrary destination features. Ambiguous commit failures require manual inspection. Custom strategies and database administrators are trusted.
- Integration tests exercise actual masking, nulls, target rejection and rollback; no guarantee of privacy compliance or anonymity.

## Before release
Review dependency and build supply chain, threat model and secure defaults; add negative cases and independent consumer tests; verify release provenance, key lifecycle documentation and legal/license obligations. No production safety certification.
