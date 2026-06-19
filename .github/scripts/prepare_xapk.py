#!/usr/bin/env python3
import argparse
import json
import shutil
from pathlib import Path


def find_manifest(root: Path) -> dict:
    manifest_path = root / "manifest.json"
    if not manifest_path.exists():
        matches = list(root.rglob("manifest.json"))
        if not matches:
            return {}
        manifest_path = matches[0]
    return json.loads(manifest_path.read_text(encoding="utf-8-sig"))


def manifest_file_names(manifest: dict) -> list[str]:
    names: list[str] = []
    for item in manifest.get("split_apks", []) or []:
        if isinstance(item, dict):
            value = item.get("file") or item.get("path") or item.get("name")
            if value:
                names.append(Path(str(value)).name)
        elif isinstance(item, str):
            names.append(Path(item).name)
    base = manifest.get("base_apk") or manifest.get("baseApk")
    if base:
        base_name = Path(str(base)).name
        names = [base_name] + [name for name in names if name != base_name]
    return names


def copy_ordered_apks(root: Path, output: Path, ordered_names: list[str]) -> list[Path]:
    apk_files = [path for path in root.rglob("*.apk") if path.is_file()]
    if not apk_files:
        raise RuntimeError(f"No APK files found in {root}")

    by_name = {path.name: path for path in apk_files}
    ordered: list[Path] = []
    for name in ordered_names:
        if name in by_name and by_name[name] not in ordered:
            ordered.append(by_name[name])

    # Make sure base/master APK comes first even when APKPure changes split order.
    remaining = [path for path in apk_files if path not in ordered]
    remaining.sort(key=lambda p: (0 if p.name in {"base.apk", "master.apk"} else 1, p.name))
    ordered.extend(remaining)

    output.mkdir(parents=True, exist_ok=True)
    copied: list[Path] = []
    for apk in ordered:
        target = output / apk.name
        if target.exists():
            target.unlink()
        shutil.copy2(apk, target)
        copied.append(target)
    return copied


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--root", required=True, type=Path)
    parser.add_argument("--output", required=True, type=Path)
    parser.add_argument("--package", required=True)
    parser.add_argument("--version", default="")
    args = parser.parse_args()

    manifest = find_manifest(args.root)
    package_name = manifest.get("package_name") or manifest.get("package") or manifest.get("packageName")
    version_name = manifest.get("version_name") or manifest.get("versionName")

    if package_name and package_name != args.package:
        raise RuntimeError(f"Unexpected package name: {package_name} != {args.package}")
    if args.version and version_name and version_name != args.version:
        raise RuntimeError(f"Unexpected version: {version_name} != {args.version}")

    copied = copy_ordered_apks(args.root, args.output, manifest_file_names(manifest))
    summary = {
        "package_name": package_name,
        "version_name": version_name,
        "copied_apks": [path.name for path in copied],
    }
    print(json.dumps(summary, ensure_ascii=False, indent=2))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
