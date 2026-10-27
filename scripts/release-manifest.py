#!/usr/bin/env python3
"""Build the bounded public update.json asset from the exact signed APK being released."""
import argparse
import hashlib
import json
import re
from pathlib import Path

parser = argparse.ArgumentParser()
parser.add_argument("version")
parser.add_argument("apk", type=Path)
parser.add_argument("--notes", type=Path)
parser.add_argument("--out", type=Path, required=True)
args = parser.parse_args()
if not re.fullmatch(r"(0|[1-9][0-9]{0,5})\.(0|[1-9][0-9]{0,5})\.(0|[1-9][0-9]{0,5})", args.version):
    parser.error("Expected a stable numeric version")
name = f"pocket-live-{args.version}-release.apk"
if args.apk.name != name or not 0 < args.apk.stat().st_size <= 12_000_000:
    parser.error("Expected matching release APK name and size <= 12 MB")
root = "https://github.com/ForXd/pocket-live/releases"
with args.apk.open("rb") as stream:
    checksum = hashlib.sha256()
    for block in iter(lambda: stream.read(65536), b""):
        checksum.update(block)
    digest = checksum.hexdigest()
result = {"draft": False, "prerelease": False, "tag_name": f"v{args.version}",
          "html_url": f"{root}/tag/v{args.version}",
          "body": args.notes.read_text()[:2000] if args.notes else "",
          "assets": [{"name": name, "state": "uploaded", "size": args.apk.stat().st_size,
                      "browser_download_url": f"{root}/download/v{args.version}/{name}",
                      "digest": "sha256:" + digest}]}
args.out.write_text(json.dumps(result, ensure_ascii=False, indent=2) + "\n")
