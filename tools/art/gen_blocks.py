"""Core block art: textures and element models for the Operations Terminal, the Secured Data Terminal, the Household Safe and the
Public Ledger. 16x16 textures, shaded from the upper left, no placeholder cubes. Original work.
"""
from __future__ import annotations
import json, os, random, sys
sys.path.insert(0, os.path.dirname(__file__))
from uc_art import preview_dir, Sprite, ramp, rgb, noise_fill, contact_sheet
from palettes import *
from shapes import bevel_rect, dither

ASSETS = os.path.join(os.path.dirname(__file__), '..', '..', 'rpg-core', 'src', 'main', 'resources', 'assets', 'underground_city')
TEX = os.path.join(ASSETS, 'textures', 'block')
MODELS = os.path.join(ASSETS, 'models', 'block')
TEXTURES: dict = {}


def tex(fn):
    TEXTURES[fn.__name__] = fn
    return fn


def brushed(s: Sprite, r, seed=1, x0=0, y0=0, x1=15, y1=15):
    """Brushed metal: tone varies by row (the grain), with only a rare pixel of scratch, so large faces stay calm."""
    rnd = random.Random(seed)
    for y in range(y0, y1 + 1):
        row = rnd.choices((1, 2, 3), weights=(2, 6, 2))[0]
        for x in range(x0, x1 + 1):
            tone = row
            if rnd.random() < 0.05:
                tone = max(1, min(3, tone + rnd.choice((-1, 1))))
            s.put(x, y, r[tone])


# ------------------------------------------------------------------------------------------------ Operations Terminal
@tex
def terminal_case():
    s = Sprite()
    brushed(s, GUNMETAL, 2)
    s.rect(0, 0, 15, 0, GUNMETAL[3]); s.rect(0, 15, 15, 15, GUNMETAL[0])
    for x in (2, 13):
        for y in (3, 12):
            s.put(x, y, STEEL[3]); s.put(x + 1 if x < 8 else x - 1, y, GUNMETAL[0])
    return s


@tex
def terminal_screen():
    """The lit display: a teal title bar, two job lines, a credit counter, tabs, all in the Core's own colours."""
    s = Sprite()
    s.rect(0, 0, 15, 15, PLASTIC_BLACK[0])
    s.rect(1, 1, 14, 14, rgb('#0b1a26'))
    s.rect(1, 1, 14, 3, rgb('#1d3a52')); s.rect(2, 2, 6, 2, CYAN[3]); s.put(13, 2, AMBER[3]); s.put(12, 2, LEAF_LIME[2])
    # tabs
    for x, col in ((2, CYAN[3]), (6, rgb('#34536c')), (10, rgb('#34536c'))):
        s.rect(x, 5, x + 2, 5, col)
    # two rows of work orders with a reward on the right
    for y, w in ((7, 6), (9, 5), (11, 7)):
        s.rect(2, y, 2 + w, y, rgb('#8fb7cf')); s.rect(11, y, 13, y, AMBER[3] if y != 9 else rgb('#8fb7cf'))
    s.rect(2, 13, 13, 13, rgb('#1d3a52'))
    s.rect(2, 13, 6, 13, CYAN[2])
    for (x, y) in ((1, 1), (14, 1), (1, 14), (14, 14)):
        s.put(x, y, PLASTIC_BLACK[1])
    return s


@tex
def terminal_keys():
    s = Sprite()
    s.rect(0, 0, 15, 15, PLASTIC_BLACK[1])
    for ky in range(1, 15, 3):
        for kx in range(1, 15, 3):
            s.rect(kx, ky, kx + 1, ky + 1, STEEL[1] if (kx + ky) % 2 else STEEL[2])
            s.put(kx, ky, STEEL[3])
    s.rect(4, 13, 11, 14, STEEL[2]); s.line(4, 13, 11, 13, STEEL[3])
    return s


# ---------------------------------------------------------------------------------------------- Secured Data Terminal
@tex
def secure_side():
    s = Sprite()
    brushed(s, ramp('#46505f'), 5)
    s.rect(0, 0, 15, 0, ramp('#46505f')[3]); s.rect(0, 15, 15, 15, ramp('#46505f')[0])
    for y in (3, 8, 13):
        s.line(0, y, 15, y, ramp('#46505f')[0])
    for (x, y) in ((1, 1), (14, 1), (1, 14), (14, 14)):
        s.put(x, y, STEEL[4])
    return s


@tex
def secure_front():
    """The kiosk's front: a keypad block, a card slot and an amber lock light on dark steel."""
    r = ramp('#46505f')
    s = Sprite()
    brushed(s, r, 6)
    s.rect(0, 0, 15, 0, r[3]); s.rect(0, 15, 15, 15, r[0])
    s.rect(3, 2, 12, 6, rgb('#0a1219')); s.rect(3, 2, 12, 2, rgb('#1f3042'))
    for x, y in ((3, 3), (3, 4), (3, 5)):
        pass
    s.rect(4, 3, 11, 3, RED[3]); s.rect(4, 4, 9, 4, RED[2]); s.rect(4, 5, 11, 5, rgb('#3a1d1d'))
    for ky in (8, 10, 12):
        for kx in (4, 7, 10):
            s.rect(kx, ky, kx + 1, ky + 1, STEEL[2]); s.put(kx, ky, STEEL[4])
    s.rect(13, 8, 14, 12, PLASTIC_BLACK[0]); s.put(13, 9, AMBER[4]); s.put(13, 11, RED[3])
    return s


@tex
def secure_screen():
    s = Sprite()
    s.rect(0, 0, 15, 15, PLASTIC_BLACK[0])
    s.rect(1, 1, 14, 14, rgb('#07140f'))
    for y in range(3, 13, 2):
        s.rect(2, y, 2 + ((y * 5) % 9) + 3, y, rgb('#31d27a') if y % 4 == 3 else rgb('#1c8a50'))
    s.rect(2, 1, 13, 1, rgb('#1c8a50'))
    s.put(12, 13, rgb('#9dffc4'))
    return s


# ----------------------------------------------------------------------------------------------------- Household Safe
SAFE = ramp('#7e8896')


@tex
def safe_side():
    s = Sprite()
    brushed(s, SAFE, 7)
    s.rect(0, 0, 15, 0, SAFE[3]); s.rect(0, 15, 15, 15, SAFE[0]); s.line(0, 0, 0, 15, SAFE[3]); s.line(15, 0, 15, 15, SAFE[1])
    for (x, y) in ((2, 2), (13, 2), (2, 13), (13, 13)):
        s.put(x, y, STEEL[4]); s.put(x + 1 if x < 8 else x - 1, y + 1, SAFE[0])
    return s


@tex
def safe_top():
    s = Sprite()
    brushed(s, ramp('#59616e'), 8)
    s.rect(0, 0, 15, 0, SAFE[3]); s.rect(0, 15, 15, 15, SAFE[0]); s.rect(0, 0, 0, 15, SAFE[3]); s.rect(15, 0, 15, 15, SAFE[0])
    return s


@tex
def safe_front():
    """A heavy safe door: inset panel, a combination dial with ticks, a spoked handle, hinge barrels and a maker's plate."""
    s = Sprite()
    brushed(s, SAFE, 9)
    s.rect(0, 0, 15, 0, SAFE[3]); s.rect(0, 15, 15, 15, SAFE[0]); s.line(0, 0, 0, 15, SAFE[3]); s.line(15, 0, 15, 15, SAFE[1])
    s.rect(2, 2, 13, 13, SAFE[1]); s.rect(3, 3, 12, 12, SAFE[2])
    s.line(2, 2, 13, 2, SAFE[0]); s.line(2, 2, 2, 13, SAFE[0]); s.line(3, 13, 13, 13, SAFE[4]); s.line(13, 3, 13, 13, SAFE[4])
    for y in (4, 11):                     # hinge barrels on the left
        s.rect(1, y, 2, y + 2, GUNMETAL[2]); s.put(1, y, GUNMETAL[4])
    # dial
    cx, cy = 8, 7
    for y in range(cy - 3, cy + 4):
        for x in range(cx - 3, cx + 4):
            d2 = (x - cx) ** 2 + (y - cy) ** 2
            if d2 <= 9:
                s.put(x, y, STEEL[1] if d2 > 5 else STEEL[3] if x < cx else STEEL[2])
    s.put(cx, cy, GUNMETAL[0]); s.put(cx, cy - 3, AMBER[4]); s.put(cx - 3, cy, STEEL[0]); s.put(cx + 3, cy, STEEL[0]); s.put(cx, cy + 3, STEEL[0])
    s.put(cx - 1, cy - 1, WHITE[4])
    # handle bar
    s.rect(4, 11, 11, 12, BRASS[2]); s.line(4, 11, 11, 11, BRASS[4]); s.line(4, 12, 11, 12, BRASS[1]); s.put(4, 11, BRASS[4])
    s.rect(11, 3, 12, 3, AMBER[3])
    return s


# ---------------------------------------------------------------------------------------------------- Public Ledger
CHALK = ramp('#2b3a34')
WOOD = ramp('#7a5a3a')


@tex
def ledger_board():
    """A slate board with pinned job sheets, a price column and a chalk coin."""
    s = Sprite()
    s.rect(0, 0, 15, 15, WOOD[2])
    for y in range(16):
        for x in range(16):
            s.put(x, y, WOOD[1] if (y // 5 + x // 3) % 2 else WOOD[2])
    s.rect(0, 0, 15, 0, WOOD[3]); s.rect(0, 15, 15, 15, WOOD[0]); s.rect(0, 0, 0, 15, WOOD[3]); s.rect(15, 0, 15, 15, WOOD[0])
    s.rect(1, 1, 14, 14, CHALK[2]); s.rect(1, 1, 14, 1, CHALK[1]); s.rect(1, 14, 14, 14, CHALK[3])
    noise_fill(s, 2, 2, 13, 13, [CHALK[2], CHALK[2], CHALK[3]], seed=4)
    # pinned sheets
    for (x0, y0, x1, y1, tint) in ((2, 2, 6, 7, PAPER[3]), (8, 2, 13, 6, PAPER[2]), (3, 9, 8, 13, PAPER[3]), (10, 8, 13, 13, rgb('#e9c3b5'))):
        s.rect(x0, y0, x1, y1, tint); s.line(x0, y0, x1, y0, PAPER[4]); s.line(x1, y0, x1, y1, PAPER[1])
        for ly in range(y0 + 2, y1, 2):
            s.line(x0 + 1, ly, x1 - 1, ly, GUNMETAL[2])
        s.put((x0 + x1) // 2, y0, RED[3])
    s.put(11, 10, AMBER[4]); s.put(12, 10, AMBER[3]); s.put(11, 11, AMBER[3])
    return s


@tex
def ledger_wood():
    s = Sprite()
    for y in range(16):
        for x in range(16):
            s.put(x, y, WOOD[1] if (x % 5 == 0 or (y + x // 5) % 7 == 0) else WOOD[2] if (x + y) % 6 else WOOD[3])
    s.rect(0, 0, 15, 0, WOOD[3]); s.rect(0, 15, 15, 15, WOOD[0])
    return s


# ------------------------------------------------------------------------------------------------------- Alarm panel (D-128)
PANEL = ramp('#d9d4c7')
PANEL_LIGHTS = {'off': None, 'armed': RED, 'entry': AMBER, 'jammed': BLUE}


def panel_front(light):
    """A wall keypad, 8 x 10 pixels at (4, 3): a cream plastic case, a small display, a status light and a keypad. ``light`` is the light's ramp, or None when it is dark."""
    s = Sprite()
    s.rect(0, 0, 15, 15, PANEL[2])
    s.rect(4, 3, 11, 12, PANEL[2])
    s.line(4, 3, 11, 3, PANEL[4]); s.line(4, 3, 4, 12, PANEL[3])
    s.line(4, 12, 11, 12, PANEL[0]); s.line(11, 4, 11, 12, PANEL[1])
    s.rect(5, 4, 10, 5, INK)
    if light:
        s.rect(6, 5, 8, 5, light[1]); s.put(5, 4, light[2])
        s.rect(9, 7, 10, 7, light[4]); s.put(9, 7, light[3])
    else:
        s.rect(9, 7, 10, 7, PLASTIC_BLACK[1])
    for ky in (7, 9, 11):
        for kx in (5, 7):
            if ky == 7 and kx == 7:
                continue
            s.put(kx, ky, STEEL[1]); s.put(kx + 1, ky, STEEL[2])
    s.rect(9, 9, 10, 11, PLASTIC_BLACK[1]); s.line(9, 9, 10, 9, PLASTIC_BLACK[2])
    return s


for _name, _ramp in PANEL_LIGHTS.items():
    TEXTURES['alarm_panel_' + _name] = (lambda r=_ramp: panel_front(r))


@tex
def alarm_panel_case():
    s = Sprite()
    s.rect(0, 0, 15, 15, PANEL[1])
    s.line(0, 0, 15, 0, PANEL[3]); s.line(0, 15, 15, 15, PANEL[0])
    return s


# ------------------------------------------------------------------------------------------------ Scrap heap (D-138)
RUST = ramp('#8a4b2a')


@tex
def scrap_heap():
    """A tangle of salvage seen from any side: dark iron, rusted plate, a bright edge of steel, a copper coil and a bolt or two."""
    s = Sprite()
    rnd = random.Random(1701)
    for y in range(16):
        for x in range(16):
            s.put(x, y, GUNMETAL[rnd.choice((0, 1, 1, 2))])
    for (x0, y0, x1, y1) in ((1, 2, 6, 5), (9, 1, 14, 3), (3, 9, 8, 12), (10, 8, 14, 13), (0, 13, 3, 15)):
        for y in range(y0, y1 + 1):
            for x in range(x0, x1 + 1):
                s.put(x, y, RUST[rnd.choice((1, 2, 2, 3))])
        s.line(x0, y0, x1, y0, RUST[3])
        s.line(x0, y1, x1, y1, RUST[0])
    for (x0, y0, x1) in ((7, 6, 13), (2, 7, 6), (5, 14, 12)):
        s.line(x0, y0, x1, y0, STEEL[3])
        s.line(x0, y0 + 1, x1, y0 + 1, STEEL[1])
    for (x, y) in ((11, 4), (12, 5), (13, 4), (12, 3)):
        s.put(x, y, COPPER[3])
    s.put(12, 4, COPPER[1])
    for (x, y) in ((4, 3), (12, 10), (6, 11), (2, 14)):
        s.put(x, y, STEEL[3]); s.put(x + 1, y + 1, INK)
    for (x, y) in ((8, 0), (15, 6), (0, 10), (9, 15)):
        s.put(x, y, INK)
    return s


# ------------------------------------------------------------------------------------------------ Utility panel (D-158)
UTILITY = ramp('#6f7f8f')
UTILITY_LIGHTS = {'ok': LEAF_LIME, 'fault': RED}


def utility_front(light):
    """A building's utility panel set into the wall: a grey steel hatch with a hinge, vents, a warning label, a keyhole and a status lamp."""
    s = Sprite()
    brushed(s, UTILITY, 11)
    s.rect(0, 0, 15, 0, UTILITY[3]); s.rect(0, 15, 15, 15, UTILITY[0]); s.rect(0, 0, 0, 15, UTILITY[3]); s.rect(15, 0, 15, 15, UTILITY[0])
    # the hatch
    s.rect(2, 2, 13, 13, UTILITY[2])
    s.line(2, 2, 13, 2, UTILITY[4]); s.line(2, 2, 2, 13, UTILITY[3]); s.line(2, 13, 13, 13, UTILITY[0]); s.line(13, 3, 13, 13, UTILITY[1])
    for y in (4, 7, 10):
        s.put(3, y, STEEL[4]); s.put(3, y + 1, GUNMETAL[1])          # hinge knuckles
    for y in (6, 8):
        s.line(5, y, 11, y, INK); s.line(5, y + 1, 11, y + 1, UTILITY[3])   # vents
    s.rect(6, 10, 9, 11, AMBER[3]); s.put(7, 10, INK); s.put(8, 11, INK)    # the warning label
    s.put(11, 11, INK); s.put(11, 12, GUNMETAL[0])                           # keyhole
    s.rect(10, 3, 12, 4, light[2]); s.rect(11, 3, 12, 3, light[4]); s.put(10, 4, light[1])   # the status lamp, big enough to read from the street
    return s


for _name, _ramp in UTILITY_LIGHTS.items():
    TEXTURES['utility_panel_' + _name] = (lambda r=_ramp: utility_front(r))


@tex
def utility_panel_side():
    s = Sprite()
    brushed(s, UTILITY, 12)
    s.rect(0, 0, 15, 0, UTILITY[3]); s.rect(0, 15, 15, 15, UTILITY[0])
    return s


# ------------------------------------------------------------------------------------------------ Machine drive housing (eighth pass: the Smith's machinery)
DRIVE = ramp('#5d6a5a')
DRIVE_STATES = ('running', 'stopped')


def drive_front(state):
    """A machine's drive housing set into a building's wall: a riveted olive steel guard with a slot through which the flywheel and belt show, a maker's plate and a lamp; a stopped one
    carries a red tag on a wire and a smoke stain above the slot."""
    s = Sprite()
    brushed(s, DRIVE, 21)
    s.rect(0, 0, 15, 0, DRIVE[3]); s.rect(0, 15, 15, 15, DRIVE[0]); s.rect(0, 0, 0, 15, DRIVE[3]); s.rect(15, 0, 15, 15, DRIVE[0])
    for (x, y) in ((2, 2), (13, 2), (2, 13), (13, 13)):
        s.put(x, y, STEEL[4]); s.put(x + 1, y + 1, DRIVE[0])          # rivets
    s.rect(3, 5, 12, 11, INK)                                           # the slot in the guard
    import math
    for k in range(8):                                                  # the flywheel's spokes and rim, seen through the slot
        a = k * math.pi / 4 + (0.0 if state == 'running' else 0.3)
        s.put(int(7.5 + 2.6 * math.cos(a)), int(8 + 2.6 * math.sin(a)), STEEL[3])
    s.put(7, 8, BRASS[3]); s.put(8, 8, BRASS[2])
    s.line(4, 6, 11, 6, GUNMETAL[2]); s.line(4, 10, 11, 10, GUNMETAL[1])   # the belt above and below the hub
    s.rect(5, 12, 10, 13, BRASS[2]); s.line(5, 12, 10, 12, BRASS[4]); s.put(7, 13, INK); s.put(8, 13, INK)   # the maker's plate
    lamp = LEAF_LIME if state == 'running' else RED
    s.rect(11, 2, 12, 3, lamp[3]); s.put(12, 2, lamp[4])
    if state == 'stopped':
        for (x, y) in ((5, 4), (6, 3), (8, 4), (9, 3), (7, 2)):
            s.put(x, y, GUNMETAL[0])                                    # soot above the slot
        s.line(10, 5, 11, 8, STEEL[2])                                  # the tag's wire
        s.rect(10, 8, 12, 10, RED[3]); s.line(10, 8, 12, 8, RED[4]); s.put(11, 9, PAPER[4])
    return s


for _state in DRIVE_STATES:
    TEXTURES['machine_drive_' + _state] = (lambda st=_state: drive_front(st))


@tex
def machine_drive_side():
    s = Sprite()
    brushed(s, DRIVE, 22)
    s.rect(0, 0, 15, 0, DRIVE[3]); s.rect(0, 15, 15, 15, DRIVE[0])
    for (x, y) in ((2, 2), (13, 2), (2, 13), (13, 13)):
        s.put(x, y, STEEL[4])
    return s


# ------------------------------------------------------------------------------------------------ Battered crate (eighth pass, D-186: exploration finds)
CRATE = ramp('#7b6243')


def crate_planks(s, seed):
    """Weathered horizontal planks with dark seams, nail heads and a grey bleach where rain got at them."""
    rnd = random.Random(seed)
    for y in range(16):
        for x in range(16):
            seam = y % 5 == 4
            c = CRATE[0] if seam else CRATE[2] if (x * 7 + y * 3 + rnd.randint(0, 3)) % 9 else CRATE[1]
            if not seam and rnd.random() < 0.08:
                c = CRATE[3]
            s.put(x, y, c)
    for y in (1, 6, 11):
        s.put(1, y, STEEL[3]); s.put(14, y, STEEL[3])
    for x in range(16):
        if rnd.random() < 0.35:
            s.put(x, 0, rgb('#8d8a80'))
    s.rect(0, 0, 0, 15, CRATE[0]); s.rect(15, 0, 15, 15, CRATE[0])


@tex
def find_front():
    """The crate's front: planks, a cross brace, and a chalk mark somebody left (a circle with a tick: something here)."""
    s = Sprite()
    crate_planks(s, 31)
    s.line(1, 1, 14, 14, CRATE[0]); s.line(1, 2, 13, 14, CRATE[3])
    # the chalk mark in the upper right, clear of the brace: a ring, and a tick inside it
    for (x, y) in ((10, 2), (11, 2), (12, 2), (13, 3), (13, 4), (13, 5), (12, 6), (11, 6), (10, 6), (9, 5), (9, 4), (9, 3)):
        s.put(x, y, PAPER[4])
    s.put(10, 4, PAPER[3]); s.put(11, 5, PAPER[3]); s.put(12, 4, PAPER[3]); s.put(12, 3, PAPER[3])
    return s


@tex
def find_side():
    s = Sprite()
    crate_planks(s, 32)
    return s


@tex
def find_top():
    """The lid, with a frayed hessian sack thrown over half of it."""
    s = Sprite()
    crate_planks(s, 33)
    sack = ramp('#a88e5f')
    for y in range(2, 12):
        for x in range(3, 13 - (y % 3 == 0)):
            s.put(x, y, sack[2] if (x + y) % 3 else sack[1])
    s.line(3, 2, 12, 2, sack[3]); s.line(3, 11, 11, 11, sack[0])
    return s


# ------------------------------------------------------------------------------------------------ Personal computer (D-158)
PC_CASE = ramp('#c9c4b6')


@tex
def computer_case():
    s = Sprite()
    s.rect(0, 0, 15, 15, PC_CASE[2])
    s.rect(0, 0, 15, 0, PC_CASE[4]); s.rect(0, 15, 15, 15, PC_CASE[0])
    for x in range(2, 14, 3):
        s.put(x, 12, PC_CASE[1])
    s.put(13, 3, LEAF_LIME[3])
    return s


@tex
def computer_screen():
    """A desktop: a dark blue wallpaper, a title bar, four app tiles (service, bench, network, a red one) and a taskbar with a clock."""
    s = Sprite()
    s.rect(0, 0, 15, 15, PLASTIC_BLACK[0])
    s.rect(1, 1, 14, 14, rgb('#123049'))
    s.rect(1, 1, 14, 2, rgb('#1d3a52')); s.put(2, 1, CYAN[3]); s.put(13, 1, RED[3])
    for (x, y, c) in ((3, 4, CYAN), (7, 4, AMBER), (3, 8, LEAF_LIME), (7, 8, RED)):
        s.rect(x, y, x + 2, y + 2, c[2]); s.put(x, y, c[4])
    s.rect(11, 5, 13, 10, rgb('#0b1a26')); s.line(11, 6, 13, 6, CYAN[2]); s.line(11, 8, 12, 8, rgb('#8fb7cf'))
    s.rect(1, 13, 14, 14, rgb('#0b1a26')); s.rect(2, 13, 3, 13, CYAN[3]); s.rect(11, 13, 13, 13, rgb('#8fb7cf'))
    return s


@tex
def computer_keys():
    s = Sprite()
    s.rect(0, 0, 15, 15, PC_CASE[1])
    for y in range(2, 14, 3):
        for x in range(1, 15, 2):
            s.put(x, y, PC_CASE[3]); s.put(x, y + 1, PC_CASE[0])
    s.rect(4, 13, 11, 14, PC_CASE[3])
    return s


# ------------------------------------------------------------------------------------------------ Electronics bench (D-158)
@tex
def bench_top():
    """A work mat seen from above: a green ESD mat with a board in a clamp, a soldering iron in its stand, a reel of solder and loose parts."""
    s = Sprite()
    s.rect(0, 0, 15, 15, WOOD[2])
    s.rect(1, 1, 14, 14, rgb('#2d6a58'))
    s.line(1, 1, 14, 1, rgb('#3b8370')); s.line(1, 14, 14, 14, rgb('#1f4b3e'))
    s.rect(3, 3, 8, 7, PCB[2]); s.line(3, 3, 8, 3, PCB[3]); s.rect(5, 4, 6, 5, INK); s.line(3, 8, 8, 8, STEEL[2])
    s.line(10, 3, 13, 9, STEEL[3]); s.put(10, 3, AMBER[4]); s.rect(12, 9, 13, 10, BLUE[2])
    s.rect(3, 10, 5, 12, STEEL[2]); s.put(4, 11, INK)
    for (x, y, c) in ((8, 11, RED[3]), (9, 12, AMBER[3]), (11, 12, CYAN[3]), (7, 12, COPPER[3])):
        s.put(x, y, c)
    return s


@tex
def bench_front():
    """The bench's front: two drawers with brass pulls under a steel edge, a socket strip."""
    s = Sprite()
    s.rect(0, 0, 15, 15, WOOD[2])
    s.rect(0, 0, 15, 1, STEEL[3]); s.line(0, 2, 15, 2, STEEL[1])
    for y0 in (4, 10):
        s.rect(1, y0, 14, y0 + 4, WOOD[3]); s.line(1, y0, 14, y0, WOOD[4]); s.line(1, y0 + 4, 14, y0 + 4, WOOD[0])
        s.rect(6, y0 + 2, 9, y0 + 2, BRASS[3]); s.put(6, y0 + 2, BRASS[4])
    s.rect(11, 3, 14, 3, PLASTIC_BLACK[2]); s.put(12, 3, RED[3])
    return s


@tex
def bench_side():
    s = Sprite()
    s.rect(0, 0, 15, 15, WOOD[1])
    s.rect(0, 0, 15, 1, STEEL[3]); s.line(0, 2, 15, 2, STEEL[1])
    s.rect(1, 3, 2, 15, WOOD[2]); s.rect(13, 3, 14, 15, WOOD[2])
    s.rect(3, 6, 12, 7, WOOD[0])
    return s


# ------------------------------------------------------------------------------------------------ Drying rack (D-160)
RACK_LOADS = ('empty', 'drying', 'dry')


@tex
def rack_wood():
    s = Sprite()
    s.rect(0, 0, 15, 15, WOOD[2])
    for y in (2, 7, 12):
        s.line(0, y, 15, y, WOOD[1])
    for (x, y) in ((3, 4), (10, 9), (6, 14), (13, 1)):
        s.put(x, y, WOOD[3])
    return s


def _rack_hang(bud) -> Sprite:
    """The hanging face: three lines of twine from the bar with bundles of buds on them (none for an empty rack); transparent between."""
    s = Sprite()
    for x in (3, 8, 13):
        s.line(x, 0, x, 3, BURLAP[2])
        if bud is None:
            s.line(x, 3, x, 6, BURLAP[1])
            continue
        for (dx, dy, rad) in ((0, 6, 2.2), (-1, 9, 1.8), (1, 9, 1.8), (0, 12, 1.9)):
            for yy in range(16):
                for xx in range(16):
                    if (xx - (x + dx)) ** 2 + (yy - dy) ** 2 <= rad * rad:
                        s.put(xx, yy, bud[2] if (xx + yy) % 3 else bud[3])
        s.put(x, 5, bud[1]); s.put(x - 1, 13, bud[1]); s.put(x + 1, 8, AMBER[1] if bud is not LEAF_LIME else AMBER[2])
    return s


@tex
def rack_hang_empty():
    return _rack_hang(None)


@tex
def rack_hang_drying():
    return _rack_hang(LEAF_LIME)


@tex
def rack_hang_dry():
    return _rack_hang(ramp('#6b6a3f'))


# ------------------------------------------------------------------------------------------------------------ models
def box(f, t, faces, rotation=None, shade=True):
    el = {'from': f, 'to': t, 'faces': faces}
    if rotation:
        el['rotation'] = rotation
    if shade is False:
        el['shade'] = False
    return el


def all_faces(texture, uv=None):
    return {d: ({'texture': '#' + texture, 'uv': uv} if uv else {'texture': '#' + texture}) for d in ('north', 'south', 'east', 'west', 'up', 'down')}


def gui_display(rot=(30, 225, 0), scale=0.62, tr=(0, -1, 0)):
    return {'gui': {'rotation': list(rot), 'translation': list(tr), 'scale': [scale] * 3},
            'ground': {'scale': [0.5] * 3}, 'fixed': {'scale': [0.7] * 3}, 'thirdperson_righthand': {'scale': [0.4] * 3}}


def terminal_model():
    return {'textures': {'case': 'underground_city:block/terminal_case', 'screen': 'underground_city:block/terminal_screen', 'keys': 'underground_city:block/terminal_keys', 'particle': 'underground_city:block/terminal_case'},
            'elements': [
                box([2, 0, 2], [14, 1.5, 13], all_faces('case')),
                box([3, 1.5, 2.5], [13, 2.5, 7], {**all_faces('case'), 'up': {'texture': '#keys', 'uv': [0, 0, 16, 16]}}, rotation={'origin': [8, 1.5, 2.5], 'axis': 'x', 'angle': 22.5}),
                box([6.5, 1.5, 8.5], [9.5, 6, 10], all_faces('case')),
                box([2, 5.5, 8], [14, 15, 10.5], {'north': {'texture': '#screen', 'uv': [0, 0, 16, 16]}, 'south': {'texture': '#case'}, 'east': {'texture': '#case'}, 'west': {'texture': '#case'}, 'up': {'texture': '#case'}, 'down': {'texture': '#case'}}),
                box([4, 6.5, 10.5], [12, 13.5, 13], all_faces('case')),
                box([12, 5.8, 7.9], [13.2, 6.4, 8.1], {'north': {'texture': '#screen', 'uv': [14, 1, 15, 2]}}),
            ], 'display': gui_display((28, 215, 0), 0.7, (0, -2, 0))}


def secure_model():
    return {'textures': {'side': 'underground_city:block/secure_side', 'front': 'underground_city:block/secure_front', 'screen': 'underground_city:block/secure_screen', 'particle': 'underground_city:block/secure_side'},
            'elements': [
                box([2, 0, 3], [14, 8, 13], {'north': {'texture': '#front'}, 'south': {'texture': '#side'}, 'east': {'texture': '#side'}, 'west': {'texture': '#side'}, 'up': {'texture': '#side'}, 'down': {'texture': '#side'}}),
                box([2.5, 8, 4], [13.5, 8.8, 12], all_faces('side')),
                box([3, 8.8, 5], [13, 15, 6.2], {'north': {'texture': '#screen', 'uv': [0, 0, 16, 16]}, 'south': {'texture': '#side'}, 'east': {'texture': '#side'}, 'west': {'texture': '#side'}, 'up': {'texture': '#side'}, 'down': {'texture': '#side'}}, rotation={'origin': [8, 8.8, 5.6], 'axis': 'x', 'angle': -22.5}),
                box([2.5, 8.8, 4], [3.2, 13, 8], all_faces('side')),
                box([12.8, 8.8, 4], [13.5, 13, 8], all_faces('side')),
                box([3, 1, 2.6], [13, 1.8, 3], {'north': {'texture': '#side', 'uv': [0, 0, 16, 2]}}),
            ], 'display': gui_display((28, 215, 0), 0.7, (0, -2, 0))}


def safe_model():
    return {'textures': {'side': 'underground_city:block/safe_side', 'top': 'underground_city:block/safe_top', 'front': 'underground_city:block/safe_front', 'particle': 'underground_city:block/safe_side'},
            'elements': [
                box([1, 1, 1], [15, 15, 15], {'north': {'texture': '#front'}, 'south': {'texture': '#side'}, 'east': {'texture': '#side'}, 'west': {'texture': '#side'}, 'up': {'texture': '#top'}, 'down': {'texture': '#top'}}),
                box([1.5, 0, 1.5], [3.5, 1, 3.5], all_faces('top')), box([12.5, 0, 1.5], [14.5, 1, 3.5], all_faces('top')),
                box([1.5, 0, 12.5], [3.5, 1, 14.5], all_faces('top')), box([12.5, 0, 12.5], [14.5, 1, 14.5], all_faces('top')),
                box([0.5, 4, 0.2], [1.5, 6, 1.5], all_faces('side', [0, 0, 2, 2])),
                box([0.5, 10, 0.2], [1.5, 12, 1.5], all_faces('side', [0, 0, 2, 2])),
            ], 'display': gui_display((28, 215, 0), 0.62, (0, -1, 0))}


def ledger_model():
    return {'textures': {'board': 'underground_city:block/ledger_board', 'wood': 'underground_city:block/ledger_wood', 'particle': 'underground_city:block/ledger_wood'},
            'elements': [
                box([0.5, 0, 6.5], [2, 15, 8.5], all_faces('wood')), box([14, 0, 6.5], [15.5, 15, 8.5], all_faces('wood')),
                box([1.5, 4, 6.8], [14.5, 15, 8], {'north': {'texture': '#board', 'uv': [0, 0, 16, 16]}, 'south': {'texture': '#wood'}, 'east': {'texture': '#wood'}, 'west': {'texture': '#wood'}, 'up': {'texture': '#wood'}, 'down': {'texture': '#wood'}}),
                box([0, 15, 5.5], [16, 16.5, 9.5], {**all_faces('wood'), 'down': {'texture': '#wood'}}),
                box([3, 2.6, 6.4], [13, 3.4, 8.4], all_faces('wood')),
            ], 'display': gui_display((24, 200, 0), 0.6, (0, -2, 0))}


def panel_model(light):
    return {'textures': {'front': f'underground_city:block/alarm_panel_{light}', 'case': 'underground_city:block/alarm_panel_case', 'particle': 'underground_city:block/alarm_panel_case'},
            'elements': [
                box([4, 3, 15], [12, 13, 16], {'north': {'texture': '#front', 'uv': [4, 3, 12, 13]}, 'south': {'texture': '#case', 'uv': [4, 3, 12, 13]},
                                              'east': {'texture': '#case', 'uv': [0, 3, 1, 13]}, 'west': {'texture': '#case', 'uv': [0, 3, 1, 13]},
                                              'up': {'texture': '#case', 'uv': [4, 0, 12, 1]}, 'down': {'texture': '#case', 'uv': [4, 0, 12, 1]}}),
            ], 'display': gui_display((0, 180, 0), 1.0, (0, 0, -6))}


def panel_states():
    return {'variants': {f'facing={d},light={light}': ({'model': f'underground_city:block/alarm_panel_{light}'} | ({'y': y} if y else {}))
                         for d, y in (('north', 0), ('east', 90), ('south', 180), ('west', 270)) for light in PANEL_LIGHTS}}


def computer_model():
    return {'textures': {'case': 'underground_city:block/computer_case', 'screen': 'underground_city:block/computer_screen', 'keys': 'underground_city:block/computer_keys', 'particle': 'underground_city:block/computer_case'},
            'elements': [
                box([2, 0, 1.5], [14, 1, 7], {**all_faces('case'), 'up': {'texture': '#keys', 'uv': [0, 0, 16, 16]}}),
                box([1, 0, 8], [15, 1.5, 15], all_faces('case')),
                box([6.5, 1.5, 11.5], [9.5, 4, 13], all_faces('case')),
                box([1.5, 4, 9.5], [14.5, 14, 11.5], {'north': {'texture': '#screen', 'uv': [0, 0, 16, 16]}, 'south': {'texture': '#case'}, 'east': {'texture': '#case'}, 'west': {'texture': '#case'}, 'up': {'texture': '#case'}, 'down': {'texture': '#case'}}),
                box([3, 5, 11.5], [13, 13, 13.5], all_faces('case')),
                box([12.5, 0, 2.5], [14.5, 1.6, 5], all_faces('case')),
            ], 'display': gui_display((28, 215, 0), 0.7, (0, -2, 0))}


def rack_model(load):
    return {'parent': 'minecraft:block/block', 'render_type': 'minecraft:cutout',
            'textures': {'wood': 'underground_city:block/rack_wood', 'hang': f'underground_city:block/rack_hang_{load}', 'particle': 'underground_city:block/rack_wood'},
            'elements': [
                box([1, 0, 7], [2.5, 15, 9], {d: {'texture': '#wood'} for d in ('north', 'south', 'east', 'west', 'up')}),
                box([13.5, 0, 7], [15, 15, 9], {d: {'texture': '#wood'} for d in ('north', 'south', 'east', 'west', 'up')}),
                box([2.5, 13.5, 7.25], [13.5, 15, 8.75], all_faces('wood')),
                box([2.5, 2, 8], [13.5, 13.5, 8], {'north': {'texture': '#hang', 'uv': [2.5, 2.5, 13.5, 14]}, 'south': {'texture': '#hang', 'uv': [2.5, 2.5, 13.5, 14]}}, shade=False),
            ], 'display': gui_display((24, 200, 0), 0.62, (0, -1, 0))}


def write_models():
    os.makedirs(MODELS, exist_ok=True)
    for name, model in (('operations_terminal', terminal_model()), ('secure_data_terminal', secure_model()), ('property_cache', safe_model()), ('public_ledger', ledger_model()), ('personal_computer', computer_model())):
        model['parent'] = 'minecraft:block/block'
        # A bottom face that lies in the support block's top plane is never seen and z-fights with it (a pale ghost under the block).
        for element in model['elements']:
            if element['from'][1] <= 0.001 and 'rotation' not in element:
                element['faces'].pop('down', None)
        with open(os.path.join(MODELS, name + '.json'), 'w') as fh:
            json.dump(model, fh, indent=1)
    with open(os.path.join(MODELS, 'scrap_heap.json'), 'w') as fh:
        json.dump({'parent': 'minecraft:block/cube_all', 'textures': {'all': 'underground_city:block/scrap_heap'}}, fh, indent=1)
    with open(os.path.join(MODELS, 'electronics_bench.json'), 'w') as fh:
        json.dump({'parent': 'minecraft:block/orientable', 'textures': {'top': 'underground_city:block/bench_top', 'front': 'underground_city:block/bench_front',
                                                                        'side': 'underground_city:block/bench_side'}}, fh, indent=1)
    for light in UTILITY_LIGHTS:
        with open(os.path.join(MODELS, f'utility_panel_{light}.json'), 'w') as fh:
            json.dump({'parent': 'minecraft:block/orientable', 'textures': {'top': 'underground_city:block/utility_panel_side', 'front': f'underground_city:block/utility_panel_{light}',
                                                                            'side': 'underground_city:block/utility_panel_side'}}, fh, indent=1)
    with open(os.path.join(MODELS, 'exploration_find.json'), 'w') as fh:
        json.dump({'parent': 'minecraft:block/orientable', 'textures': {'top': 'underground_city:block/find_top', 'front': 'underground_city:block/find_front',
                                                                        'side': 'underground_city:block/find_side'}}, fh, indent=1)
    for state in DRIVE_STATES:
        with open(os.path.join(MODELS, f'machine_drive_{state}.json'), 'w') as fh:
            json.dump({'parent': 'minecraft:block/orientable', 'textures': {'top': 'underground_city:block/machine_drive_side', 'front': f'underground_city:block/machine_drive_{state}',
                                                                            'side': 'underground_city:block/machine_drive_side'}}, fh, indent=1)
    for load in RACK_LOADS:
        with open(os.path.join(MODELS, f'drying_rack_{load}.json'), 'w') as fh:
            json.dump(rack_model(load), fh, indent=1)
    for light in PANEL_LIGHTS:
        model = panel_model(light)
        model['parent'] = 'minecraft:block/block'
        with open(os.path.join(MODELS, f'alarm_panel_{light}.json'), 'w') as fh:
            json.dump(model, fh, indent=1)


def facing_states(name):
    return {'variants': {f'facing={d}': ({'model': f'underground_city:block/{name}'} | ({'y': y} if y else {})) for d, y in (('north', 0), ('east', 90), ('south', 180), ('west', 270))}}


def write_states():
    sd = os.path.join(ASSETS, 'blockstates')
    os.makedirs(sd, exist_ok=True)
    for name in ('operations_terminal', 'secure_data_terminal', 'property_cache', 'public_ledger', 'personal_computer', 'electronics_bench', 'exploration_find'):
        with open(os.path.join(sd, name + '.json'), 'w') as fh:
            json.dump(facing_states(name), fh, indent=1)
    with open(os.path.join(sd, 'utility_panel.json'), 'w') as fh:
        json.dump({'variants': {f'facing={d},state={light}': ({'model': f'underground_city:block/utility_panel_{light}'} | ({'y': y} if y else {}))
                                for d, y in (('north', 0), ('east', 90), ('south', 180), ('west', 270)) for light in UTILITY_LIGHTS}}, fh, indent=1)
    with open(os.path.join(sd, 'machine_drive.json'), 'w') as fh:
        json.dump({'variants': {f'facing={d},running={str(state == "running").lower()}': ({'model': f'underground_city:block/machine_drive_{state}'} | ({'y': y} if y else {}))
                                for d, y in (('north', 0), ('east', 90), ('south', 180), ('west', 270)) for state in DRIVE_STATES}}, fh, indent=1)
    with open(os.path.join(sd, 'drying_rack.json'), 'w') as fh:
        json.dump({'variants': {f'facing={d},load={load}': ({'model': f'underground_city:block/drying_rack_{load}'} | ({'y': y} if y else {}))
                                for d, y in (('north', 0), ('east', 90), ('south', 180), ('west', 270)) for load in RACK_LOADS}}, fh, indent=1)
    with open(os.path.join(sd, 'alarm_panel.json'), 'w') as fh:
        json.dump(panel_states(), fh, indent=1)
    with open(os.path.join(sd, 'scrap_heap.json'), 'w') as fh:
        json.dump({'variants': {'': {'model': 'underground_city:block/scrap_heap'}}}, fh, indent=1)


def build():
    os.makedirs(TEX, exist_ok=True)
    for name, fn in TEXTURES.items():
        fn().save(os.path.join(TEX, name + '.png'))
    write_models()
    write_states()


if __name__ == '__main__':
    build()
    import preview
    from PIL import Image
    root = os.path.abspath(os.path.join(ASSETS, '..'))
    sheet = Image.new('RGBA', (4 * 3 * 260, 260), (0, 0, 0, 0))
    for i, name in enumerate(('operations_terminal', 'secure_data_terminal', 'property_cache', 'public_ledger')):
        m = preview.load_model(os.path.join(MODELS, name + '.json'))
        for j, (yaw, pitch) in enumerate(((-35, 22), (35, 22), (150, 22))):
            sheet.paste(preview.render(m, root, yaw=yaw, pitch=pitch, size=260, zoom=1.05), ((i * 3 + j) * 260, 0))
    sheet.save(os.path.join(preview_dir(), 'blocks1.png'))
