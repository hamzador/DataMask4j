# Local release rehearsal — not for upload

The development version remains `0.1.0-SNAPSHOT`; the Maven group ID is `io.github.hamzador`. Run 44206560027 verified the seven CI jobs, 118 distinct Java tests, five Python tests, two installed-artifact consumers, zero SpotBugs findings and zero OSV matches in the 73-coordinate project SBOM and all five Maven build inventories.

The new `release-preparation` Maven profile attaches source and Javadoc JARs. CI builds, tests and installs that exact snapshot, exercises both consumers and the standalone CLI, then checks the artifact ZIP contents. The local rehearsal creates a disposable one-day RSA signing key, signs and verifies 10 POMs, 27 library JARs and one shaded CLI JAR, generates SHA-256 and SHA-512 digests and records the tested Git commit. The private key is discarded; only the public rehearsal key, checksums and local preview are uploaded as CI evidence. The resulting `datamask4j-SNAPSHOT-DO-NOT-UPLOAD.zip` is deliberately not a Maven Central layout and must never be uploaded to Central. The artifacts are not rebuilt or renamed as release 0.1.0. The standalone classifier is inventoried separately for third-party license and provenance review. The rehearsal checks that the combined Apache Commons notice names CSV, Codec and IO. Jackson Core retains its separate notice and bundled third-party license files.

Maven Central requires non-SNAPSHOT release coordinates, appropriate POM metadata, matching source and Javadoc JARs, signatures and verified namespace ownership. See https://central.sonatype.org/publish/requirements/. Before owner-authorised release, review licenses (particularly shaded dependencies), approve the release version and signing key, rerun the full release build with the final version, validate final metadata and checksums and verify the release signing public key. No publishing extension, tag, credentials or remote deployment is enabled here. The Maven build uses a fixed archive timestamp and disables timestamps in generated Javadoc. To verify reproducibility, build the same revision in two clean directories with separate Maven repositories and the same JDK and Maven versions. Compare SHA-256 hashes for the binary, source, Javadoc and standalone JARs before signing. The Ryuk reaper is now pinned and its running image identity checked in the PostgreSQL fixture, with a separate HIGH/CRITICAL scan on the same runner. These new checks await CI validation.

## Operator checklist before publication

- [x] Confirm ownership of `io.github.hamzador` in Central Portal.
- [ ] Review all final code and security findings and third-party licenses.
- [ ] Confirm GitHub Pages activation and correct documentation URLs if desired.
- [ ] Explicitly authorise release 0.1.0 and the release signing key.
- [ ] Build/test/audit the exact final version, validate POMs, artifacts and signatures.
- [ ] Submit only an approved, properly signed final release bundle, never a rehearsal.
