# RPG Core (Forge 1.20.1)

First actual gameplay implementation: server-side citizen registration, saved city/career choices, and separate career/general experience counters. This is **not** the final GUI or full profession economy.

## Build

JDK 17 and Gradle 8.8 required. From repository root:

```shell
gradle -p rpg-core build --no-daemon
```

CI will also attempt the build and upload the original compiled mod jar as an artifact when successful. This module follows the Minecraft Forge MDK pattern; it compiles against the checksum-verified original Q Shop API, which remains optional at runtime and is never bundled.

## In a development world

- First login prompts for `/uc register valewood farmer` (other cities: `ironhaven`, `blackwater`; other careers: `smith`, `gunsmith`, `technician`).
- `/uc status` shows your registered city, career, level and XP.
- `/uc skills` shows general skills.
- OP-only `/uc dev career_xp <player> <amount>` and `/uc dev general_xp <player> <skill> <amount>` exercise saved progression during testing.
- Identity is stored in the Overworld's `SavedData` under `underground_city_citizens`, not in a file in this repository.

Current slice: twelve real vanilla-item work orders, Q Shop credits and earned career XP, plus one optional named vanilla supplier. Command onboarding remains; advanced city materials, taxes, skill trees, furnished districts, NPC police, shipments and raids remain unimplemented. Preserve world backups and do not use this development module with valued saves until persistence has been acceptance-tested.

## Automated checks

CI builds the mod using ForgeGradle and executes a dedicated GameTestServer with an original one-block template. The in-engine test validates NBT roundtrip of two independent citizens, XP isolation, and rejection of duplicate registrations. A separate pure-Java regression harness covers XP limits and progression monotonicity. Passing a GameTest does **not** replace actual two-client tests with selected third-party mods.

The registration/career commands are an **experimental fallback**. Before expanding them, evaluate RoleCraft and Pufferfish's Skills as possible existing replacements. See [reuse-first policy](../docs/technical/reuse-first.md).

## Ready-to-import preview builds

A green Forge CI run also creates `underground-city-core-preview.mrpack` and `underground-city-core-preview-curseforge.zip` inside its **starter import archives** artifact. Import either archive with a launcher supporting that format, rather than manually configuring Forge for this minimal test. It includes only the original Core jar. Save in a separate development instance/world, never overwrite your existing saves. Actual imports still need live-client verification.

## Paid profession slice

Install the original Q Shop 1.8.4 on server/client and stage our authored currency/shop templates before first world load. [Integration guide](../docs/technical/economy-integration.md) covers the exact API, checksum, recovery journal and playable loop. Right-click the PC → Contracts → accept → produce/deliver goods → receive credits/XP → unlock more work. Imports opens the real civilian shop. OP-only `/uc_clerk` creates one bound vanilla supplier without replacing terrain.

Run provider tests in a fresh disposable directory:

```sh
python scripts/install_qshop_templates.py --server-root rpg-core/run-economy
gradle -p rpg-core build runGameTestServer -PucEconomyTest=true -PucRunDir=run-economy --no-daemon
```

For Core-only GameTests omit `ucEconomyTest`; paid-provider tests explicitly skip. The build downloads the checksum-verified original author release as a compile-only API; it is never included in Core or starter artifacts. Starter import archives still include only original Core, so they need the separately obtained provider for paid work.
