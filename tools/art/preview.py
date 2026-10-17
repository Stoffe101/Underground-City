"""A small software renderer for Minecraft block-model JSON, used to iterate on geometry and UVs without a game client.

It follows the vanilla face and UV conventions (north/south/east/west/up/down corner order, face rotation, element
rotation about one axis) closely enough to judge shapes; the real client remains the judge of the final result.
"""
from __future__ import annotations
import json, math, os, sys
import numpy as np
from PIL import Image

from faces import FACE_CORNERS, NORMALS  # noqa: E402  (shared with the generators, which must not need numpy)


def default_uv(face, f, t):
    x0, y0, z0 = f
    x1, y1, z1 = t
    return {'north': [16 - x1, 16 - y1, 16 - x0, 16 - y0], 'south': [x0, 16 - y1, x1, 16 - y0], 'east': [16 - z1, 16 - y1, 16 - z0, 16 - y0],
            'west': [z0, 16 - y1, z1, 16 - y0], 'up': [x0, z0, x1, z1], 'down': [x0, 16 - z1, x1, 16 - z0]}[face]


def rot_point(p, origin, axis, angle):
    a = math.radians(angle)
    x, y, z = (p[0] - origin[0], p[1] - origin[1], p[2] - origin[2])
    c, s = math.cos(a), math.sin(a)
    if axis == 'x':
        y, z = y * c - z * s, y * s + z * c
    elif axis == 'y':
        x, z = x * c + z * s, -x * s + z * c
    else:
        x, y = x * c - y * s, x * s + y * c
    return (x + origin[0], y + origin[1], z + origin[2])


def load_model(path, resolve_parent=None):
    with open(path) as fh:
        model = json.load(fh)
    return model


def render(model, texdir, yaw=35.0, pitch=28.0, size=384, bg=(58, 66, 78, 255), cutout=True, zoom=1.0, center=(8, 8, 8), tex_override=None):
    textures = dict(model.get('textures', {}))
    cache = {}

    def tex(ref):
        while ref.startswith('#'):
            ref = textures.get(ref[1:], '')
        if tex_override and ref in tex_override:
            ref = tex_override[ref]
        if ref not in cache:
            ns, _, p = ref.partition(':')
            if not p:
                ns, p = 'underground_city', ns
            path = os.path.join(texdir, ns, 'textures', p + '.png')
            if not os.path.exists(path):
                path = os.path.join(texdir, 'underground_city', 'textures', p + '.png')
            cache[ref] = np.array(Image.open(path).convert('RGBA')) if os.path.exists(path) else None
        return cache[ref]

    ya, pa = math.radians(yaw), math.radians(pitch)
    cy_, sy_ = math.cos(ya), math.sin(ya)
    cp_, sp_ = math.cos(pa), math.sin(pa)
    scale = size / 26.0 * zoom

    def project(p):
        x, y, z = p[0] - center[0], p[1] - center[1], p[2] - center[2]
        x, z = x * cy_ - z * sy_, x * sy_ + z * cy_     # yaw around Y
        y, z = y * cp_ - z * sp_, y * sp_ + z * cp_     # pitch around X
        return (size / 2 + x * scale, size / 2 - y * scale, z)

    img = np.zeros((size, size, 4), dtype=np.uint8)
    img[:, :] = bg
    zbuf = np.full((size, size), 1e9)
    for el in model.get('elements', []):
        f, t = el['from'], el['to']
        pts = {'x0': f[0], 'x1': t[0], 'y0': f[1], 'y1': t[1], 'z0': f[2], 'z1': t[2]}
        rot = el.get('rotation')
        for fname, face in el['faces'].items():
            corners = [tuple(pts[c] for c in cc) for cc in FACE_CORNERS[fname]]
            if rot:
                corners = [rot_point(c, rot['origin'], rot['axis'], rot['angle']) for c in corners]
            uv = face.get('uv') or default_uv(fname, f, t)
            r = (face.get('rotation', 0) // 90) % 4
            # texture corners in order TL, TR, BR, BL; rotating the texture clockwise shifts which face corner each takes
            tc = [(uv[0], uv[1]), (uv[2], uv[1]), (uv[2], uv[3]), (uv[0], uv[3])]
            tc = tc[-r:] + tc[:-r] if r else tc
            texture = tex(face['texture'])
            if texture is None:
                continue
            n = NORMALS[fname]
            if rot:
                n0 = rot_point(n, (0, 0, 0), rot['axis'], rot['angle'])
            else:
                n0 = n
            shade = 1.0 if el.get('shade') is False else 0.62 + 0.38 * max(0.0, (n0[1] * 0.8 + n0[0] * 0.35 + n0[2] * 0.45))
            sc = [project(c) for c in corners]
            for tri in ((0, 1, 2), (0, 2, 3)):
                a, b, c = (sc[i] for i in tri)
                ta, tb, tcc = (tc[i] for i in tri)
                minx, maxx = int(max(0, min(a[0], b[0], c[0]))), int(min(size - 1, max(a[0], b[0], c[0])))
                miny, maxy = int(max(0, min(a[1], b[1], c[1]))), int(min(size - 1, max(a[1], b[1], c[1])))
                if maxx < minx or maxy < miny:
                    continue
                den = (b[1] - c[1]) * (a[0] - c[0]) + (c[0] - b[0]) * (a[1] - c[1])
                if abs(den) < 1e-9:
                    continue
                xs, ys = np.meshgrid(np.arange(minx, maxx + 1) + 0.5, np.arange(miny, maxy + 1) + 0.5)
                w0 = ((b[1] - c[1]) * (xs - c[0]) + (c[0] - b[0]) * (ys - c[1])) / den
                w1 = ((c[1] - a[1]) * (xs - c[0]) + (a[0] - c[0]) * (ys - c[1])) / den
                w2 = 1 - w0 - w1
                inside = (w0 >= -0.001) & (w1 >= -0.001) & (w2 >= -0.001)
                if not inside.any():
                    continue
                u = (w0 * ta[0] + w1 * tb[0] + w2 * tcc[0]) / 16.0 * texture.shape[1]
                v = (w0 * ta[1] + w1 * tb[1] + w2 * tcc[1]) / 16.0 * texture.shape[0]
                ui = np.clip(np.floor(u).astype(int), 0, texture.shape[1] - 1)
                vi = np.clip(np.floor(v).astype(int), 0, texture.shape[0] - 1)
                px = texture[vi, ui]
                z = w0 * a[2] + w1 * b[2] + w2 * c[2]
                ok = inside & (px[..., 3] > 40) & (z < zbuf[miny:maxy + 1, minx:maxx + 1])
                if not ok.any():
                    continue
                sub = img[miny:maxy + 1, minx:maxx + 1]
                col = (px[..., :3].astype(float) * shade).clip(0, 255).astype(np.uint8)
                sub[ok, :3] = col[ok]
                sub[ok, 3] = 255
                zbuf[miny:maxy + 1, minx:maxx + 1][ok] = z[ok]
    return Image.fromarray(img)


def turntable(model_path, texdir, out, angles=(35, 125, 215, 305), pitch=28, size=300, **kw):
    model = load_model(model_path)
    tiles = [render(model, texdir, yaw=a, pitch=pitch, size=size, **kw) for a in angles]
    sheet = Image.new('RGBA', (size * len(tiles), size))
    for i, tile in enumerate(tiles):
        sheet.paste(tile, (i * size, 0))
    sheet.save(out)


if __name__ == '__main__':
    root = '/home/user/Underground-City/rpg-core/src/main/resources/assets'
    turntable(sys.argv[1], root, sys.argv[2])
