# Tenth pass: living world, C70 cities, gameplay expansion and world quality (October 27, 2026)

Owner brief: "Underground City: Tenth Pass — Living World, C70 City Overhaul, Gameplay Expansion & World Quality". Decisions D-196 to D-204. Branch `feature/tenth-pass-living-world`, a stacked draft PR on `feature/world-playability-overhaul` (PR #25 is not merged or changed).

**The current branch and its recent gameplay systems have not received human playtesting.** Every result below comes from scripts, Forge GameTests, the Python suite, generator probes of new disposable worlds, a dedicated server or scripted real clients. None of it was played by a person. Each scripted run logs every shortcut it takes, marked `SCRIPTED` or `PREPARED`. The owner's own world `UC-Alpha-Test-01` was never opened or changed. No C70 asset, converted structure, private selection, private screenshot or private world is in the repository, the PR or any CI artifact. The owner's buildings were ported, wired, probed and photographed in private folders outside the checkout only.

## 1. Roads and city ground: what caused the blocks, and the fix (D-196)

**What the owner saw:** grass and dirt blocks on paved streets, mushrooms and plants on the paving, and dirt, gravel, granite, diorite and andesite blobs in building floors.

**What caused it** (found with a traced probe that follows every block written into a city chunk after Lost Cities finished it, with the feature that wrote it):
1. **Lost Cities 7.5.5 levels a street one block short.** `doCityChunk` calls `moveDown(x, z, cityGround + 1, ...)`. That call answers "nothing to do" when the first solid block is at `cityGround + 1`, so a grass block standing exactly one above the street stayed on the paving: 1,603 / 968 / 1,520 cells in Blackwater, Ironhaven and Valewood in one probe.
2. **Every later decoration step may write into a finished city chunk.** Lost Cities builds a city in the first decoration step; ores, springs, plants and trees run after it and may write anywhere in their 3×3 chunk region. The ore features made the blobs in floors (77 / 153 / 103 cells); grass, tree and mushroom patches put plants and leaves on the paving.
3. **A neighbour decorated before the city was built** could leave a flower on what then became paving.
4. **Bare verges:** a street part's structure-void character keeps whatever terrain was there, so streets had raw stone and dirt edges (258 / 326 / 567 cells).
5. **The corridor roads' deck repair** ignored diagonal neighbours, and the road ends into the cities were bare earth.

**The fix is at the generation step that caused each problem; no pass is run over finished chunks afterwards.** Core corrects the levelling inside Lost Cities' own step. While a later feature runs, Core refuses its writes into a recorded city chunk from three blocks under its deepest cellar up to the sky. Two kinds are let through: garden plants on soil, and park trees over soil in a chunk with no building. Ore never starts there. Verges are grassed as Lost Cities' feature returns, the corridor deck counts diagonal neighbours, and the approaches are paved in polished andesite. Only chunks generated afterwards change. Details and every number: [city-ground.md](../technical/city-ground.md).

**Results** (new disposable worlds, the same seed, radius 10 round each city, guard off and then on):

| | Off | On |
| --- | --- | --- |
| Terrain on the paving (Blackwater / Ironhaven / Valewood) | 1,603 / 968 / 1,520 | **0 / 0 / 0** |
| Vegetation on the paving | 167 / 132 / 187 | 0 / 0 / 1 (an oak leaf four blocks over a Valewood street) |
| Bare verges | 258 / 326 / 567 | **0 / 0 / 0** |
| Stone and dirt blobs written into buildings | 77 / 153 / 103 | **0 / 0 / 0** |

**The intercity roads** (road check t10b, a new world) covered 21,191 carriageway cells on the three roads, and **every one was clear deck**. There was no missing deck, no blocked headroom, no step over half a block and no missing parapet. **All six road ends are walkable onto the city ground.** Three leaf blocks of roadside trees hang 4 to 6 blocks over the deck, above the three blocks of headroom; they were left.

**The acceptance in the brief** was "no unexplained terrain blocks obstructing or replacing paved road surfaces in the tested generated-world coverage". **It holds for the coverage tested here:** three cities at radius 10 and the three roads, in public and private worlds.

## 2. The owner's buildings (C70), residential first (D-198)

**Private, in the cloud only.** The owner's pack was ported and wired again from its original in private folders, using the tools in this repository. Probes ran in new disposable worlds. Only counts are given here.

**The selection, by city** (the brief's city characters):

| City | C70 single-chunk buildings in the selection | C70 multi-chunk buildings | Our buildings left in |
| --- | --- | --- | --- |
| Valewood | 48 houses (the cottage, family-house and farmhouse families in four orientations) and 2 shops | 6: 3 houses (two terraced pairs and a nine-chunk farmhouse), 2 shops and 1 used as its data says | the functional ones (bakery, shop, villa); our generic houses and cottage at weight 0 |
| Ironhaven | 12 flats (small and comfortable apartments) and 24 workshops | 8: flats, 2 workshops, a warehouse, a club, an office, a shop and 1 used as its data says | flats, workshop, warehouse, pub |
| Blackwater | 48 offices, 8 flats and 4 shops | 5: 2 flats, an office, a shop and 1 used as its data says | apartments, lofts, club; our office at weight 0 |

**What was wrong with the multi-chunk buildings, and the fix.** Lost Cities chooses a multi-chunk building from its standard style for every city. The private pack's own, which the selection names, were never chosen. Core now keeps a multi-chunk building only when the city's character lists it, swaps it for one of the city's own with the same footprint, or flattens it under a railway (`CityCharacter.keepOrSwap`). In the probe, 69 multi-chunk building chunks were swapped for the city's own and none were kept from Lost Cities' choice.

Two of the owner's multi-chunk buildings (a hospital and a terraced pair) were still left unbuilt, with Lost Cities logging "Could not find entry 'H' in the palette for part ...". **Cause:** a multi-chunk part uses characters that only its building's palette defines. The porter now gives the part those characters, but at first it wrote them as a bare list. Lost Cities 1.20.1 decodes a part's own palette only as `{"palette": [...]}`, drops a bare list without a word, and reads a part's own palette in place of the palette the part names. The porter now writes that form, with the named palette's entries included (`test_port_lostcities_pack.py`).

The wiring tool also read only the list form, so it saw the re-ported parts as empty. It wrote a home with an empty footprint, and **Core then refused the whole datapack and the server did not start**. The wiring now reads both forms and never writes an empty unit (`test_private_wiring.py`, two new cases). After the fix:
- the probe of all three cities logged no palette error and no datapack error;
- the private building audit compared 2,424,044 cells of 97 building types (86 of them the owner's) and found **218 missing blocks (0.009%)**: dirt, grass, flowers and saplings, no building fabric. It found 1,656 before the porter fix.

**Not done, and said plainly:**
- **A multi-chunk home is not listed.** Homes are listed per chunk, so the owner's multi-chunk houses are built and lived in but cannot be rented.
- **Some multi-chunk parts name no street door**, because their door leads into the next part. The wiring reports it.
- **The condo and tower plans find no beds.**
- **Interior photographs** of the owner's buildings in this pass are covered in section 10.

## 3. A two-player supply chain, tested (brief section 6)

`scripts/run_duo_session.py` runs a new archipelago world on a dedicated server with two real clients. In this pass the Blackwater client registers as a **Vintner**, through the real first-login screen. After the operator-command checks, phase 1 runs the chain (`DuoSupplyChain`); the burglary comes after it.

1. The Farmer founds a Crew ("Berry Run") and invites the Vintner, who accepts.
2. The Farmer goes out to the fields past Valewood's edge *(scripted travel)*.
   - Tills four beds with the kit's hoe (the server's own right-click path).
   - Picks four grown berry bushes with a right click and breaks four grown beetroot by hand in survival *(the crops are set down grown: scripted)*.
   - Picks up the drops: **8 berries and 4 beetroot**.
   - Sets the kit's Mill down with a right click and mills the beetroot: **3 sugar and 8 Farmer XP**.
3. The Farmer travels to the Vintner's ground past Blackwater's edge *(scripted)* and throws 8 berries and 2 sugar, as Q does; **the Vintner picks them up**.
4. The Vintner sets down the kit's Fermentation Vat and starts a batch of berry wine, which takes 8 berries, 2 sugar and 3 bottles. After **six minutes of play, not shortened**, the Vintner takes 3 bottles and **40 Vintner XP**.
5. At 21:00 *(time set: scripted)*, at a Blackwater club, the Vintner is put on the public floor and steps up to the barkeeper. The counter opens as speaking to them does *(scripted)*.
   - The bar was short (3 bottles of 12), so it **pays 19 a bottle instead of 15**: **57 credits through Q Shop**, the shelf goes from 3 to 6, and **18 Vintner XP**.
6. The Vintner pays **30 into the Crew bank**, the Farmer **takes 20 out**, and the bank holds 10.

**Phase 2 restarts the same world.** Every number the chain left was there: both wallets (95 and 27), the Crew with its bank of 10, both careers' XP, both inventories, the bar's shelf (6) and the Mill and the Vat where they were set down, empty.

**Duo runs 8 and 9 passed the whole chain and all of its after-restart checks.** Run 8 failed only the older burglary check: the house's one-minute wait had run out during the now-longer phase 1. Run 9 put the burglary after the chain, with the same numbers; its burglary result is in section 11.

Earlier runs found real problems, all fixed:
- **Run 4:** the farm row lay in a home's garden. The building guard stands aside there for the home's own guard, which refuses a stranger's harvest; the hoe tilled (an item's use) while the harvest was refused (a block's use).
- **Run 5:** within reach of a city's welcome every cell is a building's or a home's.
- **Runs 6 and 7:** the field past the edge was not yet entity-ticking a few seconds after the move. The crops' drops were neither listed nor ticked, and the diagnostics showed it.

**Candour about what the chain proves.** The Vintner's starter kit could have made this one batch alone (16 berries, 4 sugar). The run counts the goods from hand to hand rather than proving the Vintner needed them.

## 4. Fitness, Driving and Shooting, earned and used (D-200)

All five general skills now rise from what a person does and change something they feel. See [general-skills.md](../technical/general-skills.md).

**How they are earned.**
- **Fitness** and **Driving** pay for the straight line covered in ten-second windows: 25 blocks per XP on foot, 50 at the controls of a vehicle or a boat.
  - Pacing back and forth or walking in circles pays next to nothing.
  - A window counts only if the player turned their view in the last minute, so a water stream or a moving platform pays nothing.
  - A teleport inside a window voids it; caps of 600 XP an hour each.
- **Shooting** pays 2 to 7 XP for a hit on a target block from ten blocks or more, with a TaCZ bullet or an arrow. The same block pays once in two seconds, up to 300 XP an hour.

**What they give.**
- **Fitness:** 2, 4 and 6% quicker on foot from levels 3, 6 and 10.
- **Driving:** the vehicle dealers 3, 6 and 8% cheaper, inside the shared 10% discount cap.
- **Shooting:** a shot leaves 20, 35 and 50% of the way closer to where the shooter looks, with its speed and damage unchanged.

**Evidence:**
- **GameTested only.** Nothing was driven, walked or shot by a person or a scripted client.
- The Shooting effect on TaCZ bullets was **not** measured through a real client.
- The caps reset when the server restarts.

## 5. Shops that run short, keepers who remember, a city's good name (D-201, D-202, D-204)

- **Shortages.** A shelf that a trade can fill goes short once its customers have bought it down to a quarter.
  - Until it is half full again the shop pays a quarter more a unit, always below its own price, so buying from it to sell back never pays.
  - The counter shows "short: buys at 19"; the players of that trade and the shop's regulars are told once an hour at most.
  - The duo chain sold into a real shortage.
- **Keepers remember.** Three trades at a kind of counter in a city make a regular, and filling a short shelf is remembered as help; the keepers greet both differently.
  - A regular hears of that counter's shortages whatever their trade.
  - A keeper who saw a player at their building's forced door will not serve them while they remember it.
  - It changes no price.
- **A city's good name.** Each city keeps a standing for a player from 0 to 100, with the city's existing heat still the measure of trouble.
  - Raised by a legal delivery there (+1), a civic delivery (+3 more) and a filled shortage (+2), at most 10 an hour a city; lowered by an arrest there (−15).
  - From 25, people there mention it when you speak to them; from 50, that city's sellers take 2% off, inside the shared 10% cap.

All three are GameTested (save and load included) and were not played by a person.

## 6. Burglary damage and an owner's changes (D-199)

The rules already kept an owner's changes apart; this pass proves it.
- Only a permit holder's breaks, in a building nobody holds, are recorded.
- Inside any registered home the burglary rules refuse to break anything.
- Restoration fills only cells that are still empty, never inside a held home, and then forgets the record.

A new GameTest covers the full sequence:
1. A thief breaks a wall before the house is let.
2. The house becomes the owner's home.
3. The owner takes up a carpet and builds a shelf where the wall was.
4. The wait runs out.

Result: nothing is put back, nothing is overwritten, the record is gone (so nothing comes back after the owner moves out), and nothing drops.

## 7. A bought first aid kit (D-203)

No medical mod is selected (D-085). Core's **First Aid Kit** gives three hearts back, once in thirty seconds, never at full health. Every city's exchange sells a few for 18 credits, and each city's wayside desks sell one at a time for 24. It is never free. Its sprite is drawn by the project's own art tool, in the house style. GameTested; not used by a person.

## 8. Lost Cities' memory (D-197)

**Retention found in Lost Cities' code:** a plan keeps its four neighbours in fields for good, so every plan ever made stays reachable. Core drops those links a slice at a time, every minute, without clearing Lost Cities' cache; see [lost-cities-memory.md](../technical/lost-cities-memory.md). The two exploring two-client sessions (unchaining off, then on) are reported in section 11.

## 9. Screens reviewed and corrected

The new screens were captured through a real Forge client at the three viewports the brief names, and every frame was opened and read:
- **3440×1440 at GUI scale 2;**
- **1920×1080 at GUI scale 2;**
- **1920×1080 at GUI scale 3.**

The screens:
- the Skills page's General tab, at its top and scrolled to its end;
- the Blackwater club bar's counter with a short line;
- the tenth pass's notices.

Defects found by looking, all fixed and captured again:

| Defect | Where it showed | Fix |
| --- | --- | --- |
| The General tab's five cards ran over the footer and off the screen | all three viewports; worst at 1920×1080, GUI 3 | the cards clip to their well and scroll with a bar (wheel or arrow keys); a new capture, `uc-skills-general-end`, shows the end |
| The bar's short line read "short: buys at..." and hid the price | 1920×1080, GUI 3 | the line falls back to "short: 19" when the full words do not fit |
| The shortage notice's title ran past its plate, and off the screen at GUI 3 | all three | the title is now "The Bar is short", with the city in the body; any notice title too long for its plate is cut with an ellipsis |
| "Valewood speaks well of y..." once titles were cut | 1920×1080, GUI 3 | the standing titles are now "Known in Valewood", "Respected in Valewood", "No longer respected" and "Talk of an arrest" |
| The tenth notices' capture stacked the default plates under its own | the harness | fixed in the harness |

The CI gallery check had refused the two new capture names until they were registered in `content/qa/gui-coverage.json`.

These are my judgements from the frames, not the owner's. The frames are local review copies and are not committed.

## 12. Not done, not proven, and known problems

**Not built in this pass** (the brief asked for them; they are left rather than half-made):
- **Player business v1** beyond the sixth pass's trade premises: no hired staff, no shop of a player's own that NPC customers visit.
- **Dynamic contracts:** shortages (D-201) are the only new demand that moves with the world. Work orders are still the fixed, data-driven set.
- **NPC routines:** the keepers' memory (D-202) is new; daily routines, relationships and meeting a player again outside a counter are unchanged.
- **Crime, police and logistics:** a city's good name falls with an arrest (D-204), and a seen thief is refused service (D-202). Policing, witnesses and the guilty-only rule are as the ninth pass left them. Nothing new for logistics or freight.
- **The optional World Gen Studio** was not started.
- **Repairing an existing world** is not offered. The street and floor fixes apply only to chunks generated after them.

**Built but not proven by a person or a real client:**
- **Fitness, Driving and Shooting** are GameTested only. No vehicle mod's car or boat was driven in a test. Whether TaCZ shows a steadied bullet's tracer where it flies is unverified. The hourly caps are in memory and reset on a restart.
- **Shortages, regulars, a city's good name and the first aid kit** are GameTested. The duo chain sold into one real shortage through two real clients; nothing else of them ran through a client.

**Known problems:**
- **The owner's multi-chunk homes cannot be rented** (section 2). Homes are listed per chunk. Some multi-chunk parts have no street door of their own, and the selection lists none of them. Which multi-chunk houses are one home and which are a pair of homes is the owner's call.
- **The condo and tower plans find no beds,** so their households sleep nowhere in particular.
- **In the duo chain, the Vintner's starter kit could have made the batch alone.** The run shows goods, money and XP moving between two players and surviving a restart. It does not show that the Vintner needed the Farmer.
- **The duo session puts a player at a counter by teleport.** In run 8 the Vintner stood on the barkeeper's cell. That is the script's shortcut and is logged.

**The current branch and its recent gameplay systems have not received human playtesting.**

## 13. Windows playtest instructions

The exact steps are in [playtest-guide-windows11.md, section 6j](playtest-guide-windows11.md#6j-tenth-pass-clean-streets-the-five-general-skills-shortages-and-a-supply-chain-about-60-minutes-a-new-world). In short, in PowerShell from the repository folder on each PC:

```powershell
.\scripts\windows\uc-update.ps1 -Branch feature/tenth-pass-living-world
# the host
.\scripts\windows\uc-server.ps1 -Sets furnished,scarcity -World UC-T10 -LanOfflineMode -AcceptEula
# each player (the host too, in a second window)
.\scripts\windows\uc-client.ps1 -Name <your name> -Sets furnished,scarcity -Join
```

- **Use a new world name** (`UC-T10` above). The street and floor fixes apply only to new ground. Never open `UC-Alpha-Test-01` with this branch for a test of them.
- One player registers as a **Farmer in Valewood** and the other as a **Vintner in Blackwater**. Then follow the twelve rows of section 6j.
- **The owner's private buildings** need the private pack ported and wired again with this branch's tools, into a new private folder, before a new world is made with it (section 6j's last paragraph). The scripts were written for Windows and **were not run on Windows** in this pass.

## 14. What to develop next

1. **A person plays section 6j.** Everything here waits on that: whether the streets look right, whether walking for XP feels fair, and whether a short shelf is worth a trip.
2. **Multi-chunk homes.** The owner says which of their multi-chunk houses are one home. Core then lets a home span several chunks: one box per part, under one lease.
3. **Player business v1:** a premises a player stocks, which the city's customers visit, with the stock and the money settled through the ledger.
4. **Dynamic contracts from shortages:** a short shelf that stays short becomes a timed order with a deadline, so the demand reaches players who are not at the counter.
5. **Measure Driving with a real vehicle mod** and Shooting with TaCZ through a real client, and keep the caps across a restart.
