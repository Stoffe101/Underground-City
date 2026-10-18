# Product backlog (pre-issue triage)

## Now: P0

- [ ] Run exact-version compatibility checks for the candidate mods, starting from a minimal Forge dedicated server.
- [ ] Choose a legal/feasible world-generation versus map-adaptation prototype.
- [ ] Record licensing and download provenance before using third-party assets.
- [ ] Confirm architecture constraints: persistence, server authority, multiplayer state, and mod APIs.
- [ ] Decide first-district visual targets and performance acceptance budgets.

## Next: P1–P3

- [ ] Reproducible install/manifest with checksums (no bundled unlicensed JARs).
- [ ] Custom mod scaffold, smoke-boot CI, co-op dedicated-server test.
- [ ] First-login UI, player registration, city selection and profession selection.
- [ ] Career XP/skill tree and one general skill, versioned save data.
- [x] A safe travel route: three inter-city roads with bridges (a close triangle) generate into new chunks of a fresh seed-5908 world (cloud-measured; every road continuous, bridges over real water; see [three-city notes](../technical/three-city-prototype.md)). Nobody has walked, driven or sailed them yet.
- [ ] Small district/world import, one workshop and one farm.
- [ ] Gate houses at the road checkpoints (the zones exist as data; the buildings and guard NPCs do not), patrol/NPC presence and a vehicle-mod fit test on the roads; boat customs for the water routes. Waymarkers (D-078) and wayside shelters with a road warden (D-079) are built.

## Sprint 2026-10-15 follow-ups (by priority)

**P0 (blocks calling the starter slice a real first playable)**

- [ ] A human walks Ironhaven to Valewood and to Blackwater on seed 5908 with a stopwatch and reports the time, the bridges and anything that blocks the way (the numbers so far are static estimates and block surveys).
- [x] A client-driven test of the Technician's trace: **done October 16** as a scripted-player scenario (success, wrong answer, abandonment and timeout, each with its consequence; the real keyboard and the real screen). Not yet in CI, and the frame that should show the flashing pads still shows the world.
- [ ] Real balance: a 30-60 minute two-player session on the underground contracts. Today's balance is an arithmetic validator.

**P1**

- [ ] Guard NPCs: **partly done** (October 16, D-062): `/uc_checkpoints place` builds a post with a named guard at each of the six zones and the guard voices the search. Still open: customs on water, patrols, hand-off witnesses, Immersive Vehicles cargo in searches, posts placed at world creation.
- [ ] Gunsmith loop: **partly done** (October 16): the starter kit's blueprint, the real Gun Smith Table recipe and the legal `licensed_sidearm` contract that buys the crafted Glock 17 run in a GameTest against real TaCZ. Still open: a TaCZ Blueprints starter configuration (not needed while Core grants the blueprint), server-authoritative blueprint gating (TaCZ Blueprints hides recipes in the client only), a contract for a specific crafted gun other than the sidearm.
- [ ] Vehicle fit test: **partly done** (October 16, scripted player): a Quad drove 200 blocks of road and across the Ironhaven - Valewood bridge, and the Motorboat sailed about 130 blocks under the Valewood - Blackwater bridge. Still open: survival fuel and a fuel source for cars (a creative-mode vehicle is fuel-free), a save and reload of a vehicle with cargo, a passenger, a second player, a crash, a human's driving, a loaded vehicle driven through a checkpoint post by a person (the search of an Immersive Vehicles crate is GameTested, D-077, October 17).
- [x] Registration UI: a first-login screen (home city, then profession, then confirm) with server validation and a data-driven welcome pack (D-058, October 16; D-019 stays open for a proven selector mod).

**P2**

- [ ] Founders Row inside a city by a fixed lot rather than the operator's position. **Populated** since October 16 (nine named citizens, beds, workstations, a bell, eight registered safes); a development world now builds it beside the spawn on first launch (D-064).
- [ ] Roadside settlements (the wayside shelters of D-079 are the first, smallest step: a place to rest and a warden to ask) and an optional boat taxi so that the 4-minute walk is a choice rather than the only route.
- [ ] More skill-tree effects beyond the capped inspection modifier, exchange discount and licences.

**P3**

- [ ] Tour camera framing (oblique city shots still spend half the frame on sky) and a faster world-tour job.
- [ ] Extend the visual manifest to the Household Safe and any future first-login or NPC dialogue screen.

## First playable: P4–P6

- [ ] Currency and transaction ledger, NPC buyer/stock and one tax cycle.
- [ ] Farmer production chain and Gunsmith blueprint/research chain.
- [ ] Lootr custom per-player chest test and finite shared-resource test.
- [ ] Eight to twelve named NPCs with basic routines/dialogue (**nine named citizens exist** in Founders Row as of October 16, with dialogue, a bell and an employer who walks back to the desk; no schedules, memory or relationships).
- [ ] Intro and two connected quests; save/restart and multiplayer verification.

## Later, only after foundations prove fun

- [ ] Smith/Engineer and Hacker/Technician careers.
- [ ] City crime evidence and arrest/fine rules; rival gangs and police response.
- [ ] Simulated city populations, economy and diplomacy.
- [ ] Roads/vehicles, territorial conflicts, city restoration and full campaign.
- [ ] Performance tuning, UI polish, expanded city assets, balance testing.

## Sprint 2026-10-18 follow-ups (by priority; the ordered plan is the [expansion plan](expansion-plan.md))

**P0**

- [ ] A person follows the [Windows 11 playtest guide](playtest-guide-windows11.md) with a friend for thirty minutes (Crew steps 1 to 14) and reports what confused them, what felt unfair and what they wanted next.
- [ ] Measure a player-loaded world: server tick cost with two players, Founders Row, the Row Flats and the full pack (the idle figures exist, October 17).

**P1**

- [ ] Wayside shelter shop: limited food, water, bandages and supplies at the existing exchange ledger, no free healing, no respawn change (owner direction recorded in D-079); then the survival goods in a city supplier.
- [ ] First Crew contract that needs two careers (expansion plan WP1).
- [ ] Guard gaps for leased homes (pistons, fluids, fire, mobs, item frames, hoppers); rent charged in active play only; home transfer between a player and a Crew.
- [ ] Hosted run of `journey-crew` and of the `survival` pack job (added in this PR) read and recorded.
- [ ] Optional: a Core-owned TaCZ gun pack so that component costs sit in the gun recipe itself (researched, unverified; D-087).

**P2**

- [ ] Open Parties and Claims bridge so that Core's Crew is the party OPAC reads (shared chunk claims for rural properties).
- [ ] A default-pack decision for Vinery, Farm & Charm and Kaleidoscope Tavern after a played production chain; Farm & Charm versus Farmer's Delight kitchen overlap.
- [ ] Furnishing provider for the district art pass.

Prefer GitHub issues for actionable scoped work; this document is the canonical sequencing outline until epics are created.
