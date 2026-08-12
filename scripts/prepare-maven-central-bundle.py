#!/usr/bin/env python3
from __future__ import annotations

import argparse
import shutil
import xml.etree.ElementTree as ElementTree
from pathlib import Path


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
