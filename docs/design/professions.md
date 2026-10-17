# Careers, specializations, contracts, and research

## Shared career rules

- Five target starting careers: **Farmer**, **Vintner** (the old Farmer/Vintner split in two, D-067), **Smith/Engineer**, **Gunsmith/Arms Dealer**, **Hacker/Technician**.
- Each career has independent XP, level and branching progression. A working target is 30 levels, subject to tuning.
- Skill-point nodes unlock *capabilities*: recipes, tools, manufacturing, contract types, support workers, efficiency, and business upgrades. Prefer real verbs to '+2% generic output' padding.
- Profession gates may require XP level, skill nodes, blueprint/research, physical workstation, license/contact, and resources. Show unmet requirements in UI.
- Progress through completed orders, real production value, difficult jobs and discoveries. Prevent mass low-cost craft/recycle leveling.
- Player can pursue secondary skills/careers eventually; define respec and career-change rules before implementation.
- NPC suppliers/buyers provide unavailable profession goods. No two-player softlocks.
- Legal products and underground activity are distinct; having gunsmith skills does not make a character a criminal.

## Career branches (initial design)

| Career | Branch A | Branch B | Branch C |
| --- | --- | --- | --- |
| Farmer | Cultivation, soil, greenhouses, seed quality | Kitchen and estate supply | Wholesale deals, hiring NPC farmhands, logistics |
| Vintner | Vines, pressing, fermentation, cellars | Licensed distilling | Estate and tavern trade, bottling, logistics |
| Smith/Engineer | Metallurgy and armor | Machining and tool quality | Automation, workshops, industrial networks |
| Gunsmith/Arms Dealer | Weapon design/repair | Black-market contacts and delivery | Production logistics and licensed supply |
| Hacker/Technician | Programming and automation | Defensive security/forensics | Fictional network intrusion missions |

Hacking should take place in **fictional in-game terminal puzzles and server-defined systems**; no reliance on actual network intrusion tools or external accounts.

## Blueprint lifecycle

Discover/earn/buy blueprint → research/analyze → meet skill gate → acquire components → use workstation → manufacture → use/sell. Blueprints can be discovered before they become usable. Every unlock must have obtainable inputs and at least one non-random progression path for essential plot items.

## Co-op and fairness

Players have personal career XP and unlocks. Most economic goods are tradeable; critical story knowledge unlocks per quest team if earned collaboratively. A civilian character must have interesting late-game content without entering criminal gameplay.

## First slice

Farmer + Gunsmith through five levels and meaningful early research; defer the two other careers and full tree until UX, duplication, and recipe reachability are tested.

## Implemented skill trees (October 15, 2026)

Skill trees for all four careers are live as Core datapack data with a legal path and an underground path on the
same character (D-049, D-050). See [legal and underground careers](legal-and-underground-careers.md) for the nodes,
the contracts they unlock and the balance model. The research/blueprint lifecycle above is implemented for
firearms only as contract rewards of TaCZ Blueprints items (when that mod is installed); a Core-owned research bench
is not built.
