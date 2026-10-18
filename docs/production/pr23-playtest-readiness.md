# PR #23 follow-up: the shared-finance bug, authentication, home security, survival supply and the combined journey

Branch `feature/crews-shared-homes-and-finances`, the same **draft** PR [#23](https://github.com/Stoffe101/Underground-City/pull/23). Nothing was merged. This report answers the owner's review of October 18, 2026 ("Critical Shared-Finance Fix and Playtest Readiness") point by point. Decisions: **D-089** (the purchase protocol), **D-090** (identity, authentication and the home guard), **D-091** (survival goods on sale).

**Status vocabulary** (as in the earlier sprint reports): *GameTested* = an automated test in a headless dedicated test server; *journey* = two real Minecraft clients and a real dedicated server driven by `scripts/run_cloud_journey.py`; *mutation-checked* = a deliberate bug was applied to the code and the named tests failed; *human* = a person played it. **Nothing here is *human*.** Everything ran in the Claude cloud container (4 CPUs, 16 GB) or on GitHub Actions, never on the owner's computer.

## 1. The critical bug: Crew money could leave the bank on a refused purchase

**What the owner found.** In fully shared finances, with a Crew bank of 20, a buyer's wallet of 0 and a price of 100, the purchase was refused but the member ended with 20 credits of the Crew's money in their wallet and no goods.

**Cause.** `CrewService.topUpForPurchase` (called by `RegionalMarket.purchase` before it knew whether the purchase could complete) moved the bank's share into the buyer's wallet *first*, as an ordinary withdrawal, and only then looked at whether the wallet now covered the price, the stock existed and the inventory had room. Every refusal after that point left the credits in the wallet; there was no path that returned them. It was reachable by anybody who could submit a purchase: the same call, repeated, drained the bank into one member's wallet for as long as it held credits (a member without the *Spend* right could do it too, because only the Rules page, not the code, looked at that right). A best-effort refund would not have been crash-safe either: a crash between the credit and the refund strands the money.

**Fix (D-089; protocol in [crew-economy.md](../technical/crew-economy.md#4b-purchases-paid-from-the-bank-d-089-one-decision-never-through-the-wallet)).** The bank's credits never enter a wallet for a purchase.

1. A read-only **plan** (`CrewLedger.purchaseShare`): nothing unless fully shared finances are in force, the buyer holds `SPEND`, the bank is not empty and nothing of the Crew's awaits recovery; otherwise `min(bank, price)`. The market checks, *before anything moves*, that the buyer's own wallet covers the rest, that the stock exists and that the inventory has room.
2. **Reserve** (`reservePurchase`): the share is taken out of the bank into a durable **hold** in the Crew ledger (one atomic fsynced write). No wallet is touched.
3. **Prepare**: the work-order ledger's prepared purchase record, with absolute inventory and wallet targets, **names the hold**. That one write is the commit point.
4. **Apply, commit, settle**: recovery (the same code as the normal path) sets inventory and wallet to the targets, calls `commitPurchase` (a `PURCHASE` line in the Crew history) **before** it settles and removes the prepared record. Each step is idempotent.
5. **Undecided holds are released, never guessed**: at server start, at login and at the start of every purchase, a hold that no prepared record names goes back to the bank in one atomic write. An exception after the prepare record is not treated as "refund"; the ledgers are asked what was decided.
6. Spending the bank needs the **Spend** right *and* effective fully shared finances (this closes the second half of the bug, and tightens D-084: a member without Spend used to be able to spend the bank through purchases). A Crew with an unresolved hold cannot be disbanded, left or paid out.

Q Shop stays the only wallet authority (D-037, D-039). Q Shop's own shop screens never use the bank; the Crew screen now says so.

**Which tests prove the regression cannot come back** (`CrewPurchaseGameTests`, 13 GameTests, all with the real Q Shop wallet unless Q Shop is absent):

| Owner's requirement | Test |
| --- | --- |
| **The original 20 / 0 / 100 scenario, written before the fix** | `aRefusedSharedPurchaseLeavesTheBanksCreditsInTheBank`. It was run against the old code first and **failed with "left the member with 20.0 credits of the Crew's money"** (log kept in the working notes, `regress-before.log`); it passes after the fix |
| insufficient combined funds | `insufficientCombinedFundsRefuseAndChangeNothing` |
| partial Crew funding with sufficient personal funds | `theBankPaysFirstAndTheBuyersOwnWalletPaysTheRest` |
| fully Crew-funded | `aPurchaseCanBePaidEntirelyByTheCrew` |
| no withdrawal (spend) permission | `withoutTheRightToSpendTheBankIsNeverUsed` |
| insufficient stock, full inventory | `insufficientStockAndAFullInventoryRefuseBeforeAnyCreditMoves` |
| duplicate requests | `aDuplicatedRequestChargesAndDeliversOnce` |
| exceptions during settlement, at each step | `anExceptionAtEachStepEndsWithTheWholePurchaseOrNone` |
| crashes at every transaction boundary (after reserve, after prepare, after apply, after commit; six cases, the player file written or lost where that matters, **both ledgers re-read from disk** each time, then recovery run twice to show it is idempotent) | `aCrashAtAnyBoundaryOfACrewFundedPurchaseLosesAndDuplicatesNothing` |
| two clients (two members) buying from one bank | `twoMembersSpendingTheSameBankNeverSpendOneCreditTwice` |
| **an unauthorised player cannot extract Crew funds by submitting failing purchases** | `failingPurchasesOnPurposeExtractNothingFromTheCrew`: 70 deliberately failing attempts of seven kinds, for a member without and a member with the Spend right; after every attempt the bank, both wallets, the stock and the holds equal their starting values |
| recovery and disband/leave while a hold exists | `aCrewCannotBeDisbandedOrLeftWhileAPurchaseHoldIsUnresolved` |
| conservation under random operations | `randomisedCrewPurchasesConserveEveryCredit`: 220 random purchases by two members with random funding, failures, duplicated nonces and process deaths; the sum of wallets, bank and sinks is checked at every step |
| recovery after a real restart | the **combined journey**: a purchase stopped right after its prepared record, the server JVM halted without a stop sequence, restarted (section 5) |

**Do these tests catch the bug, or only pass?** Each was shown to fail on a deliberately broken copy. Nine mutations of the purchase code, each applied, run and reverted, **all caught** (`scratchpad/mutation2-report.txt`, summarised): the buyer's wallet not checked before the hold is taken (caught by 5 tests, including the regression test); holds never released; holds released although a prepared record names them; the work-order recovery never committing the hold; a member without the Spend right using the bank; a hold that does not leave the bank; a released hold that does not return; a disband or leave that ignores a hold; the bank's share not limited to the price. Five earlier mutations of the transfer protocol and the guard were also caught (`mutation-report.txt`).

**Not covered, said plainly:** two clients on *separate threads* (the server thread serialises every request, so the tests interleave two members' requests and crashes on one thread, which is how the real server behaves); a power cut or an external `kill -9` (the journey halts the JVM with `Runtime.halt`; the file system's own durability is assumed); a human doing it.

## 2. Authentication: `online-mode=true` is the supported launch

**What was wrong.** The Windows playtest guide told players to set `online-mode=false`. With that setting the server believes any name a client sends and derives a UUID from it, so anybody who can reach the port and types a Crew member's name *is* that member.

**What changed (D-090; [identity.md](../technical/identity.md)).**

* Everything Core keeps about a person (citizenship, career, work orders, Crew membership and role, the bank, owed payouts, homes, private containers) is keyed by the **account UUID**; a name is a label updated at login. Nothing decides a right from a name (the one place a name is looked up is typing it to invite someone, which resolves once to the UUID of the player online).
* `scripts/make_server_pack.py` builds a production-style server in an untracked folder from the pinned, hash-verified jars: `online-mode=true`, `enforce-secure-profile=true`, `white-list=true`, `enforce-whitelist=true`, `pvp=false`, start scripts for Windows and Linux. The [Windows guide](playtest-guide-windows11.md#3b-authenticated-launch-for-real-accounts) has the steps (build, accept the EULA yourself, white-list, each player's normal-launcher profile) and says that `online-mode=false` is for the developer's own PC or a trusted network only.
* The server **warns** at start (`[UC] identity: online-mode=false. This server does NOT verify accounts...`) and each Crew member sees a notice at login on a server that does not verify accounts; `/uc_identity` (operator) reports the mode and counts offline-derived identities.
* **No silent migration.** An authenticated server counts the citizens and Crew members whose UUID is the one an offline server derives from their name, warns, and changes nothing. There is **no migration tool**: an offline world stays on its trusted network or the authenticated world starts fresh. (A real migration would have to move the player file with the wallet, the citizen record, the work-order journal and every Crew membership, ownership and payout in one crash-safe step; it has not been built.)

**Evidence.**

| Claim | Test |
| --- | --- |
| An unauthorised player with a member's *name* cannot act as the member: no Crew, bank, payout, home, private container, invitation or wallet; a login under the name changes nothing of the member's record; a renamed account keeps everything | GameTest `crewMembershipMoneyHomesAndPayoutsBelongToTheAccountNotTheName` |
| Offline-derived identities are recognised and **never migrated silently** | GameTest `offlineDerivedIdentitiesAreRecognisedAndNeverMigratedSilently`; Java's `UUID.nameUUIDFromBytes` values are pinned in `tests/test_authentication.py` |
| The server pack is authenticated by default | `tests/test_authentication.py` |
| A real Forge server booted with the pack's `online-mode=true` demands authentication; the same server with `online-mode=false` lets the login go on for any claimed name | `scripts/run_auth_check.py` (protocol level: the answer to a login is an **Encryption Request**); run in the cloud; added to the hosted `production-boot` job |

**Not tested, and why:** a successful login by a real Microsoft account (the cloud has none: step 1 of the Windows playtest is that test); Mojang's session server (vanilla); the normal-launcher client with the pinned jars (the server side of the pack is run, the client side is the ordinary Forge setup). The scripted two-client journeys use offline test profiles, so they run on `online-mode=false` and are labelled so.

## 3. Home security: the bypasses that were open, and what happens now

The guard (`ResidenceGuard`, server side) used to cover break, place, doors, containers, workstations and explosions. The design listed pistons, fluids, fire, mobs and entities as gaps. Each is now covered, with a GameTest that attempts it, a **control** in which the same action outside a home or by an entitled player still works, and a mutation check in which the guard's code is switched off and the test must fail (`ResidenceSecurityGameTests`, 10 GameTests):

| Bypass | Result | Test |
| --- | --- | --- |
| Pistons pushing or pulling blocks across the boundary, powered by anyone | refused (tag `residence_transfer_blocks`) | `pistonsCannotMoveBlocksAcrossAHomesBoundary` (the first version passed vacuously because the piston was unpowered; fixed with a redstone block and a wait) |
| Hoppers and droppers draining private storage | a hopper cannot be placed beside storage its placer may not open; a hopper under a chest elsewhere still drains it (control) | `aHopperCannotBePlacedToEmptyStorageItsPlacerMayNotOpen`, `controlAHopperUnderAChestDrainsItOutsideEveryHome` |
| Modded containers | recognised by their item-handler capability, not only the vanilla interface; breaking a container needs the storage right as well as the build right | `containersAreRecognisedByTheirItemHandlerNotOnlyByTheVanillaInterface`, `breakingAContainerNeedsTheStorageRightAsWellAsTheBuildRight` |
| Item frames, armor stands, paintings, chest boats, minecarts | cannot be hit, emptied, rotated, opened or broken; spared by explosions and arrows | `framesStandsPaintingsAndChestVehiclesInAHomeAreProtected` |
| Placing entities, fluids and fire (spawn eggs, boats, minecarts, stands, frames, end crystals, buckets, flint and steel, fire charges) | need the build right; a member who may enter but not build cannot | `aMemberWhoMayEnterButNotBuildCannotPlaceEntitiesFluidsOrFire` |
| Mobs: griefing, hostile spawns, trampled farmland | refused inside a home | `bucketsFireAndMobsCannotChangeAHomeFromOutsideItsRights` |
| **Authorised play must still work** | crafting, storage and building by the entitled | `normalCraftingStorageAndBuildingStillWorkForTheEntitled` |
| **NPC properties meant for burglary stay open** | a house that is not a registered player or Crew home is not covered | `anUnregisteredHouseStaysOpenForBurglary` |

Mutation results (`mutation3-report.txt`, `mutation4-report.txt`): eleven deliberate breakages of the guard, **all caught**, after one was first **not** caught (switching off the check on items that place entities, fluids and fire left the suite green) and a test for a member who may enter but not build was added; the mutation was then caught.

**Still open:** fire that spreads in from outside a home, lightning, dispensers that spill fluid across the shell, vehicles of mods other than the vanilla chest boat and minecart, and any mod that moves blocks with its own code instead of a piston. Creative-mode players are never held back.

## 4. Survival: tested mechanics, and the goods are on sale

**Mechanics, driven through each mod's own code** (`SurvivalMechanicsGameTests`, 7 GameTests with the `survival` set): thirst drains with effort and drinking replenishes it; clean and fresh water are safe, dirty water makes the player ill; a canteen holds servings and each drink takes one; boiling dirty water in a furnace makes it safe; a flask filled from a pond holds that pond's water and quality; a worn Traveler's Backpack holds items and keeps them through a save and load; a Farmer's Delight cooking pot turns ingredients into a meal that feeds. Sleeping bags and spawn behaviour: `SurvivalGameTests` (a Comforts sleeping bag does not move the respawn point; a vanilla bed, the control, does).

**Supply (D-091).** Twenty-one finite stock lines (`regional_stock/survival/`, generated from `content/gameplay/survival-supply.json` by `scripts/generate_survival_supply.py`, checked in CI) put the goods into the **existing regional exchange** rather than a second shop: waterskin, copper canteen, iron flask, backpack, cutting board, cooking pot, sleeping bag, and at wayside shelters bread, torches, water bowls and a waterskin. Stock is finite and shared and refills with active play like every supplier line; a line is left out while its mod is not installed (`requires_mod`); `outlets` decides whether a line is sold in a city or at a wayside desk. Both wayside shelter designs now include a small **provisions desk** (an Operations Terminal that sells that city's wayside lines, shows no work orders and does not count as a visit to the city). A wayside loaf costs more than the starter shop's (7 credits against 5), which a Python test checks; the other prices are first guesses. Tests: `SurvivalSupplyGameTests` (4), `tests/test_survival_supply.py`, mutation-checked (six deliberate breakages of the desk logic, all caught).

**Honest limits:** prices are first guesses, with no playtest of the economy; there is **no medical or bandage item** (no mod chosen); furnishing is not selected; Q Shop's own shop screens are separate from the exchange and always spend the personal wallet; shelters built before the desk was added keep their old layout until undone and rebuilt.

## 5. Combined integration run

See [testing.md](../technical/testing.md#the-combined-journey) for the step table and findings. Summary: **a real dedicated server on a world generated from seed 5908 by Lost Cities**, the world, firearms, vehicles, boats, vintner, farming and survival sets (and Q Shop) on the server and on both real clients, two clients pressing the real Crew screen's buttons, a Crew-funded purchase stopped after its prepared record, the server JVM halted without a stop sequence, a restart, recovery, a purchase delivered at login, backpack, canteen, gun, meal and thirst read back from the real player files, a replay refused, and three more exchange purchases (split between the bank and a wallet, wallet only, unaffordable and refused). **Passed (both phases, zero server first-start retries, one login attempt per client).** Every credit accounted for (wallets, bank, lease, interrupted purchase, exchange purchases = 400). A reobfuscated-jar production boot with every set at once also passed (`run_prod_boot_check.py --sets world firearms vehicles boats vintner farming survival`, `RESULT: pass`).

**What this does not show:** the survival half is driven by the server through the functions the packets call, not by clicks; no driving, firearm use, boat, or two people on the same chunk at once; the clients are offline test profiles; a few minutes of play, not a session.

**Two findings about third-party mods** (details and caveats in testing.md and the [mod audit](../technical/mod-audit.md)): the Lost Cities world-tick task can hold the server thread for more than a minute after bulk chunk generation (a 75.8-second tick was logged in the passing run, with no client connected; the journey server runs with `max-tick-time=-1` because of it; **a production server that pregenerates a Lost Cities area could be killed by its own watchdog**); and Traveler's Backpack 9.1.57 can crash the first start of a server while loading its newly created config (one start in eight; start again).

## 6. UI review (real screenshots, three resolutions)

Frames are produced by the real client under Xvfb with software OpenGL (`LIBGL_ALWAYS_SOFTWARE=1`) at **1280x720 (GUI scale 2), 1920x1080 (GUI scale 3) and 3440x1440 (GUI scale 2)**, 57 states each, and listed in `content/qa/gui-coverage.json` (checked by `tests/test_underground_content.py`). I viewed the Crew and terminal frames; what was checked and changed:

* **Money page** (`crew-money`, `-shared`, `-shared-nospend`, `-consent`): states in plain words which mode is in force, what pays first ("the bank pays first and you pay the rest"), that **Q Shop's own shops never use the bank**, that a member who may not spend pays themselves ("the bank is closed to you"), and what is only proposed ("Not in force yet: it needs every member's agreement"). The consent banner takes three lines, not two (it was truncated).
* **History** now has a `PURCHASE` line in amber that names the goods and how much the bank paid ("Tobias bought 1 x Waterskin, bank paid 32").
* **Rules, money mode** (`rules-mode`, `rules-mode-active`): the three mode cards carry their own Choose buttons inside the card (they straddled the card borders in the first capture); the active mode says "in force" and repeats the Q Shop limit.
* **Home** (`crew-home`): ownership is shown with a CREW or YOU badge per home, private containers are counted, leasing offers *Crew bank*, *Crew (mine)* and *For me* separately.
* **Errors** reach the player as a line at the bottom of the screen in plain words ("The bank only holds 320 credits.", "This order costs 80 credits. You have 28 in your wallet.", "The Crew bank is not available to you: you are not allowed to spend Crew money.").
* **Wayside desk** (`exchange-wayside-desk`): "WAYSIDE PROVISIONS", the location "Wayside desk", no orders; the subtitle no longer runs past the panel edge (found in this pass and fixed). **The frame uses stand-in item icons** (a potion and leather for the water bowl and the waterskin) because the capture harness does not load the optional survival mods; the real icons have not been inspected.
* The product name "Q Shop" is never split across two lines any more ("Q" alone at the end of a line looked like a typo, found in the no-spend frame).

**Not reviewed by a person:** every frame was checked by me, not by the owner; readability on a real monitor, controller or small window; colour-blind palettes; the survival mods' own screens.

## 7. Verification (October 18, cloud container, final code)

| Check | Result |
| --- | --- |
| `gradle -p rpg-core build` | passes; reobfuscated jar `underground_city-0.1.0.jar` |
| GameTests, Core only (real Q Shop 1.8.4) | **139 of 139 required tests passed** (103 before this work; +36: 13 purchase, 10 guard, 2 identity, 7 survival mechanics, 4 survival supply) |
| GameTests with the `firearms` set (TaCZ, TaCZ Blueprints) | 139 of 139 |
| GameTests with the `survival` set | 139 of 139 |
| GameTests with the `vehicles` set | 139 of 139 |
| GameTests with `vintner,farming` | 139 of 139 |
| GameTests with **every set at once** (`world,firearms,vehicles,boats,vintner,farming,survival`) | 139 of 139 |
| Python suite (`python -m unittest discover -s tests`) | 158 tests, OK |
| `validate_repo.py`; `generate_regional_content.py --check` (133 files); `generate_district.py --check`; `generate_survival_supply.py --check` (21 files); `plan_triangle_layout.py --check`; `validate_underground_balance.py`; `audit_item_sources.py` (0 findings) | all pass |
| Combined journey (generated Lost Cities world, all seven sets, two real clients, abrupt kill, restart) | passed, both phases (section 5) |
| `run_prod_boot_check.py --sets world firearms vehicles boats vintner farming survival` (reobfuscated jar, real server) | `RESULT: pass`, `boat_cargo = ok` |
| `run_auth_check.py` | online-mode=true server answers a login with an Encryption Request; online-mode=false does not (`RESULT: pass`, re-run on the final code; the check is also in the hosted `production-boot` job) |
| UI captures | 57 states at 1280x720 (GUI 2), 1920x1080 (GUI 3) and 3440x1440 (GUI 2), final code; Crew, terminal and wayside frames viewed |

**How to read the GameTest rows.** A test that needs an optional mod ends at once as a pass when that mod is not loaded (the same convention as the TaCZ tests), so the Core-only run does not execute the survival or firearm tests; the `survival`, `firearms` and every-set runs do. The mutation checks above were run in configurations in which the tests execute. A GameTest is a headless dedicated test server: it is not a client, not two players and not gameplay quality.

Hosted CI results for the head commit are in the pull request (the new jobs: `journey-combined` in `connected-clients.yml`; the all-sets boot and the authentication check in the `production-boot` job of `dev-pack-smoke.yml`).

## 8. Remaining gaps

* **Human play** of anything above, a real Microsoft account joining the authenticated server, and the normal-launcher client with the pinned jars.
* No UUID migration from offline to authenticated identities (deliberate: nothing converts silently).
* Home guard gaps listed in section 3; Crew vehicles; rent and eviction.
* No medical item; furnishing; prices unbalanced; shelters built before the desk need a rebuild.
* Lost Cities world-tick stall (production pregeneration risk) and the Traveler's Backpack first-start race: reported nowhere upstream yet.
* Concurrency: only the serial server thread and crash interleavings were tested.
* The survival half of the combined journey is not clicked by a client.
