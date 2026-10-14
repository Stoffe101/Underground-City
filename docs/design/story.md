# Narrative, co-op story, and settlement influence

## Premise (working concept)

A previously connected region has fractured into rival city-states after a political/economic collapse. Supply problems, secrets and opportunistic power struggles gradually deepen the conflict. The player arrives as an ordinary citizen with a new career, not as a preselected savior.

This is original worldbuilding inspiration and **not final canon**.

## Structure

- **Act 0: Arrival.** Choose citizenship/career, meet key NPCs, find a home, earn first wages, learn basic city rules.
- **Act 1: Community.** Solve local shortages, develop a livelihood, help public works, learn faction personalities.
- **Act 2: Fractures.** Trade routes become unsafe; police investigate smuggling; leaders request favors; discover contradictory accounts.
- **Act 3: Choices.** Supporting a faction, investigating corruption, building alliances or a neutral trade position produces different outcomes.
- **Act 4: Restoration.** Optionally reclaim and grow a ruined settlement through material, economic and political work, Tale of Kingdoms-style.

## Quests

Quests are data-driven finite-state machines: available → accepted → in_progress → ready_to_turn_in → completed/failed. They carry stable IDs, prerequisites, objectives, side effects, co-op scope, and save migrations. Reward grants and world mutations must be idempotent.

Avoid requiring a very specific profession for mandatory story progress; use alternative dialogue, a purchasable service, hired NPC, or another mission route. Add optional career-specific investigation branches.

## Co-op decisions

Some choices are personal (career, illicit reputation), some shared (city construction project, faction alliance, chapter). Shared votes/confirmations must handle one player offline and disagreement without deleting another's progress.

## World feedback

A finished public project changes a physical building/route/service and the lines spoken by residents. Economic conditions affect trade contracts. Controlled conflict changes patrols/checkpoints, not unbounded destruction.

## Lore production rules

Write named residents, factions, timelines, and quest chains *after* map and core-city layouts are selected, to avoid narratives that depend on nonexistent locations.
