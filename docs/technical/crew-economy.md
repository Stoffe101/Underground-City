# Crew accounts: who owns what, how money moves, and what has been proven

Status: **implemented in Core; tested by GameTests with the real Q Shop 1.8.4 wallet and by a two-client journey in which the dedicated server process is killed without a stop sequence and restarted (October 18); not yet driven by human players.** The decisions are D-081 to D-084 and, for purchases paid from the bank, **D-089** (which replaced the first design: see section 4b) in [decisions.md](../production/decisions.md). The product design is [crews.md](../design/crews.md). Nothing here is a second currency.

## 1. Authority

| Concern | Owner | Why |
| --- | --- | --- |
| Currency (a player's credits) | **Q Shop 1.8.4**, unchanged (D-037, D-039) | One wallet per player, already journalled and recovered by the work-order ledger |
| Crew membership, roles, invitations, the Crew bank, its history, home ownership, in-flight transfers | **Core's `CrewLedger`**, one file: `data/underground_city_crews.dat` | A membership change, a bank movement and an ownership change must never be able to disagree after a crash, so they share one atomic, fsynced write |
| Who may do what inside a home | `ResidenceGuard` reading the ledger | Decisions are server-side only; the client's view is never trusted |
| Career, XP, skills, heat, trust | unchanged, **personal** (`CitizenState`, `WorkOrderLedger`) | Crew progression is separate from career progression |

The Crew bank is a count of Q Shop credits that have **left a wallet**. Every operation conserves the sum of all wallets, all banks and all owed payouts (a lease or a purchase is a sink, named as such). It is not redeemable anywhere except into a wallet through the protocol below.

## 2. What the existing group systems offer, and why none is the authority

Research was done by opening the exact 1.20.1 Forge jars (file names and SHA-1 in [mod-audit.md](mod-audit.md#october-18-candidate-review-crews-parties-and-shared-money); downloaded to a scratch folder, nothing committed) and reading their public API.

**Q Shop 1.8.4** (`com.qshop.api`): `QShopAddonApi.currency()` exposes `getBalance`, `deposit`, `withdraw` and `set` for a player (online, or by UUID for an offline one through the saved player file), plus `buy`, `sell` and `barter` against a shop. A wallet is a per-player Forge capability (`IWallet`, stored in player data). There is **no account, team, group or shared-balance concept** in the API, in the packages (`api`, `wallet`, `currency`, `shop`, `trade`, `net`, `ftb`, `kubejs`) or in `CurrencyChangedEvent`. A Crew account therefore has to be a Core ledger of credits held in escrow, which is what the task allowed for. Consequence, stated honestly: Q Shop's own shop screens always spend the player's own wallet, so fully shared finances cannot route those purchases.

**Open Parties and Claims 0.33.0** (Forge 1.20.1, LGPL-3.0, updated 2026-10-17, requires nothing; Create is an *optional* compat hook only, so it does not violate D-080): a good server API (`OpenPACServerAPI.getPartyManager()`: create party, invite, add and remove members, ranks) and chunk claims with protection override hooks. Not adopted as the membership authority because (a) it has four fixed ranks (member, claimer, moderator, admin) and no permission matrix, finance mode or home ownership, so all of the Crew rules would live in Core anyway; (b) parties can change **outside** Core's flow (its own commands, its own screens, and an expiry setting, `partyExpirationTime`, that deletes inactive parties), which would orphan a bank or a home with no atomic way to settle both stores; (c) a Crew has to be creatable and testable without a second mod. What it does well is documented as the integration route: OPAC lets another mod register its own party system (`OPACServerAddonRegisterEvent` then `IPlayerPartySystemRegisterAPI.register(id, system)`, with `primaryPartySystem` selecting it), so a later bridge can make **Core's Crew the one party provider that OPAC reads**, giving shared chunk claims without a second authority. That bridge is not built.

**FTB Teams / FTB Chunks** (CurseForge only, not on Modrinth; needs FTB Library and Architectury): the standard team provider for the FTB mods and the one Q Shop's optional FTB Quests integration would talk to. The jar could not be fetched and inspected from the cloud container (CurseForge-only, no pinned hash; the repository could not be read), so its licence and API are **unverified** and it is not a candidate until they are.

**Lightman's Currency 2.3.0.6** (Apache-2.0): a complete second economy (its own coin items, wallets and traders) with a team model and team bank accounts (`TeamAPI`, `BankAPI`, `TeamBankReference`, deposit/withdraw notifications). Used as a **design reference only** (named bank access levels, a transfer log): installing it would create a second authoritative currency, which D-037 forbids.

## 3. The ledger

One NBT file, schema 1, written to a temporary file, fsynced, atomically moved over the old one, and the directory fsynced (the same discipline as the work-order journal). A failed write marks the ledger **faulted** and every later operation refuses until a restart, so the server never carries on after an uncertain write. An unreadable or newer-schema file fails loudly (the Crew screen says why; solo play and every other system continue). A world that has never had a Crew writes no file.

Contents: crews (id, name, founder, members with role and last seen, the permission table for managers and members, finance mode, consents, bank, the last 40 history lines), open invitations, owed payouts, residences (region, price, owner, tenants' private containers, retrieval guests), spent starter discounts, the last 256 settled transaction ids, and at most one in-flight transaction per player.

## 4. The money protocol (wallet first, ledger second)

A deposit, a withdrawal, a payout claim and a wallet-paid lease all follow the same five steps, on the server thread:

1. Validate everything (membership, permission, mode, amount 1..1,000,000, ownership, free funds) and read the wallet.
2. `begin*`: write the **prepare record** (transaction id, kind, amount, wallet `before` and `after`). A withdrawal and a claim also take their credits out of the bank or the owed balance at once, as a **hold**; a deposit and a lease change nothing yet but reserve the unit.
3. Set the wallet to the recorded absolute `after` value.
4. Save the player file and **read it back** (`PlayerStore.saveVerified`; vanilla only logs a failed player save).
5. `settle`: the ledger side (a deposit reaches the bank, a lease becomes ownership) and the removal of the prepare record are **one atomic file write**.

If step 3 or 4 fails with an exception, the service sets the wallet back to `before`, cancels the ledger side, and tells the player nothing was lost; if even that fails the player is disconnected for recovery. If `settle` itself cannot write, the ledger is faulted and the server must restart.

**Recovery** (at server start, before any player can join, and again at login) compares the wallet with the recorded values and **only ever reads a wallet** (the provider's own offline read, never a write): equal to `after` settles; equal to `before` cancels (a withdrawal's hold returns to the bank, a reservation is released); anything else is left for an operator, logged, and blocks only that player. `/uc_crew pending` lists them and `/uc_crew resolve <uuid> true|false` (permission 3) settles or cancels one after the operator has looked at the wallet.

Replay protection: every request carries a client-generated transaction id; the ledger remembers the last 256 settled ids and the id of anything in flight, so a replayed packet is refused (also after a restart). Concurrency: all of this runs on the server thread, so two players are serialised; the balance check happens at execution, so two withdrawals that together exceed the bank cannot both succeed.

## 4b. Purchases paid from the bank (D-089): one decision, never through the wallet

**What went wrong first (found in review, fixed in the same PR).** The first implementation of "exchange purchases are paid from the bank first" moved the bank's share into the buyer's wallet (`topUpForPurchase`) *before* the purchase was known to be possible, and then hoped to refund. With a bank of 20, a wallet of 0 and a price of 100 in fully shared mode the member ended with **20 credits of the Crew's money in their wallet and no goods**, and anyone who could submit a purchase could repeat it for as long as the bank had credits (a refusal for stock or space had the same shape). A best-effort refund is not crash-safe either: a crash between the credit and the refund strands the credits in the wallet. The pull-request report ([pr23-playtest-readiness.md](../production/pr23-playtest-readiness.md)) has the cause, the fix and the tests.

**The protocol that replaced it.** The bank's credits **never enter a wallet** for a purchase. The money moves in four durable steps on the server thread, and the *prepared purchase record* of the work-order ledger is the single commit point:

1. **Plan (read-only).** `CrewLedger.purchaseShare` says how much of the price the bank may pay: nothing unless the Crew is in effective fully shared mode, the buyer holds `SPEND`, the bank is not empty and nothing of the Crew's is still being recovered; otherwise `min(bank, price)`. The market then checks **before anything moves** that the buyer's own wallet covers the rest, that the stock exists and that the inventory has room. A purchase that cannot succeed has taken nothing from anybody.
2. **Reserve.** `reservePurchase` takes the bank's share out of the bank and keeps it as a durable **hold** (`transaction id`, buyer, Crew, amount, "3 x Waterskin") in the Crew ledger file: one atomic write. The credits stay in the ledger; no wallet is touched.
3. **Prepare.** The work-order ledger's prepared purchase record is written with the *absolute* inventory and wallet targets of the purchase and **names the hold** (`crew_tx`, `crew_amount`). That one atomic write is the decision: from this moment the purchase will be completed, by recovery if the process dies.
4. **Apply, commit, settle.** Recovery (the same code path as the normal one) sets the inventory and the wallet to the recorded targets, calls `commitPurchase` (the hold becomes a `PURCHASE` line in the Crew's history and its transaction id is remembered) **before** the prepared record is settled and removed. Every step is idempotent.

**An undecided hold is released, never guessed.** At server start, at login and at the start of every purchase `CrewService.resolveHolds` looks at each hold: if the work-order ledger holds a prepared record that names it, the purchase was decided and recovery completes it; if not, the purchase never reached its commit point and the hold goes back to the bank in one atomic write. Nothing is refunded "because an exception was thrown": an exception after the prepare record is ambiguous, so the code asks the ledger what was decided. A Crew with an unresolved hold cannot be disbanded, left, paid out or switched to another finance mode (the reason is shown to the player), and the hold is listed by `/uc_crew pending`.

**Who may spend.** Spending the bank needs the member's **Spend** right (a manager has it by default, a member does not) *and* fully shared finances in force. Before D-089 a member without it could still spend the bank through purchases; that was a gap between the rule on the Rules page and the code, and it is closed (this tightens D-084's table, which already listed Spend as a right).

**Q Shop's own shop screens never use the bank.** Q Shop 1.8.4 has no payer concept, so a purchase in one of *its* shops always spends the personal wallet. The bank pays only for the Underground City exchange (the Operations Terminal, the wayside provisions desks). The Crew screen says this next to the finance mode and in the purchase history.

**Test seams.** `CrewService.checkpoint(stage)` is called at every boundary after the plan (`purchase_after_reserve`, `purchase_after_prepare`, `purchase_after_apply`, `purchase_after_commit`), `crashAt` makes one of them throw a `SimulatedCrash` that nothing in production catches, and `faultAt` makes one throw an ordinary exception. They are inert unless a test sets them.

## 5. Finance modes

| Mode | What it is | Built and tested |
| --- | --- | --- |
| Separate money | no standing bank; whoever makes a Crew purchase pays it; selectable only while the bank is empty | yes |
| Personal money plus a Crew bank (default) | deposits by anyone, withdrawals and spending by permission, a history | yes |
| Fully shared finances | the pay of **work orders** is swept into the bank and **exchange purchases** are paid from it first, only while **every** member has consented **and only for a member who holds the Spend right** (D-089); one member withdrawing consent switches it off; a newcomer must consent; **Q Shop's own shop screens still use the personal wallet** | yes, with the real wallet |

Changing a mode never moves a credit. Leaving a Crew never moves a credit either: the leaver keeps their wallet; credits they put in the bank belong to the Crew (the Rules page and the joining message say so). A disband or an explicit pay-out splits the bank equally (the founder takes the remainder) into **owed payouts** that each member claims into their wallet (automatically at login), so an offline member is never skipped and never paid twice.

## 6. Proven, and not

| Claim | Evidence | Not proven |
| --- | --- | --- |
| A deposit or withdrawal conserves credits; refusals move nothing | GameTests with the real Q Shop wallet and an in-memory wallet | a human doing it |
| A crash at any of the three points of a transfer (after the prepare record, after the wallet, after the saved file), with the wallet change surviving or lost, recovers to exactly "done" or "never happened", and recovery is idempotent | 8 crash cases per run, deposit and withdrawal, plus a lease | a power cut or a `kill -9` of a real process (the crash is simulated by an `Error` that nothing catches, then a reload from disk) |
| 1,200 seeded random operations (deposits, withdrawals, claims, pay-outs, Crews founded and disbanded) over three seeds, each seed forced to include simulated crashes and restarts, never change the total of wallets, banks and owed payouts | `randomisedOperationsConserveEveryCredit` | long-run behaviour |
| A replayed transaction id is refused, also after a restart | GameTest | |
| Two members cannot together overdraw the bank | GameTest | simultaneous clients (the server serialises) |
| Real Q Shop wallet, offline read, entity save and load | GameTest `realQShopTransfers`; **and the journey:** the restarted dedicated server read both unresolved transfers from the ledger file and the saved player files before any player logged in, settled the deposit whose wallet change had been saved and cancelled the withdrawal that had not touched the wallet | a hosted run of the journey (it ran in the cloud container; the hosted job is added) |
| Shared finances route pay and purchases and stop when consent is withdrawn | GameTest with the real wallet and real work orders and the real exchange | |
| **A refused or failed Crew-funded purchase leaves the bank's credits in the bank** (the 20 / 0 / 100 case of the original bug, insufficient combined funds, no Spend right, out of stock, full inventory) | `CrewPurchaseGameTests`: `aRefusedSharedPurchaseLeavesTheBanksCreditsInTheBank` (written first and shown to fail on the old code with "left the member with 20.0 credits of the Crew's money"), `insufficientCombinedFundsRefuseAndChangeNothing`, `withoutTheRightToSpendTheBankIsNeverUsed`, `insufficientStockAndAFullInventoryRefuseBeforeAnyCreditMoves` | |
| **An unauthorised player cannot extract Crew funds by submitting failing purchases** | `failingPurchasesOnPurposeExtractNothingFromTheCrew` (70 deliberately failing attempts of seven kinds - more than the funds, quantity zero, more than one order allows, a line the exchange does not stock, more than the shared stock holds, a failure after the hold was taken, a full inventory - each for a member without and a member with the Spend right; after **every** attempt the bank, both wallets, the stock and the holds equal their starting values) | |
| Partial funding (bank then wallet), full funding by the Crew, a duplicated request, an exception at each step, a crash at every boundary (the player file written or lost, both ledgers re-read from disk, recovery run twice), two members spending the same bank, 220 random purchases with random failures and process deaths | `theBankPaysFirstAndTheBuyersOwnWalletPaysTheRest`, `aPurchaseCanBePaidEntirelyByTheCrew`, `aDuplicatedRequestChargesAndDeliversOnce`, `anExceptionAtEachStepEndsWithTheWholePurchaseOrNone`, `aCrashAtAnyBoundaryOfACrewFundedPurchaseLosesAndDuplicatesNothing`, `twoMembersSpendingTheSameBankNeverSpendOneCreditTwice`, `randomisedCrewPurchasesConserveEveryCredit`, `aCrewCannotBeDisbandedOrLeftWhileAPurchaseHoldIsUnresolved` | simultaneous clients on separate threads (the server thread serialises requests; the tests interleave two members' requests and crashes) |
| The tests above catch the bugs they claim to catch | nine deliberate mutations of the purchase code (buyer's wallet not checked, holds never released, holds released although a prepared record names them, commit never called, hold not leaving the bank, released hold not returned, disband ignoring a hold, share not limited to the price, Spend right ignored) were each applied, run, and **caught by one or more named tests**; the file is restored after each | a mutation that nobody thought of |
| Two real clients, a dedicated server killed without a stop sequence **with a Crew-funded purchase stopped right after its prepared record**, restarted: the hold is committed at login, the goods arrive once, the bank is right, the shared stock decrement survived, a replay is refused | the combined journey (`scripts/run_cloud_journey.py` with `UC_JOURNEY_SURVIVAL=true`; see [testing.md](testing.md#the-combined-journey)) | a human doing it; a power cut or an external `kill -9` |
| Two real clients found a Crew, invite, accept, deposit, lease a home from the bank, switch a permission, withdraw, make a container private, then the server process is halted without a stop sequence **with one transfer already in the wallet and one stopped after its prepare record**, restarted, and both clients reconnect | `CrewJourneyServer` and `CrewJourneyActions` (scenario `crew` of `scripts/run_cloud_journey.py`): every action is a press of a button of the real Crew screen and a key event for the text boxes; both clients' own screens after the restart showed the same Crew, roles, bank (45), wallets, the Row House as a Crew home, the member's private container and the history including the recovered deposit; replayed transaction ids of both interrupted transfers were refused after the restart; a further deposit and withdrawal after the restart conserved every credit | a person; a process kill from outside (the journey halts the JVM from inside with `Runtime.halt`); a power cut; Lost Cities terrain (the journey world is flat) |

## 7. Operator notes

Back up the whole world, including `data/underground_city_crews.dat`, before an upgrade. Do not delete the file while Crews exist: bank balances live in it. A Crew with an unresolved transfer keeps working for everyone else. Removing Q Shop from a world with Crews leaves the bank intact and blocks transfers until it is back.
