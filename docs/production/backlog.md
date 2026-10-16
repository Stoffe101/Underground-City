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
- [ ] Gate houses at the road checkpoints (the zones exist as data; the buildings and guard NPCs do not), rest stops, patrol/NPC presence and a vehicle-mod fit test on the roads; boat customs for the water routes.

## Sprint 2026-10-15 follow-ups (by priority)

**P0 (blocks calling the starter slice a real first playable)**

- [ ] A human walks Ironhaven to Valewood and to Blackwater on seed 5908 with a stopwatch and reports the time, the bridges and anything that blocks the way (the numbers so far are static estimates and block surveys).
- [ ] A client-driven test of the Technician's trace (the server rules are GameTested; the screen is only captured, not clicked in CI).
- [ ] Real balance: a 30-60 minute two-player session on the underground contracts. Today's balance is an arithmetic validator.

**P1**

- [ ] Guard NPCs at the checkpoints and customs on water, so evidence-based detection has a visible actor (today it is a seeded die at a zone).
- [ ] A TaCZ Blueprints starter configuration (accepted spelling and effect of a starter-gun entry in `taczblueprints-server.toml` were not exercised) and a Gunsmith contract that asks for a specific crafted gun, not only a blueprint or ammunition.
- [ ] Vehicle fit test: a car and the Motorboat on the real bridges, with persistent cargo through a save (the boat's cargo round trip is tested without a world; driving is not tested at all).
- [ ] Registration UI (D-019) so a new player picks a home city and profession in one first-login screen instead of a command.

**P2**

- [ ] Founders Row placed by a fixed lot rather than the operator's position, with a populated block around it (named NPC residents).
- [ ] Roadside settlements and an optional boat taxi so that the 4-minute walk is a choice rather than the only route.
- [ ] More skill-tree effects beyond the capped inspection modifier, exchange discount and licences.

**P3**

- [ ] Tour camera framing (oblique city shots still spend half the frame on sky) and a faster world-tour job.
- [ ] Extend the visual manifest to the Household Safe and any future first-login or NPC dialogue screen.

## First playable: P4–P6

- [ ] Currency and transaction ledger, NPC buyer/stock and one tax cycle.
- [ ] Farmer production chain and Gunsmith blueprint/research chain.
- [ ] Lootr custom per-player chest test and finite shared-resource test.
- [ ] Eight to twelve named NPCs with basic routines/dialogue.
- [ ] Intro and two connected quests; save/restart and multiplayer verification.

## Later, only after foundations prove fun

- [ ] Smith/Engineer and Hacker/Technician careers.
- [ ] City crime evidence and arrest/fine rules; rival gangs and police response.
- [ ] Simulated city populations, economy and diplomacy.
- [ ] Roads/vehicles, territorial conflicts, city restoration and full campaign.
- [ ] Performance tuning, UI polish, expanded city assets, balance testing.

Prefer GitHub issues for actionable scoped work; this document is the canonical sequencing outline until epics are created.
