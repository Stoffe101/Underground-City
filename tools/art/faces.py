"""Face corner order and normals of Minecraft block-model faces, shared by the generators and the software preview (no numpy here)."""
from __future__ import annotations

FACE_CORNERS = {  # TL, TR, BR, BL of the texture on the face, as (x,y,z) picks from (x0|x1, y0|y1, z0|z1)
    'north': [('x1', 'y1', 'z0'), ('x0', 'y1', 'z0'), ('x0', 'y0', 'z0'), ('x1', 'y0', 'z0')],
    'south': [('x0', 'y1', 'z1'), ('x1', 'y1', 'z1'), ('x1', 'y0', 'z1'), ('x0', 'y0', 'z1')],
    'east':  [('x1', 'y1', 'z1'), ('x1', 'y1', 'z0'), ('x1', 'y0', 'z0'), ('x1', 'y0', 'z1')],
    'west':  [('x0', 'y1', 'z0'), ('x0', 'y1', 'z1'), ('x0', 'y0', 'z1'), ('x0', 'y0', 'z0')],
    'up':    [('x0', 'y1', 'z0'), ('x1', 'y1', 'z0'), ('x1', 'y1', 'z1'), ('x0', 'y1', 'z1')],
    'down':  [('x0', 'y0', 'z1'), ('x1', 'y0', 'z1'), ('x1', 'y0', 'z0'), ('x0', 'y0', 'z0')],
}
NORMALS = {'north': (0, 0, -1), 'south': (0, 0, 1), 'east': (1, 0, 0), 'west': (-1, 0, 0), 'up': (0, 1, 0), 'down': (0, -1, 0)}
