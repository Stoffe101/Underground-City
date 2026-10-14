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

## GCA's currently advertised components (research, not installed)

The GCA author describes **Kuro's Economy** for bank accounts, automatic payments, checks and government ID cards; **MCA Reborn** for interactable village populations; **Villager Comfort** for housing/social needs; **Immersive Vehicles** for transport; and **TaCZ** for firearms. Its stated goal encourages **player-to-player** economic interdependence. We must adapt that design for only two humans by allowing NPC buyers, producers, transport workers and shops. GCA's legacy pack rules (survival food, thirst, crafting restrictions) may feel oppressive for our balanced RPG unless adjusted. Source: [GCA description](https://www.curseforge.com/minecraft/modpacks/gca).

### Current leading component comparison

- Test **Kuro's Economy vs Lightman's Currency** in separate profiles. Favor the one that offers dependable jobs, taxes and contracts without needing a custom transaction engine.
- Test **MCA Reborn + Villager Comfort** for ambient town life. Use a named/story NPC framework only where it adds capabilities that MCA cannot cover.
- Test **Immersive Vehicles** with one legally usable compatible vehicle content pack before committing to a road system.
- Test **RoleCraft** for career UI and police. Its published roles are farmer/blacksmith etc.; custom Gunsmith/Hacker extendability remains unknown.

Do not claim that these components work together until two-client dedicated-server tests pass.
