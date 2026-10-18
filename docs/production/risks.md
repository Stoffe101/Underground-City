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
| A Crew bank file is lost, corrupted or from a newer schema, so credits that left wallets are unreachable | High | One atomic fsynced file per world (`data/underground_city_crews.dat`); a failed write faults the ledger and refuses everything until restart; an unreadable file is reported on the Crew screen while solo play continues; back up the whole world (the playtest guide says so); `/uc_crew pending` and `resolve` for an operator. Not tested: a hand-corrupted file on a hosted run |
| A transfer is interrupted in a way recovery cannot decide (a wallet value matching neither recorded value) | Medium | Recovery only reads the wallet; such a transfer is logged, blocks only that player, and needs an operator's `/uc_crew resolve <uuid> true` or `false`; the journey exercised the two decidable cases with a real process halt, not this one |
| Crew rules are misread as shared money everywhere (Q Shop's own shop screens always spend the personal wallet) | Medium | The Rules page and the docs say so; shared finances cover work-order pay and exchange purchases only; revisit if Q Shop gains an account API |
| A leased home can be griefed from outside the guard's cases (pistons, fluids, fire, mobs, item frames, hoppers across the shell) | Medium | Listed in the expansion plan (WP3) with an exploit test per gap; the shell belongs to the city; creative mode is never held back |
| The opt-in survival set interacts badly with Lost Cities, vehicles and the other mods in a long session | Medium | Boot and GameTest records only; opt-in; add a played session before any default |
| A third-party table recipe makes a contract undeliverable (for example TaCZ fits a stock to the M4A1) | Medium | The firearm-contract GameTest checks that every gun requirement lists a gun the table builds plain; the item-source audit covers Core's own data |
