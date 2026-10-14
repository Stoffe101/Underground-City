# World selection and assembly pipeline

No map has been selected or imported.

## Candidate approaches

1. **Customize The Lost Cities** to build a bounded modern urban region, then furnish essential locations and add NPCs. [Official mod page](https://www.curseforge.com/minecraft/mc-mods/the-lost-cities).
2. **Adapt one connected world** containing several existing cities. Study road networks, interiors, author permissions, version and player performance before import.
3. **Hybrid assembly**: legal base map plus expressly permitted structures/districts pasted into documented anchors.

Reference-only candidates: [Greenfield](https://www.greenfieldmc.net/downloads/), [Matt's MineCity](https://www.planetminecraft.com/project/my-creative-server-world/), [Alma Bay](https://www.planetminecraft.com/project/alma-bay-realistic-modern-city/). Their listing here does not authorize import, modification or redistribution.

## Safe workflow

- Record author, version, source URL, precise permitted uses and checksum in [asset register](../legal/asset-register.csv).
- Work from a copy. Preserve the original untouched and save point-in-time backups.
- If using The Lost Cities, **select the profile before world generation**. Profile changes later create inconsistent seams across generated chunks; verify `defaultconfigs/lostcities-server.toml` for dedicated servers.
- Import a small test region first, log coordinate transforms and rotation under stable anchors, then join roads/terrain.
- Verify building interiors and lighting, NPC pathability, travel routes, border volumes, loot assignment and physical player access.
- Test in a dedicated server with two players, close/reopen, inspect save integrity, TPS, and chunk boundaries.
- Promote regions only after legal, visual and functional review.

Prefer a smaller, dense and functional city over kilometers of empty buildings. Changing world sources must not require rewriting professions or story logic.
