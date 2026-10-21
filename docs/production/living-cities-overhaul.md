# Sprint report, October 21, 2026: furnished cities, security, police, a hybrid resource economy and residents

Branch `feature/world-playability-overhaul`, draft PR [#25](https://github.com/Stoffe101/Underground-City/pull/25). Owner brief: "Underground City: Private Building Integration, Living Cities, Security & Economy Overhaul" (with the DeceasedCraft research brief). Decisions D-116 to D-124 in [decisions.md](decisions.md).

**Nobody has played any of this.** Everything below was run by scripts, Forge GameTests, probes of the game's own generator, photographs taken by a real client in a software-rendered virtual display, and one scripted real-client run. "Passed" means a script passed. The four headings the owner asked for are used throughout: **implemented and tested**, **implemented but not fully verified**, **researched or planned**, **blocked**.

## The fifteen items

### 1. The locked-door exploit (P0, earlier in this milestone)

**Implemented and tested.** In Survival, walking at a locked street door with W held and right-clicking it opened it, with or without a lockpick. Cause: `BuildingGuard.outsideSide` measured the player's side against the door *block's centre*; a closed leaf is 3/16 thick at the block's edge, so a player pressed against it stood 0.0125 blocks "inside" and the click opened it. Reproduced first in a real client (the player ended 9.01 blocks inside the building with the door open), fixed by measuring against the middle of the leaf, an exit that opens for five active seconds only, a watch that shuts a locked door found open, no signal rigging within two blocks, and a per-door close time (`PropertyLocks` schema 2). Details: [building-protection.md](../technical/building-protection.md).

### 2. Survival-mode regression testing

**Implemented and tested.** GameTests for both door orientations with and without picks, for a person leaving, redstone, a plate or a block of redstone against the door, two players, an older save's door table; the same scenario re-run in a real client on a fresh world, four tries (the player stayed outside, the door stayed shut, with picks the lock screen opened). **Not covered:** a modified client that forges its position, doors Lost Cities did not generate, trap doors and fence gates, a two-client real run of a door.

### 3. Lockpicking responsiveness

**Implemented and tested; no person has picked a lock with it.** Measured on the old model, a pick held still at a band's edge changed its reading 27 to 36 times in four seconds (a fresh random error each tick, a tremor that moved the reading with the button up). Now the reading is a function of where the pick is, the tremor is visible, the keyboard moves the pick in steps a pixel wide, and the screen was measured on a real client at 1280 by 720 and 3440 by 1440, GUI scale 2, with 300 ms of simulated latency. **New this pass:** the same session now measures how cleanly a pick was worked, which decides whether the entry is quiet ([police-response.md](../technical/police-response.md)); in a real-client burglary the pointer solves a lock pin by pin on the real screen (item 9).

### 4. Private content (the C70 buildings): status, boundaries, workflow

**Generic infrastructure implemented and tested with synthetic content. No private asset was available, so none was integrated or tested.** The owner reports permission from the content's creator to use it in a private modpack for the owner and one other player, to port and modify it, on conditions that the modpack stays private, no ready-to-play pack or reproducible server package containing it is published, and nothing of it enters this repository or a public CI artifact. The workflow (D-116, [private-content-workflow.md](../technical/private-content-workflow.md)): a folder outside the checkout named by a property, a manifest with hashes and a licence statement, fail-closed loading, a CI check that no private path is tracked or published, public CI on synthetic fixtures only. **Blocked:** testing the real assets needs the files, which the cloud does not have. An earlier attempt to fetch the content from its public reference repository in bulk was refused by the cloud's safety classifier; that is the correct outcome under the stated conditions, and no script in this repository downloads or reconstructs a protected package. To go further the owner would place the private files in the configured folder on their own machine and run the documented check; the cloud cannot see them.

### 5. Which buildings and interiors are genuinely integrated

**Implemented; photographed from outside and from inside; not walked through by a person.** Ten original buildings (not the private content): Valewood two houses, a cottage and a shop; Ironhaven a three-storey brick tenement, a workshop and a warehouse; Blackwater a four-storey apartment block, an office and a club. They are furnished with Modern Life blocks, have security classes, spots for people and a sidecar for later use. Every block of every palette was checked against the real registry; every building was generated in a disposable world and compared with its plan (675 chunks, 0 Lost Cities errors). Still requiring work: only ten types, repeated; no street furniture; counters sell nothing; a garden's planting rows are trenches; see [furnished-cities.md](../technical/furnished-cities.md). The share of buildings that are accessible, furnished, inhabited and usable is tabulated there per building type; across a whole generated city it is still **dominated by stock Lost Cities buildings** (sealed, unfurnished, empty) because the furnished set is opt-in and its character hook replaces the choice of building only for the building chunks it names.

### 6. Screenshots of the residential and commercial environments

See the frames named in section "What the photographs showed" below. They are in the scratch area of the cloud session and were **not committed** (the repository does not hold screenshots, and one set of images shows Modern Life furniture, whose licence forbids redistribution); they can be regenerated with `-PucPhotoTour` (see section "Tools"). Where the owner asks for them privately, they are attached to the session, not published.

### 7. How the three cities now differ

**Implemented; photographed.** Each city has its own Lost Cities style and its own set of buildings in rings (data: `city_character/<city>.json`), drawn in its own materials: Valewood in pink render, timber frames and tiled roofs with gardens; Ironhaven in dark brick, steel and spruce, with industrial brick bays; Blackwater in concrete, glass and modern furniture, taller (up to four storeys). The shared tenement plan is drawn by each city's own look. What is not different yet: street furniture, sound, the countryside's built features, the roads.

### 8. Furniture and building mods integrated

**Implemented and tested at the level of "boots and generates".** Modern Life (pinned, runtime only, set `furnished`) provides every furniture block; it was already pinned for the motorboat. Nothing else was added. Considered and not adopted: SecurityCraft (item 9), Open Parties and Claims (the brief asked for it only if it solved a problem Core's own protection cannot; none was found), the Create family (forbidden).

### 9. SecurityCraft, and the alarm and police-response gameplay

**SecurityCraft: researched, read from its jar, not adopted, not run.** MIT, Forge 1.20.1, Forge 47.3.30 and later, no Create dependency, 608 blocks, 54 items, 4 entities; it would be a second lock and owner system beside Core's (single authority) and its sentries and mines do not respect "nobody is punished for being offline". Kept as a candidate for an opt-in home-defence set. [police-response.md](../technical/police-response.md).

**Alarm and police response: implemented and tested headlessly; one scripted real-client burglary (below).** Observed events only: an armed alarm (a hash per building against the class's chance; the cheap flat block 25 percent, a tower 60, an office 70, a club 80, the city-centre vault always) or a witness in sight (a city person within 20 blocks with line of sight, rolled against the city's own witness chance and the thief's heat; a night break-in is seen less). A response brings officers from a street nearby after the class's time (45 to 150 seconds), who walk to the building and then after a thief who is within 40 blocks; one within three blocks arrests them through the crash-safe seizure record: stolen or forbidden goods taken, the city's fine up to the wallet, heat +3. A clean pick is a quiet pick and slips past an alarm that is not certain; a witness is not affected. Nobody is policed while away, creative or dead, and nothing is pending after a restart. **Not done:** disabling or bypassing an alarm by a skill or a tool, cameras and sensors, a fence who buys marked goods, jail, officers entering buildings, owner-installed security upgrades.

### 10. NPC housing, work, schedules and persistence

**Implemented; arithmetic, bodies, the witness link and the memory GameTested; no resident seen in a real furnished house.** A resident for each bed, a worker for each counter or desk, identical in every run, at the building's own spots by the clock (a shop is empty at night), only when a player is near, never saved, never opening doors; a forced door is remembered by the household for three active hours in a saved, bounded record; a resident at home is a witness. The street people of the previous pass remain a separate roster. [households.md](../technical/households.md).

### 11. The hybrid resource economy

**Implemented as data, an audit, one regenerating site and GameTests; the scarcity package not yet run in a real world.** No natural ore in a new world (an opt-in package, a biome modifier, never applied to chunks that exist); ten finite shared shelves in the cities (iron 9 to 12 credits, coal 3, copper 7, charcoal 2, redstone 6, lapis 11, gold 38, diamond 150); one old quarry with fourteen seams of iron, coal and copper that grow back after 20 minutes of *active* play, at most 42 ores an hour; an audit that fails when a recipe or order needs an item with no route (0 findings: 54 orders, 25 recipes, 64 items); the order margin test that stays inside its band even when every raw material is bought. [hybrid-economy.md](../technical/hybrid-economy.md).

### 12. Mod compatibility and performance

* **Compatibility:** the furnished set (Lost Cities 1.20-7.5.5 with our styles, Modern Life 1.75) generates without errors in a 675-chunk probe, and loads in a real client; Lost Cities Modern Tweaks stays parked (it breaks chunk generation in our styles); the previous pass's findings on Entity Culling and ImmediatelyFast stand. SecurityCraft: read, not run.
* **Performance, measured:** the street people's pass (previous pass) fell from a mean 25 ms to 0.5 ms after caching Lost Cities' street lookups. **Not measured for this pass:** the cost of `HouseholdService` and `PoliceResponse` under a real furnished city (each is bounded: at most 24 bodies and three Lost Cities lookups a second for the households; 24 dispatches and five wider searches for the police; the resource site reads fourteen blocks every five seconds), nor the cost of placing the quarry at world start.

### 13. Test results, failures, fixes and remaining limitations

See "Evidence" below.

### 14. A Windows 11 playtest procedure

[playtest-guide-windows11.md](playtest-guide-windows11.md) section 6e: start command, 14 steps with what to see, operator commands, what to write down.

### 15. The next recommended milestone

See "Next" below.

## Evidence

(filled in at the end of the pass)

## Next

(filled in at the end of the pass)
