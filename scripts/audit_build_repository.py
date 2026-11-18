"""Inventory actual Maven executable JAR coordinates in an isolated build repository.

Not an effective dependency graph, a CycloneDX SBOM or an audit of unopened plugins.
Classifier variants are deduplicated; own modules are excluded by the OSV audit.
"""
import argparse
import json
from pathlib import Path


def inventory(repository):
    coordinates = set()
    for jar in repository.rglob("*.jar"):
        parts = jar.relative_to(repository).parts
        if len(parts) < 4:
            raise ValueError("Unexpected Maven JAR location")
        group = ".".join(parts[:-3])
        name, version, filename = parts[-3:]
        stem = name + "-" + version
        if not (filename == stem + ".jar" or filename.startswith(stem + "-")):
            raise ValueError("Unexpected Maven JAR filename: " + filename)
        coordinates.add((group, name, version))
    if not coordinates:
        raise ValueError("No resolved JARs found in Maven repository")
    return {"components": [
        {"group": group, "name": name, "version": version}
        for group, name, version in sorted(coordinates)
    ]}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("repository", type=Path)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    result = inventory(args.repository)
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(result, indent=2) + "\n", encoding="utf-8")
    print(f"Build repository inventory: {len(result['components'])} unique JAR coordinates")


if __name__ == "__main__":
    main()
