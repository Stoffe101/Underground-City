# RPG Core (Forge 1.20.1)

First actual gameplay implementation: server-side citizen registration, saved city/career choices, and separate career/general experience counters. This is **not** the final GUI or full profession economy.

## Build

JDK 17 and Gradle 8.8 required. From repository root:

```shell
gradle -p rpg-core build --no-daemon
```

CI will also attempt the build and upload the original compiled mod jar as an artifact when successful. This module follows the Minecraft Forge MDK pattern; it has no dependency on a purchased/downloaded third-party mod.

## In a development world

- First login prompts for `/uc register valewood farmer` (other cities: `ironhaven`, `blackwater`; other careers: `smith`, `gunsmith`, `technician`).
- `/uc status` shows your registered city, career, level and XP.
- `/uc skills` shows general skills.
- OP-only `/uc dev career_xp <player> <amount>` and `/uc dev general_xp <player> <skill> <amount>` exercise saved progression during testing.
- Identity is stored in the Overworld's `SavedData` under `underground_city_citizens`, not in a file in this repository.

Current limits: three illustrative cities but no built world, no real XP awarding from professions, and command onboarding rather than UI. No city taxes, buildings, inventory item registration, or NPCs have been implemented. Preserve world backups and do not use this development module with valued saves until persistence has been acceptance-tested.
