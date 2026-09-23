#!/usr/bin/env python3
from __future__ import annotations

import argparse
import shutil
import xml.etree.ElementTree as ElementTree
import zipfile
from pathlib import Path
from xml.sax.saxutils import escape


IGNORED_SUFFIXES = (".asc", ".md5", ".sha1", ".sha256", ".sha512")
POM_NAMESPACE = {"m": "http://maven.apache.org/POM/4.0.0"}


def main() -> None:
    parser = argparse.ArgumentParser(
        description="Collect one Compose Native version into a Central bundle staging tree."
    )
    parser.add_argument("--repository", type=Path, required=True)
    parser.add_argument("--staging", type=Path, required=True)
    parser.add_argument("--version", required=True)
    parser.add_argument("--group-prefix", default="dev.brahmkshatriya")
    args = parser.parse_args()

    repository = args.repository.expanduser().resolve()
    staging = args.staging.expanduser().resolve()
    if not repository.is_dir():
        raise SystemExit(f"Maven repository does not exist: {repository}")
    if staging == repository or repository in staging.parents:
        raise SystemExit("Staging must not be the repository or a child of it")

    group_path = Path(*args.group_prefix.split("."))
    allowed_roots = [repository / group_path / name for name in ("compose", "androidx")]

    if staging.exists():
        shutil.rmtree(staging)
    staging.mkdir(parents=True)

    copied_files = 0
    copied_modules = 0
    for allowed_root in allowed_roots:
        if not allowed_root.is_dir():
            continue
        for version_directory in sorted(allowed_root.glob(f"**/{args.version}")):
            if not version_directory.is_dir():
                continue
            destination = staging / version_directory.relative_to(repository)
            destination.mkdir(parents=True, exist_ok=True)
            module_files = 0
            for source in sorted(version_directory.iterdir()):
                if not source.is_file():
                    continue
                if source.name.startswith("maven-metadata"):
                    continue
                if source.name.endswith(IGNORED_SUFFIXES):
                    continue
                shutil.copy2(source, destination / source.name)
                copied_files += 1
                module_files += 1
            if module_files:
                copied_modules += 1

    if not copied_files:
        raise SystemExit(
            f"No {args.group_prefix} Compose artifacts for {args.version} were found"
        )

    poms = sorted(staging.rglob("*.pom"))
    if not poms:
        raise SystemExit("The staged repository contains no POM files")
    for version_directory in sorted(staging.glob(f"**/{args.version}")):
        if version_directory.is_dir() and not list(version_directory.glob("*.pom")):
            raise SystemExit(f"{version_directory} contains artifacts but no POM")
    for pom in poms:
        ensure_central_metadata(pom)
        ensure_javadoc_artifact(pom)
        validate_pom(pom)
        validate_primary_artifact(pom)

    print(
        f"Staged {copied_files} files from {copied_modules} modules and "
        f"validated {len(poms)} POMs in {staging}"
    )


def validate_pom(pom: Path) -> None:
    root = ElementTree.parse(pom).getroot()
    required_paths = (
        "m:groupId",
        "m:artifactId",
        "m:version",
        "m:name",
        "m:description",
        "m:url",
        "m:licenses/m:license/m:name",
        "m:licenses/m:license/m:url",
        "m:developers/m:developer/m:name",
        "m:scm/m:url",
        "m:scm/m:connection",
    )
    missing = [path for path in required_paths if not element_text(root, path)]
    if missing:
        raise SystemExit(f"{pom} is missing required Central metadata: {missing}")
    for dependency in root.findall(".//m:dependency", POM_NAMESPACE):
        group = element_text(dependency, "m:groupId")
        version = element_text(dependency, "m:version")
        if version == "unspecified" or group.startswith("compose-multiplatform-core."):
            raise SystemExit(f"{pom} contains an unpublishable dependency: {group}:{version}")


def ensure_central_metadata(pom: Path) -> None:
    root = ElementTree.parse(pom).getroot()
    required_paths = (
        "m:name",
        "m:description",
        "m:url",
        "m:licenses/m:license/m:name",
        "m:licenses/m:license/m:url",
        "m:developers/m:developer/m:name",
        "m:scm/m:url",
        "m:scm/m:connection",
    )
    missing = [path for path in required_paths if not element_text(root, path)]
    if not missing:
        return
    if len(missing) != len(required_paths):
        raise SystemExit(f"{pom} contains partial Central metadata: {missing}")

    group = element_text(root, "m:groupId")
    artifact = element_text(root, "m:artifactId")
    if not group or not artifact:
        return

    metadata = f"""  <name>{escape(group)}:{escape(artifact)}</name>
  <description>Compose Native publication for {escape(artifact)}</description>
  <url>https://github.com/brahmkshatriya/compose-native</url>
  <licenses>
    <license>
      <name>The Apache License, Version 2.0</name>
      <url>https://www.apache.org/licenses/LICENSE-2.0.txt</url>
      <distribution>repo</distribution>
    </license>
  </licenses>
  <developers>
    <developer>
      <id>brahmkshatriya</id>
      <name>Shivam Brahmkshatriya</name>
      <url>https://github.com/brahmkshatriya</url>
    </developer>
  </developers>
  <scm>
    <url>https://github.com/brahmkshatriya/compose-native</url>
    <connection>scm:git:https://github.com/brahmkshatriya/compose-native.git</connection>
    <developerConnection>scm:git:ssh://git@github.com/brahmkshatriya/compose-native.git</developerConnection>
  </scm>
"""
    pom_text = pom.read_text(encoding="utf-8")
    for marker in ("  <dependencyManagement>", "  <dependencies>", "</project>"):
        if marker in pom_text:
            pom.write_text(
                pom_text.replace(marker, metadata + marker, 1),
                encoding="utf-8",
            )
            return
    raise SystemExit(f"{pom} has no insertion point for Central metadata")


def ensure_javadoc_artifact(pom: Path) -> None:
    root = ElementTree.parse(pom).getroot()
    packaging = element_text(root, "m:packaging") or "jar"
    if packaging != "jar":
        return
    javadoc = pom.parent / f"{pom.stem}-javadoc.jar"
    if javadoc.is_file():
        return
    with zipfile.ZipFile(javadoc, "w", compression=zipfile.ZIP_DEFLATED) as archive:
        info = zipfile.ZipInfo("META-INF/MANIFEST.MF")
        info.date_time = (1980, 1, 1, 0, 0, 0)
        info.compress_type = zipfile.ZIP_DEFLATED
        archive.writestr(
            info,
            "Manifest-Version: 1.0\n"
            "Created-By: Compose Native Central staging\n\n",
        )


def validate_primary_artifact(pom: Path) -> None:
    root = ElementTree.parse(pom).getroot()
    packaging = element_text(root, "m:packaging") or "jar"
    if packaging == "pom":
        return
    primary_artifact = pom.parent / f"{pom.stem}.{packaging}"
    if not primary_artifact.is_file():
        raise SystemExit(f"{pom} declares a missing primary artifact: {primary_artifact.name}")
    if packaging != "jar":
        return
    for classifier in ("sources", "javadoc"):
        companion = pom.parent / f"{pom.stem}-{classifier}.jar"
        if not companion.is_file():
            raise SystemExit(f"{primary_artifact} requires {companion.name} for Maven Central")


def element_text(root: ElementTree.Element, path: str) -> str:
    element = root.find(path, POM_NAMESPACE)
    return element.text.strip() if element is not None and element.text else ""


if __name__ == "__main__":
    main()
