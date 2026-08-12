#!/usr/bin/env python3
from __future__ import annotations

import argparse
import json
from collections import defaultdict
from html import escape
from pathlib import Path
from urllib.error import HTTPError
from urllib.parse import quote
from urllib.request import urlopen

NATIVE_TARGETS = {
    "linuxx64": "linux_x64",
    "linuxarm64": "linux_arm64",
    "mingwx64": "mingw_x64",
}


def main() -> None:
    parser = argparse.ArgumentParser(
        description="Create Gradle module metadata roots for local KMP publications."
    )
    parser.add_argument("--repository", type=Path, required=True)
    parser.add_argument("--version", required=True)
    parser.add_argument(
        "--all-platforms",
        action="store_true",
        help="Include every locally published platform, not only desktop-native targets.",
    )
    parser.add_argument("--group", help="Limit generation to this root group.")
    parser.add_argument(
        "--group-prefix",
        help="Limit generation to root groups equal to or nested under this prefix.",
    )
    parser.add_argument("--module", help="Limit generation to this root module.")
    parser.add_argument("--upstream-repository")
    parser.add_argument("--upstream-version")
    args = parser.parse_args()

    if bool(args.upstream_repository) != bool(args.upstream_version):
        parser.error("--upstream-repository and --upstream-version must be used together")
    if bool(args.group) != bool(args.module):
        parser.error("--group and --module must be used together")
    if args.group and args.group_prefix:
        parser.error("--group and --group-prefix cannot be used together")
    repository = args.repository.expanduser().resolve()
    version = args.version
    roots: dict[tuple[str, str], list[dict[str, object]]] = defaultdict(list)

    if args.all_platforms:
        platform_publications = (
            (platform_file, None)
            for platform_file in sorted(
                repository.glob(f"**/{version}/*-{version}.module")
            )
        )
    else:
        platform_publications = (
            (platform_file, native_target)
            for platform_suffix, native_target in NATIVE_TARGETS.items()
            for platform_file in sorted(
                repository.glob(
                    f"**/*-{platform_suffix}/{version}/"
                    f"*-{platform_suffix}-{version}.module"
                )
            )
        )

    for platform_file, native_target in platform_publications:
        platform_data = json.loads(platform_file.read_text(encoding="utf-8"))
        component = platform_data.get("component", {})
        root_group = component.get("group")
        root_module = component.get("module")
        root_version = component.get("version")
        if not root_group or not root_module or root_version != version:
            continue
        if args.group and (root_group, root_module) != (args.group, args.module):
            continue
        if args.group_prefix and not (
            root_group == args.group_prefix
            or root_group.startswith(f"{args.group_prefix}.")
        ):
            continue

        relative_parts = platform_file.relative_to(repository).parts
        platform_group = ".".join(relative_parts[:-3])
        platform_module = platform_file.parent.parent.name
        if (platform_group, platform_module) == (root_group, root_module):
            continue
        for variant in platform_data.get("variants", []):
            attributes = variant.get("attributes", {})
            if (
                native_target
                and attributes.get("org.jetbrains.kotlin.native.target")
                != native_target
            ):
                continue
            roots[(root_group, root_module)].append(
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

    if not roots:
        publication_kind = "platform" if args.all_platforms else "desktop-native"
        raise SystemExit(f"No {publication_kind} publications found in {repository}")

    created: list[str] = []
    for (root_group, root_module), variants in sorted(roots.items()):
        variants_by_name: dict[str, dict[str, object]] = {}
        if args.upstream_repository:
            encoded_version = quote(args.upstream_version, safe="")
            upstream_url = (
                f"{args.upstream_repository.rstrip('/')}/"
                f"{root_group.replace('.', '/')}/{root_module}/{encoded_version}/"
                f"{root_module}-{encoded_version}.module"
            )
            try:
                with urlopen(upstream_url) as response:
                    upstream_metadata = json.load(response)
                variants_by_name.update(
                    (variant["name"], variant)
                    for variant in upstream_metadata.get("variants", [])
                )
            except HTTPError as error:
                if error.code != 404:
                    raise

        variants_by_name.update((variant["name"], variant) for variant in variants)
        root_directory = repository.joinpath(*root_group.split("."), root_module, version)
        root_directory.mkdir(parents=True, exist_ok=True)
        pom_name = escape(f"{root_group}:{root_module}")
        pom_description = escape(
            f"Compose Native multiplatform metadata for {root_module}"
        )
        root_metadata = {
            "formatVersion": "1.1",
            "component": {
                "group": root_group,
                "module": root_module,
                "version": version,
                "attributes": {"org.gradle.status": "integration"},
            },
            "createdBy": {"gradle": {"version": "9.5.0"}},
            "variants": sorted(
                variants_by_name.values(), key=lambda variant: variant["name"]
            ),
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
  <name>{pom_name}</name>
  <description>{pom_description}</description>
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
</project>
''',
            encoding="utf-8",
        )
        created.append(f"{root_group}:{root_module}:{version}")

    publication_kind = "platform" if args.all_platforms else "desktop-native"
    print(f"Generated {len(created)} {publication_kind} root metadata publications:")
    for coordinate in created:
        print(f"  {coordinate}")


if __name__ == "__main__":
    main()
