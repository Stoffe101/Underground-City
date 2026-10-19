# Expansion plan: from a tested slice to a populated, playable region

> **Superseded in part (October 19, 2026; D-092):** the backpack provider named in this report, Traveler's Backpack, was **replaced by Sophisticated Backpacks + Sophisticated Core**. Read "Traveler's Backpack" below as the retired provider; the current facts are in [backpack-economy.md](../technical/backpack-economy.md). Everything else here is as it was when written.

Status: **proposal for the owner's decision**, written October 18, 2026 after the Crew sprint. Nothing below is built unless the "Today" line of its work package says so, and a "Today" line only claims what a named test or record shows. Accepted intent is in [vision.md](../design/vision.md) and [decisions.md](decisions.md) (D-080 in particular); this document says *in what order, with what proof*. It does not change a decision.

How to read a work package (WP):

* **Outcome**: what a player can do that they cannot do now.
* **Today**: what exists, with evidence (GameTest, scripted client, record) and what is only design.
* **Build**: the smallest set of changes, reusing a finished mod or a data file before new code ([reuse-first.md](../technical/reuse-first.md)).
* **Proof**: automated tests that must pass, real-client or two-client checks, and the human check that no script can replace.
* **Decide**: questions only the owner can answer. A recommendation is given; nothing is assumed.
* **Size**: S (days), M (about a week), L (more than a week), for one engineer with the cloud tooling.

## 0. Principles for every package

1. **A playable loop before the next system.** Each package ends with a loop a pair of friends can complete in one session and a test that walks it (central loop of D-080: explore, acquire, craft, sell, earn, unlock, improve, expand).
2. **Legal and underground side by side** (D-049): every package says what the legal and the underground version is.
3. **One authority per mechanic** (CLAUDE.md): Q Shop for credits, Core for Crews, careers, law and homes. Third-party mods supply content (items, blocks, models), not rules we depend on.
4. **No Create** (D-080), nothing that requires it.
5. **Proof levels are kept apart in every report**: unit or GameTest (server, headless), scripted real client, two real clients, human. A package is not "done" at the first level.
6. **Two players are enough**; NPCs close labour gaps; nothing punishes an absent player (D-012).

## 1. Order and dependencies

```
WP12 human playtest + measurements ---------------------------------> (continuous, first)
WP1  Crew contracts (shared production) --> WP3 property upgrades --> WP4 illegal production
WP6  contextual loot ----------------------> WP7 burglary ----------> WP8 law enforcement and raids
WP9  survival (fuel, shelter shops) --------> WP11 transport contracts (vehicles, boats)
WP10 populated districts (routines) ---------> WP7/WP8 (witnesses need people) , WP1 (NPC labour)
WP2  skill growth + WP5 manufacturing: after the loops above show where the gaps are
```

Recommended next three sprints: **A** (WP12, WP9 shelter shop, WP1 first Crew contract), **B** (WP6 and WP7 on one apartment), **C** (WP10 a real daily routine for twelve citizens, WP8 witness-based response at one post).

## WP1. Crew contracts: several professions fill one order

* **Outcome**: a Crew takes a contract that needs a gun, a barrel of wine and a delivery, each made by a different member; the reward is split by a stated rule into each member's own wallet through the Crew bank; two players can do it alone with NPC suppliers filling the third part.
* **Today**: Crews, roles, the bank (three finance modes, crash-safe), leased homes with shared storage and workstations are built and tested ([crews.md](../design/crews.md), [crew-economy.md](../technical/crew-economy.md)). Work orders are personal; there is no shared contract. *Not built:* every line below.
* **Build**: (1) a `crew_contract` data kind (schema 1): `parts[]` each with a career or "anyone", item requirements and a share; (2) a Core `CrewContractLedger` kept **inside `CrewLedger`'s file** (one atomic write with the bank, as a Crew payout already is) so that a delivery, a reward and a bank movement can never disagree; (3) the terminal shows open Crew contracts for the member's own part; (4) delivery of a part is idempotent by transaction id (reuse the ledger's replay protection); (5) NPC supplier fills a missing career part at a stated price (active-play only, never offline); (6) rewards: credits (by `deposit` into the bank or split to wallets, per the Crew's mode) and XP **only to the member who delivered**.
* **Proof**: GameTests with the real wallet for each crash point of a part delivery and of the payout (the existing crash matrix pattern), conservation over randomised member orders; a two-client journey (extend `CrewJourneyServer`) with two careers; human: is the split fair, is the contract readable.
* **Decide**: reward split default (equal, by part value, by hours); whether a Manager may accept a contract for the Crew (recommended: yes, with the SPEND permission).
* **Size**: L.

## WP2. Skill growth

* **Outcome**: a level-30 character has a full tree and a reason to specialise; Crew-wide perks exist but are small.
* **Today**: five data-driven trees (D-050), one point per level above 1, whole tree affordable at level 30; effects are limited to a capped inspection modifier, exchange discount and licences ([professions.md](../design/professions.md)). General skills have a design ([general-skills.md](../design/general-skills.md)), **no implementation**.
* **Build**: general skills (driving, negotiating, lockpicking, first aid, survival) as a second tree sharing the same loader; effects added only when a system consumes them (fuel economy, repair, medical items); one Crew perk table (data) read by `CrewService`.
* **Proof**: tree layout test (exists), effect-cap test per new effect, no stackable exploit (a test that equips every effect and checks the cap), balance simulator extended.
* **Decide**: whether general skills share points with the career tree (recommended: separate pool, slower).
* **Size**: M.

## WP3. Property: rent, upkeep, upgrades

* **Outcome**: a home can be upgraded (more private containers, a better workstation, a safe) and is paid for as long as you play, never while you are away.
* **Today**: finite leased homes with a one-time price, a server-side guard (break, place, doors, containers, workstations, explosions), private containers with a three-day retrieval window (D-084). *Not built:* rent, eviction, transfer, upgrades; the guard does not yet cover pistons, fluids, fire, mobs, item frames or hoppers across the shell.
* **Build**: (1) guard extensions for the listed gaps, each with a test that tries the exploit; (2) `rent` as an **active-play** charge (accrues only while an owner or Crew member is online, capped, visible; never an eviction without a seven-day warning that also only counts active play); (3) upgrade levels as data on `residence` (containers, workstation tag set, price, requirements); (4) home transfer between player and Crew by the ledger (one write); (5) a property terminal page.
* **Proof**: guard exploit tests, rent accrual under logout/login/restart, conservation of credits through rent and upgrades, two-client journey with an upgrade purchase.
* **Decide**: rent versus one-off price (crews.md section 8, questions 2 and 5).
* **Size**: M.

## WP4. Illegal production

* **Outcome**: a Farmer can grow the restricted crop in a hidden plot, a Vintner can run a still, a Gunsmith can assemble unregistered builds; each is profitable and carries a physical, discoverable risk. No path is compulsory.
* **Today**: contracts, contraband classification per city, checkpoint searches, heat and trust, the Sable Leaf (original art), spirits through Vinery/Kaleidoscope Tavern where installed, and unregistered-build contracts exist (D-049 to D-054, D-067); a hidden *facility* does not.
* **Build**: a `facility` data kind (a residence region with a production rule, a heat contribution and a discoverability model); production counted in active play only; discovery by evidence (a witness report, a tip, a search) per D-051; the rule that a Crew never inherits a member's heat stays.
* **Proof**: facility GameTests (yield, heat, discovery by evidence only, no discovery without a witness or a search), arithmetic balance extension (D-052), a scripted client run, a human balance session.
* **Decide**: whether a Crew facility's heat is split (recommended: attributed only to the person who operated it).
* **Size**: L.

## WP5. Advanced manufacturing

* **Outcome**: multi-step chains (parts, housings, controllers, platforms) that a Smith/Engineer, a Gunsmith and a Technician each own a link of.
* **Today**: regional commodities are original items (D-043); recipes exist for 25 products (optic mounts, composite housings, controllers, greenhouse units, still columns and others) as ordinary crafting recipes; recurring orders consume them. The **source audit** ([item-source audit](../technical/item-source-audit.md)) checks every order's inputs have a path.
* **Build**: only where a chain has a gap the audit names; machines come from licensed mods that are not Create (candidates are in the mod audit), behind a Core-owned recipe gate like D-073's, not a second progression system.
* **Proof**: the audit stays green in CI; one GameTest per chain from first input to delivered output with credits and stock conserved.
* **Decide**: none until the audit shows a gap. **Size**: M, and demand-driven.

## WP6. Contextual loot and exploration

* **Outcome**: an apartment, a workshop, a hideout and a wilderness cache each hold what fits them, and two players find different things in the same room.
* **Today**: Household Safes roll an exceptional find once from a position-fixed seed (D-045, D-053); the loop "found schematic, unpacked, traded for parts, fitted, delivered" is in the item-source audit and in one GameTest (see the Gunsmith section of the sprint report). *Not built:* per-player instancing of ordinary containers (Lootr is only a candidate), wilderness and police-site tables, salvage nodes.
* **Build**: evaluate one per-player-loot provider on the exact versions (candidates in the mod audit; none is adopted); Core tables for the missing places; a "salvage" source for components that is bounded (a node regrows in active play).
* **Proof**: per-player loot GameTest (two fake players, different results, no double withdrawal), reload and restart tests, no relog farm.
* **Decide**: which provider, if any. **Size**: M.

## WP7. Burglary

* **Outcome**: a planned break-in with a lockpick, a time window, noise, a witness check and a way out, in a house that is occupied by an NPC and restocks slowly.
* **Today**: the design ([burglary-and-property.md](../design/burglary-and-property.md)), Household Safe lockpick and forced-entry events, vigilance. *Not built:* a mission, noise, NPC occupants that react.
* **Build**: depends on WP10 (people) and WP6 (loot): a `break_in` contract using one registered home with an NPC occupant on a routine; noise as a number the server computes from the player's actions; witnesses as NPCs with a view cone; consequences through the existing heat/evidence model.
* **Proof**: GameTests (noise rules, witness rules, no crime without a witness or evidence), a scripted run, a human run.
* **Decide**: how lethal NPC reactions may be (recommended: flight, alarm, police; no combat by default). **Size**: L.

## WP8. Law enforcement and raids

* **Outcome**: police respond to evidence, fines and arrests are fair, a raid is a physical encounter at a hideout, and none of it is arbitrary.
* **Today**: city laws, checkpoint searches at six zones, heat and trust (D-051); posts with guards (D-062). *Not built:* patrols, response to a witnessed crime, arrest flow, raids ([crime-and-war.md](../design/crime-and-war.md)).
* **Build**: witness-based reports into the heat model, a bounded response (a few named officers, never a crowd), an arrest flow with a fine option and a no-loss rule for gear, and one raid scenario per high-tier contract (D-033); nothing runs against an offline player.
* **Proof**: GameTests for every consequence, a two-client scenario (one player evidence-free beside one with evidence), performance with the response active.
* **Decide**: arrest versus fine default; whether police may be fought (recommended: possible and costly). **Size**: L.

## WP9. Survival integration

* **Outcome**: thirst, food, carrying capacity and fuel matter, can be bought in cities, and shelters on the road sell limited supplies.
* **Today**: the `survival` set is pinned (Traveler's Backpack, Thirst Was Taken 2, Farmer's Delight, Comforts) and tested on a dedicated server where the sprint report says so; D-079 wayside shelters stand beside the roads with a warden who answers from real data and **sell nothing yet**. Owner direction for the shelters is recorded in D-079 and below.
* **Build**: (1) shelter wardens sell a **limited** stock of food, water, bandages and basic supplies through the existing regional-exchange ledger (stock refills in active play, prices in Q Shop credits, no free healing, no respawn change, an explicit paid "rest" as an option only); (2) city shops for the survival goods (a Q Shop supplier entry, original provider templates only, D-038); (3) vehicle fuel: a fuel item with a price and a production chain (Smith/Engineer), consumed by the vehicle mod's own fuel rule where it has one; (4) the starter kit stays unchanged.
* **Proof**: shelter-shop GameTests (stock bound, refill in active play only, wallet conserved, a refused sale changes nothing), a two-client check that a purchase persists, the survival set in the dev-pack smoke matrix.
* **Decide**: see D-079 "Decide". **Size**: M.

## WP10. Populated districts

* **Outcome**: twelve citizens per district with homes, jobs, a daily routine and memory of the player; the rest of the city is simulated cheaply off screen.
* **Today**: Founders Row has nine to twelve named villagers placed by data with beds, workstations and a bell (D-061, D-075), an employer who walks back to the terminal (D-066), contextual dialogue (D-072). *Not built:* routines beyond vanilla's, schedules in data, memory, off-screen simulation (D-014).
* **Build**: a `schedule` data file per citizen (home, workplace, shop visits by time of day) executed by vanilla's schedule and a bounded Core brain **only for citizens near a player**; an off-screen state machine that advances positions and stock coarsely and reconciles when a chunk loads; a hard cap on active citizens per player.
* **Proof**: tick-cost measurements with the cap (the perf probe pattern), reconciliation tests (no duplicate goods, no lost citizen), restart tests.
* **Decide**: citizens per district (recommended: twelve now, twenty-four when the tick cost is measured on a player-loaded world). **Size**: L.

## WP11. Vehicles, boats and transport contracts

* **Outcome**: a contract that needs a loaded vehicle or a boat; fuel costs; customs on the water routes.
* **Today**: a car drove 200 blocks and a boat sailed 130 blocks in scripted runs (cloud only); vehicle crates are searched at checkpoints (D-077). *Not built:* fuel, customs on water, waterborne smuggling, a transport contract.
* **Build**: a `transport` contract kind (cargo of N crates, a destination post, a time window in active play); a boat post; fuel from WP9.
* **Proof**: GameTests for contract rules with the real vehicle mod, a scripted run, a human drive.
* **Size**: L.

## WP12. Human playtest and real measurements (first, always)

* **Outcome**: facts instead of estimates: how long the first thirty minutes takes, how long the roads take, what the tick cost is with players in a loaded world, what two people actually do with Crews.
* **Today**: the [Windows 11 playtest guide](playtest-guide-windows11.md) exists; no human has played any of it. Measurements so far are cloud estimates and scripted runs.
* **Build**: nothing but the guide, a bug template and a short post-session questionnaire in the guide.
* **Proof**: the owner's session notes, the logs and screenshots the guide asks for.
* **Size**: S, but the single most valuable item.

## Deliberately out of scope for this plan

All four careers fully, all cities, and full warfare before the slice works; a second currency; Create and its add-ons; anything requiring an absent player.
