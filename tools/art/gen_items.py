"""Original 16x16 item art for every Core item, drawn from shaped primitives and character maps (see docs/design/visual-identity.md).

Each sprite is a recognisable object, not a symbol: ingots look like ingots, circuits like boards. Rarity is carried by the
drawing (gold foil, a hand-written seal, a bright seam) rather than by colour alone.
"""
from __future__ import annotations
import os, sys
sys.path.insert(0, os.path.dirname(__file__))
from uc_art import Sprite, ramp, rgb, contact_sheet
from palettes import *
from shapes import *

ASSETS = os.path.join(os.path.dirname(__file__), '..', '..', 'rpg-core', 'src', 'main', 'resources', 'assets', 'underground_city')
ITEM_DIR = os.path.join(ASSETS, 'textures', 'item')

ITEMS: dict = {}


def item(fn):
    ITEMS[fn.__name__] = fn
    return fn


def finish(s: Sprite, outline=None, diagonal=False) -> Sprite:
    s.outline(outline or INK, diagonal=diagonal)
    return s


# ---------------------------------------------------------------------------------------------------------- metals
def _ingot(r, seam=None, shine=None):
    s = Sprite()
    # top face (parallelogram), front face, right face
    top = [(4, 6), (11, 6), (14, 3), (7, 3)]
    s.polygon(top, r[3])
    s.polygon([(4, 7), (11, 7), (11, 11), (4, 11)], r[2])
    s.polygon([(12, 7), (14, 4), (14, 8), (12, 11)], r[1])
    for x in range(4, 12):
        s.put(x, 7, r[3])
    s.line(4, 7, 4, 11, r[3])
    s.line(5, 4, 12, 4, r[4])
    s.line(5, 5, 9, 5, r[4])
    s.line(4, 11, 11, 11, r[1])
    s.line(12, 11, 12, 8, r[0])
    s.line(11, 6, 14, 3, r[2])
    if seam:
        s.line(5, 9, 10, 9, seam)
    if shine:
        s.put(6, 8, shine)
        s.put(5, 8, shine)
    return finish(s)


@item
def refined_alloy():
    s = _ingot(STEEL, seam=CYAN[1], shine=WHITE[4])
    s.put(6, 4, WHITE[4])
    return s


@item
def prototype_alloy_ingot():
    """Rare: an ingot with a hot gold seam, violet temper colours and a spark."""
    base = ramp('#8f7bd1')
    s = _ingot(base, seam=AMBER[3], shine=WHITE[4])
    for x in range(5, 11):
        s.put(x, 8, AMBER[4] if x % 2 else AMBER[3])
        s.put(x, 10, GOLD_HOT)
    s.put(2, 2, WHITE[4]); s.put(1, 3, AMBER[4]); s.put(3, 3, AMBER[4]); s.put(2, 4, WHITE[4]); s.put(2, 3, WHITE[4])
    return s


GOLD_HOT = rgb('#ffd36a')


@item
def unstamped_plate():
    """A blank steel plate: rough corners, mill scale, a hole punched for the stamp that was never struck."""
    s = Sprite()
    s.polygon([(2, 5), (12, 3), (14, 4), (14, 10), (4, 12), (2, 11)], STEEL[2])
    s.polygon([(2, 5), (12, 3), (13, 4), (3, 6)], STEEL[4])
    s.line(2, 6, 2, 11, STEEL[3])
    s.line(3, 12, 13, 10, STEEL[0])
    s.line(14, 5, 14, 10, STEEL[0])
    dither(s, 4, 7, 12, 10, STEEL[2], STEEL[1])
    for (x, y) in ((5, 8), (11, 6)):
        s.put(x, y, STEEL[0]); s.put(x + 1, y, STEEL[3])
    s.rect(7, 7, 8, 8, STEEL[1])      # the blank stamp field
    return finish(s)


@item
def machined_parts():
    """Two meshed gears: a big steel one and a small brass pinion."""
    s = Sprite()
    gear(s, 6, 9, 5.4, 4.3, 7, STEEL, hole=1.6)
    disc(s, 6, 9, 2.4, GUNMETAL, hole=1.6)
    gear(s, 12, 4, 3.3, 2.5, 6, BRASS, hole=0.8)
    s.put(12, 4, INK)
    return finish(s)


@item
def precision_machinery_parts():
    """A ball bearing with a brass spindle through it."""
    s = Sprite()
    disc(s, 6, 8, 5.6, STEEL)
    disc(s, 6, 8, 3.6, GUNMETAL)
    disc(s, 6, 8, 1.8, BRASS)
    for (x, y) in ((6, 4), (9, 6), (9, 10), (6, 12), (3, 10), (3, 6)):
        s.put(x, y, WHITE[4])
    s.put(6, 8, INK)
    s.rect(10, 12, 15, 13, BRASS[3]); s.line(10, 12, 15, 12, BRASS[4]); s.line(10, 13, 15, 13, BRASS[1])
    s.rect(15, 11, 15, 14, BRASS[0])
    return finish(s)


@item
def lockpick():
    s = Sprite()
    # tension wrench (L-shaped) and a hooked pick laid across it
    s.line(3, 13, 3, 6, GUNMETAL[2]); s.line(4, 13, 4, 6, GUNMETAL[1]); s.line(3, 6, 8, 6, GUNMETAL[3]); s.line(4, 7, 8, 7, GUNMETAL[1])
    s.line(7, 13, 13, 3, STEEL[3]); s.line(8, 13, 14, 3, STEEL[1])
    s.put(13, 2, STEEL[4]); s.put(14, 2, STEEL[3]); s.put(15, 3, STEEL[3]); s.put(15, 4, STEEL[2])   # the hooked tip
    s.rect(6, 13, 8, 14, BURLAP[2]); s.put(6, 13, BURLAP[4]); s.rect(2, 13, 4, 14, BURLAP[1]); s.put(2, 13, BURLAP[3])
    return finish(s)


# ----------------------------------------------------------------------------------------------- Farmer / Vintner
OLIVE = ramp('#5d6b4f')
LIQ = ramp('#b9d84a')


@item
def cannabis_seeds():
    """A small paper seed packet with a seven-fingered cannabis leaf printed on it, two seeds spilled in front."""
    s = Sprite()
    bevel_rect(s, 2, 1, 12, 13, PAPER, strong=False)
    s.line(3, 2, 11, 2, PAPER[1])                                                         # the folded top
    # the printed leaf: seven leaflets fanning from the stalk, the middle ones longest and two pixels wide at their middle
    cx, cy = 7, 10
    for (x1, y1, c, wide) in ((7, 3, 3, True), (4, 4, 2, True), (10, 4, 2, True), (3, 7, 2, False), (11, 7, 2, False), (4, 10, 1, False), (10, 10, 1, False)):
        s.line(cx, cy, x1, y1, LEAF[c])
        if wide:
            mx, my = (cx + x1) // 2, (cy + y1) // 2
            s.put(mx + (1 if x1 >= cx else -1), my, LEAF[c - 1]); s.put(mx, my - 1, LEAF[c])
    s.put(7, 3, LEAF_LIME[4]); s.line(7, 10, 7, 12, LEAF[0])
    # two seeds: small brown ovals with a light flank
    for (x, y) in ((11, 14), (13, 13)):
        s.rect(x, y, x + 1, y + 1, SOIL[1]); s.put(x, y, SOIL[3])
    return finish(s)


@item
def cultivation_nutrient():
    """A round flask with a cork and a green-gold liquid, a drip of it on the label."""
    s = Sprite()
    s.rect(7, 2, 8, 3, BURLAP[2]); s.put(7, 2, BURLAP[4]); s.put(8, 3, BURLAP[0])      # cork
    s.rect(7, 4, 8, 6, WHITE[3]); s.put(7, 4, WHITE[4])                                   # neck
    disc(s, 7, 10, 5.2, WHITE)
    # liquid fills the lower two thirds
    for y in range(9, 16):
        for x in range(2, 14):
            if s.solid(x, y):
                lit = 0 if (x > 9 and y > 12) else 2 if x < 6 else 1
                s.put(x, y, LIQ[min(4, 1 + lit + (1 if y == 9 else 0))])
    s.put(5, 10, WHITE[4]); s.put(5, 11, WHITE[4]); s.put(6, 9, WHITE[4])                 # glass shine
    s.rect(6, 12, 9, 13, PAPER[3]); s.put(7, 12, LEAF[1]); s.put(8, 13, LEAF[1])          # label
    return finish(s)


@item
def cannabis_flower():
    """A fresh cannabis bud: a tight green cluster of calyxes with orange pistils and a frost of trichomes, two sugar leaves at its base."""
    s = Sprite()
    # sugar leaves at the base, then the bud itself built up of overlapping calyx discs
    s.line(4, 13, 7, 10, LEAF[1]); s.line(12, 13, 9, 10, LEAF[1]); s.line(5, 13, 7, 11, LEAF[2]); s.line(11, 13, 9, 11, LEAF[2])
    for (cx, cy, rad) in ((8, 11, 3.2), (6, 9, 2.6), (10, 9, 2.6), (8, 7, 3.0), (7, 5, 2.2), (9, 4, 2.0), (8, 2, 1.4)):
        disc(s, cx, cy, rad, LEAF_LIME)
    s.line(8, 13, 8, 15, LEAF[1])                                                        # the cut stem
    # pistils: short orange hairs curling out of the calyxes
    for (x, y) in ((5, 8), (11, 8), (6, 11), (10, 11), (8, 5), (6, 4), (10, 6), (9, 2)):
        s.put(x, y, AMBER[2]); s.put(x + (1 if x < 8 else -1), y - 1, AMBER[3])
    # trichome frost
    for (x, y) in ((7, 7), (9, 9), (8, 10), (6, 6), (10, 4), (7, 12), (9, 6)):
        s.put(x, y, WHITE[4])
    return finish(s, outline=LEAF[0])


@item
def rare_seeds():
    """A tied burlap sack with a few seeds spilled in front of it."""
    s = Sprite()
    s.polygon([(5, 6), (10, 6), (13, 12), (13, 13), (3, 13), (3, 12)], BURLAP[2])
    s.rect(6, 3, 9, 6, BURLAP[2]); s.polygon([(5, 2), (10, 2), (9, 4), (6, 4)], BURLAP[3])
    s.line(3, 12, 3, 13, BURLAP[3]); s.line(4, 8, 5, 12, BURLAP[3]); s.line(6, 6, 6, 5, BURLAP[3])
    s.line(12, 10, 13, 12, BURLAP[1]); s.line(3, 13, 13, 13, BURLAP[1])
    s.rect(6, 6, 9, 6, SOIL[1]); s.line(6, 7, 9, 7, SOIL[2])                          # the cord
    for (x, y) in ((6, 10), (9, 9), (8, 12), (4, 11)):
        s.put(x, y, BURLAP[0]) if (x + y) % 2 else s.put(x, y, BURLAP[1])
    # spilled seeds
    for (x, y, c) in ((10, 14, 0), (12, 14, 1), (14, 13, 0), (1, 14, 1)):
        s.put(x, y, AMBER[2 + c]); s.put(x + 1, y, AMBER[1 + c])
    return finish(s, outline=BURLAP[0])


@item
def heirloom_seed_packet():
    """Rare: a vintage printed seed packet with a sprouting plant, a gold border and a red wax seal."""
    s = Sprite()
    bevel_rect(s, 3, 1, 12, 14, PAPER, strong=False)
    s.line(3, 1, 12, 1, PAPER[4])
    for x in range(3, 13):
        s.put(x, 2, BRASS[3]) if x % 2 else s.put(x, 2, BRASS[2])
        s.put(x, 13, BRASS[2]) if x % 2 else s.put(x, 13, BRASS[1])
    s.rect(5, 4, 10, 9, LEAF[0])                                                       # the printed picture field
    s.rect(6, 5, 9, 8, LEAF_LIME[3])
    s.line(7, 8, 7, 6, LEAF[1]); s.line(8, 8, 8, 6, LEAF[1]); s.put(6, 6, LEAF[2]); s.put(9, 6, LEAF[2]); s.put(7, 5, LEAF[4]); s.put(8, 5, LEAF[3])
    s.rect(5, 10, 10, 10, SOIL[2])
    disc(s, 8, 12, 2.0, RED); s.put(7, 11, RED[4])                                     # wax seal
    s.put(13, 2, WHITE[4]); s.put(14, 1, AMBER[4]); s.put(14, 3, AMBER[4]); s.put(15, 2, WHITE[4]); s.put(14, 2, WHITE[4])
    return finish(s)


@item
def biopolymer():
    """A roll of translucent bio-film, one end unrolled."""
    s = Sprite()
    cylinder_h(s, 2, 12, 3, 8, LEAF_LIME)
    s.rect(2, 9, 13, 13, LEAF_LIME[3]); s.line(2, 9, 13, 9, LEAF_LIME[4]); s.line(2, 13, 13, 13, LEAF_LIME[1]); s.line(13, 9, 13, 13, LEAF_LIME[2])
    for x in range(3, 13, 3):
        s.put(x, 11, LEAF_LIME[2]); s.put(x + 1, 11, LEAF_LIME[4])
    disc(s, 12, 5, 3.0, LEAF_LIME); disc(s, 12, 5, 1.1, [LEAF[0]] * 5)
    s.put(3, 4, WHITE[4]); s.put(4, 4, WHITE[4])
    return finish(s, outline=LEAF[0])


@item
def agro_catalyst():
    """A steel canister of catalyst with a green band and a pump nozzle."""
    s = Sprite()
    cylinder_v(s, 4, 11, 5, 14, STEEL)
    s.rect(4, 8, 11, 11, LEAF[2]); cylinder_v(s, 4, 11, 8, 11, LEAF)
    s.line(4, 8, 11, 8, LEAF[4]); s.line(4, 11, 11, 11, LEAF[0])
    s.put(7, 9, WHITE[4]); s.put(8, 9, WHITE[4]); s.put(7, 10, WHITE[3])
    cylinder_v(s, 6, 9, 3, 4, GUNMETAL)
    s.rect(5, 2, 10, 2, GUNMETAL[3]); s.rect(9, 1, 13, 1, GUNMETAL[3]); s.put(13, 2, GUNMETAL[1])
    return finish(s)


@item
def irrigation_service_kit():
    s = Sprite()
    bevel_rect(s, 2, 6, 13, 13, BLUE)
    s.rect(2, 9, 13, 9, BLUE[0]); s.rect(6, 8, 9, 10, STEEL[3]); s.put(6, 8, STEEL[4]); s.put(9, 10, STEEL[1])
    s.rect(5, 4, 10, 5, GUNMETAL[2]); s.rect(5, 4, 5, 6, GUNMETAL[3]); s.rect(10, 4, 10, 6, GUNMETAL[1]); s.line(5, 3, 10, 3, GUNMETAL[3])
    # a water drop on the lid and a hose end
    s.polygon([(8, 11), (7, 12), (9, 12)], CYAN[3]); s.put(8, 12, CYAN[4]); s.put(8, 11, CYAN[2])
    s.rect(14, 12, 15, 14, CYAN[2]); s.line(13, 12, 13, 13, CYAN[1]); s.put(15, 14, CYAN[0])
    return finish(s)


@item
def climate_module():
    s = Sprite()
    bevel_rect(s, 2, 3, 13, 13, GUNMETAL)
    s.rect(4, 5, 11, 8, PCB[0]); s.rect(5, 6, 10, 7, CYAN[3]); s.put(5, 6, CYAN[4]); s.put(6, 6, CYAN[4])
    for y in (10, 11, 12):
        s.line(4, y, 11, y, GUNMETAL[0] if y % 2 else GUNMETAL[3])
    s.put(12, 5, RED[3]); s.put(12, 7, LEAF_LIME[3])
    s.line(8, 1, 8, 3, STEEL[3]); s.put(8, 0, STEEL[4])
    return finish(s)


@item
def seed_drill_kit():
    """A seed hopper on a steel frame with a brass feed gear and a trail of seed behind the coulter."""
    s = Sprite()
    s.polygon([(3, 2), (12, 2), (10, 8), (5, 8)], GUNMETAL[2])                      # the hopper
    s.line(3, 2, 12, 2, GUNMETAL[4]); s.line(3, 2, 5, 8, GUNMETAL[3]); s.line(12, 2, 10, 8, GUNMETAL[1])
    s.rect(5, 3, 10, 4, AMBER[2]); s.line(5, 3, 10, 3, AMBER[4]); s.put(7, 4, AMBER[1]); s.put(9, 3, BURLAP[3])   # seed in the hopper
    s.rect(6, 9, 9, 10, STEEL[3]); s.line(6, 9, 9, 9, STEEL[4])                      # the feed chute
    s.polygon([(10, 10), (14, 10), (14, 14), (10, 14)], BRASS[2]); s.line(10, 10, 14, 10, BRASS[4]); s.line(14, 10, 14, 14, BRASS[1])
    s.put(11, 12, INK); s.put(12, 11, INK); s.put(12, 13, INK); s.put(13, 12, INK)      # the gear's teeth
    s.line(7, 11, 7, 14, STEEL[2]); s.put(7, 14, STEEL[1])                          # the coulter
    for (x, y) in ((3, 14), (5, 13), (4, 12)):
        s.put(x, y, SOIL[3]); 
    s.put(2, 13, AMBER[3]); s.put(4, 14, AMBER[3])
    return finish(s)


@item
def cooperage_set():
    """Two barrel hoops, a driving hoop and a cooper's mallet."""
    s = Sprite()
    # the hoops: two iron rings seen on edge, one in front of the other
    s.polygon([(1, 5), (9, 5), (10, 7), (10, 12), (9, 14), (1, 14), (0, 12), (0, 7)], GUNMETAL[2])
    s.polygon([(3, 7), (7, 7), (8, 8), (8, 11), (7, 12), (3, 12), (2, 11), (2, 8)], INK)
    s.line(1, 5, 9, 5, GUNMETAL[4]); s.line(1, 14, 9, 14, GUNMETAL[0]); s.line(0, 7, 0, 12, GUNMETAL[3]); s.line(10, 7, 10, 12, GUNMETAL[1])
    s.line(3, 3, 12, 3, STEEL[3]); s.line(3, 2, 12, 2, STEEL[4]); s.line(3, 4, 12, 4, STEEL[1])   # the second hoop behind
    # the mallet
    s.rect(11, 8, 14, 11, BURLAP[2]); s.rect(11, 8, 14, 8, BURLAP[4]); s.rect(14, 8, 14, 11, BURLAP[0]); s.rect(11, 11, 14, 11, BURLAP[0])
    s.line(12, 12, 12, 15, BURLAP[1]); s.line(13, 12, 13, 15, BURLAP[3])
    return finish(s)


@item
def still_column_kit():
    """A copper column with a pressure gauge, a brass cap and the lyne arm to the condenser."""
    s = Sprite()
    s.rect(4, 4, 8, 13, COPPER[2]); s.rect(4, 4, 4, 13, COPPER[4]); s.rect(8, 4, 8, 13, COPPER[0]); s.rect(5, 4, 5, 13, COPPER[3])
    for y in (6, 9, 12):
        s.line(4, y, 8, y, COPPER[1])                                               # the plate seams
    s.polygon([(3, 2), (9, 2), (9, 4), (3, 4)], BRASS[2]); s.line(3, 2, 9, 2, BRASS[4]); s.line(9, 2, 9, 4, BRASS[1])
    s.rect(5, 0, 7, 1, BRASS[3]); s.put(5, 0, BRASS[4])
    s.line(9, 3, 13, 3, COPPER[3]); s.line(13, 3, 13, 9, COPPER[2]); s.line(13, 9, 14, 10, COPPER[1])   # the lyne arm
    s.polygon([(9, 7), (12, 7), (12, 10), (9, 10)], STEEL[3]); s.rect(10, 8, 11, 9, WHITE[3]); s.put(10, 8, RED[3])   # the gauge
    s.rect(3, 13, 9, 14, GUNMETAL[2]); s.line(3, 14, 9, 14, GUNMETAL[0])
    return finish(s)


@item
def precision_greenhouse_unit():
    s = Sprite()
    glass = rgb('#bfe8f0e0')
    s.polygon([(2, 7), (8, 2), (14, 7), (14, 13), (2, 13)], WHITE[3])
    s.polygon([(3, 8), (8, 4), (13, 8), (13, 12), (3, 12)], rgb('#8fd3e2'))
    s.rect(3, 8, 13, 8, rgb('#8fd3e2')); s.line(8, 4, 8, 12, WHITE[3]); s.line(3, 10, 13, 10, WHITE[3])
    s.line(3, 8, 13, 8, WHITE[2]); s.put(5, 7, WHITE[4]); s.put(4, 9, WHITE[4]); s.put(5, 9, WHITE[4])
    for (x, y) in ((6, 11), (7, 11), (10, 11), (11, 11)):
        s.put(x, y, LEAF[2]); s.put(x, y - 1, LEAF[3])
    s.rect(2, 13, 14, 14, GUNMETAL[2]); s.line(2, 13, 14, 13, GUNMETAL[3])
    return finish(s)


@item
def agri_sensor_repair():
    s = Sprite()
    s.line(4, 14, 7, 5, STEEL[2]); s.line(5, 14, 8, 5, STEEL[1]); s.put(4, 14, STEEL[4])
    s.polygon([(6, 5), (12, 5), (13, 8), (5, 8)], LEAF[2]); s.line(6, 5, 12, 5, LEAF[3]); s.line(5, 8, 13, 8, LEAF[0])
    s.put(8, 6, LEAF_LIME[4]); s.put(10, 7, CYAN[3]); s.put(9, 6, CYAN[4])
    s.rect(10, 10, 14, 13, PCB[2]); s.rect(10, 10, 14, 10, PCB[3]); s.put(11, 11, BRASS[3]); s.put(13, 12, BRASS[3]); s.line(10, 13, 14, 13, PCB[0])
    s.line(8, 8, 8, 9, COPPER[2]); s.line(8, 9, 10, 11, COPPER[2])
    return finish(s)


# ------------------------------------------------------------------------------------------ Smith / Engineer (cont.)
CARBON = ramp('#3b4552')


@item
def security_fabric():
    """A folded bolt of woven ballistic fabric with an orange stock tag."""
    s = Sprite()
    s.polygon([(2, 6), (13, 6), (14, 8), (14, 12), (3, 12), (2, 10)], OLIVE[2])
    s.polygon([(2, 6), (13, 6), (14, 8), (3, 8)], OLIVE[3])
    s.line(3, 6, 12, 6, OLIVE[4])
    for y in range(9, 12):
        for x in range(3, 14):
            if (x + y) % 2 == 0:
                s.put(x, y, OLIVE[1])
    s.line(3, 12, 14, 12, OLIVE[0]); s.line(14, 8, 14, 12, OLIVE[0]); s.line(3, 8, 14, 8, OLIVE[1])
    for x in range(3, 13, 2):
        s.put(x, 7, OLIVE[2])
    s.rect(11, 9, 13, 13, AMBER[3]); s.line(11, 9, 13, 9, AMBER[4]); s.line(11, 13, 13, 13, AMBER[1]); s.put(12, 11, INK)
    return finish(s)


@item
def composite_housing():
    """A moulded composite equipment shell: a slate-blue weave, a recessed window with an indicator row, a latch and four screws."""
    slate = ramp('#62728f')
    s = Sprite()
    s.polygon([(2, 4), (13, 4), (14, 5), (14, 12), (13, 13), (2, 13), (1, 12), (1, 5)], slate[2])
    for y in range(5, 13):
        for x in range(2, 14):
            if (x + y) % 3 == 0:
                s.put(x, y, slate[3])
            elif (x - y) % 4 == 0:
                s.put(x, y, slate[1])
    s.line(2, 4, 13, 4, slate[4]); s.line(1, 5, 1, 12, slate[3]); s.line(2, 13, 13, 13, slate[0]); s.line(14, 5, 14, 12, slate[0])
    s.rect(4, 6, 11, 10, INK)
    s.rect(5, 7, 10, 9, slate[0])
    for x, c in ((5, CYAN[3]), (7, CYAN[4]), (9, AMBER[3])):
        s.put(x, 8, c)
    s.rect(6, 11, 9, 11, STEEL[3]); s.put(7, 11, STEEL[4])
    for (x, y) in ((3, 5), (12, 5), (3, 12), (12, 12)):
        s.put(x, y, STEEL[4])
    return finish(s)


@item
def specialized_equipment_platform():
    """A machined base plate on four posts with a power block."""
    s = Sprite()
    s.polygon([(1, 6), (13, 6), (14, 8), (14, 9), (2, 9), (1, 8)], STEEL[3])
    s.polygon([(1, 6), (13, 6), (14, 8), (2, 8)], STEEL[4])
    s.line(2, 9, 14, 9, STEEL[1]); s.line(14, 8, 14, 9, STEEL[0])
    for x in (3, 6, 9, 12):
        s.put(x, 7, GUNMETAL[2])
    for x in (2, 12):
        s.rect(x, 10, x + 1, 13, GUNMETAL[2]); s.line(x, 10, x, 13, GUNMETAL[3]); s.line(x + 1, 10, x + 1, 13, GUNMETAL[1])
    s.rect(6, 10, 9, 13, GUNMETAL[2]); s.line(6, 10, 9, 10, GUNMETAL[3]); s.put(7, 12, AMBER[3]); s.put(8, 12, LEAF_LIME[3])
    s.line(8, 2, 8, 5, COPPER[2]); s.put(8, 2, COPPER[4]); s.put(7, 5, COPPER[1]); s.put(9, 5, COPPER[1])
    return finish(s)


@item
def field_repair_set():
    """A canvas-red tool roll with a wrench head and a screwdriver standing out of it."""
    s = Sprite()
    # the roll
    s.rect(2, 7, 13, 13, RED[2]); s.line(2, 7, 13, 7, RED[4]); s.line(2, 13, 13, 13, RED[0]); s.line(2, 7, 2, 13, RED[3]); s.line(13, 7, 13, 13, RED[1])
    s.line(3, 9, 12, 9, RED[1])                       # the fold
    for x in range(3, 13, 2):
        s.put(x, 11, RED[3])                           # stitching
    s.rect(7, 8, 8, 12, BRASS[3]); s.put(7, 8, BRASS[4]); s.put(8, 12, BRASS[1])   # the buckle strap
    # a wrench head
    s.polygon([(3, 2), (6, 2), (7, 4), (6, 6), (4, 6), (3, 4)], STEEL[3])
    s.rect(4, 3, 5, 4, INK); s.line(4, 2, 6, 2, STEEL[4])
    s.line(5, 5, 5, 7, STEEL[2])
    # a screwdriver
    s.rect(10, 1, 11, 3, AMBER[3]); s.line(10, 1, 11, 1, AMBER[4]); s.line(11, 1, 11, 3, AMBER[1])
    s.line(10, 4, 10, 7, STEEL[3]); s.line(11, 4, 11, 7, STEEL[1])
    return finish(s)


@item
def industrial_controller():
    """A rail-mounted controller: grey housing, status LEDs and an orange terminal block."""
    s = Sprite()
    bevel_rect(s, 2, 3, 13, 12, GUNMETAL)
    s.rect(4, 5, 11, 6, INK); s.put(5, 5, LEAF_LIME[3]); s.put(7, 5, AMBER[3]); s.put(9, 5, RED[3]); s.put(10, 6, CYAN[3])
    s.rect(4, 8, 11, 10, STEEL[2]); s.line(4, 8, 11, 8, STEEL[4]); s.line(4, 10, 11, 10, STEEL[0])
    for x in (5, 7, 9, 11):
        s.put(x, 9, INK)
    s.rect(2, 13, 13, 14, STEEL[2]); s.line(2, 13, 13, 13, STEEL[4]); s.line(2, 14, 13, 14, STEEL[1])
    s.rect(12, 8, 13, 11, COPPER[3]); s.put(12, 8, COPPER[4])
    return finish(s)


@item
def security_array():
    """A dome camera on a wall bracket, lens glowing."""
    s = Sprite()
    s.rect(2, 2, 4, 14, GUNMETAL[2]); s.line(2, 2, 2, 14, GUNMETAL[3]); s.line(4, 2, 4, 14, GUNMETAL[0])
    s.rect(5, 6, 8, 7, GUNMETAL[2]); s.line(5, 6, 8, 6, GUNMETAL[3])
    disc(s, 11, 7, 4.2, STEEL)
    disc(s, 12, 8, 2.2, [INK, BLUE[0], BLUE[1], CYAN[2], CYAN[3]])
    s.put(13, 7, WHITE[4]); s.put(12, 7, CYAN[4])
    s.rect(9, 12, 12, 13, STEEL[1]); s.line(9, 12, 12, 12, STEEL[2])
    s.put(3, 4, STEEL[4]); s.put(3, 12, STEEL[4])
    return finish(s)


# ---------------------------------------------------------------------------------------- Gunsmith / Arms Dealer
@item
def optic_mount_kit():
    """A rail segment with a scope ring and a loose hex key."""
    s = Sprite()
    s.rect(1, 10, 14, 13, GUNMETAL[2]); s.line(1, 10, 14, 10, GUNMETAL[4]); s.line(1, 13, 14, 13, GUNMETAL[0])
    for x in (2, 5, 8, 11):
        s.rect(x, 10, x + 1, 11, INK)
    s.line(1, 10, 1, 13, GUNMETAL[3]); s.line(14, 10, 14, 13, GUNMETAL[1])
    disc(s, 7, 5, 4.2, STEEL); disc(s, 7, 5, 2.6, [INK, BLUE[0], BLUE[1], CYAN[1], CYAN[2]])
    s.put(6, 4, WHITE[4]); s.put(7, 4, WHITE[3])
    s.rect(7, 9, 8, 10, STEEL[2])
    s.line(12, 3, 15, 3, BRASS[3]); s.line(15, 3, 15, 6, BRASS[2]); s.put(12, 3, BRASS[4])
    return finish(s)


@item
def licensed_service_kit():
    """A blue service folder with a brass licence seal."""
    s = Sprite()
    bevel_rect(s, 3, 2, 12, 14, BLUE)
    s.rect(4, 2, 4, 14, BLUE[1]); s.rect(5, 3, 11, 3, PAPER[3])
    for y in (5, 6):
        s.line(6, y, 11, y, PAPER[3] if y == 5 else PAPER[2])
    disc(s, 8, 10, 3.2, BRASS)
    s.put(8, 10, BRASS[0]); s.put(7, 9, BRASS[4]); s.line(6, 10, 10, 10, BRASS[1]); s.put(8, 10, BRASS[4]); s.put(8, 8, BRASS[3]); s.put(8, 12, BRASS[3])
    s.rect(13, 4, 14, 7, PAPER[3]); s.put(13, 4, PAPER[4])
    return finish(s)


# ------------------------------------------------------------------------------------------ Hacker / Technician
@item
def control_circuits():
    """A printed circuit board with two chips, traces and gold edge contacts."""
    s = Sprite()
    bevel_rect(s, 1, 3, 14, 12, PCB)
    for x in range(2, 14, 2):
        s.put(x, 13, BRASS[3]); s.put(x, 14, BRASS[2])
    s.rect(3, 5, 6, 8, INK); s.rect(3, 5, 6, 5, GUNMETAL[2]); s.put(4, 6, GUNMETAL[3])
    s.rect(9, 6, 12, 9, INK); s.rect(9, 6, 12, 6, GUNMETAL[2]); s.put(10, 7, GUNMETAL[3])
    for (x0, y0, x1, y1) in ((7, 6, 8, 6), (7, 8, 8, 8), (4, 10, 11, 10), (7, 7, 7, 9)):
        s.line(x0, y0, x1, y1, PCB[4])
    for (x, y) in ((2, 4), (13, 4), (2, 11), (13, 11)):
        s.put(x, y, BRASS[4])
    return finish(s, outline=PCB[0])


@item
def bypass_kit():
    """A black hard case with two latches, a cyan status light and a probe cable."""
    s = Sprite()
    bevel_rect(s, 2, 4, 13, 12, PLASTIC_BLACK)
    s.line(2, 8, 13, 8, INK)
    s.rect(2, 7, 13, 7, PLASTIC_BLACK[1])
    for x in (4, 10):
        s.rect(x, 7, x + 1, 9, STEEL[3]); s.line(x, 7, x, 9, STEEL[4])
    s.put(7, 5, CYAN[3]); s.put(8, 5, CYAN[4]); s.put(7, 6, CYAN[1])
    s.rect(5, 2, 10, 3, PLASTIC_BLACK[2]); s.line(5, 2, 10, 2, PLASTIC_BLACK[3])
    s.line(13, 11, 15, 13, CYAN[2]); s.put(15, 14, CYAN[4])
    s.line(14, 5, 15, 4, STEEL[3])
    return finish(s)


@item
def data_ledger():
    """A bound ledger with a locking strap and a green-lit data strip."""
    s = Sprite()
    bevel_rect(s, 3, 2, 12, 14, BLUE)
    s.rect(3, 2, 4, 14, BLUE[0]); s.line(4, 2, 4, 14, BLUE[1])
    s.rect(6, 4, 10, 8, PAPER[3]); s.line(6, 4, 10, 4, PAPER[4]); s.line(6, 8, 10, 8, PAPER[1])
    for y in (5, 7):
        s.line(7, y, 9, y, GUNMETAL[2])
    s.line(6, 11, 11, 11, CYAN[2]); s.put(7, 11, CYAN[4]); s.put(9, 11, CYAN[4])
    s.rect(11, 8, 14, 9, BRASS[2]); s.line(11, 8, 14, 8, BRASS[4]); s.put(14, 9, BRASS[0])
    return finish(s)


@item
def encrypted_design():
    """An optical disc in a clip, with a gold padlock."""
    s = Sprite()
    disc(s, 7, 7, 6.2, [GUNMETAL[0], PURPLE[1], PURPLE[2], CYAN[2], WHITE[3]])
    for (x, y) in ((4, 4), (5, 3), (3, 6), (10, 3), (11, 5)):
        s.put(x, y, WHITE[4])
    disc(s, 7, 7, 2.0, [INK, INK, INK, INK, INK]); s.put(7, 7, GUNMETAL[2])
    s.rect(10, 10, 14, 14, BRASS[2]); s.rect(10, 10, 14, 10, BRASS[4]); s.line(10, 14, 14, 14, BRASS[0]); s.put(12, 12, INK)
    s.line(11, 8, 11, 9, BRASS[3]); s.line(13, 8, 13, 9, BRASS[1]); s.put(12, 8, BRASS[4])
    return finish(s)


@item
def optical_components():
    """A precision lens in a brass cell with a flare of light."""
    s = Sprite()
    disc(s, 7, 7, 6.2, BRASS)
    disc(s, 7, 7, 4.8, [INK, BLUE[0], BLUE[1], CYAN[2], CYAN[3]])
    s.put(5, 5, WHITE[4]); s.put(6, 4, WHITE[4]); s.put(5, 6, WHITE[3]); s.put(4, 5, WHITE[3]); s.put(6, 5, WHITE[4])
    s.line(9, 9, 10, 10, CYAN[4])
    s.put(13, 12, WHITE[4]); s.put(12, 13, WHITE[4]); s.put(14, 13, CYAN[3])
    return finish(s)


@item
def circuit_prototype():
    """Rare: a bare silicon die in a gold-pinned carrier with a lit core."""
    s = Sprite()
    bevel_rect(s, 3, 3, 12, 12, GUNMETAL)
    for i in range(4, 12, 2):
        s.put(i, 2, BRASS[3]); s.put(i, 13, BRASS[3]); s.put(2, i, BRASS[3]); s.put(13, i, BRASS[3])
    s.rect(5, 5, 10, 10, [INK, BLUE[0], BLUE[1], PURPLE[1], PURPLE[2]][1])
    disc(s, 7, 7, 2.4, [BLUE[1], PURPLE[2], CYAN[2], CYAN[3], WHITE[4]])
    s.line(5, 5, 10, 5, PURPLE[3]); s.line(5, 5, 5, 10, PURPLE[3])
    s.put(14, 1, WHITE[4]); s.put(13, 1, AMBER[4]); s.put(15, 1, AMBER[4]); s.put(14, 0, AMBER[4]); s.put(14, 2, AMBER[4])
    return finish(s)


@item
def optics_schematic():
    """Rare: a half-unrolled blueprint of a lens train with a gold seal."""
    s = Sprite()
    s.polygon([(2, 2), (13, 2), (14, 3), (14, 12), (2, 12), (1, 3)], BLUE[2])
    s.line(2, 2, 13, 2, BLUE[4]); s.line(2, 12, 14, 12, BLUE[0]); s.line(14, 3, 14, 12, BLUE[0])
    s.rect(1, 12, 14, 14, PAPER[3]); s.line(1, 12, 14, 12, PAPER[4]); s.line(1, 14, 14, 14, PAPER[1]); s.line(14, 12, 14, 14, PAPER[0])
    s.line(3, 7, 12, 7, WHITE[3])
    disc(s, 5, 7, 2.0, [BLUE[3], WHITE[2], WHITE[3], WHITE[4], WHITE[4]]); s.put(5, 7, BLUE[2])
    disc(s, 10, 7, 1.6, [BLUE[3], WHITE[2], WHITE[3], WHITE[4], WHITE[4]])
    s.line(3, 4, 8, 4, WHITE[2]); s.line(3, 10, 6, 10, WHITE[2])
    disc(s, 12, 11, 1.8, BRASS); s.put(12, 11, BRASS[0])
    s.put(14, 0, WHITE[4]); s.put(13, 1, AMBER[4]); s.put(15, 1, AMBER[4]); s.put(14, 1, WHITE[4]); s.put(14, 2, AMBER[4])
    return finish(s)


# ----------------------------------------------------------------------------------------------- valuables a fence buys (D-125)
@item
def pocket_watch():
    """A gold watch on a chain: a round case, a white face with two hands, the crown and a loop of chain."""
    s = Sprite()
    disc(s, 8, 9, 5.4, [BRASS[0], BRASS[1], BRASS[2], BRASS[3], BRASS[4]])
    disc(s, 8, 9, 4.0, [PAPER[1], PAPER[2], PAPER[3], PAPER[4], WHITE[4]])
    s.line(8, 9, 8, 6, INK); s.line(8, 9, 10, 10, INK); s.put(8, 9, RED[2])
    for (x, y) in ((8, 5), (12, 9), (8, 13), (4, 9)):
        s.put(x, y, GUNMETAL[1])
    s.rect(7, 2, 9, 3, BRASS[3]); s.put(8, 1, BRASS[4])
    s.line(9, 1, 12, 0, BRASS[2]); s.line(12, 0, 14, 2, BRASS[3]); s.line(14, 2, 14, 5, BRASS[2])
    s.put(6, 6, WHITE[4]); s.put(5, 7, WHITE[4])
    return finish(s)


@item
def jewellery_box():
    """A small lacquered red box with a gold clasp, its lid open on a ring and a string of pearls."""
    s = Sprite()
    bevel_rect(s, 2, 8, 13, 14, RED)
    s.polygon([(2, 7), (13, 7), (12, 3), (3, 3)], RED[1])
    s.line(3, 3, 12, 3, RED[3]); s.line(2, 7, 13, 7, RED[4])
    s.rect(4, 5, 11, 6, PURPLE[1])
    for x in range(4, 12, 2):
        s.put(x, 8, WHITE[4])
    s.put(5, 9, WHITE[3]); s.put(10, 9, WHITE[3])
    disc(s, 8, 6, 1.4, [BRASS[1], BRASS[2], BRASS[3], BRASS[4], BRASS[4]]); s.put(8, 6, CYAN[4])
    s.rect(7, 10, 8, 11, BRASS[3]); s.put(7, 10, BRASS[4])
    return finish(s)


@item
def old_laptop():
    """A grey office laptop half open: a dark screen with a lit corner and a keyboard deck with a scratched-off sticker."""
    s = Sprite()
    s.polygon([(3, 2), (13, 2), (13, 9), (3, 9)], GUNMETAL[1])
    s.rect(4, 3, 12, 8, BLUE[0]); s.line(4, 3, 12, 3, BLUE[1]); s.put(11, 4, CYAN[3]); s.put(12, 4, CYAN[4])
    s.polygon([(1, 10), (15, 10), (14, 14), (2, 14)], STEEL[2])
    s.line(1, 10, 15, 10, STEEL[4]); s.line(2, 14, 14, 14, STEEL[0])
    for y in (11, 12):
        for x in range(3, 13, 2):
            s.put(x, y, GUNMETAL[2])
    s.rect(6, 13, 9, 13, GUNMETAL[1])
    s.put(13, 11, PAPER[2]); s.put(14, 12, PAPER[1])
    return finish(s)


# ----------------------------------------------------------------------------------------------- the road (D-139)
@item
def sealed_envelope():
    """A courier's envelope: a cream envelope with its flap folded down, a red wax seal and a brown address label."""
    s = Sprite()
    bevel_rect(s, 1, 4, 14, 12, PAPER)
    s.line(1, 4, 7, 9, PAPER[1]); s.line(14, 4, 8, 9, PAPER[1])
    s.line(2, 4, 7, 8, PAPER[4]); s.line(13, 4, 8, 8, PAPER[4])
    disc(s, 7.5, 9, 1.6, [RED[0], RED[1], RED[2], RED[3], RED[4]])
    s.rect(9, 10, 13, 11, BURLAP[3]); s.line(10, 10, 12, 10, INK)
    return finish(s)


def save_all(only=None):
    os.makedirs(ITEM_DIR, exist_ok=True)
    for name, fn in ITEMS.items():
        if only and name not in only:
            continue
        sprite = fn()
        sprite.save(os.path.join(ITEM_DIR, name + '.png'))


if __name__ == '__main__':
    out = sys.argv[1] if len(sys.argv) > 1 else None
    sprites = {name: fn() for name, fn in ITEMS.items()}
    if out:
        contact_sheet(sprites, scale=12, cols=6).save(out)
    else:
        save_all()
