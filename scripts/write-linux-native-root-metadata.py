#!/usr/bin/env python3
from __future__ import annotations

import argparse
import json
from pathlib import Path


def main() -> None:
    parser = argparse.ArgumentParser(
        description="Create Linux-x64-only Gradle module metadata roots for local KMP publications."
    )
    parser.add_argument("--repository", type=Path, required=True)
    parser.add_argument("--version", required=True)
    args = parser.parse_args()

    repository = args.repository.expanduser().resolve()
    version = args.version
    platform_files = sorted(
        repository.glob(f"org/jetbrains/**/**-linuxx64/{version}/*-linuxx64-{version}.module")
    )
    created: list[str] = []

    for platform_file in platform_files:
        platform_data = json.loads(platform_file.read_text(encoding="utf-8"))
        component = platform_data.get("component", {})
        root_group = component.get("group")
        root_module = component.get("module")
        root_version = component.get("version")
        if not root_group or not root_module or root_version != version:
            continue

        relative_parts = platform_file.relative_to(repository).parts
        platform_group = ".".join(relative_parts[:-3])
        platform_module = platform_file.parent.parent.name
        variants = []
        for variant in platform_data.get("variants", []):
            attributes = variant.get("attributes", {})
            if attributes.get("org.jetbrains.kotlin.native.target") != "linux_x64":
                continue
            variants.append(
                {
                    "name": variant["name"],
                    "attributes": attributes,
                    "available-at": {
                        "url": (
                            f"../../{platform_module}/{version}/"
                            f"{platform_module}-{version}.module"
                        ),
                        "group": platform_group,
                        "module": platform_module,
                        "version": version,
                    },
                }
            )
        if not variants:
            continue

        root_directory = repository.joinpath(*root_group.split("."), root_module, version)
        root_directory.mkdir(parents=True, exist_ok=True)
        root_metadata = {
            "formatVersion": "1.1",
            "component": {
                "group": root_group,
                "module": root_module,
                "version": version,
                "attributes": {"org.gradle.status": "integration"},
            },
            "createdBy": {"gradle": {"version": "9.5.0"}},
            "variants": variants,
        }
        (root_directory / f"{root_module}-{version}.module").write_text(
            json.dumps(root_metadata, indent=2) + "\n",
            encoding="utf-8",
        )
        (root_directory / f"{root_module}-{version}.pom").write_text(
            f'''<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 https://maven.apache.org/xsd/maven-4.0.0.xsd">
  <!-- do_not_remove: published-with-gradle-metadata -->
  <modelVersion>4.0.0</modelVersion>
  <groupId>{root_group}</groupId>
  <artifactId>{root_module}</artifactId>
  <version>{version}</version>
  <packaging>pom</packaging>
</project>
''',
            encoding="utf-8",
        )
        created.append(f"{root_group}:{root_module}:{version}")

    if not created:
        raise SystemExit(f"No Linux x64 platform publications found in {repository}")
    print(f"Generated {len(created)} Linux-only root metadata publications:")
    for coordinate in created:
        print(f"  {coordinate}")


if __name__ == "__main__":
    main()
