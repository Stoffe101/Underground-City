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


def write_models():
    os.makedirs(MODELS, exist_ok=True)
    for name, model in (('operations_terminal', terminal_model()), ('secure_data_terminal', secure_model()), ('property_cache', safe_model()), ('public_ledger', ledger_model())):
        model['parent'] = 'minecraft:block/block'
        # A bottom face that lies in the support block's top plane is never seen and z-fights with it (a pale ghost under the block).
        for element in model['elements']:
            if element['from'][1] <= 0.001 and 'rotation' not in element:
                element['faces'].pop('down', None)
        with open(os.path.join(MODELS, name + '.json'), 'w') as fh:
            json.dump(model, fh, indent=1)


def facing_states(name):
    return {'variants': {f'facing={d}': ({'model': f'underground_city:block/{name}'} | ({'y': y} if y else {})) for d, y in (('north', 0), ('east', 90), ('south', 180), ('west', 270))}}


def write_states():
    sd = os.path.join(ASSETS, 'blockstates')
    os.makedirs(sd, exist_ok=True)
    for name in ('operations_terminal', 'secure_data_terminal', 'property_cache', 'public_ledger'):
        with open(os.path.join(sd, name + '.json'), 'w') as fh:
            json.dump(facing_states(name), fh, indent=1)


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
