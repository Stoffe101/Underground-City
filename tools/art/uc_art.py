"""Underground City art toolkit: a tiny pixel-art library used by every generator in tools/art.

Original work (All Rights Reserved, like the rest of the repository). Nothing here reads, copies or traces any
third-party texture: every sprite is drawn from the character maps and shapes in the generator scripts.

Conventions (see docs/design/visual-identity.md):
* 16x16 items and blocks, 1 px outline in a dark tone of the object's own hue, light from the upper left.
* Colour ramps are five steps (shadow, dark, base, light, highlight); shadows lean cool, highlights lean warm.
* No anti-aliasing, no semi-transparent pixels except glass.
"""
from __future__ import annotations

import colorsys
import os
import random
import tempfile
from PIL import Image, ImageDraw

Color = tuple  # (r, g, b) or (r, g, b, a)


def rgb(value) -> tuple:
    if isinstance(value, tuple):
        return value if len(value) == 4 else (*value, 255)
    value = value.lstrip('#')
    if len(value) == 6:
        return (int(value[0:2], 16), int(value[2:4], 16), int(value[4:6], 16), 255)
    return (int(value[0:2], 16), int(value[2:4], 16), int(value[4:6], 16), int(value[6:8], 16))


def _shift_hue(h: float, target: float, amount: float) -> float:
    """Move hue h (0..1) toward target by up to `amount` along the short way round."""
    delta = ((target - h + 0.5) % 1.0) - 0.5
    return (h + max(-amount, min(amount, delta))) % 1.0


def ramp(base, steps: int = 5, spread: float = 0.30) -> list:
    """Hue-shifted ramp from darkest to lightest around `base` (the middle step)."""
    r, g, b, _ = rgb(base)
    h, l, s = colorsys.rgb_to_hls(r / 255, g / 255, b / 255)
    out = []
    mid = (steps - 1) / 2
    for i in range(steps):
        t = (i - mid) / mid  # -1 .. 1
        li = min(0.96, max(0.05, l + t * spread * (1.0 if t > 0 else 0.9)))
        hi = _shift_hue(h, 0.68, 0.035 * -t) if t < 0 else _shift_hue(h, 0.12, 0.03 * t)
        si = max(0.0, min(1.0, s * (1.0 - 0.25 * abs(t)) + (0.05 if t < 0 else 0.0)))
        rr, gg, bb = colorsys.hls_to_rgb(hi, li, si)
        out.append((round(rr * 255), round(gg * 255), round(bb * 255), 255))
    return out


class Sprite:
    """A transparent RGBA canvas with integer-pixel drawing helpers."""

    def __init__(self, w: int = 16, h: int = 16):
        self.w, self.h = w, h
        self.img = Image.new('RGBA', (w, h), (0, 0, 0, 0))
        self._d = ImageDraw.Draw(self.img)

    # ---- construction
    @classmethod
    def from_rows(cls, rows, legend: dict, w: int = None, h: int = None) -> 'Sprite':
        h = h or len(rows)
        w = w or max(len(r) for r in rows)
        s = cls(w, h)
        for y, row in enumerate(rows):
            for x, ch in enumerate(row):
                if ch in ('.', ' '):
                    continue
                if ch not in legend:
                    raise KeyError(f"legend has no entry for {ch!r} (row {y}, col {x})")
                s.put(x, y, legend[ch])
        return s

    # ---- pixels
    def put(self, x: int, y: int, c) -> None:
        if 0 <= x < self.w and 0 <= y < self.h:
            self.img.putpixel((x, y), rgb(c))

    def get(self, x: int, y: int):
        if 0 <= x < self.w and 0 <= y < self.h:
            return self.img.getpixel((x, y))
        return (0, 0, 0, 0)

    def solid(self, x: int, y: int) -> bool:
        return self.get(x, y)[3] > 0

    def rect(self, x0, y0, x1, y1, c) -> None:  # inclusive
        for y in range(y0, y1 + 1):
            for x in range(x0, x1 + 1):
                self.put(x, y, c)

    def line(self, x0, y0, x1, y1, c) -> None:
        dx, dy = abs(x1 - x0), -abs(y1 - y0)
        sx, sy = (1 if x0 < x1 else -1), (1 if y0 < y1 else -1)
        err = dx + dy
        while True:
            self.put(x0, y0, c)
            if x0 == x1 and y0 == y1:
                break
            e2 = 2 * err
            if e2 >= dy:
                err += dy
                x0 += sx
            if e2 <= dx:
                err += dx
                y0 += sy

    def polygon(self, points, c) -> None:
        self._d.polygon(points, fill=rgb(c))

    def ellipse(self, x0, y0, x1, y1, c) -> None:
        self._d.ellipse([x0, y0, x1, y1], fill=rgb(c))

    def paste(self, other: 'Sprite', x: int, y: int) -> None:
        self.img.alpha_composite(other.img, (x, y))

    # ---- effects
    def outline(self, c, diagonal: bool = False) -> None:
        """Surround every opaque pixel with a 1 px outline on transparent neighbours."""
        c = rgb(c)
        src = self.img.copy()
        for y in range(self.h):
            for x in range(self.w):
                if src.getpixel((x, y))[3] > 0:
                    continue
                nbrs = [(x + 1, y), (x - 1, y), (x, y + 1), (x, y - 1)]
                if diagonal:
                    nbrs += [(x + 1, y + 1), (x - 1, y - 1), (x + 1, y - 1), (x - 1, y + 1)]
                if any(0 <= nx < self.w and 0 <= ny < self.h and src.getpixel((nx, ny))[3] > 0 for nx, ny in nbrs):
                    self.img.putpixel((x, y), c)

    def shade_edges(self, light, dark, only_inside: bool = True) -> None:
        """Lighten the top/left rim and darken the bottom/right rim of the opaque silhouette (a cheap bevel)."""
        src = self.img.copy()
        light, dark = rgb(light), rgb(dark)
        for y in range(self.h):
            for x in range(self.w):
                if src.getpixel((x, y))[3] == 0:
                    continue
                up = y == 0 or src.getpixel((x, y - 1))[3] == 0
                left = x == 0 or src.getpixel((x - 1, y))[3] == 0
                down = y == self.h - 1 or src.getpixel((x, y + 1))[3] == 0
                right = x == self.w - 1 or src.getpixel((x + 1, y))[3] == 0
                if up or left:
                    self.img.putpixel((x, y), light)
                elif down or right:
                    self.img.putpixel((x, y), dark)

    def recolor(self, mapping: dict) -> None:
        for y in range(self.h):
            for x in range(self.w):
                p = self.img.getpixel((x, y))
                if p in mapping:
                    self.img.putpixel((x, y), rgb(mapping[p]))

    def mirror(self) -> 'Sprite':
        s = Sprite(self.w, self.h)
        s.img = self.img.transpose(Image.FLIP_LEFT_RIGHT)
        s._d = ImageDraw.Draw(s.img)
        return s

    def save(self, path: str) -> None:
        os.makedirs(os.path.dirname(path), exist_ok=True)
        self.img.save(path, optimize=True)


def noise_fill(s: Sprite, x0, y0, x1, y1, colors, weights=None, seed=1) -> None:
    rnd = random.Random(seed)
    for y in range(y0, y1 + 1):
        for x in range(x0, x1 + 1):
            s.put(x, y, rnd.choices(colors, weights=weights)[0])


def contact_sheet(sprites: dict, scale: int = 6, cols: int = 8, bg=(46, 52, 62, 255), label: bool = True) -> Image.Image:
    items = list(sprites.items())
    cw = max(s.w for _, s in items) * scale + 14
    ch = max(s.h for _, s in items) * scale + (16 if label else 6)
    rows = (len(items) + cols - 1) // cols
    sheet = Image.new('RGBA', (cols * cw, rows * ch), bg)
    d = ImageDraw.Draw(sheet)
    for i, (name, s) in enumerate(items):
        big = s.img.resize((s.w * scale, s.h * scale), Image.NEAREST)
        x, y = (i % cols) * cw + 7, (i // cols) * ch + 4
        sheet.alpha_composite(big, (x, y))
        if label:
            d.text((x, y + s.h * scale + 1), name[:20], fill=(215, 222, 232, 255))
    return sheet


def preview_dir() -> str:
    """Where the developer previews (contact sheets, enlarged sprites) are written: UC_ART_PREVIEW_DIR, else the system temp directory."""
    return os.environ.get("UC_ART_PREVIEW_DIR") or tempfile.gettempdir()
