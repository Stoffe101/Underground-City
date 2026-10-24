#!/usr/bin/env python3
"""Draw (or check) every original texture, block model and blockstate of the Core that is not generated from game data.

    python tools/art/build_all.py            write the files into rpg-core/src/main/resources/assets/underground_city
    python tools/art/build_all.py --check    draw them into a scratch copy and fail when a committed file differs

The generators in this folder are the source of truth for the item sprites, the cannabis plant (textures and block models), the terminal,
secured terminal, household safe and public ledger (textures, block models and blockstates), the GUI chrome, icons, skylines and the display
font. Everything is drawn from character maps and primitive shapes; no third-party pixel is read. PNGs are compared by decoded pixels
(encoder output varies), everything else byte for byte. Needs Pillow only.
"""
from __future__ import annotations

import argparse
import io
import shutil
import subprocess
import sys
import tempfile
from pathlib import Path

HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[1]
ASSETS = Path("rpg-core/src/main/resources/assets/underground_city")
#: Folders (relative to the assets root) that the generators own completely: a committed file there that no generator draws is an orphan.
OWNED = ("textures/gui", "textures/font", "font", "textures/entity/citizen")
OWNED_FILES = {"textures/block": ("cannabis_", "terminal_", "secure_", "safe_", "ledger_", "scrap_", "utility_panel_", "computer_", "bench_", "rack_"), "textures/item": ("",)}


def write_everything() -> None:
    sys.path.insert(0, str(HERE))
    import gen_items, gen_blocks, gen_chrome, gen_icons, gen_font, gen_crop, gen_scenes, gen_citizens
    gen_items.save_all()
    gen_blocks.build()
    gen_chrome.build()
    gen_icons.build()
    gen_font.build()
    gen_crop.write_textures()
    gen_crop.write_models()
    gen_scenes.build()
    gen_citizens.build()


def same(a: Path, b: Path) -> bool:
    if a.suffix != ".png":
        return a.read_bytes() == b.read_bytes()
    from PIL import Image
    x, y = Image.open(io.BytesIO(a.read_bytes())).convert("RGBA"), Image.open(io.BytesIO(b.read_bytes())).convert("RGBA")
    return x.size == y.size and x.tobytes() == y.tobytes()


def model_problems() -> list[str]:
    """Block models the renderer would sample outside their sprite or place outside the allowed -16..32 range (a stem taller than 16 units once did)."""
    import json
    problems = []
    for path in sorted((ROOT / ASSETS / "models" / "block").glob("*.json")):
        model = json.loads(path.read_text(encoding="utf-8"))
        for number, element in enumerate(model.get("elements", [])):
            if min(element["from"]) < -16 or max(element["to"]) > 32:
                problems.append(f"{path.name} element {number} leaves the -16..32 range")
            for side, face in element.get("faces", {}).items():
                uv = face.get("uv")
                if uv and (min(uv) < 0 or max(uv) > 16):
                    problems.append(f"{path.name} element {number} {side} samples outside the 16 x 16 sprite: {uv}")
    return problems


def check() -> int:
    with tempfile.TemporaryDirectory() as scratch:
        tmp = Path(scratch)
        shutil.copytree(HERE, tmp / "tools" / "art", ignore=shutil.ignore_patterns("__pycache__"))
        (tmp / ASSETS).mkdir(parents=True)
        subprocess.run([sys.executable, str(tmp / "tools" / "art" / "build_all.py"), "--write"], check=True, stdout=subprocess.DEVNULL)
        drawn = sorted(p for p in (tmp / ASSETS).rglob("*") if p.is_file())
        stale = [str(p.relative_to(tmp)) for p in drawn if not (ROOT / p.relative_to(tmp)).is_file() or not same(p, ROOT / p.relative_to(tmp))]
        produced = {p.relative_to(tmp / ASSETS).as_posix() for p in drawn}
        orphans = []
        for folder in OWNED:
            for path in (ROOT / ASSETS / folder).rglob("*"):
                if path.is_file() and path.relative_to(ROOT / ASSETS).as_posix() not in produced:
                    orphans.append(str(path.relative_to(ROOT)))
        for folder, prefixes in OWNED_FILES.items():
            for path in (ROOT / ASSETS / folder).glob("*.png"):
                rel = path.relative_to(ROOT / ASSETS).as_posix()
                if rel not in produced and any(path.name.startswith(prefix) for prefix in prefixes):
                    orphans.append(str(path.relative_to(ROOT)))
    broken = model_problems()
    for item in broken:
        print("model:", item)
    for item in stale:
        print("stale:", item)
    for item in orphans:
        print("orphan (drawn by no generator):", item)
    if stale or orphans or broken:
        print("Run: python tools/art/build_all.py")
        return 1
    print(f"Checked {len(drawn)} drawn files: all current")
    return 0


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--check", action="store_true")
    parser.add_argument("--write", action="store_true", help=argparse.SUPPRESS)
    args = parser.parse_args()
    if args.check:
        return check()
    write_everything()
    return 0


if __name__ == "__main__":
    sys.exit(main())
