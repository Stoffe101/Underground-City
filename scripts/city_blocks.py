#!/usr/bin/env python3
"""A tenement block that several cities draw with their own materials (D-117): a central corridor on every floor, a flat on each side of it, the stair in the corridor.

Ironhaven draws it in brick with spruce floors (three storeys); Blackwater draws it in concrete and glass with modern furniture (four storeys). The plan is the same, the look and the
yard and roof are the city's own (callbacks), so the two cities do not read alike.
"""
from __future__ import annotations

from dataclasses import dataclass
from typing import Callable

import citykit as k
from citykit import Building, Palette, Part, Spot, ml


@dataclass(frozen=True)
class TenementLook:
    wall: str
    plinth: str
    floor: str
    ceil_low: str
    ceil_high: str
    partition: str
    pane: str               # the window pane block
    pane_height: int        # 2 or 3 high
    flat_door: str          # door material of a flat's door, e.g. "spruce_door" or a Modern Life id "modernlife:modern_white_door"
    street_door: str        # material of the two street doors
    wood: str               # kitchen cabinet wood, e.g. "oak"
    sofa: str               # sofa colour
    tv: bool                # a flat screen on the living-room wall
    colours: tuple[str, ...]


def tenement(bid: str, city: str, floors: int, look: TenementLook, yard_fn: Callable[[Part], None], roof_fn: Callable[[Part], None], security: str, notes: str) -> Building:
    """A block of ``floors`` storeys with two flats on every floor (so ``2 * floors`` flats)."""
    p = Palette(bid)
    b = Building(bid, p, use="flats", city=city, min_floors=floors, max_floors=floors, filler=look.plinth, security=security, notes=notes)
    X0, Z0, X1, Z1 = 1, 1, 14, 14

    def shell(part: Part) -> None:
        part.floor(X0, Z0, X1, Z1, look.floor)
        part.ring(X0, Z0, X1, Z1, 1, 5, look.wall)
        part.ring(X0, Z0, X1, Z1, 1, 1, look.plinth)
        for (x, z) in ((X0, Z0), (X1, Z0), (X0, Z1), (X1, Z1)):
            part.fill(x, 1, z, x, 5, z, look.plinth)
        part.floor(X0 + 1, Z0 + 1, X1 - 1, Z1 - 1, look.ceil_low, y=4)
        part.floor(X0 + 1, Z0 + 1, X1 - 1, Z1 - 1, look.ceil_high, y=5)

    def door(part: Part, x: int, y: int, z: int, facing: str, material: str, hinge: str = "left") -> None:
        if ":" in material and not material.startswith("minecraft:"):
            part.put(x, y, z, f"{material}[facing={facing},half=lower,hinge={hinge},open=false,powered=false]")
            part.put(x, y + 1, z, f"{material}[facing={facing},half=upper,hinge={hinge},open=false,powered=false]")
        else:
            part.door(x, y, z, facing, material, hinge)

    def flat(part: Part, north: bool, door_x: int, colour: str) -> None:
        """One flat, drawn for the north side (z 2..5); for the south side z and the facings are flipped."""
        def Z(z: int) -> int:
            return z if north else 15 - z

        def F(facing: str) -> str:
            return facing if north else k.OPPOSITE.get(facing, facing)

        wall_z = 6 if north else 9
        part.fill(2, 1, wall_z, 13, 3, wall_z, look.partition)                    # the corridor wall of the flat
        door(part, door_x, 1, wall_z, F("south"), look.flat_door)
        part.fill(7, 1, Z(2), 7, 3, Z(5), look.partition)                          # bedroom | living room
        door(part, 7, 1, Z(4), "west", look.flat_door)
        # bedroom (x 2..6): the bed against the outer wall, a night stand, a bookcase
        k.bed(part, 3, 1, Z(3), "east", colour)
        part.put(5, 1, Z(3), ml("oak_night_stand", facing=F("south")))
        part.put(6, 1, Z(2), ml("modern_oak_bookshelf", facing=F("south")))
        # kitchen along the outer wall, a table, a sofa and a small table in the living room (x 8..13)
        k.counter_run(part, 9, 1, Z(2), "east", F("south"), ["fridge", "drawers", "stove", "sink"], look.wood)
        k.table(part, 10, 1, Z(4), 1, 1, "oak", "small"); k.chair(part, 9, 1, Z(4), "east"); k.chair(part, 11, 1, Z(4), "west")
        part.put(13, 1, Z(3), ml(f"{look.sofa}_sofa", facing="west", type="single"))
        part.put(13, 1, Z(2), ml("oak_small_table", type="single"))
        if look.tv:
            part.put(13, 2, Z(2), ml("flatscreen_tv", facing="west"))
        for x in (3, 5, 10, 12):
            part.window(x, 2, Z(1), look.pane, look.pane_height)
        part.window(14, 2, Z(3), look.pane, look.pane_height)
        part.window(1, 2, Z(3), look.pane, look.pane_height)
        k.lamp_ceiling(part, 4, Z(4)); k.lamp_ceiling(part, 10, Z(3))

    parts: list[Part] = []
    g = b.new_part("ground", ground=True)
    yard_fn(g)
    parts.append(g)
    for i in range(1, floors):
        f = b.new_part(f"floor{i}", floor=i)
        f.floor(0, 0, 15, 15, "air")
        parts.append(f)
    for part in parts:
        shell(part)
    for z in (7, 8):                                                           # a door at each end of the corridor on the ground floor
        door(g, X0, 1, z, "west", look.street_door, "right" if z == 8 else "left")
        door(g, X1, 1, z, "east", look.street_door, "left" if z == 8 else "right")
    for i, part in enumerate(parts):
        # the stair of this floor climbs from row z = 7 eastwards (even floors) or from row z = 8 westwards (odd floors); flat doors keep clear of it
        door_x = 11 if i % 2 == 0 else 4
        flat(part, True, door_x, look.colours[(2 * i) % len(look.colours)])
        flat(part, False, door_x, look.colours[(2 * i + 1) % len(look.colours)])
        part.put(8, 3, 7, ml("light_bulb", face="ceiling", facing="north", powered=True))
    for i in range(floors - 1):
        if i % 2 == 0:
            k.stair_run(parts[i], parts[i + 1], 3, 7, "east")
        else:
            k.stair_run(parts[i], parts[i + 1], 12, 8, "west")
    t = b.new_part("top", top=True)
    roof_fn(t)
    spots = [Spot("door", 1, 1, 7, "west", "street door"), Spot("door", 14, 1, 7, "east", "street door"), Spot("stove", 11, 1, 2, "south", "flat 1 stove")]
    for i in range(floors):
        spots.append(Spot("bed", 3, 1 + 6 * i, 3, "east", f"flat {2 * i + 1} bed"))
        spots.append(Spot("bed", 3, 1 + 6 * i, 12, "east", f"flat {2 * i + 2} bed"))
    b.spots += spots
    return b
