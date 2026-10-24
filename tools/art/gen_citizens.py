"""Original skins for the citizens of the three cities (64 x 64, the classic player-model layout), drawn from rectangles and character maps.

No third-party pixel is read and no player's skin is used. Every skin is a deterministic combination of a skin tone, a hairstyle, an outfit for one of the city jobs and a
few small details (a tie, a badge, a hi-vis stripe), so two citizens in a street rarely look alike and the same id always draws the same person. The outfits say what a
person does at a glance: a hard hat and an orange vest on the docks, a straw hat in the fields, a dark cap and a badge on the gate.
"""
from __future__ import annotations

import random
from pathlib import Path

from PIL import Image

ASSETS = Path("rpg-core/src/main/resources/assets/underground_city/textures/entity/citizen")

# (x0, z0, x1, z1) of each face of a box in the 64 x 64 sheet: top, bottom, right, front, left, back
def faces(u: int, v: int, w: int, h: int, d: int) -> dict[str, tuple[int, int, int, int]]:
    return {
        "top": (u + d, v, u + d + w, v + d), "bottom": (u + d + w, v, u + d + 2 * w, v + d),
        "right": (u, v + d, u + d, v + d + h), "front": (u + d, v + d, u + d + w, v + d + h),
        "left": (u + d + w, v + d, u + 2 * d + w, v + d + h), "back": (u + 2 * d + w, v + d, u + 2 * d + 2 * w, v + d + h),
    }


HEAD, HAT = faces(0, 0, 8, 8, 8), faces(32, 0, 8, 8, 8)
BODY, JACKET = faces(16, 16, 8, 12, 4), faces(16, 32, 8, 12, 4)
R_ARM, R_SLEEVE = faces(40, 16, 4, 12, 4), faces(40, 32, 4, 12, 4)
L_ARM, L_SLEEVE = faces(32, 48, 4, 12, 4), faces(48, 48, 4, 12, 4)
R_LEG, R_PANT = faces(0, 16, 4, 12, 4), faces(0, 32, 4, 12, 4)
L_LEG, L_PANT = faces(16, 48, 4, 12, 4), faces(0, 48, 4, 12, 4)

SKIN = [(244, 208, 178), (232, 184, 148), (206, 150, 112), (170, 112, 78), (128, 82, 56), (92, 58, 40)]
HAIR = [(28, 22, 20), (62, 42, 28), (104, 70, 40), (158, 112, 56), (204, 170, 96), (150, 60, 40), (120, 120, 124), (226, 226, 222)]
EYES = [(40, 70, 120), (60, 100, 60), (80, 56, 36), (44, 44, 52)]

# outfit: name -> (shirt, trousers, shoes, extras); colours are municipal enamel and workwear, never neon
OUTFITS = {
    "dockhand":   dict(shirt=(70, 92, 120), pants=(54, 60, 72), shoes=(40, 34, 30), vest=(232, 120, 40), hat="hardhat"),
    "foundry":    dict(shirt=(110, 104, 98), pants=(58, 56, 60), shoes=(36, 30, 28), apron=(96, 70, 48), hat="cap"),
    "office":     dict(shirt=(226, 228, 232), pants=(50, 58, 82), shoes=(30, 28, 30), tie=(160, 40, 48)),
    "shopkeeper": dict(shirt=(186, 120, 96), pants=(70, 62, 56), shoes=(52, 40, 32), apron=(232, 226, 214)),
    "farmer":     dict(shirt=(172, 66, 56), pants=(70, 86, 120), shoes=(86, 62, 40), plaid=True, hat="straw"),
    "vintner":    dict(shirt=(226, 218, 200), pants=(84, 60, 70), shoes=(64, 44, 34), vest=(96, 40, 66)),
    "technician": dict(shirt=(48, 58, 72), pants=(34, 38, 46), shoes=(24, 24, 28), hood=True),
    "resident":   dict(shirt=(96, 150, 140), pants=(72, 80, 96), shoes=(60, 48, 40)),
    "resident2":  dict(shirt=(190, 160, 90), pants=(60, 72, 66), shoes=(70, 54, 40)),
    "courier":    dict(shirt=(60, 130, 170), pants=(42, 46, 56), shoes=(30, 30, 34), cap_color=(60, 130, 170), hat="cap"),
    "guard":      dict(shirt=(40, 58, 96), pants=(30, 40, 66), shoes=(20, 20, 24), badge=(232, 190, 80), hat="cap", cap_color=(30, 44, 78)),
    "mechanic":   dict(shirt=(60, 98, 86), pants=(60, 98, 86), shoes=(40, 34, 30), stain=True),
    # D-162: more people need more clothes (indexes 12 to 19)
    "suit":       dict(shirt=(46, 50, 62), pants=(40, 44, 56), shoes=(22, 20, 22), collar=(226, 228, 232), tie=(120, 36, 44)),
    "casual":     dict(shirt=(120, 128, 140), pants=(58, 74, 112), shoes=(220, 220, 214), hood=True),
    "elder":      dict(shirt=(150, 120, 96), pants=(92, 88, 84), shoes=(70, 52, 40), cardigan=(110, 84, 70), grey=True),
    "warehouse":  dict(shirt=(104, 108, 112), pants=(60, 62, 70), shoes=(40, 34, 30), vest=(200, 220, 60), hat="cap", cap_color=(60, 62, 70)),
    "cafe":       dict(shirt=(36, 36, 40), pants=(44, 44, 50), shoes=(30, 28, 30), apron=(130, 92, 60)),
    "nightout":   dict(shirt=(170, 40, 70), pants=(30, 30, 36), shoes=(24, 22, 26), jacket=(28, 28, 32)),
    "nightout2":  dict(shirt=(60, 44, 120), pants=(60, 44, 120), shoes=(26, 24, 30), sparkle=(230, 214, 160)),
    "fieldhand":  dict(shirt=(214, 196, 160), pants=(70, 92, 70), shoes=(86, 62, 40), overalls=(70, 92, 70), hat="straw"),
}

# the colourings an outfit comes in (ten per outfit, the first is the base): uniforms barely change, everyday clothes a lot
EVERYDAY = [(96, 150, 140), (190, 160, 90), (150, 70, 70), (70, 90, 150), (120, 140, 90), (200, 200, 196), (60, 60, 66), (170, 110, 150), (220, 150, 90), (90, 120, 120)]
TROUSERS = [(72, 80, 96), (60, 72, 66), (50, 58, 82), (90, 74, 60), (40, 40, 46), (110, 104, 92), (58, 74, 112), (76, 60, 52), (96, 96, 104), (64, 52, 70)]
UNIFORM = {"guard", "courier", "dockhand", "warehouse", "technician", "foundry", "mechanic"}
NIGHT = [(170, 40, 70), (40, 120, 160), (200, 160, 60), (130, 40, 140), (220, 220, 224), (30, 140, 110), (210, 90, 50), (90, 90, 200), (190, 60, 110), (60, 60, 64)]


def coloured(name: str, variant: int, rnd: random.Random) -> dict:
    """An outfit in one of its colourings: everyday clothes take a shirt and trousers from the palettes, night clothes a bright colour, uniforms only shade a little."""
    outfit = dict(OUTFITS[name])
    if variant == 0:
        return outfit
    shade = lambda c, k: tuple(max(0, min(255, int(v * k))) for v in c)
    if name in UNIFORM:
        k = 0.88 + 0.03 * (variant % 5)
        outfit["shirt"], outfit["pants"] = shade(outfit["shirt"], k), shade(outfit["pants"], k)
    elif name in ("nightout", "nightout2"):
        outfit["shirt"] = NIGHT[variant % len(NIGHT)]
        if name == "nightout2":
            outfit["pants"] = outfit["shirt"]
    elif name in ("suit", "office"):
        outfit["shirt"] = [(46, 50, 62), (60, 60, 64), (36, 44, 70), (70, 64, 58), (226, 228, 232), (180, 196, 214)][variant % 6] if name == "suit" else [(226, 228, 232), (180, 196, 214), (232, 222, 200), (200, 214, 200)][variant % 4]
        outfit["tie"] = [(120, 36, 44), (40, 60, 120), (60, 90, 60), (120, 100, 40), (90, 40, 90)][variant % 5]
    else:
        outfit["shirt"] = EVERYDAY[(variant * 3 + len(name)) % len(EVERYDAY)]
        outfit["pants"] = TROUSERS[(variant * 7 + len(name)) % len(TROUSERS)]
    return outfit


def fill(img: Image.Image, rect, color) -> None:
    x0, z0, x1, z1 = rect
    for x in range(x0, x1):
        for z in range(z0, z1):
            img.putpixel((x, z), color + (255,))


def box(img: Image.Image, parts: dict, color, shade: float = 1.0) -> None:
    """Fill a whole box; the sides a little darker than the front and the bottom darker still, so the model reads as solid."""
    tint = lambda c, k: tuple(max(0, min(255, int(v * k))) for v in c)
    for name, rect in parts.items():
        fill(img, rect, tint(color, {"top": 1.08, "front": 1.0, "back": 0.9, "left": 0.94, "right": 0.94, "bottom": 0.78}[name] * shade))


def draw(spec: dict, rnd: random.Random) -> Image.Image:
    img = Image.new("RGBA", (64, 64), (0, 0, 0, 0))
    skin, hair, eyes = spec["skin"], spec["hair"], spec["eyes"]
    outfit = coloured(spec["outfit"], spec.get("variant", 0), rnd)
    # body parts
    box(img, HEAD, skin)
    box(img, R_ARM, skin); box(img, L_ARM, skin)
    box(img, BODY, outfit["shirt"])
    box(img, R_LEG, outfit["pants"]); box(img, L_LEG, outfit["pants"])
    # sleeves: short or long
    long_sleeves = spec["sleeves"] == "long"
    for arm in (R_ARM, L_ARM):
        sleeve = {k: (v[0], v[1], v[2], v[3] if long_sleeves else v[1] + 5) for k, v in arm.items()}
        for key in ("front", "back", "left", "right"):
            fill(img, sleeve[key], outfit["shirt"])
    # shoes: the bottom three rows of each leg
    for leg in (R_LEG, L_LEG):
        for key in ("front", "back", "left", "right"):
            x0, z0, x1, z1 = leg[key]
            fill(img, (x0, z1 - 3, x1, z1), outfit["shoes"])
    # face: eyes, brows, mouth
    fx0, fz0, fx1, fz1 = HEAD["front"]
    img.putpixel((fx0 + 1, fz0 + 4), (255, 255, 255, 255)); img.putpixel((fx0 + 2, fz0 + 4), eyes + (255,))
    img.putpixel((fx0 + 6, fz0 + 4), (255, 255, 255, 255)); img.putpixel((fx0 + 5, fz0 + 4), eyes + (255,))
    brow = tuple(max(0, v - 30) for v in hair)
    for x in (1, 2, 5, 6):
        img.putpixel((fx0 + x, fz0 + 3), brow + (255,))
    mouth = tuple(max(0, int(v * 0.62)) for v in skin)
    for x in (3, 4):
        img.putpixel((fx0 + x, fz0 + 6), mouth + (255,))
    # hair
    style = spec["style"]
    if style != "bald":
        for key in ("top", "back", "left", "right"):
            x0, z0, x1, z1 = HEAD[key]
            drop = {"short": 2, "long": 7, "bob": 5, "buzz": 1, "crop": 3}[style] if key != "top" else 99
            fill(img, (x0, z0, x1, min(z1, z0 + drop)), hair)
        fx0, fz0, fx1, fz1 = HEAD["front"]
        fringe = {"short": 2, "long": 2, "bob": 2, "buzz": 1, "crop": 1}[style]
        fill(img, (fx0, fz0, fx1, fz0 + fringe), hair)
        if style in ("long", "bob"):
            for key in ("left", "right"):
                x0, z0, x1, z1 = HEAD[key]
                fill(img, (x0, z0, x0 + 1, min(z1, z0 + (7 if style == "long" else 5))), hair)
                fill(img, (x1 - 1, z0, x1, min(z1, z0 + (7 if style == "long" else 5))), hair)
    # outfit details on the front of the body
    bx0, bz0, bx1, bz1 = BODY["front"]
    if "vest" in outfit:
        fill(img, (bx0, bz0, bx0 + 2, bz1), outfit["vest"]); fill(img, (bx1 - 2, bz0, bx1, bz1), outfit["vest"])
        if spec["outfit"] == "dockhand":
            fill(img, (bx0, bz0 + 7, bx1, bz0 + 8), (240, 240, 235))   # reflective stripe
    if "apron" in outfit:
        fill(img, (bx0 + 1, bz0 + 3, bx1 - 1, bz1), outfit["apron"])
        fill(img, (bx0 + 3, bz0 + 4, bx0 + 5, bz0 + 6), tuple(max(0, v - 28) for v in outfit["apron"]))   # a pocket
    if "collar" in outfit:
        fill(img, (bx0 + 2, bz0, bx1 - 2, bz0 + 8), outfit["collar"])   # a shirt under an open jacket
    if "tie" in outfit:
        fill(img, (bx0 + 3, bz0, bx0 + 5, bz0 + 7), outfit["tie"])
    if "jacket" in outfit:
        fill(img, (bx0, bz0, bx0 + 2, bz1), outfit["jacket"]); fill(img, (bx1 - 2, bz0, bx1, bz1), outfit["jacket"])
        for arm in (R_ARM, L_ARM):
            for key in ("front", "back", "left", "right"):
                fill(img, arm[key], outfit["jacket"])
    if "cardigan" in outfit:
        fill(img, (bx0, bz0, bx0 + 3, bz1), outfit["cardigan"]); fill(img, (bx1 - 3, bz0, bx1, bz1), outfit["cardigan"])
    if "overalls" in outfit:
        fill(img, (bx0 + 1, bz0 + 4, bx1 - 1, bz1), outfit["overalls"])
        fill(img, (bx0 + 1, bz0, bx0 + 2, bz0 + 4), outfit["overalls"]); fill(img, (bx1 - 2, bz0, bx1 - 1, bz0 + 4), outfit["overalls"])
    if "sparkle" in outfit:
        for _ in range(5):
            img.putpixel((rnd.randint(bx0, bx1 - 1), rnd.randint(bz0, bz1 - 1)), outfit["sparkle"] + (255,))
    if outfit.get("plaid"):
        for z in range(bz0, bz1, 3):
            fill(img, (bx0, z, bx1, z + 1), tuple(max(0, v - 38) for v in outfit["shirt"]))
        for x in range(bx0, bx1, 3):
            fill(img, (x, bz0, x + 1, bz1), tuple(max(0, v - 38) for v in outfit["shirt"]))
    if "badge" in outfit:
        fill(img, (bx0 + 5, bz0 + 3, bx0 + 7, bz0 + 5), outfit["badge"])
    if outfit.get("stain"):
        for _ in range(6):
            img.putpixel((rnd.randint(bx0, bx1 - 1), rnd.randint(bz0 + 2, bz1 - 1)), (40, 36, 30, 255))
    if outfit.get("hood"):
        for key in ("front", "back", "left", "right"):
            x0, z0, x1, z1 = HAT[key]
            fill(img, (x0, z0, x1, z0 + 2), outfit["shirt"])
        fx0, fz0, fx1, fz1 = HAT["front"]
        fill(img, (fx0, fz0, fx0 + 1, fz1), outfit["shirt"]); fill(img, (fx1 - 1, fz0, fx1, fz1), outfit["shirt"])
        fill(img, (HAT["top"][0], HAT["top"][1], HAT["top"][2], HAT["top"][3]), outfit["shirt"])
    # hats on the second layer
    hat = outfit.get("hat")
    if hat:
        color = {"hardhat": (240, 200, 60), "straw": (214, 184, 100), "cap": outfit.get("cap_color", (60, 60, 70))}[hat]
        fill(img, (HAT["top"][0], HAT["top"][1], HAT["top"][2], HAT["top"][3]), color)
        for key in ("front", "back", "left", "right"):
            x0, z0, x1, z1 = HAT[key]
            fill(img, (x0, z0, x1, z0 + (3 if hat != "straw" else 2)), color)
        if hat in ("straw",):
            for key in ("front", "back", "left", "right"):
                x0, z0, x1, z1 = HAT[key]
                fill(img, (x0, z0 + 2, x1, z0 + 3), tuple(max(0, v - 30) for v in color))
    # a few citizens wear glasses: a dark frame round each eye and across the bridge
    if spec["glasses"]:
        fx0, fz0, fx1, fz1 = HEAD["front"]
        for x in (0, 3, 4, 7):
            img.putpixel((fx0 + x, fz0 + 4), (60, 60, 66, 255))
    return img


def specs(count: int = 200) -> list[dict]:
    """Skin {@code i} wears outfit {@code i % 20} in colouring {@code i // 20} (PopulationPlan.OUTFITS and SKINS say the same)."""
    rnd = random.Random(2026_10_20)
    outfits = list(OUTFITS)
    out = []
    for index in range(count):
        outfit = outfits[index % len(outfits)]
        hair = rnd.choice(HAIR)
        if OUTFITS[outfit].get("grey"):
            hair = rnd.choice([(120, 120, 124), (226, 226, 222), (180, 180, 182)])
        out.append({"index": index, "outfit": outfit, "variant": index // len(outfits), "skin": rnd.choice(SKIN), "hair": hair, "eyes": rnd.choice(EYES),
                    "style": rnd.choice(["short", "long", "bob", "buzz", "crop", "bald"] if outfit not in ("guard", "dockhand") else ["short", "buzz", "crop", "bob"]),
                    "sleeves": rnd.choice(["long", "short"]), "glasses": rnd.random() < 0.18})
    return out


def build(root: Path | None = None) -> list[Path]:
    root = root or Path(__file__).resolve().parents[2]
    folder = root / ASSETS
    folder.mkdir(parents=True, exist_ok=True)
    written = []
    for old in folder.glob("c_*.png"):
        old.unlink()   # the 48 two-digit skins before D-162
    for spec in specs():
        image = draw(spec, random.Random(spec["index"] * 7919))
        path = folder / f"c_{spec['index']:03d}.png"
        image.save(path)
        written.append(path)
    return written


if __name__ == "__main__":
    print(f"{len(build())} citizen skins written")
