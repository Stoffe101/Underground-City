# Testing and evidence strategy

**No gameplay tests have been run.** Start with docs/manifest lint. Real integration tests begin once the modpack and world exist.

| Level | Evidence required |
| --- | --- |
| Repository | Required docs, local links, candidate metadata and unsafe committed binary checks |
| Content | Schema, prereqs, recipes, pricing, loot, contracts, localization |
| Unit | Tax timing, reward idempotency, ledger conservation, XP caps, migration |
| Client/server | Exact mod versions, clean Forge boot, two clients connect, rejoin and restart |
| Gameplay | Registration, early production, Blueprint unlock, shop trade, first tax, Lootr and NPC quest |
| World | Import seam inspection, pathfinding, collision, physical loot, backups and rollback |
| Performance | TPS, memory, tick duration, active NPC count, FPS baseline |
| Visual UX | Screenshots of empty/locked/full/overflow states and GUI-scale checks |

## Non-negotiable examples

A contract only pays once. A reconnect does not regrant the starting kit. Two players' personal loot and skill XP are independent. No tax accrues offline. No critical progression depends on an absent human profession. Incompatible mods do not silently corrupt the only save. City AI does not attack citizens without a defined rule.

Store actual test evidence with commit SHA, Java/Forge/mod versions, server logs (redacted), steps, outcomes, screenshots and rollback notes. Keep raw player data and private saves out of public Git.

## Evidence from initial implementation

On October 14, 2026, Forge 47.4.10 compiled the initial Core in GitHub Actions and the headless GameTestServer reported **1 required test passed**. The test checked independent citizen records and a save-data NBT roundtrip; it did **not** test dedicated-server restart or two connected Minecraft clients. Offline Python tests verify packaged/exported modpack readers and pure-Java progression behavior. Real multiplayer and third-party mod compatibility remain open tasks.
