"""Validate and rehearse signing the actual tested SNAPSHOT; never deploy."""
import argparse
import hashlib
import json
from pathlib import Path
import subprocess
import xml.etree.ElementTree as ET
from zipfile import ZipFile, ZIP_DEFLATED

ROOT = Path(__file__).resolve().parents[1]
NS = {"m": "http://maven.apache.org/POM/4.0.0"}


def require(condition, message):
    if not condition:
        raise ValueError(message)


def inventory(root):
    parent = ET.parse(root / "pom.xml").getroot()
    version = parent.findtext("m:version", namespaces=NS)
    group = parent.findtext("m:groupId", namespaces=NS)
    require(group == "io.github.hador" and version == "0.1.0-SNAPSHOT",
            "Rehearsal accepts only the authorised snapshot")
    modules = [node.text for node in parent.findall("m:modules/m:module", NS)]
    require(len(modules) == 9 and len(set(modules)) == 9, "Expected nine modules")
    files = {"datamask4j-parent/pom.xml": root / "pom.xml"}
    license_bytes = (root / "LICENSE").read_bytes()
    for module in modules:
        path = root / module
        pom = ET.parse(path / "pom.xml").getroot()
        require(pom.findtext("m:artifactId", namespaces=NS) == module, "Unexpected module metadata")
        require(pom.findtext("m:parent/m:groupId", namespaces=NS) == group
                and pom.findtext("m:parent/m:version", namespaces=NS) == version,
                "Unexpected parent metadata")
        files[f"{module}/pom.xml"] = path / "pom.xml"
        for suffix, kind in [("", "binary"), ("-sources", "sources"), ("-javadoc", "javadoc")]:
            name = f"{module}-{version}{suffix}.jar"
            artifact = path / "target" / name
            with ZipFile(artifact) as jar:
                names = jar.namelist()
                require(len(names) == len(set(names)) and jar.testzip() is None,
                        f"Invalid archive {name}")
                if kind == "binary":
                    require(any(n.endswith(".class") for n in names), f"No classes in {name}")
                    require(jar.read("META-INF/LICENSE") == license_bytes, f"Missing project license in {name}")
                elif kind == "sources":
                    require(any(n.endswith(".java") for n in names), f"No sources in {name}")
                else:
                    require("index.html" in names, f"No Javadoc index in {name}")
            files[f"{module}/{name}"] = artifact
    # The shaded CLI is checked separately and NOT included in a Central-ready bundle.
    standalone = root / "datamask4j-cli/target" / f"datamask4j-cli-{version}-standalone.jar"
    with ZipFile(standalone) as jar:
        require(jar.testzip() is None, "Corrupt standalone CLI")
        require(b"Main-Class: io.github.hador.datamask4j.cli.DataMaskCli" in
                jar.read("META-INF/MANIFEST.MF"), "Missing CLI main class")
        embedded = sorted(n for n in jar.namelist() if n.startswith("META-INF/maven/") and n.endswith("/pom.properties"))
    files[f"datamask4j-cli/{standalone.name}"] = standalone
    return files, embedded


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--gpg-home", type=Path, required=True)
    parser.add_argument("--fingerprint", required=True)
    args = parser.parse_args()
    files, embedded = inventory(ROOT)
    output = ROOT / "target/datamask4j-SNAPSHOT-DO-NOT-UPLOAD.zip"
    output.parent.mkdir(parents=True, exist_ok=True)
    manifest = {"status": "LOCAL_SNAPSHOT_REHEARSAL_ONLY", "commit": subprocess.check_output(
        ["git", "rev-parse", "HEAD"], cwd=ROOT, text=True).strip(),
        "standalone_embedded_maven_metadata": embedded, "artifacts": []}
    with ZipFile(output, "w", compression=ZIP_DEFLATED) as archive:
        for entry, path in sorted(files.items()):
            data = path.read_bytes()
            signature = args.gpg_home.parent / "artifact.asc"
            signature.unlink(missing_ok=True)
            gpg = ["gpg", "--homedir", str(args.gpg_home), "--batch", "--no-tty"]
            subprocess.run([*gpg, "--pinentry-mode", "loopback", "--passphrase", "",
                            "--local-user", args.fingerprint, "--armor", "--detach-sign",
                            "--output", str(signature), str(path)], check=True)
            subprocess.run([*gpg, "--verify", str(signature), str(path)], check=True,
                           stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
            sha256, sha512 = hashlib.sha256(data).hexdigest(), hashlib.sha512(data).hexdigest()
            archive.writestr(entry, data)
            archive.writestr(entry + ".asc", signature.read_bytes())
            archive.writestr(entry + ".sha256", sha256 + "\n")
            archive.writestr(entry + ".sha512", sha512 + "\n")
            manifest["artifacts"].append({"name": entry, "size": len(data), "sha256": sha256, "sha512": sha512})
        archive.writestr("DO-NOT-UPLOAD.txt", "SNAPSHOT, disposable CI key, non-Central staging layout. Never upload.\n")
        archive.writestr("manifest.json", json.dumps(manifest, indent=2) + "\n")
    with ZipFile(output) as archive:
        require(archive.testzip() is None, "Invalid staging ZIP")
        for artifact in manifest["artifacts"]:
            require(hashlib.sha256(archive.read(artifact["name"])).hexdigest() == artifact["sha256"],
                    "Staged checksum mismatch")
    (ROOT / "target/rehearsal-manifest.json").write_text(json.dumps(manifest, indent=2) + "\n", encoding="utf-8")
    print(f"Local snapshot rehearsal: {len(files)} files, signed and verified; SHA-256/SHA-512 generated")
    print("DO NOT UPLOAD: disposable signing key, snapshot version, non-Central staging layout")


if __name__ == "__main__":
    main()
