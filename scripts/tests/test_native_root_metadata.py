import importlib.util
import json
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
import zipfile

SCRIPT = Path(__file__).parents[1] / "write-linux-native-root-metadata.py"
SPEC = importlib.util.spec_from_file_location("root_metadata", SCRIPT)
metadata = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(metadata)
GROUP = "dev.brahmkshatriya.compose.desktop"
VERSION = "1.13.0-test"


class NativeRootMetadataTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.directory = Path(self.temporary.name)
        self.repository = self.directory / "repo"
        self.shared = self.directory / "shared"
        self.root = self.repository / GROUP.replace(".", "/") / "desktop-native" / VERSION
        platform = self.root.parent.parent / "desktop-native-linuxx64" / VERSION
        platform.mkdir(parents=True)
        sources = platform / "sources.jar"
        with zipfile.ZipFile(sources, "w") as archive:
            archive.writestr("desktopNativeMain/Window.kt", "fun Window()")
        (platform / f"desktop-native-linuxx64-{VERSION}.module").write_text(json.dumps({
            "component": {"group": GROUP, "module": "desktop-native", "version": VERSION},
            "variants": [
                {"name": "linuxX64ApiElements", "attributes": {
                    "org.jetbrains.kotlin.native.target": "linux_x64",
                    "org.gradle.usage": "kotlin-api",
                }, "files": []},
                {"name": "linuxX64SourcesElements", "attributes": {
                    "org.jetbrains.kotlin.native.target": "linux_x64",
                    "org.gradle.docstype": "sources",
                }, "files": [{"name": sources.name, "url": sources.name}]},
            ]
        }))
        self.write("desktopNativeMain/default/manifest",
                   "unique_name=desktop-native_desktopNativeMain\nnative_targets=\n")
        self.write("desktopNativeMain/default/linkdata/package_window/0.knm", "Window")
        for name in ("sdl3", "nativeDesktop"):
            self.write(f"desktopNativeMain-cinterop/desktop-native-cinterop-{name}/default/manifest",
                       f"unique_name=sdk:{name}\ninterop=true\n"
                       "commonizer_native_targets=linux_arm64 linux_x64\n"
                       "native_targets=linux_arm64 linux_x64\n")

    def write(self, relative, text):
        path = self.shared / relative
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(text)

    def publish(self, include_shared=True):
        arguments = [sys.executable, str(SCRIPT), "--repository", str(self.repository),
                     "--version", VERSION, "--group", GROUP, "--module", "desktop-native"]
        if include_shared:
            arguments += ["--desktop-native-shared-metadata", str(self.shared)]
        result = subprocess.run(arguments, capture_output=True, text=True)
        self.assertEqual(0, result.returncode, result.stderr + result.stdout)

    def test_one_binary_platform_keeps_full_shared_api_and_real_header_targets(self):
        self.publish()
        module = json.loads((self.root / f"desktop-native-{VERSION}.module").read_text())
        binary_variants = [v for v in module["variants"] if "available-at" in v and v.get("attributes", {}).get("org.gradle.usage") == "kotlin-api"]
        self.assertEqual(["linuxX64ApiElements"], [v["name"] for v in binary_variants])
        artifact = self.root / f"desktop-native-{VERSION}.jar"
        original = artifact.read_bytes()
        with zipfile.ZipFile(artifact) as archive:
            topology = json.loads(archive.read("META-INF/kotlin-project-structure-metadata.json"))
            self.assertEqual(5, len(topology["projectStructure"]["variants"]))
            for path in self.shared.rglob("*"):
                if path.is_file():
                    self.assertEqual(path.read_bytes(), archive.read(path.relative_to(self.shared).as_posix()))
        self.publish(include_shared=False)
        self.assertEqual(original, artifact.read_bytes())
        self.assertFalse((self.root.parent.parent / "desktop-native-linuxarm64" / VERSION).exists())

    def test_rejects_leaf_metadata_instead_of_relabeling_it_as_shared(self):
        self.write("desktopNativeMain/default/manifest",
                   "unique_name=desktop-native\nnative_targets=linux_x64\n")
        with self.assertRaisesRegex(RuntimeError, "shared compilation"):
            metadata.create_desktop_native_metadata(self.repository, GROUP, "desktop-native", VERSION, self.shared)

    def test_all_platforms_merges_existing_web_and_new_native_publications(self):
        group = "dev.brahmkshatriya.compose.foundation"
        base = self.repository / group.replace(".", "/")
        root = base / "foundation" / VERSION
        root.mkdir(parents=True)
        (root / f"foundation-{VERSION}.module").write_text(json.dumps({
            "variants": [{"name": "metadataApiElements", "attributes": {
                "org.gradle.usage": "kotlin-metadata",
            }, "files": []}]
        }))
        for suffix, attributes in (
            ("linuxx64", {"org.jetbrains.kotlin.native.target": "linux_x64"}),
            ("wasm-js", {"org.jetbrains.kotlin.platform.type": "wasm"}),
        ):
            platform = base / f"foundation-{suffix}" / VERSION
            platform.mkdir(parents=True)
            (platform / f"foundation-{suffix}-{VERSION}.module").write_text(json.dumps({
                "component": {"group": group, "module": "foundation", "version": VERSION},
                "variants": [{"name": f"{suffix}ApiElements", "attributes": {
                    **attributes, "org.gradle.usage": "kotlin-api",
                }, "files": []}]
            }))
        result = subprocess.run([
            sys.executable, str(SCRIPT), "--repository", str(self.repository),
            "--version", VERSION, "--group", group, "--module", "foundation", "--all-platforms",
        ], capture_output=True, text=True)
        self.assertEqual(0, result.returncode, result.stderr + result.stdout)
        module = json.loads((root / f"foundation-{VERSION}.module").read_text())
        self.assertEqual(
            {"metadataApiElements", "linuxx64ApiElements", "wasm-jsApiElements"},
            {variant["name"] for variant in module["variants"]},
        )

    def test_rejects_uncommonized_native_headers(self):
        self.write("desktopNativeMain-cinterop/desktop-native-cinterop-sdl3/default/manifest",
                   "unique_name=sdk:sdl3\ninterop=true\ncommonizer_native_targets=linux_x64\n")
        with self.assertRaisesRegex(RuntimeError, "genuinely commonized"):
            metadata.create_desktop_native_metadata(self.repository, GROUP, "desktop-native", VERSION, self.shared)


if __name__ == "__main__":
    unittest.main()
