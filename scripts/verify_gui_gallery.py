#!/usr/bin/env python3
"""Fail a real Minecraft UI gallery if a required panel is missing or duplicated.

This is *mechanical* visual QA, not aesthetic approval. Reviewers must inspect
the actual PNGs before authorizing artifact cleanup.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import math
from pathlib import Path

from PIL import Image, ImageChops, ImageStat

ROOT = Path(__file__).resolve().parents[1]


def inspect(manifest: dict, screenshots: Path, width: int, height: int) -> dict:
    if manifest.get("schema_version") != 1:
        raise ValueError("Unknown GUI coverage schema")
    expected = manifest.get("integrated_surfaces")
    if not isinstance(expected, list) or not expected:
        raise ValueError("No integrated GUI surfaces registered")
    ids = [entry["id"] for entry in expected]
    filenames = [entry["expected_file"] for entry in expected]
    if len(ids) != len(set(ids)) or len(filenames) != len(set(filenames)):
        raise ValueError("Duplicate GUI panel IDs or screenshot filenames")
    if any(not filename.startswith("uc-") or not filename.endswith(".png") or
           "/" in filename or "\\" in filename for filename in filenames):
        raise ValueError("Unsafe screenshot filename")
    files = sorted(screenshots.glob("uc-*.png"))
    extra = sorted(set(f.name for f in files) - set(filenames))
    if extra:
        raise ValueError(f"Unexpected unregistered GUI screenshots: {extra}")
    observed = []
    previous = []
    for entry in expected:
        path = screenshots / entry["expected_file"]
        if not path.is_file():
            raise ValueError(f"Required GUI missing: {entry['id']} ({path.name})")
        if path.stat().st_size < 2048:
            raise ValueError(f"Screenshot too small to be real framebuffer: {path.name}")
        with Image.open(path) as image:
            if image.format != "PNG":
                raise ValueError(f"{path.name} not PNG")
            if image.size != (width, height):
                raise ValueError(f"{path.name}: resolution mismatch {image.size}; expected {width}x{height}")
            image = image.convert("RGB")
            # Sample centre UI so background scenery isn't mistaken for
            # legitimate contrast or changed panel.
            sw, sh = image.size
            # Include the complete centered UI, especially Q Shop's left wallet.
            # A narrow central grid alone makes funded/empty wallets look equal.
            crop = image.crop((int(sw*.17),int(sh*.12),int(sw*.83),int(sh*.88)))
            avg = ImageStat.Stat(crop).stddev
            if max(avg) < 5:
                raise ValueError(f"Blank or nearly flat centre panel: {path.name}")
            differences = []
            for old_id, old_img in previous:
                difference = ImageChops.difference(crop, old_img)
                changed = sum(1 for px in difference.convert("L").getdata() if px > 12)
                differences.append({"other": old_id, "changed_pixels": changed})
                if changed < manifest.get("minimum_difference_pixels", 100):
                    raise ValueError(f"Duplicate-looking screenshot {entry['id']} versus {old_id}: {changed} pixels")
            previous.append((entry["id"], crop.copy()))
        observed.append({
            "id": entry["id"],
            "filename": path.name,
            "width": width, "height": height,
            "sha256": hashlib.sha256(path.read_bytes()).hexdigest(),
            "centre_channel_stddev": [round(x, 2) for x in avg],
            "different_from_previous": differences,
        })
    return {
        "status": "mechanical_real_client_capture_validated",
        "aesthetic_approval": False,
        "client_resolution": [width, height],
        "captured_count": len(observed),
        "total_integrated_surfaces": len(expected),
        "unintegrated_future_screen_count": len(manifest.get("unintegrated_future_interfaces", [])),
        "checks": "file_presence,png_dimensions,distinct_tabs,nonblank_centre",
        "screenshots": observed,
    }


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--manifest", type=Path, default=ROOT/"content/qa/gui-coverage.json")
    parser.add_argument("--screenshots", type=Path, required=True)
    parser.add_argument("--width", type=int, required=True)
    parser.add_argument("--height", type=int, required=True)
    parser.add_argument("--report", type=Path, required=True)
    args = parser.parse_args()
    manifest = json.loads(args.manifest.read_text(encoding="utf-8"))
    report = inspect(manifest, args.screenshots, args.width, args.height)
    args.report.parent.mkdir(parents=True, exist_ok=True)
    args.report.write_text(json.dumps(report, sort_keys=True, indent=2)+"\n", encoding="utf-8")
    print("Real-game screenshot coverage:", report["captured_count"], "/",
          report["total_integrated_surfaces"],
          "(aesthetic review still REQUIRED)")


if __name__ == "__main__":
    main()
