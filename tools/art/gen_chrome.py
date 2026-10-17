"""Interface chrome: the enamel panel, the recessed well, buttons, tabs and cards (9-slice textures). Original work.

Look: stamped municipal enamel on steel. Panels carry brass rivets, wells are recessed, buttons are raised plates with a
bright top edge; the one accent per state is drawn by the screen code so a single texture serves every colour scheme.
"""
from __future__ import annotations
import os, random
from PIL import Image

ASSETS = os.path.join(os.path.dirname(__file__), '..', '..', 'rpg-core', 'src', 'main', 'resources', 'assets', 'underground_city', 'textures', 'gui')


def c(h, a=255):
    h = h.lstrip('#')
    return (int(h[0:2], 16), int(h[2:4], 16), int(h[4:6], 16), a)


def lerp(a, b, t):
    return tuple(round(a[i] + (b[i] - a[i]) * t) for i in range(4))


def vgrad(im, x0, y0, x1, y1, top, bottom, noise=0, seed=3):
    rnd = random.Random(seed)
    for y in range(y0, y1 + 1):
        t = (y - y0) / max(1, y1 - y0)
        base = lerp(top, bottom, t)
        for x in range(x0, x1 + 1):
            n = rnd.randint(-noise, noise) if noise else 0
            im.putpixel((x, y), (max(0, min(255, base[0] + n)), max(0, min(255, base[1] + n)), max(0, min(255, base[2] + n)), 255))


def rect_border(im, x0, y0, x1, y1, col):
    for x in range(x0, x1 + 1):
        im.putpixel((x, y0), col)
        im.putpixel((x, y1), col)
    for y in range(y0, y1 + 1):
        im.putpixel((x0, y), col)
        im.putpixel((x1, y), col)


def chamfer(im, w, h, n=1):
    for i in range(n):
        for (x, y) in ((i, 0), (0, i), (w - 1 - i, 0), (w - 1, i), (i, h - 1), (0, h - 1 - i), (w - 1 - i, h - 1), (w - 1, h - 1 - i)):
            pass
    for (x, y) in ((0, 0), (w - 1, 0), (0, h - 1), (w - 1, h - 1)):
        im.putpixel((x, y), (0, 0, 0, 0))


def panel(size=48):
    im = Image.new('RGBA', (size, size), (0, 0, 0, 0))
    vgrad(im, 0, 0, size - 1, size - 1, c('#1b2b3b'), c('#121d2a'), noise=2)
    rect_border(im, 0, 0, size - 1, size - 1, c('#06090e'))
    rect_border(im, 1, 1, size - 2, size - 2, c('#34506a'))
    for x in range(2, size - 2):
        im.putpixel((x, 2), c('#4f7088'))
    for y in range(2, size - 2):
        im.putpixel((2, y), c('#436079'))
    for x in range(2, size - 2):
        im.putpixel((x, size - 3), c('#0e1823'))
    for y in range(2, size - 2):
        im.putpixel((size - 3, y), c('#0e1823'))
    # corner rivets
    for (rx, ry) in ((4, 4), (size - 6, 4), (4, size - 6), (size - 6, size - 6)):
        im.putpixel((rx, ry), c('#e8c279'))
        im.putpixel((rx + 1, ry), c('#b98a3e'))
        im.putpixel((rx, ry + 1), c('#b98a3e'))
        im.putpixel((rx + 1, ry + 1), c('#6e4f22'))
    chamfer(im, size, size)
    return im


def inset(size=24):
    im = Image.new('RGBA', (size, size), (0, 0, 0, 0))
    vgrad(im, 0, 0, size - 1, size - 1, c('#0d1620'), c('#0b131b'), noise=1, seed=9)
    for x in range(size):
        im.putpixel((x, 0), c('#05080c'))
        im.putpixel((x, size - 1), c('#2a4054'))
    for y in range(size):
        im.putpixel((0, y), c('#05080c'))
        im.putpixel((size - 1, y), c('#233649'))
    for x in range(1, size - 1):
        im.putpixel((x, 1), c('#0a1119'))
    return im


def button(top, bottom, hi, lo, edge, w=24, h=20, pressed=False):
    im = Image.new('RGBA', (w, h), (0, 0, 0, 0))
    if pressed:
        top, bottom = bottom, lerp(bottom, c('#000000'), 0.25)
    vgrad(im, 0, 0, w - 1, h - 1, top, bottom)
    rect_border(im, 0, 0, w - 1, h - 1, edge)
    if not pressed:
        for x in range(1, w - 1):
            im.putpixel((x, 1), hi)
            im.putpixel((x, h - 2), lo)
        for y in range(1, h - 1):
            im.putpixel((1, y), lerp(hi, top, 0.5))
            im.putpixel((w - 2, y), lerp(lo, bottom, 0.4))
    else:
        for x in range(1, w - 1):
            im.putpixel((x, 1), lo)
    chamfer(im, w, h)
    return im


def tab(active, hover, w=28, h=18):
    im = Image.new('RGBA', (w, h), (0, 0, 0, 0))
    if active:
        vgrad(im, 0, 0, w - 1, h - 1, c('#2a4258'), c('#1b2d3e'))
        edge = c('#06090e')
    elif hover:
        vgrad(im, 0, 0, w - 1, h - 1, c('#22364a'), c('#17232f'))
        edge = c('#06090e')
    else:
        vgrad(im, 0, 0, w - 1, h - 1, c('#16222f'), c('#0f1822'))
        edge = c('#06090e')
    rect_border(im, 0, 0, w - 1, h - 1, edge)
    for x in range(1, w - 1):
        im.putpixel((x, 1), c('#54748e') if active else c('#3a566d') if hover else c('#27394a'))
    if active:
        for x in range(1, w - 1):
            im.putpixel((x, h - 1), c('#1b2d3e'))        # opens into the panel below
    chamfer(im, w, h)
    return im


def card(kind, w=24, h=16):
    im = Image.new('RGBA', (w, h), (0, 0, 0, 0))
    fills = {'normal': (c('#17283a'), c('#122030')), 'hover': (c('#1f3a50'), c('#17293b')), 'selected': (c('#223c52'), c('#192c3f')), 'disabled': (c('#111a24'), c('#0e1620'))}
    edges = {'normal': c('#2c4a63'), 'hover': c('#55d3bc'), 'selected': c('#e8b04a'), 'disabled': c('#1d2b39')}
    top, bottom = fills[kind]
    vgrad(im, 0, 0, w - 1, h - 1, top, bottom)
    rect_border(im, 0, 0, w - 1, h - 1, edges[kind])
    if kind in ('hover', 'selected'):
        rect_border(im, 1, 1, w - 2, h - 2, lerp(edges[kind], top, 0.6))
    else:
        for x in range(1, w - 1):
            im.putpixel((x, 1), c('#223a50') if kind == 'normal' else c('#141f2b'))
    chamfer(im, w, h)
    return im


def widgets():
    sheet = Image.new('RGBA', (128, 96), (0, 0, 0, 0))
    def put(im, x, y):
        sheet.alpha_composite(im, (x, y))
    neutral = dict(top=c('#2f465c'), bottom=c('#24374a'), hi=c('#58788f'), lo=c('#16222e'), edge=c('#070b10'))
    put(button(**neutral), 0, 0)
    put(button(top=c('#3a566e'), bottom=c('#2b4358'), hi=c('#7fd9c6'), lo=c('#16222e'), edge=c('#070b10')), 24, 0)
    put(button(pressed=True, **neutral), 48, 0)
    put(button(top=c('#1b2733'), bottom=c('#17212b'), hi=c('#26394a'), lo=c('#111a23'), edge=c('#0b1016')), 72, 0)
    amber = dict(top=c('#c1892f'), bottom=c('#946420'), hi=c('#f2cb78'), lo=c('#5c3d12'), edge=c('#2a1c06'))
    put(button(**amber), 0, 20)
    put(button(top=c('#d89a38'), bottom=c('#a8731f'), hi=c('#ffe0a0'), lo=c('#5c3d12'), edge=c('#2a1c06')), 24, 20)
    put(button(pressed=True, **amber), 48, 20)
    put(button(top=c('#3a3328'), bottom=c('#2f2a22'), hi=c('#4b4333'), lo=c('#231f19'), edge=c('#14110d')), 72, 20)
    red = dict(top=c('#b4453e'), bottom=c('#862f2b'), hi=c('#ee8f82'), lo=c('#501a18'), edge=c('#240a09'))
    put(button(**red), 0, 40)
    put(button(top=c('#c9564d'), bottom=c('#9a3732'), hi=c('#ffb0a2'), lo=c('#501a18'), edge=c('#240a09')), 24, 40)
    put(button(pressed=True, **red), 48, 40)
    put(button(top=c('#3a2a2a'), bottom=c('#2f2222'), hi=c('#4b3636'), lo=c('#231919'), edge=c('#140d0d')), 72, 40)
    put(tab(False, False), 0, 60)
    put(tab(False, True), 28, 60)
    put(tab(True, False), 56, 60)
    for i, k in enumerate(('normal', 'hover', 'selected', 'disabled')):
        put(card(k), i * 24, 80)
    return sheet


def build():
    os.makedirs(ASSETS, exist_ok=True)
    panel().save(os.path.join(ASSETS, 'panel.png'))
    inset().save(os.path.join(ASSETS, 'inset.png'))
    widgets().save(os.path.join(ASSETS, 'widgets.png'))


if __name__ == '__main__':
    build()
    # preview: a panel at 3x with an inset, buttons and cards stretched 9-slice style
    prev = Image.new('RGBA', (360, 200), (30, 36, 44, 255))
    p = panel(48)
    big = Image.new('RGBA', (340, 180), (0, 0, 0, 0))
    def nine(src, w, h, corner):
        out = Image.new('RGBA', (w, h), (0, 0, 0, 0))
        sw, sh = src.size
        parts = [((0, 0, corner, corner), (0, 0)), ((sw - corner, 0, sw, corner), (w - corner, 0)), ((0, sh - corner, corner, sh), (0, h - corner)), ((sw - corner, sh - corner, sw, sh), (w - corner, h - corner))]
        for box, pos in parts:
            out.alpha_composite(src.crop(box), pos)
        edges = [((corner, 0, sw - corner, corner), (corner, 0, w - 2 * corner, corner)), ((corner, sh - corner, sw - corner, sh), (corner, h - corner, w - 2 * corner, corner)),
                 ((0, corner, corner, sh - corner), (0, corner, corner, h - 2 * corner)), ((sw - corner, corner, sw, sh - corner), (w - corner, corner, corner, h - 2 * corner)),
                 ((corner, corner, sw - corner, sh - corner), (corner, corner, w - 2 * corner, h - 2 * corner))]
        for box, (x, y, ww, hh) in edges:
            out.alpha_composite(src.crop(box).resize((ww, hh), Image.NEAREST), (x, y))
        return out
    prev.alpha_composite(nine(p, 340, 180, 8), (10, 10))
    prev.alpha_composite(nine(inset(), 160, 90, 4), (24, 24))
    sheet = widgets()
    x = 200
    for i in range(4):
        prev.alpha_composite(nine(sheet.crop((i * 24, 0, i * 24 + 24, 20)), 60, 20, 4), (190 + (i % 2) * 70, 24 + (i // 2) * 26))
    for i in range(4):
        prev.alpha_composite(nine(sheet.crop((i * 24, 20, i * 24 + 24, 40)), 60, 20, 4), (190 + (i % 2) * 70, 80 + (i // 2) * 26))
    for i in range(3):
        prev.alpha_composite(sheet.crop((i * 28, 60, i * 28 + 28, 78)), (190 + i * 30, 140))
    prev.alpha_composite(nine(sheet.crop((0, 80, 24, 96)), 60, 24, 3), (24, 130))
    prev.alpha_composite(nine(sheet.crop((24, 80, 48, 96)), 60, 24, 3), (90, 130))
    prev.resize((prev.width * 3, prev.height * 3), Image.NEAREST).save('/tmp/claude-0/-home-user-Underground-City/85892c3f-5725-536f-8521-6d736810d423/scratchpad/chrome1.png')
