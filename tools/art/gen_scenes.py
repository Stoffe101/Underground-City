"""City banners for the first-login screen: three 176x64 pixel-art skylines, one per city. Original work.

Ironhaven: chimneys, saw-tooth factory roofs, a gantry crane, a furnace glow.   Valewood: rolling vineyards, a bell tower, red roofs.
Blackwater: glass towers over a harbour with a crane and a ship, lights on the water.
All shapes are drawn from rectangles and lines with a seeded random number generator, so the banners are reproducible.
"""
from __future__ import annotations
import os, random
from PIL import Image

ASSETS = os.path.join(os.path.dirname(__file__), '..', '..', 'rpg-core', 'src', 'main', 'resources', 'assets', 'underground_city', 'textures', 'gui')
W, H = 176, 64


def c(h):
    h = h.lstrip('#')
    return (int(h[0:2], 16), int(h[2:4], 16), int(h[4:6], 16), 255)


def mix(a, b, t):
    return tuple(round(a[i] + (b[i] - a[i]) * t) for i in range(3)) + (255,)


class Canvas:
    def __init__(self):
        self.im = Image.new('RGBA', (W, H), (0, 0, 0, 255))

    def put(self, x, y, col):
        if 0 <= x < W and 0 <= y < H:
            self.im.putpixel((x, y), col)

    def rect(self, x0, y0, x1, y1, col):
        for y in range(y0, y1 + 1):
            for x in range(x0, x1 + 1):
                self.put(x, y, col)

    def line(self, x0, y0, x1, y1, col):
        dx, dy = abs(x1 - x0), -abs(y1 - y0)
        sx, sy = (1 if x0 < x1 else -1), (1 if y0 < y1 else -1)
        err = dx + dy
        while True:
            self.put(x0, y0, col)
            if x0 == x1 and y0 == y1:
                break
            e2 = 2 * err
            if e2 >= dy:
                err += dy; x0 += sx
            if e2 <= dx:
                err += dx; y0 += sy

    def sky(self, top, bottom, bands=6):
        """Banded sky with ordered dithering between bands."""
        bayer = [[0, 8, 2, 10], [12, 4, 14, 6], [3, 11, 1, 9], [15, 7, 13, 5]]
        for y in range(H):
            t = y / (H - 1) * (bands - 1)
            lo, frac = int(t), t - int(t)
            a, b = mix(top, bottom, lo / (bands - 1)), mix(top, bottom, min(bands - 1, lo + 1) / (bands - 1))
            for x in range(W):
                self.put(x, y, b if frac * 16 > bayer[y % 4][x % 4] else a)

    def save(self, name):
        self.im.save(os.path.join(ASSETS, name))


def windows(cv, x0, y0, x1, y1, lit, dark, rnd, step_x=3, step_y=3, chance=0.45):
    for y in range(y0 + 2, y1 - 1, step_y):
        for x in range(x0 + 2, x1 - 1, step_x):
            cv.put(x, y, lit if rnd.random() < chance else dark)


def ironhaven():
    rnd = random.Random(11)
    cv = Canvas()
    cv.sky(c('#2b3550'), c('#e08a4e'), bands=7)
    # smog band and far factory silhouettes
    far = c('#4a4258')
    for x in range(0, W, 14):
        h = 10 + rnd.randint(0, 9)
        cv.rect(x, 46 - h, x + 11, 46, far)
        for sx in range(x, x + 12, 4):             # saw-tooth roof
            cv.line(sx, 46 - h, sx + 3, 46 - h - 3, far)
            cv.line(sx + 3, 46 - h - 3, sx + 3, 46 - h, far)
    # chimneys with smoke
    for (cx, top, w) in ((26, 8, 5), (58, 14, 5), (128, 6, 6), (152, 16, 5)):
        cv.rect(cx, top, cx + w, 50, c('#3a3340'))
        cv.rect(cx, top, cx + 1, 50, c('#5a4f60'))
        cv.rect(cx, top, cx + w, top + 1, c('#e06a3a'))
        for k in range(5):
            px, py = cx + w // 2 + rnd.randint(-2, 2) + k, top - 2 - k * 3
            for dx in range(-2, 3):
                cv.put(px + dx, py, mix(c('#9a8f9c'), c('#e08a4e'), 0.2 + k * 0.12))
    # near factory blocks
    near = c('#2a2631')
    for (x0, x1, h) in ((0, 30, 20), (34, 70, 26), (78, 112, 18), (116, 176, 24)):
        cv.rect(x0, 54 - h, x1, 56, near)
        cv.rect(x0, 54 - h, x1, 54 - h, c('#4b4152'))
        for sx in range(x0, x1 - 4, 8):
            for k in range(3):
                cv.put(sx + k, 54 - h - 1 - k, near); cv.put(sx + 4 - k, 54 - h - 1 - k, near)
            cv.line(sx, 54 - h, sx + 3, 54 - h - 3, near)
        windows(cv, x0, 54 - h, x1, 55, c('#ffb35a'), c('#3c3544'), rnd, 4, 4, 0.42)
    # furnace glow, crane, ground
    cv.rect(88, 46, 96, 54, c('#ff7a2e')); cv.rect(90, 48, 94, 54, c('#ffd27a'))
    for y in range(36, 47):
        for x in range(82, 103):
            if abs(x - 92) + (46 - y) * 0.8 < 9 and rnd.random() < 0.18:
                cv.put(x, y, c('#ff8a3a'))
    cv.line(150, 56, 150, 22, c('#1d1a22')); cv.line(150, 22, 120, 22, c('#1d1a22')); cv.line(150, 22, 166, 30, c('#1d1a22')); cv.line(121, 22, 121, 30, c('#1d1a22'))
    cv.line(150, 24, 158, 40, c('#2c2733')); cv.line(150, 24, 142, 40, c('#2c2733'))
    cv.rect(0, 57, W, H, c('#17141b')); cv.rect(0, 57, W, 57, c('#3d3545'))
    for x in range(0, W, 6):
        cv.put(x, 60, c('#2c2733')); cv.put(x + 1, 60, c('#2c2733'))
    return cv


def valewood():
    rnd = random.Random(21)
    cv = Canvas()
    cv.sky(c('#6aa6d8'), c('#f6e2a8'), bands=6)
    # sun and clouds
    for dy in range(-4, 5):
        for dx in range(-4, 5):
            if dx * dx + dy * dy <= 16:
                cv.put(140 + dx, 14 + dy, c('#fff3c4'))
    for (cx, cy) in ((30, 12), (72, 8), (104, 18)):
        for k in range(10):
            cv.rect(cx + k, cy + (k % 3 == 1) * -1, cx + k + 3, cy + 2, c('#f4f7fb'))
    # far hills
    for x in range(W):
        top = 38 + int(5 * __import__('math').sin(x / 17.0)) + int(3 * __import__('math').sin(x / 7.0 + 1))
        for y in range(top, 64):
            cv.put(x, y, c('#8bb36b') if y < top + 6 else c('#7aa35c'))
    # near vineyard hill with rows
    for x in range(W):
        top = 46 + int(4 * __import__('math').sin(x / 23.0 + 2))
        for y in range(top, 64):
            cv.put(x, y, c('#5f9a4a'))
        for row in range(0, 9):
            y = top + 2 + row * 2
            if (x + row * 3) % 5 in (0, 1):
                cv.put(x, y, c('#3d7a3a')) if y < 64 else None
    # village: houses with red roofs, a bell tower, trees
    for (x0, w, h) in ((14, 16, 10), (36, 14, 8), (100, 18, 11), (128, 14, 9)):
        base = 52
        cv.rect(x0, base - h, x0 + w, base, c('#efe3c8'))
        cv.rect(x0, base - h, x0 + w, base - h, c('#fff6dd'))
        for k in range(w // 2 + 1):
            cv.line(x0 - 1 + k, base - h - k // 1 if k < 5 else base - h - 5, x0 - 1 + k, base - h, c('#b5472f'))
        for k in range(w // 2 + 1):
            cv.rect(x0 - 2 + k * 1, base - h - min(k, 5), x0 + w + 2 - k, base - h - min(k, 5), c('#c9573a'))
        cv.rect(x0 + 3, base - 6, x0 + 5, base - 3, c('#5b4a3a')); cv.rect(x0 + w - 6, base - 6, x0 + w - 4, base - 4, c('#6aa0c8'))
    cv.rect(70, 22, 76, 52, c('#e9ddc2')); cv.rect(70, 22, 71, 52, c('#fff4d8'))
    for k in range(7):
        cv.line(70 + k, 22 - k // 1 if k < 4 else 19, 70 + k, 22, c('#b5472f'))
    cv.rect(72, 18, 74, 14, c('#b5472f')); cv.line(73, 14, 73, 10, c('#d9b04a')); cv.line(72, 12, 74, 12, c('#d9b04a'))
    cv.rect(72, 28, 74, 33, c('#2d3a4a')); cv.put(73, 36, c('#e8b04a'))
    for (tx, ty) in ((52, 48), (88, 49), (164, 47), (6, 49), (116, 50)):
        for dy in range(-4, 3):
            for dx in range(-4, 5):
                if dx * dx + dy * dy * 1.4 <= 14:
                    cv.put(tx + dx, ty + dy, c('#3f8a3d') if (dx + dy) % 3 else c('#52a14a'))
        cv.rect(tx, ty + 3, tx, ty + 6, c('#5b4332'))
    cv.rect(0, 60, W, H, c('#4a8a3e'))
    return cv


def blackwater():
    rnd = random.Random(31)
    cv = Canvas()
    cv.sky(c('#16264f'), c('#4a9bd6'), bands=7)
    for _ in range(26):
        cv.put(rnd.randint(0, W - 1), rnd.randint(0, 18), c('#cfe8ff'))
    # distant towers
    for x in range(0, W, 9):
        h = 14 + rnd.randint(0, 14)
        cv.rect(x, 44 - h, x + 7, 44, c('#27456f'))
    # main glass towers
    palette = [c('#1c2e4d'), c('#223a5e'), c('#18273f')]
    x = 4
    while x < W - 10:
        w = rnd.choice((10, 12, 14)); h = rnd.randint(22, 42)
        col = rnd.choice(palette)
        cv.rect(x, 52 - h, x + w, 52, col)
        cv.rect(x, 52 - h, x + 1, 52, mix(col, c('#7fc4f2'), 0.35)); cv.rect(x, 52 - h, x + w, 52 - h, mix(col, c('#7fc4f2'), 0.5))
        windows(cv, x, 52 - h, x + w, 52, c('#bfe9ff'), mix(col, c('#0c1626'), 0.5), rnd, 2, 3, 0.4)
        if rnd.random() < 0.4:
            cv.line(x + w // 2, 52 - h, x + w // 2, 52 - h - 5, c('#6a7c96')); cv.put(x + w // 2, 52 - h - 6, c('#ff5a4a'))
        x += w + rnd.randint(1, 3)
    # harbour crane and ship
    cv.line(150, 53, 150, 30, c('#d9534a')); cv.line(150, 30, 126, 30, c('#d9534a')); cv.line(150, 30, 168, 36, c('#d9534a')); cv.line(128, 30, 128, 40, c('#9aa7b8'))
    cv.line(150, 32, 138, 30, c('#b4423b'))
    cv.rect(98, 48, 122, 52, c('#2d3b52')); cv.rect(100, 44, 112, 47, c('#e9edf2')); cv.rect(102, 41, 106, 43, c('#e9edf2')); cv.rect(98, 52, 122, 52, c('#d9534a'))
    # water with reflections
    for y in range(53, H):
        t = (y - 53) / (H - 54)
        base = mix(c('#1d4e86'), c('#0c2748'), t)
        for xx in range(W):
            cv.put(xx, y, base)
    for _ in range(70):
        xx, yy = rnd.randint(0, W - 1), rnd.randint(54, H - 1)
        cv.line(xx, yy, xx + rnd.randint(1, 4), yy, c('#7fc4f2') if rnd.random() < 0.5 else c('#bfe9ff'))
    for xx in range(4, W, 9):
        cv.line(xx, 54, xx, 60, mix(c('#bfe9ff'), c('#1d4e86'), 0.55))
    return cv


def build():
    os.makedirs(ASSETS, exist_ok=True)
    ironhaven().save('skyline_ironhaven.png')
    valewood().save('skyline_valewood.png')
    blackwater().save('skyline_blackwater.png')


if __name__ == '__main__':
    build()
    sheet = Image.new('RGBA', (W * 3 + 16, H + 8), (20, 28, 38, 255))
    for i, n in enumerate(('ironhaven', 'valewood', 'blackwater')):
        sheet.alpha_composite(Image.open(os.path.join(ASSETS, f'skyline_{n}.png')), (4 + i * (W + 4), 4))
    sheet.resize((sheet.width * 3, sheet.height * 3), Image.NEAREST).save('/tmp/claude-0/-home-user-Underground-City/85892c3f-5725-536f-8521-6d736810d423/scratchpad/skylines1.png')
