# Starting pack decision spike

**Outcome: no base pack selected yet.** The first round identified viable 1.20.1 Forge candidates; selection needs installed package manifests, startup tests, performance checks and mod-specific licensing review.

## A/B testing procedure

1. Create isolated launcher instances: GCA, DeceasedCraft, Crusthaven, and an empty Forge 1.20.1 baseline. Keep all saves independent.
2. Export every instance's effective modlist and client/server configs. Inventory exact versions and dependencies.
3. Score each pack for living city visuals, usable interiors, civilian jobs, NPC presence, realistic weapons/blueprints, economy, exploration, loot, performance, maintainability and difficulty of removing incompatible systems.
4. In a *copy* of the selected pack, remove unwanted features in coherent dependency groups and boot the client and dedicated server after each group.
5. Compare the adapted pack against a small clean-room mod stack. Choose lower lifetime complexity, not largest feature count.

## Candidate hypotheses to test

- **GCA first** for city roleplay, crime and income: close genre fit but small and presently unverified.
- **DeceasedCraft second** for density and systems: strongest environment benchmark, but survival/apocalypse mechanics could make reusing the whole pack more expensive than reassembling its freely available mod components.
- **Clean Forge baseline** remains viable if existing packs couple too tightly to incompatible gameplay.
- **Do not migrate an existing populated server world** until backup, version and permissions checks pass.

## Acceptance evidence

Source/version of base pack, manifest delta, licenses/credits, clean two-player dedicated-server boot, average TPS and FPS, one legal profession loop, role/skill integration test, gameplay screenshot comparison, and an explicit switch/keep decision. No claim of compatibility from the fact that two projects both say 'Forge 1.20.1'.
