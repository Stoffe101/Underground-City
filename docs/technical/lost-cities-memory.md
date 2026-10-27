# Lost Cities' memory in a long session (D-197)

**Status (October 27, 2026): the retention was found in Lost Cities 7.5.5's code, and a fix is implemented. Two 30-minute exploring sessions with two real clients measured it (below). Not played by a person.**

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

Both sessions ran a new world, seed 5908, with the same routes and the same number of hops: 40 in each third, 120 in all. Live plans were counted from the JVM's class histogram, which forces a full collection, at the end of each third.

| End of third (where the players were) | Live plans, unchaining off | Live plans, on | Plan memory, off / on | Plans in Lost Cities' cache, on |
| --- | --- | --- | --- | --- |
| First (Ironhaven and its road) | 491,528 | **32,266** | 114 MB / 7.5 MB | 2,303 |
| Second (Valewood) | 392,081 | **9,559** | 91 MB / 2.2 MB | 3,260 |
| Last (Blackwater) | 123,930 | **18,432** | 29 MB / 4.3 MB | 4,497 |

**What this shows.**
- **With unchaining off**, up to about 190 times as many plans stay alive as Lost Cities' cache holds: 491,528 against 2,573 in the cache at the end of the first third. The count fell as the players moved to the next island. The retention is therefore the linked area of the island being explored, not everything ever generated. It is still 0.5 million plans at its peak.
- **With unchaining on**, 85% to 98% fewer plans stay alive. Core dropped 81,205 links in 31 passes over the 36,000 ticks.
- The plans still alive with it on are several times the cache. Links made since the last pass, and anything else that holds a plan, are not covered. That was not measured further.

**The whole heap.** The heap in use, sampled every 30 seconds, averaged lower with unchaining on in every third: 1,795 / 1,813 / 1,904 MB, against 2,171 / 2,233 / 2,209 MB with it off. That is 300 to 420 MB less, with about the same number of loaded chunks (4,400 to 5,100 on average). A single snapshot after a full collection is not a clean measure of the fix: it also depends on which of the roughly 5,000 chunks round two clients are loaded at that moment. The second third with unchaining on measured 2,254 MB after a full collection, against 1,866 MB off; the first and last thirds measured 648 and 678 MB, against 2,157 and 1,004 MB.

**Ticks.** The worst ticks are new ground being generated, as D-096 describes. They were there in both sessions: 2.5 to 18 s off, 6.9 to 16 s on. p95 and p99 were a little lower with unchaining on, and so was the count of ticks over 250 ms:

| Third | p95, off / on | p99, off / on | Ticks over 250 ms, off / on |
| --- | --- | --- | --- |
| First | 54.6 / 48.2 ms | 99.6 / 89.2 ms | 32 / 22 |
| Second | 61.0 / 53.3 ms | 107.5 / 93.0 ms | 23 / 26 |
| Last | 64.8 / 59.1 ms | 108.8 / 98.5 ms | 20 / 14 |

**Not shown.**
- The sessions are 30 minutes with flights and teleports; a person exploring for hours on foot or by road was not run.
- The off session's restart phase lost a client, kicked for floating because it rejoined in the air. The session tool now allows flight for this plan. The on session's restart passed: both players rejoined as the same citizens, with 567 live plans and 530 MB of heap after a full collection.
- The off session also ran the bonded store step, which failed on its delivery; the on session does not run it. Neither changes Lost Cities' plans.
