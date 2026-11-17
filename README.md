# DataMask4j

**Privacy-first data masking for Java — build safer test datasets without sending sensitive records to a third-party service.**

[![Java 21+](https://img.shields.io/badge/Java-21%2B-blue)](https://openjdk.org/projects/jdk/21/)
[![Build and test](https://github.com/hamzador/DataMask4j/actions/workflows/ci.yml/badge.svg)](https://github.com/hamzador/DataMask4j/actions/workflows/ci.yml)

> Early development (0.1.0-SNAPSHOT). The first milestone is a small, deterministic, in-process masking engine. Nothing has been published to Maven Central. Database, file, Spring and automated anonymization capabilities described in the roadmap are not available yet.

## Why DataMask4j?

Development, QA, staging, demonstrations and incident reproduction often need realistic data. Copying production datasets directly into these environments can expose personal information, trade secrets and financial data. Ad-hoc replacements may break equality relationships, silently leave sensitive columns untouched or generate identifiers that are unsafe to use.

DataMask4j aims to make transformations explicit, repeatable, testable and auditable while leaving data ownership and access decisions with the application.

Typical use cases:

- Create masked customer fixtures for a Java integration test.
- Replace direct identifiers with stable, scoped pseudonyms for a controlled test dataset.
- Remove fields that are unnecessary for testing, rather than copying everything.
- Apply the same reviewed field policy in multiple applications.
- Eventually export a consistent, masked relational subset into an isolated test environment.

### European privacy context

The project is motivated by data minimisation and privacy by design, particularly GDPR Articles 5, 25 and 32. Pseudonymisation is defined in Article 4(5); pseudonymised personal data can still be personal data. Masking names or identifiers alone does not establish anonymisation. Indirect identifiers, correlations, small groups and external information may enable reidentification.

DataMask4j is a technical building block, **not a GDPR certification, legal opinion or guarantee of anonymity**. Organisations remain responsible for lawful processing, access controls, retention, risk analysis, data subject rights and any necessary DPIA.

References: [GDPR official text](https://eur-lex.europa.eu/eli/reg/2016/679/oj), [CNIL anonymisation guidance](https://www.cnil.fr/fr/lanonymisation-de-donnees-personnelles).

## Design principles

- Local execution: no telemetry, remote masking service or network access in the core.
- Explicit policy: all input fields must have a rule; unknown and missing fields fail closed.
- Minimise first: drop a field when it is unnecessary; keep a field only after intentional classification.
- Determinism when needed: keyed HMAC-SHA-256 with explicit scopes and versioned tokens.
- No mutation of the input map. Returned maps are immutable and may contain null.
- No sensitive values in library-generated errors, no implicit logging, no embedded secret.
- Small, dependency-free runtime; Java 21 baseline.
- No claim that cryptographic transformation alone preserves uniqueness, legal anonymity or referential integrity.

## Current capabilities

| Feature | Status |
| --- | --- |
| String-valued records represented as maps | Initial milestone |
| Explicit allowlist policy and schema checking | Initial milestone |
| Keep, drop, constant replacement, redaction | Initial milestone |
| HMAC-SHA-256 pseudonymisation, domain separation | Initial milestone |
| Immutable output, null preservation | Initial milestone |
| CSV/JSON/XML adapters | Planned |
| Java bean/record annotations | Planned |
| YAML policy loading | Planned |
| JDBC export and relational consistency | Planned |
| Spring Boot / Spring Batch integrations | Planned |
| Dry-run, safe reports, key rotation and faker adapters | Planned |

Detailed roadmap below. Only use features that exist in the current source tree.

## Requirements and build

- JDK 21 or newer
- Maven 3.9+
- Git

```bash
git clone https://github.com/hamzador/DataMask4j.git
cd DataMask4j
mvn -B -ntp clean verify
mvn -B -ntp clean install
```

The second command installs development artifacts into your local Maven repository only. No remote publishing is configured.

### Maven coordinates (local development only)

```xml
<dependency>
    <groupId>io.github.hamzador</groupId>
    <artifactId>datamask4j-core</artifactId>
    <version>0.1.0-SNAPSHOT</version>
</dependency>
<dependency>
    <groupId>io.github.hamzador</groupId>
    <artifactId>datamask4j-crypto</artifactId>
    <version>0.1.0-SNAPSHOT</version>
</dependency>
```

The crypto module depends on core. Use only core if you do not need HMAC. These coordinates resolve locally after installation; there is no public release yet.

## Quick start

```java
import io.github.hamzador.datamask4j.core.*;
import java.util.Map;

var policy = MaskingPolicy.builder()
    .field("customerId", FieldRule.keep())
    .field("name", FieldRule.redact())
    .field("email", FieldRule.constant("masked@example.test"))
    .field("phone", FieldRule.drop())
    .build();

var input = Map.of(
    "customerId", "C-1001",
    "name", "Example Customer",
    "email", "private@example.test",
    "phone", "0000000000"
);

Map<String, String> masked = new DataMasker(policy).mask(input);
```

Expected output (ordering is not part of the API):

```json
{
  "customerId": "C-1001",
  "name": "[REDACTED]",
  "email": "masked@example.test"
}
```

The phone field is absent, the source map is unchanged, and the ID is deliberately retained only for illustration. A real customer ID may itself identify someone: classify it and prefer a scoped pseudonym where appropriate. A constant email is not unique and is unsuitable for a unique column.

## Stable pseudonymisation and scoped identity

Use a randomly generated key of at least 32 bytes, supplied by an approved secret manager. Never commit the key, log it or send it with the output dataset. The following example reads a Base64-encoded key from the environment; environment variables are a local example, not a full key management solution.

```java
import io.github.hamzador.datamask4j.core.*;
import io.github.hamzador.datamask4j.crypto.HmacPseudonymizer;
import java.util.Arrays;
import java.util.Base64;
import java.util.Map;
import java.util.Objects;

byte[] key = Base64.getDecoder().decode(
    Objects.requireNonNull(System.getenv("DATAMASK_KEY_BASE64"))
);
try {
    var identity = new HmacPseudonymizer(key, "customer-id:v1");
    var email = new HmacPseudonymizer(key, "customer-email:v1");
    var customers = new DataMasker(MaskingPolicy.builder()
        .field("id", FieldRule.transform(identity))
        .field("email", FieldRule.transform(email))
        .build());
    var orders = new DataMasker(MaskingPolicy.builder()
        .field("customerId", FieldRule.transform(identity))
        .field("orderId", FieldRule.keep())
        .build());

    var customer = customers.mask(Map.of("id", "C-42", "email", "demo@example.test"));
    var order = orders.mask(Map.of("customerId", "C-42", "orderId", "O-1"));
    assert customer.get("id").equals(order.get("customerId"));
} finally {
    Arrays.fill(key, (byte) 0);
}
```

Generate a key with an approved cryptographically secure generator. For local Unix development only: `openssl rand -base64 32` (avoid shell history and insecure storage; do not reuse a test key in production).

Properties of the default token:

- HMAC-SHA-256 over a fixed protocol label and length-prefixed UTF-8 scope and input.
- Output: `dm1_` followed by 43 unpadded Base64URL characters (47 characters total).
- Exact input bytes matter: there is no trimming, case folding, Unicode normalisation or type conversion. Malformed UTF-16 is rejected rather than silently replaced.
- Same key, scope and value produce the same result. Changing the key or scope intentionally breaks linkage.
- Identical source IDs and the same scope can preserve equality across separately transformed records; this is not yet a relational integrity engine. Check target lengths, collation, uniqueness, foreign keys and canonicalisation.
- Stable tokens can reveal frequency and equality. A compromised key or accessible tokenisation oracle may enable guessing, especially for low-entropy identifiers. Isolate keys and scopes by purpose, tenant and environment.
- HMAC is not encryption; there is no decryption API. Key rotation requires deliberate migration or regeneration.
- The caller can clear its input key bytes; Java/JCA may retain internal copies, so this is not a guarantee of memory erasure.

### Nulls, empty strings and strict input policies

```java
import java.util.HashMap;
import java.util.Map;
import io.github.hamzador.datamask4j.core.*;

var masker = new DataMasker(MaskingPolicy.builder()
    .field("email", FieldRule.redact())
    .field("internalNotes", FieldRule.drop())
    .build());

Map<String, String> nullable = new HashMap<>();
nullable.put("email", null);
nullable.put("internalNotes", "not needed");
var result = masker.mask(nullable); // email is present with null; notes absent

// Unknown or missing fields are errors, even if their values are null.
masker.mask(Map.of("email", "demo@example.test")); // throws MaskingException
```

Null is preserved for non-drop rules; empty strings are real values. Duplicate rules, blank field names and null arguments are rejected. No wildcard or implicit KEEP behaviour exists. A policy containing only DROP rules may return an empty map.

### Custom strategies

```java
var policy = MaskingPolicy.builder()
    .field("country", FieldRule.keep())
    .field("postalCode", FieldRule.transform(value ->
        value.length() >= 2 ? value.substring(0, 2) + "***" : "[REDACTED]"))
    .build();
```

Custom strategies must be trusted, deterministic if required, thread-safe for concurrent use, and must never log raw inputs. The engine rejects null transformation results for non-null inputs and wraps ordinary strategy failures without exposing causes or values. It cannot prevent custom code from logging, accessing external services or retaining secrets. Generalisation alone does not establish anonymity.

## Architecture

```text
DataMask4j
├── datamask4j-core       # policy, field rules, masking engine
├── datamask4j-crypto     # scoped HMAC pseudonymisation
├── docs/                # threat model and roadmap
└── .github/workflows/   # build and tests
```

Core does not depend on crypto, Spring or database drivers. Optional modules will depend inward on the core contracts rather than introduce framework dependencies into every consumer.

## Security and operating limits

The first implementation is an in-process trusted API, not a sandbox. It handles string maps only and does not discover personal data automatically. Inputs must already be authorised for processing. Do not use unreviewed policies on production exports.

- Run in an isolated environment with least privilege. Never give test systems access to production databases or keys.
- Avoid logging input/output records, exposing them in error reports, or including them in CI artifacts. Use synthetic test fixtures.
- Treat retained values, stable pseudonyms and sensitive combinations of attributes as potentially personal data.
- The engine throws before returning on invalid schema or failed transformation. This is not a transactional rollback of custom strategy side effects.
- Review allowed output fields and downstream destinations. Masking can break application validation, column lengths or uniqueness.
- File/database exports, tenant isolation, key lifecycle and reidentification tests are separate milestones.

See [threat model](docs/threat-model.md) and [security policy](SECURITY.md).

## Roadmap

| Milestone | Scope | Acceptance gate |
| --- | --- | --- |
| M1 — Core | explicit field policies, HMAC, unit tests | compile, deterministic tests, schema failures |
| M2 — Structured formats | streaming CSV, JSON, YAML policies | no unclassified fields leaked; resource bounds |
| M3 — Relational export | JDBC metadata, separate source/target, FK/PK consistency, dry-run | PostgreSQL integration, read-only credentials, rollback |
| M4 — Java integration | annotations, Spring Boot starter, Spring Batch adapters | standalone consumer tests, compatible versions |
| M5 — Operational features | reports without raw data, scoped key lifecycle, fake data adapters | threat model review, misuse tests, performance |
| M6 — Release | SBOM, dependency and secret scans, signatures, documentation | independently reviewed 0.1.0 release candidate |

Potential future features: safe database subsetting, EU locale-specific synthetic fixtures, detection assistance and risk assessment. No automatic publication or production compliance claim.

## Testing and contributing

Run `mvn -B -ntp clean verify`. Test names use English camelCase and JUnit Jupiter. Prefer small tests of observable behaviour and known cryptographic vectors; use Mockito annotations when mocking is actually needed. Add negative cases for schema mismatches, unsafe input, key/scope boundaries and no accidental disclosure. New dependencies and security-sensitive algorithms require review.

See [roadmap](docs/roadmap.md). Report suspected vulnerabilities privately according to [SECURITY.md](SECURITY.md); never include raw personal records or credentials in GitHub issues.

## License

Apache-2.0 is the intended project license. See [LICENSE](LICENSE). The first release must undergo owner and third-party licensing review.

## Project status

DataMask4j is an independent open-source project by the maintainer of [BatchRecover](https://github.com/hamzador/BatchRecover). It is not affiliated with Spring, the CNIL or an EU institution.
