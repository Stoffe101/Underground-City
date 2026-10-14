# Data contracts and progression integrity

These are proposed **illustrative** records. No runtime loader, schema or registry entries exist yet.

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
