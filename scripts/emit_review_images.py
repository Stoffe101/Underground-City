#!/usr/bin/env python3
"""CI helper: print compressed reviewable copies of screenshots into the job log.

Hosted artifact downloads are not reachable from every review environment, but job logs
are. This prints bounded, downscaled copies (never saves, mods or full galleries) plus
statistics of the full-resolution image. Decode with scripts/decode_review_images.py.
"""
import argparse
import base64
import hashlib
import sys
from io import BytesIO
from pathlib import Path

from PIL import Image, ImageStat


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("paths", nargs="+")
    parser.add_argument("--max-width", type=int, default=640)
    parser.add_argument("--format", choices=("jpeg", "png"), default="jpeg")
    parser.add_argument("--quality", type=int, default=60)
    parser.add_argument("--colors", type=int, default=48, help="palette size for png output")
    parser.add_argument("--crop", help="left,top,right,bottom crop of the full image before scaling")
    args = parser.parse_args()
    for raw in args.paths:
        path = Path(raw)
        if not path.is_file():
            print(f"UC-REVIEW-IMAGE-MISSING name={path.name}")
            continue
        full = Image.open(path).convert("RGB")
        stats = ImageStat.Stat(full)
        print(f"UC-REVIEW-STATS name={path.name} size={full.size[0]}x{full.size[1]} "
              f"mean={[round(v, 1) for v in stats.mean]} stddev={[round(v, 1) for v in stats.stddev]}")
        image = full
        if args.crop:
            image = image.crop(tuple(int(v) for v in args.crop.split(",")))
        if image.width > args.max_width:
            image = image.resize((args.max_width, round(image.height * args.max_width / image.width)), Image.LANCZOS)
        buffer = BytesIO()
        if args.format == "jpeg":
            image.save(buffer, "JPEG", quality=args.quality, optimize=True)
        else:
            image.quantize(colors=args.colors, method=Image.MEDIANCUT).save(buffer, "PNG", optimize=True)
        data = buffer.getvalue()
        digest = hashlib.sha256(data).hexdigest()
        print(f"UC-REVIEW-IMAGE-BEGIN name={path.name} format={args.format} w={image.width} h={image.height} "
              f"bytes={len(data)} sha256={digest}")
        encoded = base64.b64encode(data).decode()
        for index in range(0, len(encoded), 120):
            print("UC-B64 " + encoded[index:index + 120])
        print(f"UC-REVIEW-IMAGE-END name={path.name}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
