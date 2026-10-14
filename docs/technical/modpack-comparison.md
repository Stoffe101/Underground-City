# Compare installed modpacks without redistributing files

Before deciding whether to use GCA, DeceasedCraft or a clean Forge installation as the base, compare **official launcher export manifests**. The tools inspect exports locally and do not download pack binaries or rehost third-party mods.

## Usage

Get a pack export from a legitimately installed instance in CurseForge or a compatible launcher. Export it as a **CurseForge ZIP** (containing `manifest.json`) or **Modrinth .mrpack** (containing `modrinth.index.json`). Keep files outside the public Git repository.

```sh
python scripts/audit_modpack.py --pack path/to/pack.zip --output path/to/report.json
python scripts/compare_modpacks.py path/to/first.zip path/to/second.zip --output path/to/comparison.json
```

With actual installed JARs on your own machine:

```sh
python scripts/audit_modpack.py --mods path/to/instance/mods --output path/to/installed.json
python scripts/compare_modpacks.py path/to/instance-a/mods path/to/instance-b/mods
```

The comparator reports counts, shared identifiers, unique identifiers, Jaccard similarity, different CurseForge file IDs and Minecraft/loader labels. A cross-format comparison cannot reliably resolve identities (CurseForge numeric project IDs and Modrinth paths are not interchangeable), so it **does not invent an overlap score**. Metadata checks may miss embedded dependencies, incorrect version ranges, and runtime conflicts.

### What CI can do automatically

CI tests this logic using synthetic manifests on every PR and on the weekly schedule, without needing your installed packs. After actual candidate exports are available in a permitted location, run the tools on those inputs to produce comparison reports; CI **does not already have** your private launcher folders, CurseForge account, or proprietary downloads.

### What is still required before selecting a base

Inspect the actual exported lists, test clean dedicated-server startup and two-client login, audit license/permissions for included maps and custom assets, measure NPC/urban performance, and verify disabling conflicting apocalypse or survival mechanics does not break progression. See [base selection](../production/base-comparison.md) and [reuse-first policy](reuse-first.md).
