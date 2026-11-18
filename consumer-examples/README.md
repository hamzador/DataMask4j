# Independent consumer smoke tests

These two sample projects intentionally have no DataMask4j parent and are not modules of its reactor. They consume artifacts installed in a local Maven repository. Explicit lifecycle plugin pins mirror reviewed build tooling; they are not application dependencies.

Run from the repository root with Java 21 and Maven 3.9+:

```sh
mvn -B -ntp clean install
mvn -B -ntp -f consumer-examples/core/pom.xml clean verify
mvn -B -ntp -f consumer-examples/spring-boot/pom.xml clean verify
```

The core consumer verifies explicit masking and absence of optional Spring/Jackson/PostgreSQL/crypto classes. The starter consumer starts a real non-web Spring Boot application, verifies auto-configuration discovery from the installed JAR, opt-in activation and external synthetic key use. All data is synthetic, no network services are started. No deployment is performed. These tests are limited to the published API shape of the development snapshot and the initial Spring Boot baseline; they do not certify arbitrary applications or other versions.
