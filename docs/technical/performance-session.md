# The two-player performance session (alpha criterion 9)

`scripts/run_perf_session.py` starts a dedicated server on a **new** furnished archipelago world (world, furnished and scarcity sets) and two real clients (the same sets plus Xaero's Minimap and World Map on the clients only), each driven by a scripted walker (`PerfSessionClient`), on one machine. Phase 1 runs three equal segments, then a timed full save and a normal stop; phase 2 starts the same world again, both clients rejoin, and two more minutes are measured. `PerfSessionServer` keeps every server tick's length per segment; `TickSampler` logs where any tick over 100 ms spent its time.

**What these numbers are.** The server and both clients share one cloud machine, and the clients render in software in a virtual display. The server's numbers are an upper bound for a server with a machine to itself; the clients' frame times (about 4 frames a second) say whether the game hitches, not how fast a graphics card draws it. Nobody played: the clients walk on a script, and every teleport, registration and grant is logged as `SCRIPTED` or `PREPARED`.

## Eighth pass, 30 minutes (October 26, 2026)

The first attempt stopped four minutes in: the older cloud-journey fixture also runs when a journey phase is set, refused the performance server's folder and stopped the server. It now stands aside for the performance session (the session had never been started before this pass).

| Segment | Ticks | Mean ms | p95 ms | p99 ms | Worst ms | Over 100 ms | Over 250 ms |
| --- | --- | --- | --- | --- | --- | --- | --- |
| Ironhaven, both players, day: arrival | 200 | 31.4 | 69.2 | 152.9 | 243.4 | 6 | 0 |
| Ironhaven, both players, day | 11,800 | 34.5 | 63.8 | 92.9 | 332.9 | 80 | 2 |
| Ironhaven and Valewood, day: arrival | 200 | 31.3 | 66.3 | 104.1 | 147.8 | 3 | 0 |
| Ironhaven and Valewood, day | 11,800 | 30.9 | 56.5 | 78.6 | 222.5 | 26 | 0 |
| Blackwater, both players, night: arrival | 200 | 25.2 | 56.6 | 107.6 | 270.5 | 3 | 1 |
| Blackwater, both players, night | 11,800 | 21.9 | 41.4 | 65.1 | 191.1 | 25 | 0 |
| After the restart: arrival | 200 | 37.9 | 93.0 | 214.1 | 473.3 | 9 | 1 |
| After the restart | 2,200 | 21.8 | 42.5 | 64.2 | 236.5 | 3 | 0 |

* **The p99 target (under 100 ms) holds in every walking segment** (92.9, 78.6, 65.1 and 64.2 ms). The arrival windows (the 200 ticks after a teleport, kept apart) are above it.
* **A full save took 226 ms**; both citizens were the same after the stop and start.
* **Samples every 30 s:** 20 to 181 townspeople (the street service's peak was 100 out at once against a cap of 120; the night in Blackwater held 20 to 45), 3,000 to 5,400 loaded chunks, 1.0 to 2.5 GB of the server's 3 GB heap.
* **The street service** averaged 1.3 to 2.0 ms a pass; its worst pass was 50 ms (a top-up of new people). Lost Cities was asked 911 times in phase 1 at 38 to 58 us each (its plan lock is not waited on: 3,960 plans recorded on the generation workers, D-167).

### The ticks over 250 ms

| Tick | Segment | Where the sampler found it |
| --- | --- | --- |
| 272 ms | Ironhaven, day | vanilla villager point-of-interest pathfinding (`AcquirePoi.findPathToPois`; the shopkeepers are villagers) |
| 331 ms | Ironhaven, day | vanilla entity ticking (`ServerLevel.tickNonPassenger`) |
| 268 ms | Blackwater arrival | vanilla chunk saving as the chunks left behind were unloaded after the teleport (`ChunkMap.processUnloads`) |
| 473 ms | after the restart, arrival | the rejoin's chunk loading |

None of them was in Core's code. The samples cover only part of each long tick (4 ms samples once a tick passes 30 ms), so the rest is most likely garbage collection or the two software-rendered clients taking the machine's cores. Over every long tick (over 100 ms) of the measured segments, entity pathfinding led (vanilla villagers' point-of-interest searches most), Core's townspeople accounted for 5 to 7 of about 40 samples and the population services for a handful more.

### Two players on the bonded store (server-driven, both clients connected)

A minute into the first segment, both players were made thieves of level 8 with Professional Tools (PREPARED). The farmer took Kessler's job; the smith was refused ("Employer demand is full"); the smith's search of the store found nothing and left their heat at 0; the farmer's two searches found the shipment once (heat 0 to 2). A minute into the Blackwater segment the farmer delivered it: paid 700 once, the smith paid nothing, a second delivery refused. After the restart the delivery replayed was refused and paid nothing, and the job stayed closed. The searches are the call the burglary search makes; nobody walked into the store (raid run 3 did, with one client).

### Xaero with two clients

Each player's tracked place was chosen at the moment of the segment's teleport, before the chunks round them were loaded, so only the farmer (already standing in Ironhaven at the second segment) ever tracked something: a shop at 13 Coke Yard, one Core waypoint on the farmer's minimap, none on the smith's, and the farmer's waypoint still there after the restart. That does not show two players' waypoints kept apart; the place is now chosen half a minute into each segment, and a short session measured it again.

## Eighth pass, a short session (6 minutes, same day)

Run for the Xaero check, with each player's place chosen half a minute into each segment (PREPARED: put in their Places book and tracked by the server, as Track on the Places page does).

**Xaero with two clients: kept apart (L4, two scripted clients).** Minute by minute, each client showed exactly the destination the server had given that player and never the other's at the same time: Ironhaven, an apartment block at 25 Anvil Row for the farmer and a workshop at 36 Anvil Row for the smith; then the workshop for the farmer and a shop at 94 Willow Way in Valewood for the smith; then two offices in Blackwater. Each minimap held at most one of Core's waypoints, replaced rather than added to when the destination changed, and both were still there after the stop and start. The server does not load Xaero. (The runner's first check counted the workshop, tracked by each player in turn, as shared; it now compares minute by minute.) Not shown: a client without Xaero beside one with it, manual waypoints, another dimension.

| Segment | Ticks | Mean ms | p95 ms | p99 ms | Worst ms | Over 250 ms |
| --- | --- | --- | --- | --- | --- | --- |
| Ironhaven, both: arrival | 200 | 42.0 | 45.4 | 171.8 | 4,685.3 | 2 |
| Ironhaven, both | 2,200 | 32.4 | 66.4 | 114.3 | 237.4 | 0 |
| Ironhaven and Valewood: arrival | 200 | 29.3 | 63.5 | 84.1 | 112.6 | 0 |
| Ironhaven and Valewood | 2,200 | 36.2 | 70.5 | 105.8 | 376.5 | 1 |
| Blackwater, both, night: arrival | 200 | 21.5 | 59.1 | 159.8 | 227.2 | 0 |
| Blackwater, both, night | 2,200 | 22.4 | 50.1 | 69.1 | 184.3 | 0 |
| After the restart: arrival | 200 | 40.7 | 105.2 | 206.3 | 426.4 | 1 |
| After the restart | 2,200 | 25.6 | 57.9 | 113.9 | 322.5 | 2 |

Two-minute segments are too short for a stable p99; the 30-minute session is the measurement. **The 4.7 s tick on arriving in Ironhaven was Core's:** 724 of its samples were in the street service's line-of-sight check (`PopulationService.inLine`), whose ray into a chunk still being generated made the server generate it on the spot. The check now counts a line through a chunk that is not loaded as blocked (a person cannot see into a chunk that does not exist yet), and the street service's other sight check does the same. The two-player bonded store passed every check again.
