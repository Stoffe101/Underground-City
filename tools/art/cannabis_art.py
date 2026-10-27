"""Cannabis: the Farmer's restricted crop (D-142), drawn as a palmate cannabis plant.

Botanical cues used (general botanical anatomy, no cultivation detail): palmately compound leaves with an odd number of
narrow, pointed, serrated leaflets radiating from one point; the middle leaflet longest and the outer pair smallest; a
paler midrib; leaves held on slender petioles from a straight, ridged stem; flowering clusters (colas) of tightly
stacked bracts with thin pale-orange hairs, topping the stem and its side branches. Everything here is stylised pixel
art; abstract in-game items and costs live in data, not here.
"""
from __future__ import annotations
import math
from PIL import Image, ImageDraw
from uc_art import Sprite, ramp, rgb

SS = 8  # supersampling factor for shape construction

LEAF = ramp('#4c9a4a')      # base green
LEAF_YOUNG = ramp('#7bbd55')
LEAF_DEEP = ramp('#2f7a43')
STEM = ramp('#6f8f45')
HAIR = [rgb('#f2b66a'), rgb('#fff0c4')]


# Hand-placed fingers on the 16x16 sprite, right half only (x 8..15); the left half is the mirror image (x -> 15 - x).
# Chosen by looking at the result: clean 2:1, 1:1 and 1:2 stair steps read as deliberate pixel art.
_F_CENTRE = {(8, y) for y in range(0, 12)} | {(9, 2), (9, 4), (9, 6), (9, 8)}
_F1 = {(9, 10), (9, 9), (10, 9), (10, 8), (10, 7), (11, 7), (11, 6), (11, 5), (12, 5), (12, 4), (12, 3), (13, 3), (13, 2), (14, 2),
       (10, 10), (11, 8), (12, 6), (13, 4), (14, 3)}
_F2 = {(10, 11), (11, 10), (12, 9), (13, 8), (14, 7), (15, 6),
       (10, 12), (11, 11), (12, 10), (13, 9), (14, 8), (15, 7)}
_F3 = {(10, 13), (11, 12), (12, 12), (13, 11), (14, 11), (15, 10), (11, 13), (13, 12), (15, 11)}
_SERR = {(11, 4), (12, 2), (13, 7), (14, 6), (14, 10), (15, 9)}      # saw teeth on the outer edge


def _assemble(parts, teeth: bool) -> set:
    right = set()
    for p in parts:
        right |= p
    if teeth:
        right |= _SERR
    pts = set(right) | {(15 - x, y) for (x, y) in right}
    for x in range(5, 11):          # hub
        for y in (11, 12):
            pts.add((x, y))
    pts |= {(6, 13), (7, 13), (8, 13), (9, 13), (7, 14), (8, 14), (7, 15), (8, 15)}
    return {(x, y) for (x, y) in pts if 0 <= x < 16 and 0 <= y < 16}


def palmate_leaf(leaflets: int, size: float = 1.0, palette=LEAF, droop: float = 0.0, **_ignored) -> Sprite:
    """A 16x16 palmate leaf (3, 5 or 7 leaflets); palette is darkest -> lightest, five steps."""
    centre = {3: {(8, y) for y in range(5, 12)}, 5: {(8, y) for y in range(2, 12)}, 7: _F_CENTRE}[leaflets]
    parts = {3: [centre, {(9, 10), (10, 9), (10, 8), (11, 7), (11, 6), (12, 6), (10, 10)}],
             5: [centre, _F1, _F3],
             7: [centre, _F1, _F2, _F3]}[leaflets]
    pts = _assemble(parts, teeth=leaflets >= 5)
    s = Sprite(16, 16)
    sh, dk, bs, lt, hi = palette
    for (x, y) in pts:
        edge = any((nx, ny) not in pts for nx, ny in ((x + 1, y), (x - 1, y), (x, y + 1), (x, y - 1)))
        left_half = x < 8
        if y >= 14:
            colour = bs if left_half else dk
        elif x in (7, 8) and y < 12:
            colour = lt if left_half else bs
        elif left_half:
            colour = bs if edge else lt
        else:
            colour = dk if edge else bs
        s.put(x, y, colour)
    return s


def with_outline(sprite: Sprite, color) -> Sprite:
    sprite.outline(color)
    return sprite


if __name__ == '__main__':
    import sys
    from uc_art import contact_sheet
    items = {}
    for n in (3, 5, 7):
        items[f'leaf{n}'] = palmate_leaf(n)
        items[f'leaf{n}young'] = palmate_leaf(n, palette=LEAF_YOUNG)
        items[f'leaf{n}deep'] = palmate_leaf(n, palette=LEAF_DEEP)
    sheet = contact_sheet(items, scale=14, cols=6)
    sheet.save(sys.argv[1])


def preview(path: str) -> None:
    from uc_art import contact_sheet
    import cannabis_parts
    items = {}
    for n in (3, 5, 7):
        items[f'leaf{n}'] = palmate_leaf(n)
        items[f'leaf{n}young'] = palmate_leaf(n, palette=LEAF_YOUNG)
        items[f'leaf{n}deep'] = palmate_leaf(n, palette=LEAF_DEEP)
    items['cola'] = cannabis_parts.cola()
    items['seedling'] = cannabis_parts.seedling()
    items['stem'] = cannabis_parts.stem()
    items['stake'] = cannabis_parts.stake()
    contact_sheet(items, scale=14, cols=6).save(path)
