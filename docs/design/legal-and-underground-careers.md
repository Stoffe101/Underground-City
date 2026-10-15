# Legal and underground career paths

Status: **implemented in Core as a data-driven vertical slice** (skill trees, contracts, city law, checkpoints,
contraband, buyer trust, heat, exceptional loot, the Sable Leaf crop, the Technician's trace, an in-terminal Skills
page). Evidence for each piece is listed in [Status](#status). Nothing here is a finished economy; balance is
arithmetic over data (`scripts/validate_underground_balance.py`) and needs real 30-60 minute co-op sessions.

Decision records: D-049 (the pillar), D-050 (Core-owned skill trees), D-051 (evidence, not omniscience), D-052
(balance model), D-053 (exceptional loot), D-054 (what the Technician's challenge is), D-055 (the district).

## The pillar

Every one of the four careers (Farmer/Vintner, Smith/Engineer, Gunsmith/Arms Dealer, Hacker/Technician) has a
**legal path** and an **underground path** that are open to the same character at the same time.

- No alignment, no faction lock, no permanent choice. A Farmer can learn Restricted Cultivation and still sell
  wheat to the bakery; nothing is ever re-labelled "criminal" because of a skill.
- Underground work pays more *on paper* (about 2.0x the legal margin) but the real return is lower than legal work
  for anyone who is careless, and only modestly higher for someone who prepared. It has to earn the risk:
  inspections, fines, seized goods, rising heat, buyers who stop trusting you.
- Legal work stays viable and satisfying: it rewards blueprints, seeds, prototype components and licences, not just
  credits. A licence turns a "prohibited" load into a legal delivery, which is the legitimate route to the same
  customers.
- Consequences come from **evidence** (a search at a road checkpoint, a witness at a hand-off), never from the
  server knowing what you carry. Possession alone is never detected.
- Underground contracts build on legal skills. The underground branch of every tree requires a legal node first.

## Skill trees

One skill point per level above 1, so a character at level 30 can afford any whole tree. Nodes are capabilities
(contract access, licences, a tool) with only three numeric effects, all capped: an inspection modifier (at most
-0.25 per node, -0.35 in total), an exchange discount (at most 10%), and licences.

| Career | Fundamentals | Legal | Utility | Underground |
| --- | --- | --- | --- | --- |
| Farmer | Field Fundamentals | Greenhouse Management, Cultivar Broker, Estate Wholesale, Controlled-Crop Licence | Field Logistics (-5% exchange) | Restricted Cultivation, Concealed Stores (-0.12 search), Underground Trade, Grey Market Contacts |
| Smith | Forge Fundamentals | Structural Works, Armoury Contracts, Industrial Licence | Precision Machining (-5% exchange) | Unlisted Fabrication, Unstamped Production (-0.15 search), Contraband Armoury |
| Gunsmith | Bench Fundamentals | Licensed Repairs, Arms Dealer Licence, Security Contracts | Ballistics Lab (-5% exchange) | Unregistered Builds, Filed Serials (-0.10 search), Arms Brokerage |
| Technician | Bench Electronics | Municipal Repairs, Automation Installer, Security Systems | Signal Analysis (-5% exchange) | Bypass Tooling, Signal Scrubbing (-0.10 search), Data Brokerage |

The trees are `data/underground_city/skill_trees/<career>.json`; `SkillRules` validates them (acyclic, level and
cost bounds, effect caps, every underground node reachable only through a legal node).

## Contracts

All contracts live in `content/gameplay/contracts.json` and are generated into vanilla datapack resources.
Accepted terms are snapshotted (an update never changes an order you already accepted); employer demand is shared
and replenishes with active play; deliveries are settled through the fsynced `WorkOrderLedger`.

| Contract | Path | Needs | Buyer (city) | Credits | Also pays |
| --- | --- | --- | --- | --- | --- |
| Greenhouse wholesale | Farmer, legal | Greenhouse Management | town exchange | 100 | |
| Broker heirloom seed stock | Farmer, legal | Cultivar Broker | | 150 | Heirloom Seed Packet |
| Licensed controlled-crop supply | Farmer, legal | Controlled-Crop Licence | Blackwater dispensary | 200 | |
| Quiet bale delivery | Farmer, underground | Underground Trade | Marrow (Blackwater) | 220 | buyer trust |
| Reserve load | Farmer, underground | Grey Market Contacts, trust 2 | Marrow | 440 | 2 cuttings |
| Structural steel | Smith, legal | Structural Works | | 150 | |
| Plating for the guard | Smith, legal | Armoury Contracts | Ironhaven guard | 270 | Prototype Alloy Ingot |
| Unstamped plate | Smith, underground | Unlisted Fabrication | Hollis (Valewood) | 300 | trust |
| Armoury order | Smith, underground | Contraband Armoury, trust 2 | Hollis | 600 | trust |
| Licensed sidearm | Gunsmith, legal | Licensed Repairs, **TaCZ** | Valewood co-op | 190 | |
| Hunting shotgun | Gunsmith, legal | Security Contracts, **TaCZ Blueprints** | Valewood co-op | 170 | `blueprint_shotgun` (m870) |
| Source parts for Dray | Gunsmith, underground errand | Unregistered Builds, **TaCZ Blueprints** | Dray (Blackwater) | 220 | `blueprint_smg` (uzi) |
| An unregistered SMG | Gunsmith, underground | Unregistered Builds, trust 1, **TaCZ** | Dray | 500 | trust |
| Restricted rifle | Gunsmith, underground | Arms Brokerage, trust 2, **TaCZ** | Dray | 640 | trust |
| Municipal terminals | Technician, legal | Municipal Repairs | | 160 | |
| Commercial automation | Technician, legal | Automation Installer | | 190 | Circuit Prototype |
| Recover a ledger | Technician, underground | Bypass Tooling | Vesper (Valewood) | 270 | trust |
| Ledger brokerage | Technician, underground | Data Brokerage, trust 2 | Vesper | 800 | Optics Schematic |

Contracts that name a mod (`requires_mod`) are **skipped, not errors**, when that mod is not installed, so Core
runs and passes its tests without TaCZ.

Underground buyers have a **finite demand** (shared stock that replenishes only with active play) and a per-player
**trust** level (maximum 5) that gates the bigger contracts, so a buyer cannot be farmed.

## Evidence, not omniscience (D-051)

- `data/underground_city/city_laws/<city>.json` assigns each contraband category `legal`, `permit` or
  `prohibited` and gives the inspection chances for each gate risk level.
- `data/underground_city/contraband/*.json` classifies items by **registry id and an NBT key** (for example a TaCZ
  gun by `GunId`), so a hunting rifle and an unregistered SMG are different categories. A specific entry beats a
  catch-all.
- Checkpoints (`data/underground_city/checkpoints/*.json`, generated from the road blueprint) are 48-block zones at
  the corridor gates. Entering one rolls a **deterministic seeded die** (no reroll by relogging: a 10-active-minute
  cooldown per checkpoint). A search seizes only what that city's law forbids *for this traveller*; a licence turns
  a "permit" category into a legal carry but never a "prohibited" one. One fine per category, capped at the wallet;
  personal and named items are never taken.
- Hand-offs can be witnessed: delivering underground goods can add **heat** in the buyer's city. Heat raises the
  inspection chance (5 points per step, capped at 90%) and decays with active play (one step per five minutes).
- Blackwater is lenient, Ironhaven strict, Valewood in between, so route choice matters.

| | Ironhaven | Valewood | Blackwater |
| --- | --- | --- | --- |
| Search chance at low / normal / high-risk gates | 10% / 28% / 50% | 8% / 22% / 42% | 5% / 16% / 34% |
| Fine: permit / prohibited | 30 / 80 | 25 / 60 | 20 / 40 |
| Witness chance at a hand-off | 35% | 25% | 10% |

**Known limits** (honest): the checkpoint is a *zone*, so a player on foot can walk around it (the deliberate
smuggler's trail, a longer and rougher walk). Boat routes have no customs yet. None of this is an NPC guard
behaviour: a search is a server-side event with a chat message, not a visible patrol.

## Balance model (D-052)

`scripts/validate_underground_balance.py` computes, for every non-errand underground contract, the expected return
over the real corridor between the contract's origin city and the buyer's city:
`EV = P(not searched) x credits - cost - E[fine]`, compared with the career's legal baseline `L`
(mean `credits / cost - 1` over its legal priced work). Checked rules:

| Profile | Rule |
| --- | --- |
| careless (no skills, no heat) | return <= 0.95 L: smuggling without preparation loses to legal work |
| hot (careless, heat 4) | return <= 0.60 L |
| trained (every concealment skill) | between 0.85 L and 1.80 L: a prepared smuggler earns modestly more |
| best (concealment and the matching licence) | return <= 2.0 L: nothing doubles legal income |

Current output (run `python scripts/validate_underground_balance.py`): careless 0.02-0.59 against a legal baseline
of 0.55-0.67; trained 0.59-0.80; best at most 1.08. The test suite fails if a data change breaks these rules. This
is **arithmetic over data, not a playtest**.

## Rare and exceptional loot (D-053)

- Household Safes roll an **exceptional find** exactly once, at registration, from a position-fixed seed (chances
  10-12% for apartments, offices and industrial, 35% for an illegal cache). Restocking never rolls it again, so
  breaking in repeatedly does not renew it. `clear` resets the flag, so re-registering is an operator action.
- Exceptional finds are profession-wide and contextual: they come from datapack tables
  (`exceptional_loot/*.json`) that name items from any profession (heirloom seeds, prototype alloy, circuit
  prototypes, optics schematics) and, when TaCZ is installed, **real TaCZ guns with their real NBT**.
- The loader is a custom listener because a vanilla loot table that names a missing item fails to parse, and a
  Core-only run must stay valid.
- Rare components arrive as packed items that the exchange unpacks into what it sells; they are never currency.

## The Sable Leaf crop (Farmer, fictional restricted crop)

An abstract mechanic, not a real-world drug: `RestrictedCropBlock` grows in four stages, only at light level 12 or
higher and only after the plant has been nourished with a Cultivation Nutrient (right-click). A mature plant
yields Sable Leaf Bales (2-3) or a cutting. Bone meal does not work. Planting a cutting needs the Restricted
Cultivation skill (`RestrictedCuttingItem`); the bale is the contraband item that the law categories classify.

## The Technician's trace (D-054)

An interactive challenge, not a button. A Technician with **Bypass Tooling** uses a placed Secured Data Terminal,
spending a Bypass Kit. The server picks a hidden five-symbol sequence from four pads and sends it to the client,
which flashes it (`ChallengeScreen`); the player repeats it from memory within 12 seconds. Exactly one answer is
accepted, only after the sequence has finished and before the deadline, from within 12 blocks. A correct answer
recovers an Encrypted Ledger (a terminal holds one, up to two, refilling every 45 active minutes); anything else
trips the alarm: the terminal locks down for three active minutes and the city's heat rises by one.

It is a *memory and attention* skill check, and the sequence necessarily reaches the client, so it is not
anti-cheat. Pads are numbered and use distinct colours, keys 1-4 work, and ESC counts as abandoning.

## Status

| Piece | Status |
| --- | --- |
| Skill trees, rules and persistence | implemented; pure rules and data tested (Python data tests, GameTests) |
| Underground and legal contracts with rewards, trust and requirements | implemented; GameTests on the real datapack |
| City law, contraband classification, checkpoint search, heat, no-reroll | implemented; GameTests; no real guards |
| Exceptional loot and TaCZ gun NBT preservation | implemented; TaCZ cases run only in the `-PucPack=firearms` job |
| Sable Leaf crop | implemented; GameTests |
| Technician trace (server and client screen) | implemented; GameTests for the server rules; the client screen is captured by the visual harness |
| Skills, contract and market pages in the terminal | implemented; real-client captures are reviewed in `content/qa/reviewed-runs.json` |
| Balance | arithmetic validator only; **no playtest** |
| Hand-off witnesses as visible NPCs, patrols, boat customs | planned |
| In-terminal registration screen | planned (D-019 prefers a proven class selector); `/uc register` works today |
