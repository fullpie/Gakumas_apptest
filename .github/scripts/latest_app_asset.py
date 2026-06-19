#!/usr/bin/env python3
import argparse
import json
import urllib.request


def fetch_json(url: str):
    request = urllib.request.Request(
        url,
        headers={
            "Accept": "application/vnd.github+json",
            "User-Agent": "gkms-localify-actions",
        },
    )
    with urllib.request.urlopen(request, timeout=30) as response:
        return json.loads(response.read().decode("utf-8"))


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--repository", required=True)
    args = parser.parse_args()

    releases = fetch_json(f"https://api.github.com/repos/{args.repository}/releases?per_page=30")
    for release in releases:
        if release.get("draft") or not str(release.get("tag_name", "")).startswith("app-v"):
            continue
        for asset in release.get("assets", []):
            name = str(asset.get("name", ""))
            if name.lower().endswith(".apk"):
                print(asset["browser_download_url"])
                return 0

    raise SystemExit("No app-v release APK asset found")


if __name__ == "__main__":
    raise SystemExit(main())
