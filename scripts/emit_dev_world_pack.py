#!/usr/bin/env python3
"""Write the original three-city world package as plain files, so Gradle can build it without Python.

``scripts/build_world_prototype.py`` renders the Lost Cities low-code mod (city shapes, the road/bridge worldgen
feature configuration, loot, design metadata) from ``content/region-blueprints``. A Windows developer who runs
``gradlew runClient`` should not need Python, so this script emits the same files into a committed directory:

* ``content/generated/world-prototype/pack``       contents of the low-code mod jar (Gradle zips it into ``mods``)
* ``content/generated/world-prototype/game-config`` Lost Cities configuration for a development game directory

Never edit the generated files by hand: change the blueprint and run this script. CI runs it with ``--check``.

    python scripts/emit_dev_world_pack.py            # rewrite the generated tree
    python scripts/emit_dev_world_pack.py --check    # exit 1 when the tree differs from the blueprint
"""
from __future__ import annotations

import argparse
import json
import shutil
import sys
from pathlib import Path

from build_world_prototype import DEFAULT, ROOT, render
from install_world_prototype import config_files

LAYOUTS = {
    "legacy": (DEFAULT, ROOT / "content/generated/world-prototype"),
    "archipelago": (ROOT / "content/region-blueprints/archipelago/cities.json", ROOT / "content/generated/world-archipelago"),
}


def expected(data: dict, layout: str = "legacy") -> dict[str, bytes]:
    files = {f"pack/{name}": content for name, content in render(data, layout).items()}
    files.update({f"game-config/{name}": text.encode("utf-8") for name, text in config_files(data).items()})
    return files


def current(folder: Path) -> dict[str, bytes]:
    return {path.relative_to(folder).as_posix(): path.read_bytes() for path in sorted(folder.rglob("*")) if path.is_file()}


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--check", action="store_true")
    parser.add_argument("--layout", choices=sorted(LAYOUTS), default="legacy", help="which world layout's package to write or check")
    parser.add_argument("--blueprint", type=Path)
    args = parser.parse_args()
    blueprint, OUT = LAYOUTS[args.layout]
    data = json.loads((args.blueprint or blueprint).read_text(encoding="utf-8"))
    want = expected(data, args.layout)
    if args.check:
        have = current(OUT) if OUT.exists() else {}
        missing = sorted(set(want) - set(have))
        extra = sorted(set(have) - set(want))
        changed = sorted(name for name in set(want) & set(have) if want[name] != have[name])
        if missing or extra or changed:
            print(f"{OUT.relative_to(ROOT)} is out of date; run python scripts/emit_dev_world_pack.py --layout {args.layout}", file=sys.stderr)
            for label, names in (("missing", missing), ("unexpected", extra), ("changed", changed)):
                for name in names[:10]:
                    print(f"  {label}: {name}", file=sys.stderr)
            return 1
        print(f"world package up to date ({len(want)} files)")
        return 0
    if OUT.exists():
        shutil.rmtree(OUT)
    for name, content in want.items():
        target = OUT / name
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_bytes(content)
    print(f"wrote {len(want)} files under {OUT.relative_to(ROOT)}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
