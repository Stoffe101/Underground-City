"""Shared material ramps for items, blocks and the interface (darkest -> lightest, five steps each)."""
from uc_art import ramp, rgb

INK = rgb('#161c25')
STEEL = ramp('#8b9bb0')
GUNMETAL = ramp('#566275')
BRASS = ramp('#c9954a')
COPPER = ramp('#c4693a')
LEAF = ramp('#4c9a4a')
LEAF_LIME = ramp('#8cc152')
PAPER = ramp('#e2d8bc')
BURLAP = ramp('#b08a56')
CYAN = ramp('#41c4d6')
BLUE = ramp('#4a6fb5')
PURPLE = ramp('#7b63c8')
RED = ramp('#d0534a')
AMBER = ramp('#e8a84a')
PCB = ramp('#2f8f5a')
SOIL = ramp('#7a5a3a')
WHITE = ramp('#e9eef4')
PLASTIC_BLACK = ramp('#3a414c')


def pal(*ramps, outline=INK, extra=None):
    """Legend for ASCII sprites: ramps take consecutive letters (abcde, fghij, klmno, pqrst, uvwxy, zABCD...), '0' is the outline."""
    letters = 'abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ'
    legend = {'0': outline}
    for i, r in enumerate(ramps):
        for j, color in enumerate(r):
            legend[letters[i * 5 + j]] = color
    if extra:
        legend.update(extra)
    return legend
