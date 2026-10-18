# Citizens, schedules, relationships, and simulation

## NPC classes

1. **Named/story:** mayors, employers, key residents, quest contacts, faction captains. Persistent identity, state, schedules, dialogue, reputation and meaningful reactions.
2. **Functional:** merchants, police, workers, couriers and guards. Persistent positions/work roles and stock/contract links.
3. **Ambient:** limited background citizens that create public-life density near players; safe spawn/despawn and predictable behavior.
4. **Off-screen simulated:** household/business population aggregates in unloaded chunks; no physical NPC tick cost until activation.

## Living schedules

NPCs go between home, workplace, public spaces and safe shelter based on local time and events where pathing allows. NPCs cannot simply walk through closed buildings or traffic. Important interactions need fallback locations so the user is not locked out of a quest at night.

## Performance rules

- Define active NPC radius and maximum count per district; benchmark actual server TPS rather than assuming a target count is safe.
- Expensive pathfinding/combat AI only when relevant; switch to scheduled aggregate production when chunks unload.
- Simulated stock and cash must reconcile with visible shops when they load; no duplication between physical and background systems.
- Social responses and reputation persist across restarts; NPC death/respawn policy must support story continuity.
- Separate civilian and military aggression; city residents should not attack on mere foreign citizenship unless narrative/legal conditions justify it.

## Gameplay

Named buyers issue real demand-driven contracts. Town leaders request aid and provide civic decisions. Guards have patrol ranges and respond to witnessed crime, reports or warrants. Hostile factions control chokepoints and create contested-route risk.

## What exists today (October 16, 2026)

A first, deliberately small citizen layer, built on vanilla villagers rather than a new NPC mod ([D-061](../production/decisions.md)):

- **Founders Row** has twelve named citizens (October 17): a registrar, an employer for each of the five careers, two market vendors, three residents and a gate guard. Each has a bed or a workstation (or both), so the vanilla routines (sleeping, working at the job site, gathering at the village bell) apply without any code of ours. They are persistent and invulnerable; `/uc_district repopulate founders_row` puts back one that an operator removed.
- **What they do** is read from the district data when a player speaks to them: the registrar opens the first-login screen for a newcomer and gives a next step to a registered citizen (their employer and a rough direction, then the skills page); an employer opens the Operations Terminal bound to their office for a citizen of their own career and, for anyone else, says what they do and names the player's own employer; residents and the guard talk. Lines are data, not code.
- **Checkpoint posts** ([D-062](../production/decisions.md)) give each road checkpoint zone a booth and a guard who turns to the traveller and speaks when the existing search triggers.
- **Wayside shelters** ([D-079](../production/decisions.md)) give the roads a **road warden**, a named citizen with the `warden` role who answers from real data: where the road leads and how far in minutes on foot, where the nearest checkpoint is, and what the city law says at each end. The same warden stands at every shelter (a name per road is an open question).
- **Not simulated:** no schedules beyond the vanilla ones, no relationships, no memory of the player, no off-screen population, no quest state per NPC, no killing or arrest. Employers do not pay or trade themselves; contracts still go through the terminal and Q Shop.
- **Known vanilla behaviours to expect:** villagers can still breed when they have beds and food, and wander to the nearest bell; both are left alone on purpose.

## Spike before implementation

Evaluate CustomNPCs-Unofficial, MineColonies and armed NPC addons **separately** on a clean dedicated server. Record entity sync, persistence, scripting API, pathfinding, combat compatibility, performance, and licensing. Do not assume different AI mods integrate cleanly.
