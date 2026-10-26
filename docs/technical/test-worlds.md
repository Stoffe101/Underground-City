# Test worlds with the islands generated ahead of play

Owner request, ninth pass: "have the chunks of the islands and such pre-loaded at least now when we test stuff". Core can generate the islands of a world once, in the background, and save them. Walking, travelling and teleporting there later read the chunks from disk instead of waiting for Lost Cities to build them.

**Status:** implemented (`ChunkPregen`, `PregenScenario`) and measured once in the cloud (see "Measured" below). Nobody has played in a pre-generated world yet.

## What is generated

`/uc admin pregen start islands` (the default scope) generates, in this order:

1. every city's circle, nearest each centre first;
2. the rest of the three islands' land, read from the world's own island plan (the archipelago's terrain data), nearest a city first;
3. the three roads and their bridges over the straits, once the roads are planned (a little after a new world opens).

Each area gets a margin of 32 blocks unless another is given (0 to 256).

Other scopes:
* `all`: the cities' circles only.
* `valewood`, `ironhaven` or `blackwater`: that city's circle.

A world without an island plan (the legacy layout) gets its cities only.

How it works:
* About a dozen chunks are asked for at a time, a tile of 8 by 8 chunks at a time. The tiles go ring by ring round the city, and round each ring in turn, so the chunks loaded at once lie together.
* A finished chunk is kept loaded for 10 seconds (at most 64 are), as the next row needs it as a neighbour. Then it is let go, so nothing stays loaded.
* Every 500 chunks, Lost Cities' plans of the chunks already done are let go, as Lost Cities itself does when a server stops. Without this they fill the memory (pregen run 4).
* A chunk that does not complete within a minute is let go and counted as "let go unfinished". It is then generated when somebody comes near.
* Only chunks that do not exist yet are generated. An existing chunk is loaded and let go.
* Progress is saved in the world (`underground_city_pregen`). A stop, a crash or a restart carries on where it was. A plan saved by an older version in another order starts again from the beginning; the chunks already made load at once.

## How to use it

| Where | How |
| --- | --- |
| Any world, as an operator | `/uc admin pregen start [islands\|all\|<city>] [margin]`, `/uc admin pregen status`, `/uc admin pregen stop` ([admin-commands.md](admin-commands.md)) |
| A development launch (server or single player) | `-PucPregen=islands` (and optionally `-PucPregenMargin=<blocks>`). It starts once, on the first start of a world that has not had it |
| Windows scripts | `uc-server.ps1 ... -Pregen`, or `uc-new-world.ps1 ... -Pregen` / `uc-client.ps1 ... -World <name> -Pregen` for single player. A joining client cannot pre-generate: the server does |

You can play while it runs, but it uses most of the processor. On a four-core machine, give it the time it needs first.

## Template worlds for scripted runs (cloud)

`-PucPlaytest=pregen` makes a new world exactly as every scenario does, generates the islands, saves and quits. Options:
* `-PucPtScope=islands`
* `-PucPtMargin=32`

The saved world, `rpg-core/run-<dir>/saves/<world>`, is a template.

A scenario opens a world of its own name if one exists (`Playtest`). So copying the template to `run-<new dir>/saves/<new world>` before the run saves that run the wait for Lost Cities. Copy it, never open the template itself.

A template holds what the packs generated when it was made:
* A change to the furnished buildings, the scarcity package or a private pack needs a new template. Chunks already built keep their old buildings.
* The things a new world builds once (Founders Row, the Row Flats, the roadside places) are already in it.
* A scenario that checks those first-start builds needs a world made new.

A template is a disposable test world. It is never the owner's world, and the owner's worlds are never copied into the cloud.

## Measured

See the ninth-pass record, [ninth-pass-c70-critical-fixes.md](../production/ninth-pass-c70-critical-fixes.md), section 8.
