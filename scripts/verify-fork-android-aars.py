#!/usr/bin/env python3

import argparse
import io
from pathlib import Path
import zipfile


MODULES = {
    "compose/foundation/foundation-android": "androidx/compose/foundation/",
    "compose/material3/material3-android": "androidx/compose/material3/",
}


def main() -> None:
    parser = argparse.ArgumentParser(
        description="Verify that the published fork Android AARs contain implementation classes."
    )
    parser.add_argument("--repository", required=True, type=Path)
    parser.add_argument("--version", required=True)
    parser.add_argument("--group-prefix", default="dev.brahmkshatriya")
    args = parser.parse_args()

    group_root = args.repository.joinpath(*args.group_prefix.split("."))
    for module_path, class_prefix in MODULES.items():
        publication = group_root / module_path / args.version
        aars = sorted(publication.glob("*.aar"))
        if len(aars) != 1:
            raise SystemExit(
                f"Expected one AAR in {publication}, found {len(aars)}"
            )

        with zipfile.ZipFile(aars[0]) as aar:
            try:
                classes = aar.read("classes.jar")
            except KeyError as error:
                raise SystemExit(f"{aars[0]} has no classes.jar") from error

        with zipfile.ZipFile(io.BytesIO(classes)) as jar:
            if not any(
                name.startswith(class_prefix) and name.endswith(".class")
                for name in jar.namelist()
            ):
                raise SystemExit(
                    f"{aars[0]} contains no implementation classes under {class_prefix}"
                )

        print(f"Verified {aars[0]}")


if __name__ == "__main__":
    main()
