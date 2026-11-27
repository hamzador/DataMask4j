"""Assemble validated release artifacts and checksums for offline signing."""
import hashlib
from pathlib import Path
import xml.etree.ElementTree as ET
from zipfile import ZIP_DEFLATED, ZipFile

NS = {"m": "http://maven.apache.org/POM/4.0.0"}
ROOT = Path(__file__).resolve().parents[1]
GROUP = "io.github.hamzador"
VERSION = "0.1.0"


def main():
    parent = ET.parse(ROOT / "pom.xml")
    if (parent.findtext("m:groupId", namespaces=NS),
            parent.findtext("m:version", namespaces=NS)) != (GROUP, VERSION):
        raise ValueError("Unexpected release coordinates")

    parent_id = parent.findtext("m:artifactId", namespaces=NS)
    artifacts = {f"{parent_id}/{VERSION}/{parent_id}-{VERSION}.pom": ROOT / "pom.xml"}
    for module in parent.findall("m:modules/m:module", NS):
        artifact = module.text
        stem = f"{artifact}-{VERSION}"
        prefix = f"{artifact}/{VERSION}/{stem}"
        module_path = ROOT / artifact
        artifacts[prefix + ".pom"] = module_path / "pom.xml"
        for suffix, required_entry in (
            (".jar", ".class"),
            ("-sources.jar", ".java"),
            ("-javadoc.jar", "index.html"),
        ):
            jar_path = module_path / "target" / (stem + suffix)
            with ZipFile(jar_path) as jar:
                if jar.testzip() or not any(n.endswith(required_entry) for n in jar.namelist()):
                    raise ValueError(f"Invalid release JAR: {jar_path}")
            artifacts[prefix + suffix] = jar_path

    if len(artifacts) != 37:
        raise ValueError(f"Expected 37 artifacts, got {len(artifacts)}")

    output = ROOT / "target" / f"datamask4j-{VERSION}-unsigned.zip"
    output.parent.mkdir(exist_ok=True)
    with ZipFile(output, "w", ZIP_DEFLATED) as archive:
        for relative_path, path in sorted(artifacts.items()):
            entry = f"{GROUP.replace('.', '/')}/{relative_path}"
            contents = path.read_bytes()
            archive.writestr(entry, contents)
            for algorithm in ("md5", "sha1", "sha256", "sha512"):
                checksum = hashlib.new(algorithm, contents, usedforsecurity=False).hexdigest()
                archive.writestr(f"{entry}.{algorithm}", checksum)
    print(output)


if __name__ == "__main__":
    main()
