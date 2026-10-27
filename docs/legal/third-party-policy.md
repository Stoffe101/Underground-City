# Third-party content and private-play policy

**Status:** mandatory project policy; not legal advice.

## Non-negotiable rules

1. **Private play is not a blanket redistribution license.** We may play privately with legitimately obtained mods/maps; publishing those binaries or assets to a public repository requires separate rights.
2. **Mod metadata is not a license.** Check the project's actual license, download page, distribution rules, dependencies, and any terms governing modified assets or modpacks.
3. **All Rights Reserved means ask first.** Unless a specific permission or exception applies, do not redistribute, modify, or incorporate another creator's protected assets.
4. **Do not extract/copy DeceasedCraft's custom structures, loot tables, scripts, or art by default.** Its atmosphere is a design reference, not an asset license. The one exception is the owner's reported private permission for the city-building content of its creator (C70 and team), which allows private use and private porting only and keeps every file out of this repository: see [private-content.md](private-content.md) (D-116).
5. **Map stitching and schematic imports require provenance.** An available download is not automatically permission to incorporate and redistribute it, especially after alterations.
6. **Credit is not a substitute for permission.** Record required credit precisely; retain license texts where necessary.
7. **Never commit mod jars, purchased files, world downloads, secrets, player saves, or server backups.** Maintain machine-local installs and a dependency manifest.
8. **Review permissions again if distribution scope changes.** A private two-player project becoming a shared pack, public repository artifact, or server download changes the legal assessment.
9. **Original contributions to this repository are proprietary under LICENSE** unless explicitly agreed otherwise in writing. This does not retroactively relicense contributors' or third parties' rights.

## Intake checklist

For each third-party candidate, record: ID, name, creator, canonical source, exact file/version, Minecraft version, loader, license URL, attribution requirements, modifications planned, server/client requirement, dependency tree, distribution constraints, evidence, and approval state.

States: `candidate` → `under_review` → `approved_for_local_use` or `approved_for_repo_distribution` → `retired`. Approval for local use does **not** imply approval to commit content or package it in releases.

## Map and modpack strategy

Prefer fresh configuration of legally obtained mods; data definitions authored for this project; original structures; creator-approved schematics; and maps with explicit permissions matching our intended use. Link to downloads instead of mirroring files. For a private custom world, document each external map region and transformation in the same register.

## Security and credits

Do not store accounts, API keys, server IPs, personal addresses, UUID/player inventories, or unredacted crash logs. If a third-party notice is required, update THIRD_PARTY_NOTICES.md in the same PR that introduces the asset.
