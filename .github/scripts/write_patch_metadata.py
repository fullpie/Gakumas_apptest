#!/usr/bin/env python3
import argparse
import hashlib
import json
import os
from datetime import datetime, timezone
from pathlib import Path


def sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for block in iter(lambda: handle.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--game-package", required=True)
    parser.add_argument("--game-version", required=True)
    parser.add_argument("--release-tag", required=True)
    parser.add_argument("--repository", required=True)
    parser.add_argument("--asset", required=True, type=Path)
    parser.add_argument("--output", required=True, type=Path)
    parser.add_argument("--minimum-app-version-code", default="12")
    args = parser.parse_args()

    asset_name = args.asset.name
    download_url = (
        f"https://github.com/{args.repository}/releases/download/"
        f"{args.release_tag}/{asset_name}"
    )
    metadata = {
        "schemaVersion": 1,
        "kind": "gakumas-game-patch",
        "createdAt": datetime.now(timezone.utc).isoformat(),
        "gamePackageName": args.game_package,
        "gameVersion": args.game_version,
        "releaseTag": args.release_tag,
        "patchMode": "lspatch-manager",
        "languagePackMode": "user-selectable",
        "appPackageName": "io.github.chinosk.gakumas.localify",
        "minimumAppVersionCode": int(args.minimum_app_version_code),
        "assets": [
            {
                "name": asset_name,
                "browserDownloadUrl": download_url,
                "sha256": sha256_file(args.asset),
                "size": os.path.getsize(args.asset),
                "contentType": "application/vnd.android.package-archive",
            }
        ],
    }
    args.output.write_text(json.dumps(metadata, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
