# Crew accounts: who owns what, how money moves, and what has been proven

Status: **implemented in Core; tested by GameTests with the real Q Shop 1.8.4 wallet and by a two-client journey in which the dedicated server process is killed without a stop sequence and restarted (October 18); not yet driven by human players.** The decisions are D-081 to D-084 in [decisions.md](../production/decisions.md). The product design is [crews.md](../design/crews.md). Nothing here is a second currency.

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

## 5. Finance modes

| Mode | What it is | Built and tested |
| --- | --- | --- |
| Separate money | no standing bank; whoever makes a Crew purchase pays it; selectable only while the bank is empty | yes |
| Personal money plus a Crew bank (default) | deposits by anyone, withdrawals and spending by permission, a history | yes |
| Fully shared finances | the pay of **work orders** is swept into the bank and **exchange purchases** are paid from it first, only while **every** member has consented; one member withdrawing consent switches it off; a newcomer must consent; Q Shop's own shop screens still use the personal wallet | yes, with the real wallet |

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
| Two real clients found a Crew, invite, accept, deposit, lease a home from the bank, switch a permission, withdraw, make a container private, then the server process is halted without a stop sequence **with one transfer already in the wallet and one stopped after its prepare record**, restarted, and both clients reconnect | `CrewJourneyServer` and `CrewJourneyActions` (scenario `crew` of `scripts/run_cloud_journey.py`): every action is a press of a button of the real Crew screen and a key event for the text boxes; both clients' own screens after the restart showed the same Crew, roles, bank (45), wallets, the Row House as a Crew home, the member's private container and the history including the recovered deposit; replayed transaction ids of both interrupted transfers were refused after the restart; a further deposit and withdrawal after the restart conserved every credit | a person; a process kill from outside (the journey halts the JVM from inside with `Runtime.halt`); a power cut; Lost Cities terrain (the journey world is flat) |

## 7. Operator notes

Back up the whole world, including `data/underground_city_crews.dat`, before an upgrade. Do not delete the file while Crews exist: bank balances live in it. A Crew with an unresolved transfer keeps working for everyone else. Removing Q Shop from a world with Crews leaves the bank intact and blocks transfers until it is back.
