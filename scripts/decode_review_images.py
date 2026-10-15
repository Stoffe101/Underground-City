#!/usr/bin/env python3
"""Rebuild screenshots printed by scripts/emit_review_images.py from a saved CI log."""
import argparse
import base64
import hashlib
import re
import sys
from pathlib import Path

BEGIN = re.compile(r"UC-REVIEW-IMAGE-BEGIN name=(\S+) format=(\S+) w=(\d+) h=(\d+) bytes=(\d+) sha256=([0-9a-f]{64})")


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("log")
    parser.add_argument("--out", required=True)
    args = parser.parse_args()
    out = Path(args.out)
    out.mkdir(parents=True, exist_ok=True)
    current, chunks, written = None, [], 0
    for line in Path(args.log).read_text(errors="replace").splitlines():
        match = BEGIN.search(line)
        if match:
            current, chunks = match.groups(), []
            continue
        if current and "UC-B64 " in line:
            chunks.append(line.split("UC-B64 ", 1)[1].strip())
        elif current and "UC-REVIEW-IMAGE-END" in line:
            data = base64.b64decode("".join(chunks))
            name, fmt, _, _, size, digest = current
            if len(data) != int(size) or hashlib.sha256(data).hexdigest() != digest:
                print(f"CORRUPT {name}: log text was truncated or altered", file=sys.stderr)
            else:
                target = out / (Path(name).stem + ("-review.jpg" if fmt == "jpeg" else "-review.png"))
                target.write_bytes(data)
                print("wrote", target)
                written += 1
            current = None
    return 0 if written else 1


if __name__ == "__main__":
    sys.exit(main())
