# Threat model — initial core

## Scope and trust boundaries
A trusted Java application supplies string records, immutable field policies and, where required, a random secret key. The core performs no network calls, autonomous discovery, persistence or logging. Untrusted plugins, database credentials, SQL generation, streaming and access control are not implemented.

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

## Before release
Review dependency and build supply chain, threat model and secure defaults; add negative cases and independent consumer tests; verify release provenance, key lifecycle documentation and legal/license obligations. No production safety certification.
