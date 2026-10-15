# Data contracts and progression integrity

The examples below remain illustrative designs. The runtime schemas listed in the next section load through the vanilla datapack reload system and validate item registries, career IDs, quantities, prices, XP and shared demand. Accepted terms and settlement targets are snapshotted in the durable journal; see [economy integration](economy-integration.md).

## Implemented runtime schemas (Core, `schema_version` required)

All are datapack resources under `data/underground_city/`, loaded atomically on reload; invalid data fails the reload instead of partially applying.

| Resource | Schema | Fields and validation |
| --- | --- | --- |
| `work_orders/*.json` | 1 and 2 | title <= 80 chars, `career` in the four careers, `level` 1-30, credits/xp 1-1000, `capacity` 1-16, `refill_ticks` 1200-72000. Schema 1: one `item`+`count` (1-64). Schema 2: `requirements` (1-4 unique registered, non-air items), optional `foreign_visits` 0-2. At most 32 orders. Accepted terms are snapshotted; pre-schema-2 saved snapshots still load |
| `regional_stock/*.json` | 1 | `city`, registered non-air `item`, `unit_price` 1-10000, `initial` <= `capacity` <= 256, `units_per_interval` 1-64, `interval_ticks` 1200-720000, `max_order` 1-64; at most 64 lines |
| `regions/*.json` | 1 | `id` (one of the three cities), `dimension`, `center_chunk` [x,z], `radius_blocks` 16-4096; generated from the world blueprint; at most 16 |
| `property_policies/*.json` | 1 | `capacity` 1-9, `initial_stock` <= capacity, `interval_ticks`, `items_per_interval` 1-4, `lockpicks` 1-8, `loot_table`; at most 32 |
| `underground_city_items.json` (classpath) | 1 | generated list of the 22 original item IDs, kind and stack size |

Generated files are produced from [regional-content.json](../../content/gameplay/regional-content.json), [restock-policies.json](../../content/property/restock-policies.json), [loot-tables.json](../../content/property/loot-tables.json) and [cities.json](../../content/region-blueprints/cities.json) by `scripts/generate_regional_content.py`; edit the sources, never the output.

## Rules

Use stable namespaced IDs and explicit `schema_version`. Separate item names, display language, world coordinates and gameplay IDs. Track external registry aliases and migrations. Persist currency as integer units, not floats.

### Example city
```json
{
  "schema_version": 1,
  "id": "underground_city:valewood",
  "spawn_anchor": "underground_city:valewood_registration",
  "region_id": "underground_city:valewood_region",
  "treasury_policy": "underground_city:citizen_starter"
}
```

### Example career
```json
{
  "schema_version": 1,
  "id": "underground_city:farmer",
  "max_level": 30,
  "branches": ["cultivation", "vintner", "agribusiness"],
  "xp_events": ["contract_completed", "new_production"]
}
```

### Example research unlock
```json
{
  "schema_version": 1,
  "id": "underground_city:training_part",
  "career_id": "underground_city:gunsmith",
  "min_level": 3,
  "required_skill_ids": ["underground_city:component_basics"],
  "workstation_id": "underground_city:starter_workbench",
  "reward_id": "underground_city:mechanical_part"
}
```

These example IDs are not confirmed Minecraft items or working recipes.

## Content integrity tests to implement

Validate schemas, unique IDs, missing dependencies, circular prerequisites, unobtainable recipe inputs, mandatory-story reachability for two players, item-registry availability, zero/negative prices and duplicate rewards. Every contract, NPC, crime, city and loot record needs a stable identifier, defined source of truth and migration strategy.
