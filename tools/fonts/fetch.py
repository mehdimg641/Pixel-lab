#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Fetches the bundled font files named by `families.txt` into the APK's assets.

Run from anywhere:  python3 tools/fonts/fetch.py [--check]

Why this exists rather than a hundred files somebody once downloaded: a binary nobody can reproduce
is a binary nobody can correct. With this, "which cut of Vazirmatn is in the build" has an answer,
and so does "re-fetch everything after upstream fixed a shaping bug".

`--check` verifies that the manifest and the directory agree, without touching the network. That is
what CI would run.

### Picking a file

Where a family publishes a variable font the fetcher takes it — one file carries every weight, and
the app's own parser reads `fvar` and prefers it. Otherwise the Regular upright. Italics and extra
static weights are deliberately skipped: this is a picker for setting a headline, not a foundry
catalogue, and every extra file is real install size.

### Why it probes rather than looks up

Two obvious routes are closed. The `google/fonts` tree API refuses unauthenticated callers with a
403, and the CSS API only ever answers with static per-weight instances — it cannot hand back the
variable cut, which is the file worth having. So each family's filename is tried against the small
set of patterns Google Fonts actually uses, across the three licence directories.
"""
import argparse
import os
import re
import sys
import urllib.parse
import urllib.request

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(os.path.dirname(HERE))
MANIFEST = os.path.join(HERE, "families.txt")
TARGET = os.path.join(ROOT, "app", "android", "src", "main", "assets", "fonts")

RAW = "https://raw.githubusercontent.com"
GOOGLE = f"{RAW}/google/fonts/main"

# Google Fonts keeps families under three licence directories and the family name never says which,
# so each candidate is tried in all three.
LICENCE_DIRS = ("ofl", "apache", "ufl")

# Filename patterns, best first. `{n}` is the family name with spaces removed.
PATTERNS = (
    "{n}[wght].ttf",
    "{n}[slnt,wght].ttf",
    "{n}[opsz,wght].ttf",
    "{n}[wdth,wght].ttf",
    "{n}[YTLC,wght].ttf",
    # Two families carry their own bespoke axes ahead of `wght`, and there is no rule that predicts
    # them — Handjet's element grid and shape, Readex Pro's Arabic horizontal expansion. Listed by
    # name because probing a combinatorial space of axis tags would be slower than reading the
    # directory, which is the thing the API will not let us do.
    "{n}[ELGR,ELSH,wght].ttf",
    "{n}[HEXP,wght].ttf",
    "{n}-Regular.ttf",
    "{n}-Regular.otf",
    "{n}.ttf",
)

AGENT = {"User-Agent": "pixel-lab-font-fetch"}


def get(url):
    with urllib.request.urlopen(urllib.request.Request(url, headers=AGENT), timeout=120) as response:
        return response.read()


def exists(url):
    request = urllib.request.Request(url, method="HEAD", headers=AGENT)
    try:
        with urllib.request.urlopen(request, timeout=60) as response:
            return response.status == 200
    except Exception:  # noqa: BLE001 — a miss and an error are the same answer here
        return False


def manifest():
    """(source, identifier) pairs, in file order."""
    entries = []
    with open(MANIFEST, encoding="utf-8") as handle:
        for line in handle:
            line = line.strip()
            if not line or line.startswith("#"):
                continue
            source, _, identifier = line.partition(" ")
            entries.append((source, identifier.strip()))
    return entries


def slug(family):
    """Google Fonts' directory name: lowercase, letters and digits only."""
    return re.sub(r"[^a-z0-9]", "", family.lower())


def resolve(family):
    """(url, filename) for the one file to ship, or (None, None)."""
    bare = family.replace(" ", "")
    directory = slug(family)
    for pattern in PATTERNS:
        name = pattern.format(n=bare)
        for licence in LICENCE_DIRS:
            url = f"{GOOGLE}/{licence}/{directory}/{urllib.parse.quote(name)}"
            if exists(url):
                return url, name
    return None, None


def on_disk():
    if not os.path.isdir(TARGET):
        return set()
    return {n for n in os.listdir(TARGET) if n.lower().endswith((".ttf", ".otf"))}


def check(entries):
    files = on_disk()
    total = sum(os.path.getsize(os.path.join(TARGET, n)) for n in files)
    print(f"{len(entries)} families in the manifest, {len(files)} files on disk, {total // 1024 // 1024} MB")
    if len(files) < len(entries):
        print(f"  {len(entries) - len(files)} families have no file")
        return 1
    return 0


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--check", action="store_true", help="verify, do not download")
    args = parser.parse_args()

    entries = manifest()
    os.makedirs(TARGET, exist_ok=True)
    if args.check:
        return check(entries)

    written, skipped, failed = 0, 0, []
    for source, identifier in entries:
        if source == "gf":
            url, name = resolve(identifier)
            if not url:
                failed.append(f"{identifier}: no file matched any known pattern")
                continue
            # The variable cut's filename carries its axes in brackets, which is noise on disk and
            # loses nothing — the parser reads the axes from `fvar`, never from the name.
            name = re.sub(r"\[.*?\]", "", name)
        elif source == "gh":
            owner, repo, *rest = identifier.split("/")
            url = f"{RAW}/{owner}/{repo}/master/{'/'.join(rest)}"
            name = os.path.basename(identifier)
        else:
            failed.append(f"{identifier}: unknown source `{source}`")
            continue

        destination = os.path.join(TARGET, name)
        if os.path.exists(destination):
            skipped += 1
            continue
        try:
            data = get(url)
        except Exception as error:  # noqa: BLE001 — the report is the point, not the type
            failed.append(f"{identifier}: {error}")
            continue
        # Sniffed rather than trusted: a 404 page saved under a `.ttf` name is the failure that
        # only shows up later, as a face missing from the picker with no explanation.
        if not data.startswith((b"\x00\x01\x00\x00", b"OTTO", b"true", b"ttcf")):
            failed.append(f"{identifier}: {url} did not return a font")
            continue
        with open(destination, "wb") as handle:
            handle.write(data)
        written += 1
        print(f"  {name}  {len(data) // 1024} KB")

    files = on_disk()
    total = sum(os.path.getsize(os.path.join(TARGET, n)) for n in files)
    print(f"\nwrote {written}, skipped {skipped} already present, {len(failed)} failed")
    print(f"{len(files)} files, {total // 1024 // 1024} MB")
    for problem in failed:
        print("  !", problem)
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main())
