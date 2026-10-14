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

## Spike before implementation

Evaluate CustomNPCs-Unofficial, MineColonies and armed NPC addons **separately** on a clean dedicated server. Record entity sync, persistence, scripting API, pathfinding, combat compatibility, performance, and licensing. Do not assume different AI mods integrate cleanly.
