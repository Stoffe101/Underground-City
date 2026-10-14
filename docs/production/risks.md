# Risk register

Severity: critical/high/medium. The mitigation is a plan, not a claim of completion.

| Risk | Severity | Mitigation / evidence required |
| --- | --- | --- |
| Cross-mod incompatibility and dedicated-server crash | Critical | Minimal binary-search mod staging; pin versions; archive client/server logs and manifest |
| Existing city maps are incompatible or legally unsuitable | Critical | Permission register; export in disposable test worlds; run world validation; preserve originals |
| Generated city profile changed after chunks exist | High | Lock profile before first generation; back up then new world/region when modifying |
| Too many NPC pathfinding entities degrade TPS | High | Spatial budgets, activation and off-screen simulation; benchmark at target counts |
| Economy inflates due to infinite NPC purchases or loot duplication | High | Quantity budgets, demand curves, shared vs personalized sources, transaction audit |
| Taxes punish players for not logging in | High | Active-play counter; caps and grace periods; no real-time offline accumulation |
| Profession progression becomes grindy or impossible with 2 players | High | Task-based XP, NPC alternatives, reachability graph/recipe validator |
| Firearm systems break combat balance or policing | High | Tier, rarity, ammo cost, NPC accuracy, cover, legal rules, controlled encounters |
| Saved contracts/roles/skills corrupt on update | Critical | Versioned save schemas, migrations, idempotency, backup/restore tests |
| City and quest triggers rely on fixed coordinates | High | World-data registry with region and location identifiers, configurable bindings |
| Overengineering delays any playable result | High | Small P0.1 scope, phase gates, feature flags, deferral discipline |
| ARR project accidentally republishes third-party files | Critical | No binaries in git, asset ledger, notice review and build artifact policy |
| Two-player conflicting choices stall the main quest | Medium | Shared milestones plus individual choice state and explicit co-op conflict handling |

Review at each milestone; raise new risks when technical spikes reveal them.
