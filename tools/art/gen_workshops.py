"""The workshop stations of the trades (D-163): textures, block models and blockstates for the Grading Table, the Mill, the
Fermentation Vat, the Oak Cask, the Pot Still, the Machinist's Bench and the Gunsmith's Bench, each in three tiers. A better tier
shows what was fitted to it: iron bands and brackets at tier 2, brass fittings, a gauge or a lamp at tier 3. 16x16 textures drawn
from primitives, shaded from the upper left. Original work.
"""
from __future__ import annotations
import json, os, sys
sys.path.insert(0, os.path.dirname(__file__))
from uc_art import Sprite, ramp, rgb
from palettes import *
from shapes import bevel_rect, disc

ASSETS = os.path.join(os.path.dirname(__file__), '..', '..', 'rpg-core', 'src', 'main', 'resources', 'assets', 'underground_city')
TEX = os.path.join(ASSETS, 'textures', 'block')
MODELS = os.path.join(ASSETS, 'models', 'block')

STATIONS = ('grading_table', 'mill', 'fermentation_vat', 'oak_cask', 'pot_still', 'machinist_bench', 'gunsmith_bench')
TIERS = (1, 2, 3)

OAK = ramp('#9a7650')
DARK_OAK = ramp('#5e4130')
WALNUT = ramp('#6b4a33')
STONE = ramp('#8d8e8a')
FELT = ramp('#3e6b4f')
MUST = ramp('#5a1f3c')
COPPER_HOT = ramp('#c06a3a')
BAND = {1: ramp('#4b3626'), 2: GUNMETAL, 3: BRASS}

TEXTURES: dict = {}


def tex(name):
    def wrap(fn):
        TEXTURES[name] = fn
        return fn
    return wrap


def planks(s: Sprite, wood, vertical=False, x0=0, y0=0, x1=15, y1=15, step=4):
    """Boards with dark seams and a few lighter grain pixels."""
    s.rect(x0, y0, x1, y1, wood[2])
    for i, k in enumerate(range((x0 if vertical else y0) + step - 1, (x1 if vertical else y1), step)):
        if vertical:
            s.line(k, y0, k, y1, wood[1])
        else:
            s.line(x0, k, x1, k, wood[1])
    for (x, y) in ((x0 + 2, y0 + 1), (x0 + 9, y0 + 6), (x0 + 5, y0 + 11), (x0 + 13, y0 + 13)):
        if x <= x1 and y <= y1:
            s.put(x, y, wood[3])


def band_h(s: Sprite, y, tier, x0=0, x1=15):
    r = BAND[tier]
    s.line(x0, y, x1, y, r[3]); s.line(x0, y + 1, x1, y + 1, r[1])
    if tier >= 2:
        for x in range(x0 + 2, x1, 6):
            s.put(x, y, r[4])


def band_v(s: Sprite, x, tier, y0=0, y1=15):
    r = BAND[tier]
    s.line(x, y0, x, y1, r[3]); s.line(x + 1, y0, x + 1, y1, r[1])
    if tier >= 2:
        for y in range(y0 + 2, y1, 6):
            s.put(x, y, r[4])


def corner_brackets(s: Sprite, tier):
    if tier < 2:
        return
    r = BAND[tier]
    for (x, y, dx, dy) in ((0, 0, 1, 1), (15, 0, -1, 1), (0, 15, 1, -1), (15, 15, -1, -1)):
        s.put(x, y, r[3]); s.put(x + dx, y, r[3]); s.put(x, y + dy, r[3]); s.put(x + 2 * dx, y, r[2]); s.put(x, y + 2 * dy, r[2])


def plate(s: Sprite, x0, y0, x1, y1):
    """A small brass maker's plate (tier 3)."""
    s.rect(x0, y0, x1, y1, BRASS[3]); s.line(x0, y0, x1, y0, BRASS[4]); s.line(x0, y1, x1, y1, BRASS[1])
    s.line(x0 + 1, (y0 + y1) // 2, x1 - 1, (y0 + y1) // 2, BRASS[1])


# ------------------------------------------------------------------------------------------------ Grading Table (Farmer)
for t in TIERS:
    @tex(f'ws_grading_top_{t}')
    def _gt(t=t):
        """The table seen from above: a slatted sorting tray in three bays (carrots, potatoes, apples), a scale at tier 3."""
        s = Sprite()
        planks(s, OAK)
        s.rect(1, 1, 14, 14, OAK[1])
        for i, c in enumerate((AMBER, BURLAP, RED)):
            x0 = 2 + i * 4
            s.rect(x0, 2, x0 + 3, 9 if t < 3 else 8, OAK[0])
            for (dx, dy) in ((1, 3), (2, 5), (1, 7), (2, 3)):
                s.put(x0 + dx, dy, c[3] if (dx + dy) % 2 else c[2])
        if t >= 2:
            band_h(s, 10, t, 1, 14)
        if t >= 3:
            s.rect(10, 11, 14, 14, BRASS[2]); s.rect(11, 12, 13, 13, PAPER[3]); s.put(12, 12, INK)
            s.rect(2, 12, 7, 13, PAPER[3]); s.line(3, 12, 6, 12, INK)
        else:
            s.rect(2, 11, 9, 14, BURLAP[2]); s.line(2, 11, 9, 11, BURLAP[3])
        corner_brackets(s, t)
        return s

    @tex(f'ws_grading_front_{t}')
    def _gf(t=t):
        """The apron: a crate of graded produce on a shelf between the legs, a brass grade plate at tier 3."""
        s = Sprite()
        planks(s, OAK)
        s.rect(0, 0, 15, 2, OAK[3]); s.line(0, 3, 15, 3, OAK[0])
        s.rect(0, 4, 1, 15, OAK[1]); s.rect(14, 4, 15, 15, OAK[1])
        bevel_rect(s, 3, 7, 12, 13, ramp('#8a6440'), strong=False)
        s.line(3, 10, 12, 10, OAK[0])
        for x in (5, 7, 9, 11):
            s.put(x, 8, AMBER[3] if x % 4 == 1 else RED[3])
        s.line(2, 14, 13, 14, OAK[0])
        if t >= 2:
            band_h(s, 4, t)
        if t >= 3:
            plate(s, 6, 1, 9, 2)
        corner_brackets(s, t)
        return s

    @tex(f'ws_grading_side_{t}')
    def _gs(t=t):
        s = Sprite()
        planks(s, OAK)
        s.rect(0, 0, 15, 2, OAK[3]); s.line(0, 3, 15, 3, OAK[0])
        s.rect(0, 4, 1, 15, OAK[1]); s.rect(14, 4, 15, 15, OAK[1]); s.rect(2, 4, 13, 15, OAK[0])
        s.line(2, 12, 13, 12, OAK[2])
        if t >= 2:
            band_h(s, 4, t)
        corner_brackets(s, t)
        return s


# ------------------------------------------------------------------------------------------------ Mill (Farmer)
for t in TIERS:
    @tex(f'ws_mill_base_{t}')
    def _mb(t=t):
        """The quern's stand: a stone trough in a wooden frame, flour at the spout."""
        s = Sprite()
        s.rect(0, 0, 15, 15, STONE[2])
        for (x, y) in ((2, 3), (9, 2), (5, 9), (12, 11), (3, 13), (13, 5)):
            s.put(x, y, STONE[1]); s.put(x + 1, y, STONE[3])
        s.rect(0, 0, 1, 15, OAK[2]); s.rect(14, 0, 15, 15, OAK[2]); s.rect(0, 0, 15, 1, OAK[3])
        s.rect(6, 6, 9, 9, STONE[0]); s.rect(6, 8, 9, 9, PAPER[3])
        if t >= 2:
            band_h(s, 12, t)
        return s

    @tex(f'ws_mill_stone_{t}')
    def _ms(t=t):
        """The upper millstone's edge: dressed stone with grooves, an iron ring at tier 2, a brass collar at tier 3."""
        s = Sprite()
        s.rect(0, 0, 15, 15, STONE[2])
        for x in range(1, 16, 3):
            s.line(x, 0, x, 15, STONE[1])
        s.line(0, 0, 15, 0, STONE[3])
        if t >= 2:
            band_h(s, 6, t)
        return s

    @tex(f'ws_mill_top_{t}')
    def _mt(t=t):
        """The upper stone from above: a round dressed face with a feed hole and furrows, wheat in the hole."""
        s = Sprite()
        s.rect(0, 0, 15, 15, STONE[1])
        disc(s, 7.5, 7.5, 7.2, STONE)
        for (x0, y0, x1, y1) in ((7, 1, 7, 5), (10, 3, 8, 6), (13, 7, 9, 7), (10, 12, 8, 9), (2, 7, 6, 7), (4, 3, 6, 6)):
            s.line(x0, y0, x1, y1, STONE[1])
        disc(s, 7.5, 7.5, 1.8, ramp('#3c3a36'))
        s.put(7, 7, AMBER[3]); s.put(8, 8, AMBER[2])
        if t >= 2:
            s.line(0, 0, 15, 0, BAND[t][3]); s.line(0, 15, 15, 15, BAND[t][1])
        if t >= 3:
            s.rect(12, 12, 14, 14, BRASS[3]); s.put(13, 13, BRASS[1])
        return s


# ------------------------------------------------------------------------------------------------ Fermentation Vat (Vintner)
for t in TIERS:
    @tex(f'ws_vat_side_{t}')
    def _vs(t=t):
        """Upright oak staves held by two hoops: withies at tier 1, iron at tier 2, brass at tier 3."""
        s = Sprite()
        planks(s, OAK, vertical=True, step=3)
        band_h(s, 2, t); band_h(s, 12, t)
        if t >= 3:
            band_h(s, 7, t)
        return s

    @tex(f'ws_vat_front_{t}')
    def _vf(t=t):
        """The front: the staves and hoops, a tap from tier 2 and a thermometer at tier 3."""
        s = TEXTURES[f'ws_vat_side_{t}']()
        if t >= 2:
            s.rect(7, 9, 8, 11, BAND[t][3]); s.rect(6, 10, 9, 10, BAND[t][2]); s.put(7, 11, MUST[3])
        if t >= 3:
            s.rect(12, 4, 12, 10, PAPER[4]); s.rect(12, 8, 12, 10, RED[3]); s.put(12, 11, RED[2])
        return s

    @tex(f'ws_vat_top_{t}')
    def _vt(t=t):
        """The open vat from above: dark must with a crust of skins and a little foam inside the rim."""
        s = Sprite()
        s.rect(0, 0, 15, 15, OAK[1])
        s.rect(1, 1, 14, 14, MUST[2])
        for (x, y) in ((3, 4), (8, 3), (11, 9), (5, 11), (9, 12), (12, 5)):
            s.put(x, y, MUST[3]); s.put(x + 1, y, PURPLE[2])
        for (x, y) in ((6, 6), (7, 7), (10, 6), (4, 8)):
            s.put(x, y, PAPER[3])
        s.line(0, 0, 15, 0, BAND[t][3]); s.line(0, 15, 15, 15, BAND[t][1]); s.line(0, 0, 0, 15, BAND[t][2]); s.line(15, 0, 15, 15, BAND[t][1])
        return s


# ------------------------------------------------------------------------------------------------ Oak Cask (Vintner)
for t in TIERS:
    @tex(f'ws_cask_end_{t}')
    def _ce(t=t):
        """The cask's head: round boards in a hoop, a bung and a chalked date, a brass tap at tier 3."""
        s = Sprite()
        s.rect(0, 0, 15, 15, DARK_OAK[1])
        disc(s, 7.5, 7.5, 7.3, BAND[t])
        disc(s, 7.5, 7.5, 6.2, OAK)
        for y in (4, 8, 11):
            s.line(2, y, 13, y, OAK[1])
        s.rect(3, 9, 6, 9, PAPER[4]); s.put(5, 10, PAPER[4])
        if t >= 3:
            s.rect(7, 11, 8, 13, BRASS[3]); s.put(7, 13, BRASS[1])
        else:
            s.rect(7, 12, 8, 12, DARK_OAK[0])
        return s

    @tex(f'ws_cask_side_{t}')
    def _cs(t=t):
        """The cask's belly along its length: staves lying along it, hoops across."""
        s = Sprite()
        planks(s, OAK, vertical=False, step=3)
        band_v(s, 2, t); band_v(s, 12, t)
        if t >= 2:
            band_v(s, 7, t)
        return s

    @tex(f'ws_cask_top_{t}')
    def _ct(t=t):
        """The cask's top along its length: staves running front to back, hoops across, the bung."""
        s = Sprite()
        planks(s, OAK, vertical=True, step=3)
        band_h(s, 2, t); band_h(s, 12, t)
        if t >= 2:
            band_h(s, 7, t)
        s.rect(7, 9, 8, 10, DARK_OAK[0])
        return s

    @tex(f'ws_cask_rack_{t}')
    def _cr(t=t):
        s = Sprite()
        planks(s, DARK_OAK)
        if t >= 2:
            band_h(s, 7, t)
        return s


# ------------------------------------------------------------------------------------------------ Pot Still (Vintner)
for t in TIERS:
    @tex(f'ws_still_copper_{t}')
    def _sc(t=t):
        """Hammered copper: dimpled, warm at the top and darker below; rivets at tier 2, polish and a gauge at tier 3."""
        s = Sprite()
        r = COPPER_HOT if t >= 3 else COPPER
        for y in range(16):
            for x in range(16):
                tone = 3 if y < 4 else 2 if y < 11 else 1
                if (x * 7 + y * 3) % 11 == 0:
                    tone = max(0, tone - 1)
                if (x * 5 + y * 9) % 13 == 0:
                    tone = min(4, tone + 1)
                s.put(x, y, r[tone])
        if t >= 2:
            for x in range(1, 16, 3):
                s.put(x, 2, STEEL[3]); s.put(x, 13, STEEL[3])
        if t >= 3:
            disc(s, 11.5, 6.5, 2.4, STEEL)
            disc(s, 11.5, 6.5, 1.5, PAPER)
            s.put(12, 6, RED[3]); s.put(11, 6, INK)
        return s

    @tex(f'ws_still_top_{t}')
    def _st(t=t):
        s = Sprite()
        r = COPPER_HOT if t >= 3 else COPPER
        s.rect(0, 0, 15, 15, r[2])
        disc(s, 7.5, 7.5, 3.0, r)
        s.put(7, 7, r[0]); s.put(8, 8, r[0])
        return s

    @tex(f'ws_still_fire_{t}')
    def _sf(t=t):
        """The firebox under the pot: brick with an iron door; a glow behind the grate."""
        s = Sprite()
        s.rect(0, 0, 15, 15, ramp('#8a4b3a')[2])
        for y in range(3, 16, 4):
            s.line(0, y, 15, y, ramp('#8a4b3a')[1])
            for x in range((y // 4 % 2) * 4, 16, 8):
                s.line(x, y - 3, x, y - 1, ramp('#8a4b3a')[1])
        s.rect(4, 6, 11, 13, GUNMETAL[1]); s.rect(5, 8, 10, 12, AMBER[2]); s.line(5, 10, 10, 10, RED[3])
        for x in (6, 8, 10):
            s.line(x, 8, x, 12, GUNMETAL[0])
        return s


# ------------------------------------------------------------------------------------------------ Machinist's Bench (Smith)
for t in TIERS:
    @tex(f'ws_machinist_top_{t}')
    def _mtp(t=t):
        """A steel-topped bench from above: a vice, files and a drawn part; a drill press at tier 2 and a small lathe bed at tier 3."""
        s = Sprite()
        s.rect(0, 0, 15, 15, STEEL[1])
        for y in range(1, 15, 2):
            s.line(1, y, 14, y, STEEL[2])
        s.line(0, 0, 15, 0, STEEL[3])
        s.rect(1, 2, 5, 6, GUNMETAL[2]); s.rect(2, 3, 4, 3, GUNMETAL[4]); s.line(3, 7, 3, 9, STEEL[4])
        s.line(7, 3, 12, 3, WOOD_T[2]); s.line(7, 5, 11, 5, STEEL[4]); s.put(12, 5, WOOD_T[2])
        s.rect(7, 10, 11, 13, PAPER[3]); s.line(8, 11, 10, 11, BLUE[2]); s.line(8, 12, 9, 12, BLUE[2])
        if t >= 2:
            disc(s, 13, 11, 2.0, GUNMETAL); s.put(13, 11, INK)
        if t >= 3:
            s.rect(1, 12, 6, 14, GUNMETAL[1]); s.line(1, 13, 6, 13, STEEL[4]); s.rect(1, 12, 2, 14, BRASS[3])
        return s

    @tex(f'ws_machinist_front_{t}')
    def _mfr(t=t):
        """Steel drawers under a steel edge; a wooden carcass at tier 1, steel at tier 2, with a brass plate at tier 3."""
        s = Sprite()
        body = OAK if t == 1 else GUNMETAL
        s.rect(0, 0, 15, 15, body[2])
        s.rect(0, 0, 15, 1, STEEL[3]); s.line(0, 2, 15, 2, STEEL[1])
        for y0 in (4, 9):
            s.rect(1, y0, 14, y0 + 3, body[3]); s.line(1, y0, 14, y0, body[4]); s.line(1, y0 + 3, 14, y0 + 3, body[0])
            s.rect(6, y0 + 1, 9, y0 + 1, STEEL[4])
        s.rect(1, 14, 14, 15, body[1])
        if t >= 3:
            plate(s, 11, 13, 14, 14)
        return s

    @tex(f'ws_machinist_side_{t}')
    def _msd(t=t):
        s = Sprite()
        body = OAK if t == 1 else GUNMETAL
        s.rect(0, 0, 15, 15, body[1])
        s.rect(0, 0, 15, 1, STEEL[3]); s.line(0, 2, 15, 2, STEEL[1])
        s.rect(1, 3, 2, 15, body[2]); s.rect(13, 3, 14, 15, body[2]); s.rect(3, 8, 12, 9, body[0])
        if t >= 2:
            for y in (5, 11):
                for x in (4, 7, 10):
                    s.put(x, y, INK)
        return s


# ------------------------------------------------------------------------------------------------ Gunsmith's Bench (Gunsmith)
for t in TIERS:
    @tex(f'ws_gunsmith_top_{t}')
    def _gtp(t=t):
        """A walnut bench from above: a green felt mat with a parts tray, a screwdriver set and a cleaning rod; a jig at tier 2, a test fixture with a lamp at tier 3."""
        s = Sprite()
        planks(s, WALNUT)
        s.rect(1, 1, 14, 14, FELT[2]); s.line(1, 1, 14, 1, FELT[3]); s.line(1, 14, 14, 14, FELT[1])
        s.rect(2, 2, 6, 5, STEEL[2]); s.put(3, 3, BRASS[3]); s.put(5, 4, STEEL[4]); s.put(4, 4, INK)
        for i, c in enumerate((RED, AMBER, BLUE)):
            s.line(9 + i * 2, 2, 9 + i * 2, 6, c[3]); s.put(9 + i * 2, 7, STEEL[4])
        s.line(2, 11, 13, 11, STEEL[3]); s.put(13, 11, BRASS[3])
        if t >= 2:
            s.rect(2, 7, 7, 9, GUNMETAL[2]); s.line(2, 7, 7, 7, GUNMETAL[4])
        if t >= 3:
            s.rect(9, 9, 13, 13, GUNMETAL[1]); disc(s, 11, 11, 1.4, AMBER); s.put(11, 11, PAPER[4])
        return s

    @tex(f'ws_gunsmith_front_{t}')
    def _gfr(t=t):
        """Walnut drawers with brass pulls; a locked steel cabinet door at tier 3."""
        s = Sprite()
        s.rect(0, 0, 15, 15, WALNUT[2])
        s.rect(0, 0, 15, 1, WALNUT[4]); s.line(0, 2, 15, 2, WALNUT[0])
        for y0 in (4, 9):
            s.rect(1, y0, 7 if t >= 3 else 14, y0 + 3, WALNUT[3]); s.line(1, y0, 7 if t >= 3 else 14, y0, WALNUT[4])
            s.rect(3, y0 + 1, 5, y0 + 1, BRASS[3])
        if t >= 3:
            s.rect(9, 4, 14, 12, GUNMETAL[2]); s.line(9, 4, 14, 4, GUNMETAL[4]); s.rect(12, 8, 13, 8, BRASS[3]); s.put(12, 9, INK)
        elif t >= 2:
            s.put(1, 4, GUNMETAL[3]); s.put(14, 4, GUNMETAL[3]); s.put(1, 12, GUNMETAL[3]); s.put(14, 12, GUNMETAL[3])
        s.rect(1, 14, 14, 15, WALNUT[1])
        return s

    @tex(f'ws_gunsmith_side_{t}')
    def _gsd(t=t):
        s = Sprite()
        s.rect(0, 0, 15, 15, WALNUT[1])
        s.rect(0, 0, 15, 1, WALNUT[4]); s.line(0, 2, 15, 2, WALNUT[0])
        s.rect(1, 3, 2, 15, WALNUT[2]); s.rect(13, 3, 14, 15, WALNUT[2]); s.rect(3, 9, 12, 10, WALNUT[0])
        if t >= 2:
            band_h(s, 5, t, 3, 12)
        return s


WOOD_T = ramp('#8a5a32')


# ------------------------------------------------------------------------------------------------------------ models
def box(f, t, faces):
    return {'from': f, 'to': t, 'faces': faces}


def faces(**by):
    """Faces from keyword textures: n, s, e, w, u, d (missing faces are left out)."""
    names = {'n': 'north', 's': 'south', 'e': 'east', 'w': 'west', 'u': 'up', 'd': 'down'}
    return {names[k]: {'texture': '#' + v} for k, v in by.items() if v}


def gui(rot=(30, 225, 0), scale=0.62, tr=(0, 0, 0)):
    return {'gui': {'rotation': list(rot), 'translation': list(tr), 'scale': [scale] * 3},
            'ground': {'scale': [0.4] * 3}, 'fixed': {'scale': [0.6] * 3}, 'thirdperson_righthand': {'rotation': [75, 45, 0], 'translation': [0, 2.5, 0], 'scale': [0.375] * 3}}


def model(station, t):
    p = f'underground_city:block/ws_'
    if station == 'grading_table':
        textures = {'top': f'{p}grading_top_{t}', 'front': f'{p}grading_front_{t}', 'side': f'{p}grading_side_{t}'}
        elements = [box([0, 0, 0], [16, 15, 16], faces(n='front', s='side', e='side', w='side', u='top'))]
    elif station == 'mill':
        textures = {'base': f'{p}mill_base_{t}', 'stone': f'{p}mill_stone_{t}', 'top': f'{p}mill_top_{t}', 'wood': 'underground_city:block/ws_cask_rack_1'}
        elements = [box([1, 0, 1], [15, 7, 15], faces(n='base', s='base', e='base', w='base', u='base')),
                    box([2, 7, 2], [14, 11, 14], faces(n='stone', s='stone', e='stone', w='stone', u='top')),
                    box([11, 11, 3], [12.5, 15, 4.5], faces(n='wood', s='wood', e='wood', w='wood', u='wood'))]
    elif station == 'fermentation_vat':
        textures = {'side': f'{p}vat_side_{t}', 'front': f'{p}vat_front_{t}', 'top': f'{p}vat_top_{t}'}
        elements = [box([1, 0, 1], [15, 15, 15], faces(n='front', s='side', e='side', w='side', u='top'))]
    elif station == 'oak_cask':
        textures = {'end': f'{p}cask_end_{t}', 'side': f'{p}cask_side_{t}', 'top': f'{p}cask_top_{t}', 'rack': f'{p}cask_rack_{t}'}
        elements = [box([1, 2, 1], [15, 13, 15], faces(n='end', s='end', e='side', w='side', u='top', d='rack')),
                    box([2, 0, 2], [4, 2, 14], faces(n='rack', s='rack', e='rack', w='rack', u='rack')),
                    box([12, 0, 2], [14, 2, 14], faces(n='rack', s='rack', e='rack', w='rack', u='rack'))]
    elif station == 'pot_still':
        textures = {'copper': f'{p}still_copper_{t}', 'top': f'{p}still_top_{t}', 'fire': f'{p}still_fire_{t}'}
        elements = [box([1, 0, 1], [15, 4, 15], faces(n='fire', s='fire', e='fire', w='fire', u='fire')),
                    box([2, 4, 2], [14, 11, 14], faces(n='copper', s='copper', e='copper', w='copper', u='top')),
                    box([5, 11, 5], [11, 13, 11], faces(n='copper', s='copper', e='copper', w='copper', u='top')),
                    box([6.5, 13, 6.5], [9.5, 16, 9.5], faces(n='copper', s='copper', e='copper', w='copper', u='top')),
                    box([9.5, 14, 7], [16, 15.5, 8.5], faces(n='copper', s='copper', e='copper', w='copper', u='copper', d='copper'))]
    elif station == 'machinist_bench':
        textures = {'top': f'{p}machinist_top_{t}', 'front': f'{p}machinist_front_{t}', 'side': f'{p}machinist_side_{t}'}
        elements = [box([0, 0, 0], [16, 15, 16], faces(n='front', s='side', e='side', w='side', u='top'))]
    else:
        textures = {'top': f'{p}gunsmith_top_{t}', 'front': f'{p}gunsmith_front_{t}', 'side': f'{p}gunsmith_side_{t}'}
        elements = [box([0, 0, 0], [16, 15, 16], faces(n='front', s='side', e='side', w='side', u='top'))]
    textures['particle'] = next(iter(textures.values()))
    return {'parent': 'minecraft:block/block', 'textures': textures, 'elements': elements, 'display': gui()}


def states(station):
    return {'variants': {f'facing={d},tier={t},working={w}': ({'model': f'underground_city:block/{station}_{t}'} | ({'y': y} if y else {}))
                         for d, y in (('north', 0), ('east', 90), ('south', 180), ('west', 270)) for t in TIERS for w in ('false', 'true')}}


def build():
    os.makedirs(TEX, exist_ok=True)
    os.makedirs(MODELS, exist_ok=True)
    for name, fn in TEXTURES.items():
        fn().save(os.path.join(TEX, name + '.png'))
    sd = os.path.join(ASSETS, 'blockstates')
    os.makedirs(sd, exist_ok=True)
    for station in STATIONS:
        for t in TIERS:
            with open(os.path.join(MODELS, f'{station}_{t}.json'), 'w') as fh:
                json.dump(model(station, t), fh, indent=1)
        with open(os.path.join(sd, f'{station}.json'), 'w') as fh:
            json.dump(states(station), fh, indent=1)


if __name__ == '__main__':
    build()
