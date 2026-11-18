# Maven build dependency remediation

Baseline: workflow run 44205547918 (commit a883d6f). All seven jobs, 114 distinct Java tests and three Python tests passed. The project SBOM scan found no matches among 73 Maven coordinates; isolated build inventories reported eight package/version/advisory matches across four dependencies:

| Build dependency | Observed versions | Advisory | Intended plugin override |
| --- | --- | --- | --- |
| commons-io:commons-io | 2.11.0 | GHSA-78wr-2p64-hpwj | 2.22.0 |
| org.apache.commons:commons-lang3 | 3.12.0, 3.14.0 | GHSA-j288-q9x7-2f5v | 3.18.0 |
| org.codehaus.plexus:plexus-utils | 3.0.24, 3.5.1, 4.0.1, 4.0.2 | GHSA-6fmv-xxpf-w3cw | 3.6.4 or 4.0.3, retaining major-version compatibility |
| org.iq80.snappy:snappy | 0.4 | GHSA-8wh2-6qhj-h7j9 | 0.5 |

The parent plugin management adds explicit build-only overrides for the lifecycle plugins, JaCoCo, Failsafe, Shade and Build Helper, alongside existing PIT, SpotBugs and CycloneDX pins. The next CI will verify compatibility and residual downloads. An isolated repository inventory measures downloaded JAR coordinates, not necessarily executed code; filtered Maven class-realm traces supplement it. No advisories are excluded or reclassified as false positives. Build inventory OSV remains report-only until the full clean run is confirmed; the project SBOM and final image gates remain blocking. Any unresolved findings block release approval.

Only Maven plugin coordinates and new JAR paths are retained from Maven debug traces, not the raw debug output. Do not run the tracing wrapper on a credential-bearing release job. This does not scan the Maven distribution, JVM, all runner tools or all container helpers. See the threat model for scope.

Core coverage and PIT mutation floors are raised to 100%, based on the last verified 56/56 lines and 28/28 mutants. Crypto remains at its previous measured floor: five surviving mutations relate to best-effort memory clearing, which cannot be reliably observed through the public Java API. No mutation exclusions or unsupported claims of memory erasure.

Advisories: https://github.com/advisories/GHSA-78wr-2p64-hpwj, https://github.com/advisories/GHSA-j288-q9x7-2f5v, https://github.com/advisories/GHSA-6fmv-xxpf-w3cw, https://github.com/advisories/GHSA-8wh2-6qhj-h7j9.

## Verified follow-up and blocking gate

Run [44206114290](https://github.com/hamzador/DataMask4j/actions/runs/44206114290) passed all seven jobs, 114 distinct Java tests and five Python tests. Seven of the eight original inventory matches disappeared. The downloaded mutation job provenance shows `org.apache.maven.plugins:maven-enforcer-plugin:3.6.2` loaded `org.codehaus.plexus:plexus-utils:jar:4.0.2`; all other recorded versions in that trace were fixed. Add a direct build-plugin override to 4.0.3. The five isolated Maven inventory scans now fail on any OSV match, with no exclusions. Their clean results and the new independent consumer builds remain pending CI. The Maven distribution, JVM, and Testcontainers helper images are not covered by this inventory.
