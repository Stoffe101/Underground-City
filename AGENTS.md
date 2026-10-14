# Agent / automation instructions

Project: Underground City, a private-play semi-modern Minecraft living-world RPG. Read `README.md`, `docs/README.md`, `docs/production/decisions.md`, and `docs/production/mvp-v0.1.md` before modifying code, world assets, or gameplay data.

- **Reuse before rebuilding**: evaluate installed compatible mods, creator-approved configs, and existing modpack components before writing replacement systems; document choice and APIs. See `docs/technical/reuse-first.md`.
- **Do not silently lock technologies**: Java 1.20.1 / Forge 47.x / Java 17 are candidate baselines. Verify exact versions and dependencies before implementation.
- **No unlicensed imports**: Never copy external mod binaries, structures, music, textures, screenshots, scripts, or maps into this repo without explicit applicable permission; update the third-party register.
- **NPC-first co-op**: All essential story quests and professions must be completable with two players and NPC substitutes.
- **Server-authoritative systems**: Money, inventory rewards, tax, reputation, crimes, faction control, and XP are validated server-side and saved durably.
- **Data-driven gameplay**: Stable namespaced IDs; schemas for careers, recipes, cities, contracts, items, and loot; no hardcoded world coordinates.
- **Preserve worlds**: Backup before editing/generated terrain, test migration, never overwrite a user's live save.
- **Avoid incoherent simulation**: Only NPCs near players require full entity behavior; off-screen activity is simulated deterministically and bounded.
- **Quality evidence**: Run lint/smoke tests; verify multiplayer and persistence; manually inspect screenshots for UI/UX. Never claim a tested result without a recorded test.
- **Code hygiene**: Small reviewable commits, change logs for major decisions, no warnings hidden, no credentials or binaries committed.
- **Progressive deliverables**: Focus on the smallest complete slice first; never replace functional gameplay with speculative systems for scale.

Original project materials are proprietary and all rights reserved. Third-party material is independently licensed.
