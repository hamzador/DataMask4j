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
- This assumes trusted provisioning, stable schema/roles and no concurrent privileged DDL. Naming and grants are not proof of non-production; deployment isolation and credentials are required. No cross-database atomic transaction or arbitrary destination features. Ordered multi-table export supports restricted validated immediate target FKs within the plan. Ambiguous commit failures require manual inspection. Custom strategies and database administrators are trusted.
- Integration tests exercise actual masking, nulls, target rejection and rollback; no guarantee of privacy compliance or anonymity.

## Ordered relational export
- One repeatable-read source snapshot and one serializable target transaction for up to 32 unique source and target tables. Global row cap, parent-first order, target references entirely within plan, no cyclic/deferred/cascading FKs or disabled/non-FK triggers. Target session requires origin trigger mode.
- FK validity does not prove semantic linkage, privacy or anonymity. Caller supplies consistent keys, scopes and policies. Concurrent external writes and privileged DDL remain outside the trust model. Any pre-commit failure rolls back all target inserts; commit failure may be ambiguous.

## Synthetic fixture substitutions
- Names use a small fixed vocabulary and may coincide with real people; collisions and distribution leakage are expected. A common seed is required for consistent first/last/full name. Email uses a full keyed digest and reserved test-domain suffix, but outbound messaging must still be blocked.
- No new external runtime dependencies. Scope separation uses reserved faker:v1 prefixes, locale and purpose. Key reuse, tokenisation oracles and remaining indirect identifiers are residual risks. These substitutions do not establish anonymity. No routable phone numbers or real-world identity/financial identifiers are generated.

## Annotation-driven record masking
- Public flat records only; all components explicitly classified before reading any values, with scalar KEEP and String-only transformations. No accessibility override or implicit nested traversal. NULLIFY retains the field shape; it does not remove the field.
- Record accessors and canonical constructors are trusted and may perform side effects or rewrite masked values; use simple reviewed DTOs. Generic failures omit causes; the API cannot undo user code side effects. Keys are defensively copied but JVM memory erasure is not guaranteed. Annotation literals are visible in compiled classes and must not contain secrets.

## Spring Boot integration
- Auto-configuration is disabled by default, creates only an explicit RecordMasker bean and never automatically masks application traffic or starts a job. The application can override the bean; that override owns its security decisions.
- External MaskingKeyProvider supplies a fresh transferred key array; the starter validates length and clears it after defensive copying. Missing required, invalid and ambiguous providers fail startup. No key configuration property, fallback hardcoded key or key-generation service. Provider invocation errors are sanitized; Spring/application initialization and logging remain outside the masking boundary.
- Explicit key-optional mode permits unkeyed masks without a provider; keyed record policies still fail when invoked. JVM copies may remain. No rotation, secret-store client, actuator or native-image support; no automatic database export.

## Spring Batch processor boundary
- Explicit map and annotated record processors only; no automatic source fallback, null item filtering, reader/writer, job or transaction configuration. Generic failures omit custom causes. A fully dropped map emits an empty map.
- Job metadata, listeners, readers and writers may retain or expose raw input independently. Applications must use isolated transactional destinations, safe metadata, reviewed policies and appropriate retry/skip handling. Stable keys and policies are required for deterministic reprocessing. Do not mask previous output again as raw input. Processor tests passed. The new H2 integration fixture exercises actual chunk rollback and JDBC JobRepository restart; CI passed. Earlier committed chunks remain committed; a failed job is not equivalent to rolling back the entire dataset. Arbitrary reader/writer or distributed restart guarantees are not provided.

## CLI file publication
- Explicit UTF-8 local files only; final symlinks rejected; private POSIX 0700 directory and 0600 staging/output, no overwrite via hard-link publication after successful stream closure. Trusted stable parent paths are required; not a sandbox against concurrent privileged filesystem changes.
- Keys are read as exactly 32 raw bytes from owner-only regular files, not command-line values. Temporary key array is cleared; JVM copies remain. Output is count-only; staging cleanup is best effort and a crash can leave private files. No fsync guarantee, remote publishing or database CLI.

## CI assurance boundary
- Parallel quality baseline collects per-module JaCoCo, core/crypto PIT and all-module SpotBugs reports. Initial line/mutation floors are enforced in the new workflow and SpotBugs must emit zero findings. The previous baseline had one CLI null-flow alert and five uncovered core schema mutants; fixes passed all seven CI jobs; Core 100% lines and mutants killed. Eight build inventory advisory matches remain under remediation with explicit plugin overrides and filtered class-realm traces. Validation pending. Build JAR inventories use isolated Maven repositories and OSV report-only inspection: findings remain release blockers and must be reviewed; no claim of full build provenance.
- OSV gates known Maven component advisories on the aggregate SBOM (including test profile); Gitleaks checks reachable history using a checksum-pinned tool; Trivy gates HIGH/CRITICAL findings on the exact local PostgreSQL CI fixture used by integration tests. The initial base image scan found 26 HIGH/CRITICAL findings in gosu's Go standard library. A local-only derivative rebuilds gosu from a locked source commit using checksum-pinned Go 1.27.4, preserving the base OS and original image user; the patched image passed Trivy with zero HIGH/CRITICAL findings and the 23 PostgreSQL integration tests in run 44204487332. The scanner still gates the entire final image; no CVE exclusions. Database freshness and scanner completeness are external, and helper images, Maven plugin classpaths and shaded artifact provenance need further review. No automated deployment or release.

## Before release
Review dependency and build supply chain, threat model and secure defaults; add negative cases and independent consumer tests; verify release provenance, key lifecycle documentation and legal/license obligations. No production safety certification.
