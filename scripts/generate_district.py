#!/usr/bin/env python3
"""Generate the Founders Row district definition (D-055, extended by D-061).

The district is data: a list of block and citizen operations relative to an origin chosen by an operator at build time
(the footprint's north-west corner, extending east and south), so no world coordinate is hard-coded. ``y = 0`` is the
walkable level; the paved platform is ``y = -1``. Core reads ``data/underground_city/districts/*.json``.

Founders Row is one lot that makes the first playable loop usable end to end and gives it people:

* north row: the supply stall (Operations Terminal and the supply clerk), a records office with three registered
  Household Safes, the data office (Secured Data Terminal, the Technician's employer) and a greenhouse for the controlled crop;
* a paved lane south from the stalls to a gate, with the village bell in the middle of it;
* west side: the gunsmith's counter, the foundry, and two cottages; east side: the farm co-op, a cottage, a storehouse with
  registered safes and the registry where the registrar works;
* the gate, with its guard.

Every citizen is a named villager (registrar, four career employers, three residents, the gate guard) with a bed, a workstation or
both, so the vanilla routines (sleep, work, gather at the bell) apply. An employer is bound to an Operations Terminal in their office.
Lines of dialogue live here, in data. It is an operator tool for a fresh world, not generated city content.

``python scripts/generate_district.py`` writes the file; ``--check`` fails when the committed file differs.
"""
from __future__ import annotations

import argparse
import json
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
DIR = ROOT / "rpg-core/src/main/resources/data/underground_city/districts"
WIDTH, DEPTH, HEIGHT = 25, 41, 8
LANE = (10, 14)
LANE_START = 6


def fill(a, b, block):
    return {"op": "fill", "from": list(a), "to": list(b), "block": block}


def put(at, block, requires_mod: str | None = None):
    op = {"op": "block", "at": list(at), "block": block}
    if requires_mod:
        op["requires_mod"] = requires_mod
    return op


def npc(at, role, name, profession, lines, career=None, terminal=None):
    op = {"op": "npc", "at": list(at), "role": role, "name": name, "profession": profession, "lines": lines}
    if career:
        op["career"] = career
    if terminal:
        op["terminal"] = list(terminal)
    return op


FLIP = {"east": "west", "west": "east", "north": "north", "south": "south"}


class Building:
    """A 7 x 7 (or 7 x 6) room whose doorway is on its east side; ``mirror`` builds the same room facing west."""

    def __init__(self, x0: int, z0: int, mirror: bool = False, depth: int = 7, width: int = 7):
        self.x0, self.z0, self.mirror, self.d, self.w = x0, z0, mirror, depth, width
        self.door_z = 3

    def p(self, lx: int, ly: int, lz: int) -> tuple[int, int, int]:
        return (self.x0 + (self.w - 1 - lx if self.mirror else lx), ly, self.z0 + lz)

    def face(self, direction: str) -> str:
        return FLIP[direction] if self.mirror else direction

    def shell(self, wall: str, trim: str, floor: str, ceiling: str, roof: str) -> list[dict]:
        w, d = self.w, self.d
        ops = [
            fill(self.p(0, -1, 0), self.p(w - 1, -1, d - 1), floor),
            fill(self.p(0, 0, 0), self.p(w - 1, 2, 0), wall),
            fill(self.p(0, 0, d - 1), self.p(w - 1, 2, d - 1), wall),
            fill(self.p(0, 0, 0), self.p(0, 2, d - 1), wall),
            fill(self.p(w - 1, 0, 0), self.p(w - 1, 2, d - 1), wall),
        ]
        for lx, lz in ((0, 0), (w - 1, 0), (0, d - 1), (w - 1, d - 1)):
            ops.append(fill(self.p(lx, 0, lz), self.p(lx, 2, lz), trim))
        ops += [
            fill(self.p(1, 0, 1), self.p(w - 2, 2, d - 2), "minecraft:air"),
            fill(self.p(0, 3, 0), self.p(w - 1, 3, d - 1), ceiling),
            fill(self.p(1, 4, 1), self.p(w - 2, 4, d - 2), roof),
            # doorway on the lane side: two high, with the wall above it as a lintel
            fill(self.p(w - 1, 0, self.door_z), self.p(w - 1, 1, self.door_z), "minecraft:air"),
            # windows on the back wall and the two side walls
            put(self.p(0, 1, 2), "minecraft:glass_pane"),
            put(self.p(0, 1, d - 3), "minecraft:glass_pane"),
            put(self.p(3, 1, 0), "minecraft:glass_pane"),
            put(self.p(3, 1, d - 1), "minecraft:glass_pane"),
            put(self.p(3, 2, 3), "minecraft:lantern[hanging=true]"),
        ]
        return ops

    def bed(self, lx: int, lz: int, color: str = "red") -> list[dict]:
        """A bed whose foot is at ``(lx, lz)`` and head one block north."""
        return [put(self.p(lx, 0, lz), f"minecraft:{color}_bed[facing=north,part=foot]"),
                put(self.p(lx, 0, lz - 1), f"minecraft:{color}_bed[facing=north,part=head]")]


def stall(x0: int, x1: int, floor: str) -> list[dict]:
    """A roofed open-front stall against the north wall: wood floor, back wall, fence posts, slab roof, hanging lantern."""
    mid = (x0 + x1) // 2
    return [
        fill((x0, -1, 1), (x1, -1, 5), floor),
        fill((x0, 0, 1), (x1, 2, 1), "minecraft:spruce_planks"),
        fill((x0, 0, 5), (x0, 2, 5), "minecraft:spruce_fence"),
        fill((x1, 0, 5), (x1, 2, 5), "minecraft:spruce_fence"),
        fill((x0, 3, 1), (x1, 3, 5), "minecraft:spruce_slab[type=bottom]"),
        put((mid, 2, 3), "minecraft:lantern[hanging=true]"),
    ]


def office(b: Building, *, wall: str, floor: str, career: str, name: str, profession: str, workstation: str, extras: list[dict], lines: list[str]) -> list[dict]:
    """A career employer's office: an Operations Terminal bound to the employer, their workstation and bed."""
    ops = b.shell(wall, "minecraft:stripped_spruce_log", floor, "minecraft:spruce_planks", "minecraft:spruce_slab[type=bottom]")
    terminal = b.p(1, 0, 2)
    ops += [
        {"op": "terminal", "at": list(terminal), "facing": b.face("east")},
        put(b.p(3, 0, 1), workstation),
        npc(b.p(2, 0, 2), "employer", name, profession, lines, career=career, terminal=terminal),
    ]
    ops += extras
    ops += b.bed(1, b.d - 2)
    return ops


def cottage(b: Building, *, name: str, profession: str, workstation: str, lines: list[str], bed: str, wall: str = "minecraft:spruce_planks") -> list[dict]:
    """A home: a bed, a registered apartment-grade safe (a burglary target with a lockpick) and the resident's workstation."""
    ops = b.shell(wall, "minecraft:stripped_oak_log", "minecraft:oak_planks", "minecraft:oak_planks", "minecraft:oak_slab[type=bottom]")
    ops += [
        put(b.p(3, 0, 1), workstation),
        {"op": "safe", "at": list(b.p(1, 0, 2)), "policy": "apartment_common"},
        put(b.p(5, 0, 1), "minecraft:crafting_table"),
        put(b.p(5, 0, b.d - 2), "minecraft:bookshelf"),
        npc(b.p(3, 0, 3), "resident", name, profession, lines),
    ]
    ops += b.bed(1, b.d - 2, bed)
    return ops


def build() -> dict:
    ops = [
        fill((0, -4, 0), (WIDTH - 1, -2, DEPTH - 1), "minecraft:stone"),
        fill((0, -1, 0), (WIDTH - 1, -1, DEPTH - 1), "minecraft:stone_bricks"),
        fill((0, 0, 0), (WIDTH - 1, HEIGHT - 1, DEPTH - 1), "minecraft:air"),
        fill((LANE[0], -1, LANE_START), (LANE[1], -1, DEPTH - 1), "minecraft:polished_andesite"),
        fill((0, 0, 0), (WIDTH - 1, 0, 0), "minecraft:stone_brick_wall"),
    ]
    for x in (0, 6, 12, 18, 24):
        ops += [fill((x, 0, 0), (x, 2, 0), "minecraft:stone_brick_wall"), put((x, 3, 0), "minecraft:lantern")]
    # A: Operations Terminal and the supply clerk.
    ops += stall(1, 5, "minecraft:spruce_planks")
    ops += [{"op": "terminal", "at": [3, 0, 2], "facing": "south"}, {"op": "clerk", "at": [4, 0, 2], "terminal": [3, 0, 2]}]
    # B: records office with three registered Household Safes of different security.
    ops += stall(7, 11, "minecraft:smooth_stone")
    for x, policy in ((8, "apartment_common"), (9, "office"), (10, "industrial")):
        ops.append({"op": "safe", "at": [x, 0, 2], "policy": policy})
    # C: data office, walled on three sides with a window of bars, holding the Secured Data Terminal and the Technician's employer.
    ops += [
        fill((13, -1, 1), (17, -1, 5), "minecraft:polished_deepslate"),
        fill((13, 0, 1), (17, 3, 1), "minecraft:deepslate_bricks"),
        fill((13, 0, 2), (13, 3, 5), "minecraft:deepslate_bricks"),
        fill((17, 0, 2), (17, 3, 5), "minecraft:deepslate_bricks"),
        fill((13, 4, 1), (17, 4, 5), "minecraft:deepslate_brick_slab[type=bottom]"),
        fill((14, 3, 5), (16, 3, 5), "minecraft:deepslate_bricks"),
        fill((13, 1, 3), (13, 2, 3), "minecraft:iron_bars"),
        fill((17, 1, 3), (17, 2, 3), "minecraft:iron_bars"),
        put((15, 3, 3), "minecraft:lantern[hanging=true]"),
        {"op": "secure_terminal", "at": [15, 0, 2]},
        {"op": "terminal", "at": [14, 0, 3], "facing": "east"},
        put((16, 0, 2), "minecraft:lectern[facing=south]"),
        npc([16, 0, 3], "employer", "Kit Arden - Network Office", "librarian", [
            "Municipal terminals, sensor repairs, timing circuits: dull work that keeps the lights on. The orders are on the terminal.",
            "The secured terminal on the wall runs a trace: watch the sequence once, then enter it from memory. The terminal, not you, checks the answer.",
            "Ledger work for Vesper pays well and leaves tracks. Learn Signal Scrubbing before you try it.",
        ], career="technician", terminal=[14, 0, 3]),
    ]
    # D: glass greenhouse with irrigated soil for the controlled crop.
    ops += [
        fill((19, -1, 1), (23, -1, 6), "minecraft:stone_bricks"),
        fill((20, -1, 2), (22, -1, 5), "minecraft:farmland[moisture=7]"),
        put((21, -1, 3), "minecraft:water"),
        put((21, -1, 4), "minecraft:water"),
        fill((19, 0, 1), (23, 3, 1), "minecraft:glass"),
        fill((19, 0, 1), (19, 3, 6), "minecraft:glass"),
        fill((23, 0, 1), (23, 3, 6), "minecraft:glass"),
        fill((19, 0, 6), (23, 3, 6), "minecraft:glass"),
        fill((19, 4, 1), (23, 4, 6), "minecraft:glass"),
        fill((21, 0, 6), (21, 1, 6), "minecraft:air"),
        put((20, 3, 2), "minecraft:lantern[hanging=true]"),
        put((22, 3, 5), "minecraft:lantern[hanging=true]"),
    ]

    # The village bell: the meeting point villagers gather at.
    ops += [put((12, 0, 23), "minecraft:stone_bricks"), put((12, 1, 23), "minecraft:bell[attachment=floor,facing=north]"),
            put((11, 0, 23), "minecraft:lantern"), put((13, 0, 23), "minecraft:lantern")]

    # West side (door on the lane side, the east wall).
    gun = Building(2, 8)
    ops += office(gun, wall="minecraft:stone_bricks", floor="minecraft:smooth_stone", career="gunsmith",
                  name="Ines Calloway - Gunsmith", profession="weaponsmith", workstation="minecraft:grindstone[face=floor,facing=north]",
                  extras=[put(gun.p(4, 0, 1), "tacz:gun_smith_table", "tacz"), put(gun.p(5, 0, 1), "minecraft:anvil[facing=north]")],
                  lines=[
                      "Licensed sidearms and shotguns for the Valewood co-op, range targets, optic mounts: the orders are on the terminal.",
                      "With the gun smith table in the corner and a blueprint, parts become a real gun. Without the parts, a blueprint is only paper.",
                      "Arms brokerage is not something I offer. Dray in Blackwater might, if you have the trust and the papers.",
                  ])
    foundry = Building(2, 16)
    ops += office(foundry, wall="minecraft:stone_bricks", floor="minecraft:smooth_stone", career="smith",
                  name="Brannock Voss - Foundry", profession="toolsmith", workstation="minecraft:smithing_table",
                  extras=[put(foundry.p(4, 0, 1), "minecraft:anvil[facing=north]"), put(foundry.p(5, 0, 1), "minecraft:furnace[facing=south]"),
                          put(foundry.p(5, 0, 5), "minecraft:iron_block")],
                  lines=[
                      "Iron tools for the public workshop, parts for the foundry, plating for the Ironhaven guard. The terminal lists what is open.",
                      "Structural steel and housings pay steady money. Armoury orders for trusted buyers pay more, and ask more of you.",
                      "Unstamped work is a different trade with a different buyer. I do not discuss it where the guard can hear.",
                  ])
    ops += cottage(Building(2, 24), name="Tomas Reyes - Haulier", profession="fisherman", workstation="minecraft:barrel[facing=up]", bed="blue", lines=[
        "I drive for Hollis and Sons when there is a road worth driving. All three cities are joined now, and every road has its bridge.",
        "Mind the bridges; the river is wider than it looks from the bank. A car is quick, a boat is stranger, your own legs are free.",
        "I keep my pay in the safe by the bed. The apartment lock stops a curious neighbour. It does not stop a lockpick.",
    ])
    ops += cottage(Building(2, 32, depth=6), name="Mira Voss - Retired Courier", profession="cleric", workstation="minecraft:brewing_stand", bed="purple", lines=[
        "Buyers remember. Deliver what you promised and the next order is bigger. Short them and nobody picks up the phone.",
        "Heat fades if you keep your head down and your hands clean. A fine is cheaper than a reputation.",
        "Some doors on this row look locked and are not, and some are locked and look open. Learn which before dark.",
    ], wall="minecraft:stone_bricks")

    # East side (door on the lane side, the west wall).
    farm = Building(16, 8, mirror=True)
    ops += office(farm, wall="minecraft:spruce_planks", floor="minecraft:oak_planks", career="farmer",
                  name="Odette Marsh - Farm Co-op", profession="farmer", workstation="minecraft:composter[level=0]",
                  extras=[put(farm.p(4, 0, 1), "minecraft:barrel[facing=up]"), put(farm.p(5, 0, 1), "minecraft:hay_block"), put(farm.p(5, 0, 5), "minecraft:hay_block")],
                  lines=[
                      "Orders go up on the terminal: bread, pies, wholesale carrots and potatoes. The co-op pays by the sack, not the story.",
                      "The greenhouse next door keeps its own soil wet. Seeds are cheap at the supply stall, and what you grow is yours to sell.",
                      "A licence lets you grow the controlled crop for the Blackwater dispensary. Without one, do not carry it through a gate.",
                  ])
    ops += cottage(Building(16, 16, mirror=True), name="Ada Brightwater - Market Gardener", profession="farmer", workstation="minecraft:composter[level=0]", bed="green", lines=[
        "I sell to the co-op and the bakery. Anyone who says a carrot is not a business has not counted mine.",
        "There is a safe by my bed. Apartment grade: fine against a neighbour, hopeless against a lockpick. Mind where you keep things.",
        "The guard at the gate searches on evidence, not on suspicion. Honest cargo has never cost me more than a minute.",
    ])
    # E3: the storehouse, with registered safes of two security grades.
    store = Building(16, 24, mirror=True)
    ops += store.shell("minecraft:stone_bricks", "minecraft:stripped_dark_oak_log", "minecraft:smooth_stone", "minecraft:spruce_planks", "minecraft:stone_brick_slab[type=bottom]")
    ops += [
        {"op": "safe", "at": list(store.p(1, 0, 2)), "policy": "industrial"},
        {"op": "safe", "at": list(store.p(1, 0, 4)), "policy": "office"},
        put(store.p(5, 0, 1), "minecraft:barrel[facing=up]"), put(store.p(5, 0, 2), "minecraft:barrel[facing=up]"),
        put(store.p(5, 0, 4), "minecraft:barrel[facing=up]"), put(store.p(5, 0, 5), "minecraft:barrel[facing=up]"),
    ]
    # E4: the registry, where the registrar works.
    registry = Building(16, 32, mirror=True, depth=6)
    ops += registry.shell("minecraft:stone_bricks", "minecraft:stripped_spruce_log", "minecraft:polished_andesite", "minecraft:spruce_planks", "minecraft:spruce_slab[type=bottom]")
    ops += [
        {"op": "terminal", "at": list(registry.p(1, 0, 2)), "facing": registry.face("east")},
        put(registry.p(3, 0, 1), "minecraft:cartography_table"),
        put(registry.p(5, 0, 1), "minecraft:bookshelf"), put(registry.p(5, 0, 4), "minecraft:bookshelf"),
        npc(registry.p(2, 0, 3), "registrar", "Imani Okafor - Registrar", "cartographer", [
            "Welcome to the register. Choose a home city and a trade: the clerk gives you a welcome pack and an Operations Terminal.",
            "The register keeps one entry for each citizen. Your home city is where your name is on file, and your trade decides what you can make.",
            "Nobody here asks about your past. Do the work, keep your papers straight, and the register has no reason to ask.",
        ]),
    ]
    ops += registry.bed(1, 4, "yellow")

    # The gate, with its guard.
    ops += [
        fill((9, 0, DEPTH - 2), (9, 4, DEPTH - 2), "minecraft:stone_bricks"),
        fill((15, 0, DEPTH - 2), (15, 4, DEPTH - 2), "minecraft:stone_bricks"),
        fill((9, 4, DEPTH - 2), (15, 4, DEPTH - 2), "minecraft:stone_bricks"),
        put((10, 3, DEPTH - 2), "minecraft:lantern[hanging=true]"),
        put((14, 3, DEPTH - 2), "minecraft:lantern[hanging=true]"),
        put((8, 0, DEPTH - 3), "minecraft:blast_furnace[facing=east]"),
        put((8, 1, DEPTH - 3), "minecraft:lantern"),
        npc([9, 0, DEPTH - 3], "guard", "Sergeant Halloran - Gate Guard", "armorer", [
            "I keep the lane clear. The real checkpoints are out on the roads between the cities, and they search on evidence, not suspicion.",
            "Carry what your licence covers and you will never see the same inspector twice. Carry what it does not, and that is your trouble to carry.",
            "Ironhaven wants plating, Valewood wants guns for the hunt, Blackwater wants quiet. The gates know the difference.",
        ]),
    ]
    return {
        "schema_version": 1, "id": "founders_row", "title": "Founders Row",
        "note": "Operator-built lot for a fresh world; origin is the north-west corner, extending east and south. The south end of the lane meets a road.",
        "size": [WIDTH, HEIGHT + 4, DEPTH], "ops": ops,
    }


def checkpoint_post() -> dict:
    """A roadside inspection post, built beside a road at each checkpoint zone by ``/uc_checkpoints place``.

    A 9 x 9 paved lot with a small booth, lanterns and one guard (a villager armorer with a blast furnace as their post). It is repeatable:
    one copy per checkpoint, each reversible on its own. It stands beside the road rather than across it, so it never blocks a traveller; the
    zone, not the building, decides who is searched (a traveller may leave the road and walk around, which is the smuggler's trail).
    """
    ops = [
        fill((0, -4, 0), (8, -2, 8), "minecraft:stone"),
        fill((0, -1, 0), (8, -1, 8), "minecraft:stone_bricks"),
        fill((0, 0, 0), (8, 7, 8), "minecraft:air"),
        # the booth: 3 x 3 with a window on each side and a doorway to the south
        fill((3, -1, 2), (5, -1, 4), "minecraft:polished_andesite"),
        fill((3, 0, 2), (5, 2, 4), "minecraft:stone_bricks"),
        fill((4, 0, 3), (4, 2, 3), "minecraft:air"),
        fill((4, 0, 4), (4, 1, 4), "minecraft:air"),
        put((3, 1, 3), "minecraft:glass_pane"), put((5, 1, 3), "minecraft:glass_pane"), put((4, 1, 2), "minecraft:glass_pane"),
        fill((2, 3, 1), (6, 3, 5), "minecraft:stone_brick_slab[type=bottom]"),
        put((4, 2, 3), "minecraft:lantern[hanging=true]"),
        put((5, 0, 6), "minecraft:barrel[facing=up]"),
        put((3, 0, 6), "minecraft:blast_furnace[facing=south]"),
        put((3, 1, 6), "minecraft:lantern"),
    ]
    for x, z in ((0, 0), (8, 0), (0, 8), (8, 8)):
        ops += [fill((x, 0, z), (x, 2, z), "minecraft:stone_brick_wall"), put((x, 3, z), "minecraft:lantern")]
    ops.append(npc((4, 0, 6), "guard", "Checkpoint Guard", "armorer", [
        "Routine inspection post. We search on what we see and what we are told, not on what we guess. Carry what your papers cover.",
        "A permit makes a restricted load legal here. Without one, I confiscate it and the fine comes out of your wallet.",
        "The post covers the road, not the countryside. Past the last milestone, the hills are nobody's business, and the hills are not kind.",
        "Searches cool off. The more you are caught, the sooner we look again. Keep your bags in order and the gate stays easy.",
        "Ride in on a boat or a cart and I search its hold along with you. A boat left outside my zone is not my business; what I cannot see, I do not know.",
    ]))
    return {
        "schema_version": 1, "id": "checkpoint_post", "title": "Checkpoint post", "repeatable": True,
        "note": "A guard post built beside the road at a checkpoint zone; origin is the north-west corner, extending east and south.",
        "size": [9, 12, 9], "ops": ops,
    }


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--check", action="store_true")
    args = parser.parse_args()
    outputs = {"founders_row": build(), "checkpoint_post": checkpoint_post()}
    status = 0
    for name, data in outputs.items():
        out = DIR / f"{name}.json"
        text = json.dumps(data, indent=1, ensure_ascii=False) + "\n"
        if args.check:
            if not out.exists() or out.read_text(encoding="utf-8") != text:
                print(f"Committed district {name} differs; run scripts/generate_district.py")
                status = 1
            continue
        out.parent.mkdir(parents=True, exist_ok=True)
        out.write_text(text, encoding="utf-8")
        citizens = sum(1 for op in data["ops"] if op["op"] == "npc")
        print(f"Wrote {out.relative_to(ROOT)} with {len(data['ops'])} operations and {citizens} citizens")
    if args.check and status == 0:
        print("Districts match the generator")
    return status


if __name__ == "__main__":
    raise SystemExit(main())
