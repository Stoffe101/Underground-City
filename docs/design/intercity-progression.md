# Inter-city dependency, frontier travel, and living conflict

**Direction:** the three cities must be connected, hostile enough to make foreign travel risky, and mutually necessary for serious character progression. The player does not spend the campaign permanently inside their starting city. This design is captured as machine-validated **planning data**. One slice is live: recurring tier-2/3 work orders require rival-city visits recorded from a terminal placed inside a city circle (server-side, durable), and regional goods are sold only at that city's exchange. Guarded crossings, checkpoints, convoys, war states and any physical road are **not** implemented, and native Lost Cities highways do not link the fixed cities (see the [world notes](../technical/three-city-prototype.md)).

## Three genuinely different suppliers

| City | Advanced exclusive supplies (logical IDs) | Major locations worth exploring |
| --- | --- | --- |
| Ironhaven | Refined alloy, machined parts, security fabrics | Machine shops, factories, guarded warehouses |
| Valewood | Rare seeds, biopolymer, agricultural catalysts | Greenhouses, research farms, vineyards |
| Blackwater | Control circuits, encrypted designs, optical parts | Tech shops, office towers, restricted data vaults |

These items are **logical design IDs**, not currently registered Minecraft items. We will map them to existing compatible mod items and weapon packs wherever possible before adding custom materials. A source must exist physically and not just as an abstract loot table.

## Tier gates: the world is the tech tree

- **Tier 1:** enough materials and tools in your home city to begin your livelihood without leaving on day one.
- **Tier 2:** first expedition to at least one rival city, to visit a qualified source/supplier and return with a technology/resource your home city cannot produce. Buying the same item from an importer without leaving does **not** complete first discovery.
- **Tier 3:** personal visits to **all three** cities, earning access to their specialists, markets and locations; equipment combines inputs from the entire region.
- **Later story:** political allegiance, embargoes, and faction objectives change which crossing is safe and what each supplier will sell. No recipe should become permanently inaccessible because of a faction storyline.

**Recurring visits matter:** Tier unlocks are only the first discovery. Later work orders and equipment batches consume specialist supplies, which belong to producers in specific cities and must be obtained again when stock runs low. Suppliers themselves have limited shared stock, gradual production recovery, and travel/permit requirements. You can stockpile components to complete several jobs per trip, or have a co-op partner deliver them. See [the recurring-supply system](recurring-supply-chains.md) and its 12 repeated profession work orders. This is testable source logic, not Minecraft recipes yet.

The milestones in [intercity-supply.json](../../content/progression/intercity-supply.json) apply this to farmer, smith, gunsmith, and technician, always offering a peaceful route and a dangerous alternative. Two players may specialize differently and trade with one another, but neither profession is locked behind an absent third person.

Examples: a gunsmith needs Ironhaven machining and Blackwater optics for advanced mechanisms; top-tier designs add Valewood composites. An agricultural engineer needs Blackwater circuits, Ironhaven parts, and Valewood catalysts for precision viticulture. A technician needs materials sourced from industrial, agricultural and electronic industries. Higher-tier rewards cannot be obtained indefinitely from one ordinary apartment.

## War makes travel consequential

[Border conflict data](../../content/factions/border-conflict.json) defines an armed standoff on Ironhaven–Valewood, a more militarized Ironhaven–Blackwater front, and a trade embargo on Valewood–Blackwater. Each crossing should support distinct player choices:

- **Legal travel:** permits, guarded convoys, neutral trader escorts and reputation with border officers. This is risky and costs effort but remains possible for noncombat characters.
- **Risky travel:** trespassing, smuggling, forged manifests as fictional gameplay, contested backroads, illegal contacts or opportunistic combat/loot.
- **Reactive consequences:** local suspicion and inspection, witnesses, patrols, curfews, heat and reputation. A foreign citizen may be stopped or warned, but should not be shot for merely standing in a peaceful public street.
- **No dead-end wars:** defensible safe hubs, recoverable fines/warrants and alternative crossing routes. PvP between the two human players stays off by default.

## Roads, bridges, and topology

Three corridors form a *connected triangle*: Ironhaven–Valewood (guarded highway), Ironhaven–Blackwater (militarized highway), and Valewood–Blackwater (a longer frontier edge for traders or smugglers).

[Corridor plan](../../content/region-blueprints/corridors.json) records block-coordinate polylines, gate locations, modes and security. **The two highways are now real blocks**: Core's `corridor_roads` worldgen feature builds a 7-wide deck with parapets and supports into newly generated chunks, and rides four free blocks above any water so a boat can pass beneath ([measurements](../technical/three-city-prototype.md#roads-and-bridges-original-generated-with-new-chunks)). Water between the cities is deliberate: **a bridge is the safe way over and a boat is the alternative.** In the reference seed the Valewood–Blackwater edge is open sea, so a direct trip needs a boat; the other way round goes through Ironhaven. Checkpoint gate houses, rest stops, patrols and the frontier road are still design only.

### How roads are built

1. Lost Cities' native highway planner ignores predefined cities (measured), so it is not used.
2. A deterministic profile is derived from the chunk generator's own heights for the seed, then slope-limited so walking, riding and vehicles never meet more than a half-block rise per two blocks.
3. Generation is limited to new chunks of a fresh world; it never edits an existing save.
4. A native dedicated-server survey reads the real blocks along each road; a native client photographs a bridge from the side and from the deck. Both must be reviewed on every change, because passing numbers can still look wrong.
5. Still to add: two-sided border checkpoints, vehicle-mod compatibility, abandoned service stations and risk/reward stops.

## Living cities rather than empty terrain

World generation is just physical scaffolding. Each city needs at least one functional district with shops, NPC jobs, residents, homes, opening hours, police patrols, social NPC dialogue, and a distinct economy before expansion. Near players, NPCs can visibly work/commute/inspect borders. Outside loaded areas, bounded aggregate simulation updates food demand, supply and faction tension. **No thousands of permanently active NPC AI entities.**

## Proof of completion

Three actual road-connected, traversable urban areas; different-looking functional markets; two-player crossing demonstrations including a civilian contract and a risky route; tier 2 and tier 3 profession unlocks using genuine installed items; route-specific loot; persistent faction/visitor response. **The current code only validates the plan and an isolated stock-recovery model.**
