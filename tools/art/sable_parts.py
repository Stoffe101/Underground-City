"""Hand-drawn Sable Leaf parts that are not leaves: the flowering cola, the seedling, the stem and the nutrient stake."""
from uc_art import Sprite, rgb
from sable import LEAF, LEAF_DEEP, LEAF_YOUNG

PALETTE = {'o': rgb('#e9a458'), 'w': rgb('#fbe7b4'), 'g': LEAF_DEEP[1], 'G': LEAF[2], 'l': LEAF[3], 'h': LEAF_YOUNG[4],
           'd': LEAF_DEEP[0], 'p': rgb('#8a6fb0'), 'y': rgb('#e8d65a'), 'Y': rgb('#b9a53a'), 's': rgb('#6b7480')}

COLA = [
    "......o.o.......",
    ".......wl.......",
    "......GlGg......",
    ".....GlGGgg.....",
    "....oGGlGgGg....",
    "....GlGGGgGg....",
    ".....GGlGGgo....",
    "....oGlGGGgg....",
    ".....GGlGgGg....",
    "......GGlGg.....",
    "......gGGg......",
    ".......gg.......",
    "......GlGg......",
    ".......Gg.......",
    ".......Gg.......",
    ".......gg.......",
]

SEEDLING = [
    "................",
    "................",
    "................",
    "................",
    "................",
    "....l.....l.....",
    "...lGl...lGl....",
    "....Gl...lg.....",
    "...l.Gl.lg.l....",
    "..lGl.Gdg.lGl...",
    "...lGlGdgGlg....",
    "....lGGdgGg.....",
    ".....lgdg.......",
    "......gdg.......",
    "......gdg.......",
    "......gdg.......",
]

STAKE = [
    "................",
    "................",
    "................",
    "................",
    "................",
    "................",
    "................",
    "................",
    "................",
    "................",
    "......yy........",
    "......Yy........",
    "......Yy........",
    "......sd........",
    "......sd........",
    "......sd........",
]


def cola() -> Sprite:
    return Sprite.from_rows(COLA, PALETTE)


def seedling() -> Sprite:
    return Sprite.from_rows(SEEDLING, PALETTE)


def stake() -> Sprite:
    return Sprite.from_rows(STAKE, PALETTE)


def stem() -> Sprite:
    """Ridged olive-green stem strip with a darker node band every eight pixels."""
    s = Sprite(16, 16)
    from uc_art import rgb as _rgb
    base, dark, light, deep = _rgb('#6c8f3c'), _rgb('#4a6b2b'), _rgb('#90b556'), _rgb('#35511f')
    cols = [dark, base, light, base, dark, deep, base, light]
    for y in range(16):
        for x in range(16):
            s.put(x, y, deep if y % 8 == 0 else cols[x % len(cols)])
    return s
