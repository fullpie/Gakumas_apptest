#!/usr/bin/env python3
import argparse
import json
import re
import sys
import urllib.parse
import urllib.request


def latest_xapk_url(package_name: str) -> str:
    return f"https://d.apkpure.com/b/XAPK/{package_name}?version=latest"


def version_from_text(text: str) -> str | None:
    patterns = [
        r"_(\d+(?:\.\d+)+)_APKPure\.xapk",
        r"-(\d+(?:\.\d+)+)-APKPure\.xapk",
        r"(\d+(?:\.\d+)+)_APKPure\.xapk",
    ]
    for pattern in patterns:
        match = re.search(pattern, text)
        if match:
            return match.group(1)
    return None


def probe_version(url: str) -> str:
    request = urllib.request.Request(
        url,
        headers={
            # APKPure currently blocks browser-like probes on this endpoint, while
            # aria2-style range requests still return the redirect and filename.
            "User-Agent": "aria2/1.37.0",
            "Accept": "*/*",
            "Range": "bytes=0-0",
        },
    )
    with urllib.request.urlopen(request, timeout=45) as response:
        candidates = [
            response.url,
            response.headers.get("Content-Disposition", ""),
        ]
    for candidate in candidates:
        decoded = urllib.parse.unquote(candidate)
        version = version_from_text(decoded)
        if version:
            return version
    raise RuntimeError("Could not detect APKPure version from redirect headers")


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--package", required=True)
    args = parser.parse_args()

    url = latest_xapk_url(args.package)
    data = {
        "source": "apkpure",
        "xapk_url": url,
        "version": probe_version(url),
    }
    print(json.dumps(data, ensure_ascii=False))
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except Exception as exc:
        print(str(exc), file=sys.stderr)
        raise SystemExit(1)
