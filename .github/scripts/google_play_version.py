#!/usr/bin/env python3
import argparse
import json
import re
import sys
import time
from typing import Any
from urllib.error import HTTPError
from urllib.request import Request, urlopen


SCRIPT_RE = re.compile(r"AF_initDataCallback[\s\S]*?</script")
KEY_RE = re.compile(r"key: ['\"]([^'\"]+)['\"]")
VALUE_RE = re.compile(r"data:([\s\S]*?), sideChannel: \{\}\}\);</")


def fetch(url: str, retries: int = 3) -> str:
    last_error: Exception | None = None
    for attempt in range(retries):
        try:
            request = Request(
                url,
                headers={
                    "User-Agent": (
                        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) "
                        "AppleWebKit/537.36 (KHTML, like Gecko) "
                        "Chrome/125.0 Safari/537.36"
                    )
                },
            )
            with urlopen(request, timeout=30) as response:
                return response.read().decode("utf-8")
        except HTTPError as exc:
            if exc.code == 404:
                raise RuntimeError(f"package not found on Google Play: {url}") from exc
            last_error = exc
        except Exception as exc:  # noqa: BLE001
            last_error = exc
        time.sleep(2 * (attempt + 1))
    raise RuntimeError(f"failed to fetch Google Play page: {last_error}")


def nested_lookup(source: Any, indexes: list[int]) -> Any:
    value = source
    for index in indexes:
        value = value[index]
    return value


def get_version(package_name: str, language: str) -> str:
    page = fetch(f"https://play.google.com/store/apps/details?id={package_name}&hl={language}")
    dataset: dict[str, Any] = {}
    for match in SCRIPT_RE.findall(page):
        keys = KEY_RE.findall(match)
        values = VALUE_RE.findall(match)
        if keys and values:
            dataset[keys[0]] = json.loads(values[0])

    try:
        version = nested_lookup(dataset["ds:5"], [1, 2, 140, 0, 0, 0])
    except (KeyError, TypeError, IndexError) as exc:
        raise RuntimeError("could not locate app version in Google Play page data") from exc

    if not isinstance(version, str) or not version.strip():
        raise RuntimeError(f"invalid Google Play version value: {version!r}")
    return version.strip()


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--package", required=True)
    parser.add_argument("--language", default="en")
    args = parser.parse_args()

    print(get_version(args.package, args.language))
    return 0


if __name__ == "__main__":
    sys.exit(main())
