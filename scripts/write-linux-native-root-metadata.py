#!/usr/bin/env python3
from __future__ import annotations

import argparse
import json
import re
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
OFFICIAL_SKIKO_GROUP = "org.jetbrains.skiko"
SKIKO_MODULE = "skiko"


def upstream_group_for_fork_dependency(group: str, group_prefix: str) -> str | None:
    compose_prefix = f"{group_prefix}.compose."
    androidx_prefix = f"{group_prefix}.androidx."
    if group.startswith(compose_prefix):
        return f"org.jetbrains.compose.{group.removeprefix(compose_prefix)}"
    if group.startswith(androidx_prefix):
        return f"org.jetbrains.androidx.{group.removeprefix(androidx_prefix)}"
    return None


def upstream_version_for_dependency(
    group: str, upstream_versions: dict[str, str]
) -> str | None:
    if group == "org.jetbrains.compose.material3":
        return upstream_versions.get("material3")
    if group.startswith("org.jetbrains.compose."):
        return upstream_versions.get("compose")
    if group == "org.jetbrains.androidx.lifecycle":
        return upstream_versions.get("lifecycle")
    if group == "org.jetbrains.androidx.navigation3":
        return upstream_versions.get("navigation3")
    if group == "org.jetbrains.androidx.navigationevent":
        return upstream_versions.get("navigationevent")
    if group == "org.jetbrains.androidx.savedstate":
        return upstream_versions.get("savedstate")
    return None


def rewrite_module_dependency_groups(
    module_file: Path,
    group_prefix: str,
    publication_version: str,
    upstream_versions: dict[str, str],
    native_skiko_group: str,
    native_skiko_version: str,
) -> int:
    metadata = json.loads(module_file.read_text(encoding="utf-8"))
    rewritten = 0
    for variant in metadata.get("variants", []):
        is_desktop_native = (
            variant.get("attributes", {}).get("org.jetbrains.kotlin.native.target")
            in NATIVE_TARGETS.values()
        )
        for dependency_section in ("dependencies", "dependencyConstraints"):
            for dependency in variant.get(dependency_section, []):
                group = dependency.get("group")
                if not isinstance(group, str):
                    continue
                if (
                    is_desktop_native
                    and group == OFFICIAL_SKIKO_GROUP
                    and dependency.get("module") == SKIKO_MODULE
                ):
                    dependency["group"] = native_skiko_group
                    rewritten += 1
                    version = dependency.get("version")
                    if isinstance(version, dict):
                        for key in ("requires", "strictly", "prefers"):
                            if key in version and version[key] != native_skiko_version:
                                version[key] = native_skiko_version
                                rewritten += 1
                    continue
                upstream_group = upstream_group_for_fork_dependency(
                    group, group_prefix
                )
                was_fork_dependency = upstream_group is not None
                if was_fork_dependency:
                    dependency["group"] = upstream_group
                    rewritten += 1
                else:
                    upstream_group = group
                upstream_version = upstream_version_for_dependency(
                    upstream_group, upstream_versions
                )
                version = dependency.get("version")
                if upstream_version is not None and isinstance(version, dict):
                    for key in ("requires", "strictly", "prefers"):
                        if version.get(key) == publication_version:
                            version[key] = upstream_version
                            rewritten += 1
    if rewritten:
        module_file.write_text(
            json.dumps(metadata, indent=2) + "\n",
            encoding="utf-8",
        )
    return rewritten


def rewrite_pom_dependency_groups(
    pom_file: Path,
    group_prefix: str,
    publication_version: str,
    upstream_versions: dict[str, str],
    native_skiko_group: str,
    native_skiko_version: str,
) -> int:
    pom = pom_file.read_text(encoding="utf-8")
    rewritten = 0
    artifact = pom_file.parent.parent.name
    is_desktop_native = any(
        artifact.endswith(f"-{platform_suffix}")
        for platform_suffix in NATIVE_TARGETS
    )

    def rewrite_dependency(match: re.Match[str]) -> str:
        nonlocal rewritten
        dependency = match.group(0)

        group_match = re.search(r"<groupId>([^<]+)</groupId>", dependency)
        if group_match is None:
            return dependency
        group = group_match.group(1)
        artifact_match = re.search(r"<artifactId>([^<]+)</artifactId>", dependency)
        if (
            is_desktop_native
            and group == OFFICIAL_SKIKO_GROUP
            and artifact_match is not None
            and artifact_match.group(1) == SKIKO_MODULE
        ):
            dependency = dependency.replace(
                f"<groupId>{group}</groupId>",
                f"<groupId>{native_skiko_group}</groupId>",
                1,
            )
            rewritten += 1
            version_pattern = re.compile(r"(<version>)([^<]+)(</version>)")
            dependency, version_rewrites = version_pattern.subn(
                rf"\g<1>{native_skiko_version}\g<3>", dependency, count=1
            )
            rewritten += version_rewrites
            return dependency
        upstream_group = upstream_group_for_fork_dependency(group, group_prefix)
        if upstream_group is not None:
            dependency = dependency.replace(
                f"<groupId>{group}</groupId>",
                f"<groupId>{upstream_group}</groupId>",
                1,
            )
            rewritten += 1
        else:
            upstream_group = group

        upstream_version = upstream_version_for_dependency(
            upstream_group, upstream_versions
        )
        if upstream_version is not None:
            version_pattern = re.compile(
                rf"(<version>)({re.escape(publication_version)})(</version>)"
            )
            dependency, version_rewrites = version_pattern.subn(
                rf"\g<1>{upstream_version}\g<3>", dependency, count=1
            )
            rewritten += version_rewrites
        return dependency

    modified_pom = re.sub(
        r"<dependency>.*?</dependency>",
        rewrite_dependency,
        pom,
        flags=re.DOTALL,
    )
    if rewritten:
        pom_file.write_text(modified_pom, encoding="utf-8")
    return rewritten


def rewrite_repository_dependency_groups(
    repository: Path,
    version: str,
    group_prefix: str,
    upstream_versions: dict[str, str],
    native_skiko_group: str,
    native_skiko_version: str,
) -> tuple[int, int]:
    modules_rewritten = sum(
        rewrite_module_dependency_groups(
            module_file,
            group_prefix,
            version,
            upstream_versions,
            native_skiko_group,
            native_skiko_version,
        )
        for module_file in repository.glob(f"**/{version}/*-{version}.module")
    )
    poms_rewritten = sum(
        rewrite_pom_dependency_groups(
            pom_file,
            group_prefix,
            version,
            upstream_versions,
            native_skiko_group,
            native_skiko_version,
        )
        for pom_file in repository.glob(f"**/{version}/*-{version}.pom")
    )
    return modules_rewritten, poms_rewritten


def is_publishable_root_variant(variant: dict[str, object]) -> bool:
    """Keep shared metadata; platform variants are rebuilt from collected publications."""
    return str(variant.get("name", "")).startswith("metadata")


def remove_unpublished_stub_dependencies(variant: dict[str, object]) -> None:
    dependencies = variant.get("dependencies")
    if not isinstance(dependencies, list):
        return
    variant["dependencies"] = [
        dependency
        for dependency in dependencies
        if not (
            isinstance(dependency, dict)
            and str(dependency.get("group", "")).startswith(
                "compose-multiplatform-core."
            )
            and (
                dependency.get("version") == {"requires": "unspecified"}
                or dependency.get("version") == {"strictly": "unspecified"}
            )
        )
    ]


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
    parser.add_argument("--upstream-compose-version")
    parser.add_argument("--upstream-material3-version")
    parser.add_argument("--upstream-lifecycle-version")
    parser.add_argument("--upstream-navigation3-version")
    parser.add_argument("--upstream-navigationevent-version")
    parser.add_argument("--upstream-savedstate-version")
    parser.add_argument("--native-skiko-group")
    parser.add_argument("--native-skiko-version")
    args = parser.parse_args()

    if bool(args.upstream_repository) != bool(args.upstream_version):
        parser.error("--upstream-repository and --upstream-version must be used together")
    if bool(args.group) != bool(args.module):
        parser.error("--group and --module must be used together")
    if args.group and args.group_prefix:
        parser.error("--group and --group-prefix cannot be used together")
    if bool(args.native_skiko_group) != bool(args.native_skiko_version):
        parser.error(
            "--native-skiko-group and --native-skiko-version must be used together"
        )
    if args.group_prefix and not args.native_skiko_group:
        parser.error(
            "--native-skiko-group and --native-skiko-version are required with "
            "--group-prefix"
        )
    repository = args.repository.expanduser().resolve()
    version = args.version
    upstream_versions = {
        key: value
        for key, value in {
            "compose": args.upstream_compose_version,
            "material3": args.upstream_material3_version,
            "lifecycle": args.upstream_lifecycle_version,
            "navigation3": args.upstream_navigation3_version,
            "navigationevent": args.upstream_navigationevent_version,
            "savedstate": args.upstream_savedstate_version,
        }.items()
        if value
    }
    if args.group_prefix:
        module_dependencies_rewritten, pom_dependencies_rewritten = (
            rewrite_repository_dependency_groups(
                repository,
                version,
                args.group_prefix,
                upstream_versions,
                args.native_skiko_group,
                args.native_skiko_version,
            )
        )
    else:
        module_dependencies_rewritten = 0
        pom_dependencies_rewritten = 0
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
        root_directory = repository.joinpath(*root_group.split("."), root_module, version)
        root_module_file = root_directory / f"{root_module}-{version}.module"
        if root_module_file.is_file():
            local_root_metadata = json.loads(
                root_module_file.read_text(encoding="utf-8")
            )
            variants_by_name.update(
                (variant["name"], variant)
                for variant in local_root_metadata.get("variants", [])
                if is_publishable_root_variant(variant)
            )
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
        for variant in variants_by_name.values():
            remove_unpublished_stub_dependencies(variant)
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

    if args.group_prefix:
        additional_module_dependencies, additional_pom_dependencies = (
            rewrite_repository_dependency_groups(
                repository,
                version,
                args.group_prefix,
                upstream_versions,
                args.native_skiko_group,
                args.native_skiko_version,
            )
        )
        module_dependencies_rewritten += additional_module_dependencies
        pom_dependencies_rewritten += additional_pom_dependencies

    publication_kind = "platform" if args.all_platforms else "desktop-native"
    print(f"Generated {len(created)} {publication_kind} root metadata publications:")
    for coordinate in created:
        print(f"  {coordinate}")
    print(
        "Rewrote "
        f"{module_dependencies_rewritten} module and "
        f"{pom_dependencies_rewritten} POM dependency coordinates for publication"
    )


if __name__ == "__main__":
    main()
