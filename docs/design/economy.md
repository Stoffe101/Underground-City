# Economic system, contracts, taxes, and city growth

## Principles

The economy must be legible, finite enough to balance, and tied to the physical world. A city is not just a vendor with infinite funds: it has stocks, budgets, consumption and consequences.

## Accounts and ledgers

- Player wallet/bank, business cash accounts, city treasuries, NPC buyers/sellers, optional criminal cash and escrow.
- Server-authoritative durable transactions: unique event ID, payer, payee, amount, goods reference, reason, timestamp/playtime, and correlation to a contract.
- Money changes through explicit transactions and bounded currency faucets/sinks. Replays/failed transfers must not duplicate currency or items.
- Track economic supply per region by commodity groups, then deepen categories only when necessary.
- NPC population demand and production may be simulated in aggregates off-screen; active shops reconcile stock on loading.

## Tax policy proposal

No offline accrual. A low, capped levy per *active playtime* or a transparent share of eligible paid business income, with grace period, payment warning, and payment plans. For casual private play, no automatic eviction or forced bankruptcy. Tax revenue funds city projects and services; show civic dashboard impact.

## Markets and anti-inflation

- NPC vendors have stock, refresh budgets, daily/active-time purchase limits and demand-based prices within clear bounds.
- Prevent buying a good from a vendor and immediately reselling to the same system for a guaranteed profit.
- Distinguish shared physical cargo from per-player personalized exploration loot.
- Material sources: farming, processing, salvage, mines, contracts, imports. Money sources: work orders, trade, discovery, story; sinks: tools, travel, upkeep, taxes, research, property, project contributions.
- Price bands and item tier/rarity must be defined for a two-player demand scale.

## Sample supply chain (illustrative)

Vintner produces 20 wine bottles → inn reserves 10 and city event requests 10 → player receives money and career XP → city food/service economy improves → tax funds a security outpost. Another contract might require smith-made tools or electronics from technician.

## Testing invariants

For every transaction: conserved debit/credit, no negative stock, no item duplication, idempotent retry, journal persists after restart, failure rolls back, no unbounded exploit loop. Write tests before expanding the commodity catalog.
