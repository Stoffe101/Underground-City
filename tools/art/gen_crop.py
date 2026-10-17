"""Sable Leaf crop: textures and the four growth-stage block models (plus a 'fed' variant of each with a nutrient stake).

The model is built from flat leaf quads that all start at the stem, so no leaf floats: a leaf pointing north is a square
quad whose south edge midpoint sits on the stem; the texture is turned so that its fingers point away from it. Yaw
offsets (multiples of 22.5 degrees about the stem) and tilts (about the quad's root edge) give the plant volume.
"""
from __future__ import annotations
import json, math, os, sys
sys.path.insert(0, os.path.dirname(__file__))
from uc_art import Sprite
import sable, sable_parts
from faces import FACE_CORNERS

ASSETS = os.path.join(os.path.dirname(__file__), '..', '..', 'rpg-core', 'src', 'main', 'resources', 'assets', 'underground_city')
TEX = os.path.join(ASSETS, 'textures', 'block')
MODELS = os.path.join(ASSETS, 'models', 'block')

DIRS = {'N': (0, -1), 'E': (1, 0), 'S': (0, 1), 'W': (-1, 0)}


def write_textures():
    textures = {
        'sable_leaf3': sable.palmate_leaf(3, palette=sable.LEAF_YOUNG),
        'sable_leaf5': sable.palmate_leaf(5, palette=sable.LEAF),
        'sable_leaf5_deep': sable.palmate_leaf(5, palette=sable.LEAF_DEEP),
        'sable_leaf7': sable.palmate_leaf(7, palette=sable.LEAF),
        'sable_leaf7_deep': sable.palmate_leaf(7, palette=sable.LEAF_DEEP),
        'sable_cola': sable_parts.cola(),
        'sable_seedling': sable_parts.seedling(),
        'sable_stem': sable_parts.stem(),
        'sable_stake': sable_parts.stake(),
    }
    for name, sprite in textures.items():
        sprite.save(os.path.join(TEX, name + '.png'))


def _face(tex, uv=None, rotation=0):
    face = {'texture': '#' + tex}
    if uv is not None:
        face['uv'] = uv
    if rotation:
        face['rotation'] = rotation
    return face


def _top_direction(face_name, frm, to, rotation):
    """World direction (dx, dz) that the texture's top edge points to on a horizontal face with the given rotation."""
    pts = {'x0': frm[0], 'x1': to[0], 'y0': frm[1], 'y1': to[1], 'z0': frm[2], 'z1': to[2]}
    corners = [tuple(pts[c] for c in cc) for cc in FACE_CORNERS[face_name]]   # TL, TR, BR, BL
    r = (rotation // 90) % 4
    order = corners[-r:] + corners[:-r] if r else corners                      # position taken by texture TL, TR, BR, BL... shifted
    # after rotation, the texture's TL sits where `order[0]` is; top edge runs TL->TR, bottom edge BL->BR
    tl, tr, br, bl = order[0], order[1], order[2], order[3]
    top_mid = ((tl[0] + tr[0]) / 2, (tl[2] + tr[2]) / 2)
    bot_mid = ((bl[0] + br[0]) / 2, (bl[2] + br[2]) / 2)
    return (top_mid[0] - bot_mid[0], top_mid[1] - bot_mid[1])


def _rotation_for(face_name, frm, to, want):
    best, best_dot = 0, -9
    for rot in (0, 90, 180, 270):
        d = _top_direction(face_name, frm, to, rot)
        norm = math.hypot(*d) or 1
        dot = (d[0] * want[0] + d[1] * want[1]) / norm
        if dot > best_dot:
            best, best_dot = rot, dot
    return best


def leaf(y, direction, size, tex, yaw=0.0, tilt=0.0):
    """A flat leaf quad rooted on the stem at height y (block units). tilt > 0 raises the tip."""
    dx, dz = DIRS[direction]
    half = size / 2
    if direction == 'N':
        frm, to = [8 - half, y, 8 - size], [8 + half, y, 8]
    elif direction == 'S':
        frm, to = [8 - half, y, 8], [8 + half, y, 8 + size]
    elif direction == 'E':
        frm, to = [8, y, 8 - half], [8 + size, y, 8 + half]
    else:
        frm, to = [8 - size, y, 8 - half], [8, y, 8 + half]
    up_rot = _rotation_for('up', frm, to, (dx, dz))
    down_rot = _rotation_for('down', frm, to, (dx, dz))
    el = {'from': frm, 'to': to, 'shade': False,
          'faces': {'up': _face(tex, [0, 0, 16, 16], up_rot), 'down': _face(tex, [0, 0, 16, 16], down_rot)}}
    if yaw:
        el['rotation'] = {'origin': [8, y, 8], 'axis': 'y', 'angle': yaw}
    elif tilt:
        if direction in 'NS':
            sign = 1 if direction == 'N' else -1
            el['rotation'] = {'origin': [8, y, 8], 'axis': 'x', 'angle': tilt * sign}
        else:
            sign = 1 if direction == 'E' else -1
            el['rotation'] = {'origin': [8, y, 8], 'axis': 'z', 'angle': tilt * sign}
    return el


def cross(tex, size_w, size_h, y0=0, cx=8, cz=8, yaw=45):
    """Two vertical planes crossing at (cx, cz), each rotated +-yaw (the vanilla plant cross)."""
    els = []
    for a in (yaw, -yaw):
        els.append({'from': [cx - size_w / 2, y0, cz], 'to': [cx + size_w / 2, y0 + size_h, cz], 'shade': False,
                    'rotation': {'origin': [cx, y0, cz], 'axis': 'y', 'angle': a},
                    'faces': {'north': _face(tex, [0, 0, 16, 16]), 'south': _face(tex, [0, 0, 16, 16])}})
    return els


def stem(h, x=8, z=8, w=1.5):
    """The main stem as boxes of at most 16 units each: a model UV must stay inside the 16 x 16 sprite, so a taller stem is stacked."""
    els = []
    y = 0.0
    while y < h - 1e-6:
        seg = min(16.0, h - y)
        f = [x - w / 2, y, z - w / 2]
        t = [x + w / 2, y + seg, z + w / 2]
        els.append({'from': f, 'to': t, 'shade': False,
                    'faces': {s: _face('stem', [6.5, 16 - seg, 6.5 + w, 16]) for s in ('north', 'south', 'east', 'west')} | {'up': _face('stem', [6.5, 0, 6.5 + w, w])}})
        y += seg
    return els


def branch(y, direction, length, w=1.0):
    dx, dz = DIRS[direction]
    x0, x1 = (8, 8 + dx * length) if dx else (8 - w / 2, 8 + w / 2)
    z0, z1 = (8, 8 + dz * length) if dz else (8 - w / 2, 8 + w / 2)
    f = [min(x0, x1), y, min(z0, z1)]
    t = [max(x0, x1), y + w, max(z0, z1)]
    return {'from': f, 'to': t, 'shade': False, 'faces': {s: _face('stem', [9.0, 2.0, 9.0 + w, 2.0 + w]) for s in ('north', 'south', 'east', 'west', 'up', 'down')}}


def cola_at(cx, cz, y0, w, h, yaw=45):
    return cross('cola', w, h, y0, cx, cz, yaw)


def stake_el():
    return {'from': [11.2, 0, 3.2], 'to': [12.2, 5, 4.2], 'shade': False,
            'faces': {s: _face('stake', [6, 11, 7, 16]) for s in ('north', 'south', 'east', 'west')} | {'up': _face('stake', [6, 10, 7, 11])}}


def _specs(rows):
    return [leaf(y, d, s, t, **{kind: a}) for (y, d, s, t, kind, a) in rows]


def stage_elements(age: int):
    els = []
    if age == 0:
        els += cross('seedling', 11, 11, 0)
        return els
    if age == 1:
        els += stem(7, w=0.8)
        els += _specs([(1.6, 'N', 5.0, 'leaf3', 'tilt', -22.5), (1.6, 'S', 5.0, 'leaf3', 'tilt', -22.5),
                       (3.2, 'E', 5.5, 'leaf5', 'tilt', 22.5), (3.2, 'W', 5.5, 'leaf5', 'tilt', 22.5),
                       (5.0, 'N', 4.5, 'leaf3', 'yaw', 22.5), (5.0, 'S', 4.5, 'leaf3', 'yaw', 22.5),
                       (7.0, 'E', 3.5, 'leaf3', 'tilt', 45), (7.0, 'W', 3.5, 'leaf3', 'tilt', 45)])
        return els
    if age == 2:
        els += stem(12, w=0.9)
        els += _specs([(3.0, 'N', 7.0, 'leaf5_deep', 'tilt', -22.5), (3.0, 'S', 7.0, 'leaf5_deep', 'tilt', -22.5),
                       (3.0, 'E', 7.0, 'leaf5_deep', 'tilt', -22.5), (3.0, 'W', 7.0, 'leaf5_deep', 'tilt', -22.5),
                       (6.0, 'N', 6.5, 'leaf7', 'yaw', 22.5), (6.0, 'S', 6.5, 'leaf7', 'yaw', 22.5),
                       (6.0, 'E', 6.5, 'leaf7', 'yaw', -22.5), (6.0, 'W', 6.5, 'leaf7', 'yaw', -22.5),
                       (9.0, 'N', 5.5, 'leaf5', 'tilt', 22.5), (9.0, 'S', 5.5, 'leaf5', 'tilt', 22.5),
                       (9.0, 'E', 5.5, 'leaf5', 'tilt', 22.5), (9.0, 'W', 5.5, 'leaf5', 'tilt', 22.5),
                       (11.5, 'N', 4.0, 'leaf3', 'tilt', 45), (11.5, 'S', 4.0, 'leaf3', 'tilt', 45),
                       (11.5, 'E', 4.0, 'leaf3', 'tilt', 45), (11.5, 'W', 4.0, 'leaf3', 'tilt', 45)])
        return els
    # age 3: mature and flowering
    els += stem(19, w=1.0)
    els += _specs([(6.0, 'N', 8.5, 'leaf7_deep', 'tilt', -45), (6.0, 'S', 8.5, 'leaf7_deep', 'tilt', -45),
                   (6.0, 'E', 8.5, 'leaf7_deep', 'tilt', -45), (6.0, 'W', 8.5, 'leaf7_deep', 'tilt', -45),
                   (8.5, 'N', 8.5, 'leaf7', 'yaw', 22.5), (8.5, 'S', 8.5, 'leaf7', 'yaw', 22.5),
                   (8.5, 'E', 8.5, 'leaf7', 'yaw', -22.5), (8.5, 'W', 8.5, 'leaf7', 'yaw', -22.5),
                   (10.5, 'N', 8.0, 'leaf7', 'tilt', 22.5), (10.5, 'S', 8.0, 'leaf7', 'tilt', 22.5),
                   (10.5, 'E', 8.0, 'leaf7', 'tilt', 22.5), (10.5, 'W', 8.0, 'leaf7', 'tilt', 22.5),
                   (12.5, 'N', 7.0, 'leaf5', 'yaw', -22.5), (12.5, 'S', 7.0, 'leaf5', 'yaw', -22.5),
                   (12.5, 'E', 7.0, 'leaf5', 'yaw', 22.5), (12.5, 'W', 7.0, 'leaf5', 'yaw', 22.5),
                   (14.5, 'N', 6.0, 'leaf5', 'tilt', 22.5), (14.5, 'S', 6.0, 'leaf5', 'tilt', 22.5),
                   (14.5, 'E', 6.0, 'leaf5', 'tilt', 22.5), (14.5, 'W', 6.0, 'leaf5', 'tilt', 22.5),
                   (16.5, 'N', 4.5, 'leaf3', 'tilt', 45), (16.5, 'S', 4.5, 'leaf3', 'tilt', 45),
                   (16.5, 'E', 4.5, 'leaf3', 'tilt', 45), (16.5, 'W', 4.5, 'leaf3', 'tilt', 45)])
    # side branches carrying small flowering clusters, and the main cola on the top of the stem
    els += [branch(10.0, 'W', 3.4, w=0.8), branch(12.4, 'E', 3.4, w=0.8), branch(8.2, 'S', 3.4, w=0.8)]
    els += cola_at(4.6, 8.0, 9.4, 5.0, 7.0) + cola_at(11.4, 8.0, 11.8, 5.0, 7.0) + cola_at(8.0, 11.4, 7.6, 5.0, 7.0)
    els += cola_at(8, 8, 14.0, 7.0, 11.5)
    return els


def model(age: int, fed: bool) -> dict:
    els = stage_elements(age)
    if fed:
        els.append(stake_el())
    texmap = {'leaf3': 'underground_city:block/sable_leaf3', 'leaf5': 'underground_city:block/sable_leaf5',
              'leaf5_deep': 'underground_city:block/sable_leaf5_deep', 'leaf7': 'underground_city:block/sable_leaf7',
              'leaf7_deep': 'underground_city:block/sable_leaf7_deep', 'cola': 'underground_city:block/sable_cola',
              'seedling': 'underground_city:block/sable_seedling', 'stem': 'underground_city:block/sable_stem',
              'stake': 'underground_city:block/sable_stake', 'particle': 'underground_city:block/sable_leaf5'}
    return {'render_type': 'minecraft:cutout', 'ambientocclusion': False, 'textures': texmap, 'elements': els}


def write_models():
    for age in range(4):
        for fed in (False, True):
            name = f'sable_leaf_crop_stage{age}' + ('_fed' if fed else '')
            with open(os.path.join(MODELS, name + '.json'), 'w') as fh:
                json.dump(model(age, fed), fh, indent=1)
    variants = {}
    for age in range(4):
        for fed in (False, True):
            variants[f'age={age},nourished={"true" if fed else "false"}'] = {'model': f'underground_city:block/sable_leaf_crop_stage{age}' + ('_fed' if fed else '')}
    os.makedirs(os.path.join(ASSETS, 'blockstates'), exist_ok=True)
    with open(os.path.join(ASSETS, 'blockstates', 'sable_leaf_crop.json'), 'w') as fh:
        json.dump({'variants': variants}, fh, indent=1)


if __name__ == '__main__':
    write_textures()
    write_models()
    print('sable leaf textures and models written')
