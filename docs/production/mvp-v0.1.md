# Prototype 0.1 — First Citizenship

**Goal:** prove that two players can enjoy a 60–90-minute vertical slice of civilian life, progression, exploration, and a small story without having to farm diamonds or rely on extra human players.

## Proposed scope, not already implemented

- One urban district, one small farm/outskirts, one resource site, and a safe route between them.
- Eight to twelve **named** NPCs: registrar, mayor/delegate, two employers/buyers, trader, guard, researcher, courier, and optional narrative contacts. Background people are future work.
- Two complete **early** careers: Farmer/Vintner and Gunsmith (five early levels each). Smith/Engineer and Hacker/Technician remain designed but deferred.
- Career skill allocation plus basic trading/shooting general-skill progression.
- Initial currency and goods markets, one citizen tax cycle, NPC contracts and money ledger.
- One starter blueprint chain, three illustrative weapon categories (final gun and component selection TBD), research/workstation and crafting requirements.
- Per-player rewards from a few intentionally populated Lootr containers; no abandoned world-full-of-free-cash exploits.
- Intro registration, one economic quest, one exploration or low-risk conflict quest; co-op completion tracking.
- Functional save/rejoin, respawn, and server restart.

## Player-journey acceptance

1. Fresh player joins, selects city/career, receives onboarding, does not re-trigger onboarding on reconnect.
2. Two players receive independent careers, wallets, skill levels, and personal loot.
3. Either completes a legal production/delivery contract, buys a useful item, levels a skill, and sees stock and treasury update.
4. One blueprint is researched and results in a genuinely manufacturable item (all dependencies obtainable).
5. Tax is calculated against gameplay time or eligible activity, never unattended real-world absence.
6. A named NPC remains consistent over a save/reload; a quest can be resumed; no duplicate rewards.
7. Two clean clients join a dedicated server; no critical log errors; replay after restart gives the same persistent state.
8. A player can finish the prototype without selecting the other profession or inviting more users.

## Explicitly excluded from prototype 0.1

Full three/four-city deployment, live urban crowd simulation, mass faction wars, automated cars, deep police AI, player-run city founding, full campaign, all 30 profession levels, dozens of firearms, and fully polished building interiors.

## Exit evidence

Version manifest, server/client startup logs, test screenshots, test save backup/reload results, short recorded user journeys, performance samples, known-issues list, and pass/fail table. No 'done' without actual evidence.

## Current implementation evidence

The October 14 pass supplies a starter legal produce/deliver → credits/XP → unlock → buy loop, three vanilla orders per career, one named vanilla supply clerk, and actual provider GameTests/client screenshots. This does not complete the milestone: named district residents, story quests, TaCZ research, skill trees, taxes, connected clients and surveyed terrain remain absent. See [readiness](oct19-test-readiness.md).
