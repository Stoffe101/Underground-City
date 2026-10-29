# Economy telemetry (twelfth pass)

**Status:** implemented; L2 (`EconomyTelemetryGameTests`); L1 (`tests/test_economy_report.py`). Read from scripted real-client runs of this pass; no person has played with it. The current branch and its recent gameplay systems have not received human playtesting.

The economy model ([economy-model.md](economy-model.md)) and the career balance ([career-balance.md](career-balance.md)) rest on assumptions about time: that half of an active hour is hands-on work, how many minutes an order or a service call takes, how long a trip between cities is. Telemetry records what happened in a world so a playtest can replace them.

## What is written

`EconomyTelemetry` appends one JSON line per event to `underground_city_telemetry.jsonl` in the **world folder** (a server's world, or a single-player save). At 4 MB the file is rolled to `.1`. Nothing is sent anywhere.

| Field | Meaning |
| --- | --- |
| `t` | the shared active clock in ticks (`LockService.clock`; it runs only while somebody is online) |
| `wall` | epoch seconds |
| `p` | a **pseudonym**: the first ten hex digits of SHA-256 over the world's seed and the player's id; never the name, never the id |
| `career`, `level`, `wallet` | the player's career, career level and Q Shop balance after the event |
| `kind` | what happened (below) |

| Kind | Written when | Fields |
| --- | --- | --- |
| `login`, `logout` | a player joins or leaves | |
| `city` | a player enters a city or leaves into open country (read every 5 seconds) | `city` (empty for open country), `from` |
| `accepted` | a work order is accepted | `order` |
| `order` | a work order is delivered (after its settlement) | `order`, `legality`, `credits` (paid), `base` (the order's credits), `xp`, `minutes` (active minutes since it was accepted in this run of the server, or -1), `buyer`, `level_up` |
| `service` | a Technician's or Smith's service call is paid | `what`, `credits`, `xp`, `parts` |
| `purchase` | goods are bought at an exchange | `good`, `units`, `credits`, `bank` (the Crew bank's share), `city` |
| `supply` | goods are sold to a shop | `good`, `units`, `credits`, `own_trade` |
| `fence` | stolen goods are sold to a fence | `credits`, `city`, `home_city` |
| `encounter` | a road encounter or a letter is settled | `encounter`, `credits` |
| `business` | a business's takings are collected | `credits` |
| `arrest` | an arrest (an officer or a checkpoint on a warrant) | `city`, `fine` (paid), `asked`, `stacks` (taken), `post` |
| `search` | a checkpoint search that found something | `city`, `fine`, `stacks`, `post` |

Every line is written **after** its settlement and never takes part in one: a failure to write is logged once and ignored. Off with the JVM argument `-Duc.economyTelemetry=false`.

Not recorded yet: rent and deposits (the billing clock's receipts; the wallet column shows their effect), a business's individual sales and their tax (the takings are), Crew bank movements, vehicle purchases through Immersive Vehicles' own shops.

## The report

```
python scripts/economy_report.py <world folder or .jsonl> [...] [--out report.md]
```

It prints, per pseudonym, the active minutes, career and levels, credits earned by source and spent on goods and fines, the net and **net credits per active hour**; per order, the active minutes from accepting to delivering it against the model's hands-on minutes for it (**the model's minutes ÷ the observed**: the economy model assumes 0.5); the trips between cities; and the police: arrests, searches, fines asked and paid, stacks taken.

Reading it: accept-to-deliver time includes travel and waiting, so it is an upper bound on hands-on time; a short session says little. The report holds pseudonyms only, but a world's file is still the owner's: share a report, not the world.

## Rights and privacy

The file holds no names, ids, chat, inventories or positions beyond the city a player is in. It stays in the world folder; `uc-logs.ps1` does not pack it (it packs logs, never a world).
