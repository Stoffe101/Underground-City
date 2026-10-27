# Lost Cities' memory in a long session (D-197)

**Status (October 27, 2026): the retention is found in Lost Cities 7.5.5's code, and a fix is implemented. The measurement in exploring sessions is in the section below. Not played by a person.**

## What Lost Cities keeps

Lost Cities plans each chunk of a city as a `BuildingInfo`. The plans live in timed caches (`BUILDING_INFO_MAP`, `CITY_INFO_MAP`, `CITY_LEVEL_CACHE`), which forget an entry `cacheCleanupSeconds` (300 by default) after it was last asked for.

A plan that has once been asked for its neighbour to the west, east, north or south keeps that neighbour in a field for good: `xmin`, `xmax`, `zmin` and `zmax`, read and filled only by `getXmin` and the three like it. Nothing clears these fields until the server stops. So a plan the cache has forgotten stays in memory while any plan still in the cache links to it, and that plan's neighbours link to theirs. The whole of an area that was ever generated stays reachable from the few plans near a player.

The ninth pass counted 102,264 live plans after pre-generating 13,000 chunks, about 330 MB. Pre-generation lets go of them every 500 chunks (D-194). Ordinary play did not.

## What Core does (`LostCitiesMemory`)

Every 1,200 server ticks, 4,000 plans a tick, Core sets the four neighbour fields to null on each plan in the cache:
- **No plan is removed** and the cache is not cleared.
- A plan that needs a neighbour asks the cache again. It gets the same object while that neighbour is still cached, or a new one worked out the same way, because Lost Cities' plans depend only on the seed and the profile.
- **Safe while generation threads read the fields:** only the two getters read them, each into a local variable before using it. A field dropped while a generation thread reads it is simply answered from the cache.

It is on by default. `-Duc.lostcities.unchain=false` (Gradle `-PucLcUnchain=off`) switches it off, to measure the retention it removes. If Lost Cities' cache ever changes shape, the reflection fails once, Core logs it and stops unchaining. The game goes on, and memory then grows with the area explored, as before.

The session samples report the cache sizes and what the unchaining has done (`LostCitiesMemory.status`). At the end of each segment the perf session counts live `BuildingInfo` objects from the JVM's own class histogram, which takes a full collection, so only measurement runs do this.

## Measurement

`scripts/run_perf_session.py --plan explore` runs a dedicated server with two real clients for 30 minutes:
- one player moves along the three corridor roads;
- the other circles each city at its radius + 160 blocks, a hop of 96 blocks every 300 ticks. The hops are flights and teleports, logged as `SCRIPTED`.

The same session is run with the unchaining off and on, each with the machine to itself.

*Results: pending at the time of writing; the tenth-pass report gives them.*
