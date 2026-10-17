"""Shaded primitives for item art: every shape lights from the upper left and uses a five-step ramp (dark -> light)."""
from __future__ import annotations
import math
from uc_art import Sprite, rgb


def bevel_rect(s: Sprite, x0, y0, x1, y1, r, strong: bool = True):
    """A lit box face: base fill, bright top/left rim, dark bottom/right rim."""
    s.rect(x0, y0, x1, y1, r[2])
    for x in range(x0, x1 + 1):
        s.put(x, y0, r[3])
        s.put(x, y1, r[1])
    for y in range(y0, y1 + 1):
        s.put(x0, y, r[3])
        s.put(x1, y, r[1])
    s.put(x0, y0, r[4] if strong else r[3])
    s.put(x1, y1, r[0])
    s.put(x1, y0, r[2])
    s.put(x0, y1, r[2])


def cylinder_v(s: Sprite, x0, x1, y0, y1, r):
    """A vertical cylinder: light on the left third, shade on the right."""
    w = x1 - x0 + 1
    for i in range(w):
        t = i / max(1, w - 1)
        col = r[4] if t < 0.2 else r[3] if t < 0.42 else r[2] if t < 0.65 else r[1] if t < 0.88 else r[0]
        for y in range(y0, y1 + 1):
            s.put(x0 + i, y, col)


def cylinder_h(s: Sprite, x0, x1, y0, y1, r):
    h = y1 - y0 + 1
    for j in range(h):
        t = j / max(1, h - 1)
        col = r[4] if t < 0.2 else r[3] if t < 0.42 else r[2] if t < 0.65 else r[1] if t < 0.88 else r[0]
        for x in range(x0, x1 + 1):
            s.put(x, y0 + j, col)


def disc(s: Sprite, cx, cy, rad, r, hole: float = 0.0):
    """A disc/sphere lit from the upper left. cx, cy may be .5 for even sizes."""
    for y in range(int(cy - rad - 1), int(cy + rad + 2)):
        for x in range(int(cx - rad - 1), int(cx + rad + 2)):
            dx, dy = x + 0.5 - (cx + 0.5), y + 0.5 - (cy + 0.5)
            d = math.hypot(dx, dy)
            if d > rad or d < hole:
                continue
            lit = (-dx * 0.62 - dy * 0.78) / max(rad, 1)       # -1..1, positive toward the light
            idx = 2 + round(lit * 2.0)
            s.put(x, y, r[max(0, min(4, idx))])


def gear(s: Sprite, cx, cy, r_out, r_in, teeth, r, hole=1.2):
    for y in range(int(cy - r_out - 2), int(cy + r_out + 3)):
        for x in range(int(cx - r_out - 2), int(cx + r_out + 3)):
            dx, dy = x + 0.5 - (cx + 0.5), y + 0.5 - (cy + 0.5)
            d = math.hypot(dx, dy)
            ang = math.atan2(dy, dx)
            tooth = (math.cos(ang * teeth) > 0.15)
            lim = r_out if tooth else r_in
            if d <= lim and d >= hole:
                lit = (-dx * 0.62 - dy * 0.78) / max(r_out, 1)
                s.put(x, y, r[max(0, min(4, 2 + round(lit * 2.0)))])


def line_shaded(s: Sprite, x0, y0, x1, y1, r, width=1):
    s.line(x0, y0, x1, y1, r[2])
    if width > 1:
        s.line(x0 + 1, y0, x1 + 1, y1, r[1])
    s.put(x0, y0, r[3])


def highlight(s: Sprite, pts, color):
    for x, y in pts:
        s.put(x, y, color)


def dither(s: Sprite, x0, y0, x1, y1, a, b):
    for y in range(y0, y1 + 1):
        for x in range(x0, x1 + 1):
            if s.solid(x, y):
                s.put(x, y, a if (x + y) % 2 == 0 else b)
