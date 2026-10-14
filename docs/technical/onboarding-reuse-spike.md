# Reuse-first first-join onboarding: city → profession

**Research date:** 2026-10-14. **Decision state:** short list, **not yet installed or integrated**. Do not add an overlapping second registration screen to RPG Core.

## Cisco inspiration

Cisco's Fantasy Medieval RPG wiki describes a layered initial choice of **Origin, Divine Blessing, and Class**. Its wiki explicitly describes these choices as a combination of mods, datapacks, and custom design, not one magical mod that solves every downstream progression system. Source: https://ciscos-rpg.fandom.com/wiki/Origins_(Dragonfyre). The modpack's assets and proprietary datapacks are **not** copied.

For our modern two-player RPG, the *interaction pattern* becomes:

1. **Home city (citizenship):** summarize security, economy, local laws and starting district.
2. **Primary profession:** give truthful previews of the work, specializations and early play loop.
3. **Confirmation:** commit both choices once on the server, then begin at a municipal registration point.
4. **Later progression:** separate general skills, profession skill trees, reputation and civic influence. A career choice is not a fantasy race or a permanent combat stat loadout.

Our cross-provider draft content lives in [onboarding catalog](../../content/onboarding/catalog.json). It is **design data only**, not a functional mod configuration. Names and town lore are working placeholders.

## Shortlist: Minecraft 1.20.1 Forge

| Candidate | Features relevant to us | Weakness / unknown | Assessment |
| --- | --- | --- | --- |
| [Class Selection Mod](https://www.curseforge.com/minecraft/mc-mods/class-selection-mod) | **Faction then class** menus, faction images, icons, class trait/kit, configurable item/crafting/block restrictions, change requests, character sheet; changelog for 1.5.8 specifically mentions **TaCZ** and **Epic Fight** integration | **All Rights Reserved**. Does it expose an API for citizenship, persistent economy, skill trees, class UI labels, and server hooks? Starter kit duplication on class change requires tests. Published page describes different version numbers in different sections; pin a real artifact | **First integration spike** |
| [Class Enhancement](https://www.curseforge.com/minecraft/mc-mods/class-enhancement) | Highly configurable class-selection GUI, custom class entries in TOML, items/attributes/commands; established 1.20.1 Forge mod with much wider adoption | One class layer, not a built-in separate city/faction choice; likely requires a city system or additional screen | **Second choice** |
| [RoleCraft](https://www.curseforge.com/minecraft/mc-mods/role-craft) | First-login profession selection, job-specific XP/perks, police tools and item restrictions | Built-in career list may not allow fully custom Gunsmith and Hacker and independent city identities. No integration API verified | **Evaluate with class/progression stack** |
| [Starting Classes](https://www.curseforge.com/minecraft/mc-mods/starting-classes) | Data-driven starter loadout menu on first join; add/modify classes using datapacks, Forge 1.20.1 | Primarily **starting equipment**, not municipal citizenship or deep profession XP | **Lightweight alternative** |
| [Role Selection](https://www.curseforge.com/minecraft/mc-mods/role-selection) | Forge 1.20.1, MIT, JSON5-defined roles, one-time UI, starter items and protection while choosing | Very new; only a single selection layer and no economy/skill tree by itself | **Open-license fallback** |
| [Origins (Forge) + Origins: Classes (Forge)](https://www.curseforge.com/minecraft/mc-mods/origins-classes-forge) | Cisco-like layered selection: origin, plus a second class layer; datapack customization possible. Origins: Classes Forge is MIT | Race abilities are unnecessary or undesired for modern civilian game; city-as-origin risks forcing lore/mechanics through a fantasy design. Confirm required Origins Forge release and client/server integration | **Use only if multi-layer configuration outperforms faction/class mod** |

Also consider [Origins QoL & UI Fixes](https://www.curseforge.com/minecraft/mc-mods/origins-qol-ui-fixes-forge) **only if Origins wins**: its documented rules support blocking impossible choices and adding a Back button. Test its client/server version and packet behavior.

**Not current candidate:** Rise of Classes currently publishes for **Minecraft 1.21.1 NeoForge**, not our 1.20.1 Forge hypothesis. Do not mix incompatible loaders because its first-join UI looks good.

## What these mods do not automatically solve

- Cities with leaders, ownership, taxes, political reputation and police jurisdiction.
- Skill-tree abilities gated by contracts/blueprints instead of only choosing a starter kit.
- Server-side correlation of citizenship with NPC factions and persistent economy.
- Legitimate and underground versions of gun sales, research and material requirements.
- Anti-exploit co-op quest state and migration if a class mod is replaced.

These are *integration questions*, not just UX considerations. **Only one source of truth** for selected city and career. The existing `/uc register` command is a test harness and should be disabled or bridged once a proven third-party UI is chosen. Never double-pay a starter kit or double-award XP.

## Decision procedure and acceptance tests

First run Class Selection Mod **alone** on a clean Forge 1.20.1 server with two clients, then with our Core commands disabled, then with TaCZ and a candidate skill mod. Follow these acceptance criteria in sequence:

1. Install exact files and dependencies; license/source/sha256 registered; both client and dedicated server start.
2. Fresh accounts show **city then career**, allow Back and confirm; players cannot keep playing with half-finished identity.
3. Choice is permanent through logout/restart; each account stays isolated.
4. Starting gear is granted **once only**. Changing roles, restarting, disconnecting during confirmation, or being killed cannot duplicate expensive TaCZ guns/components.
5. Selection exposes stable IDs or documented commands/API so a city registry, NPC faction, finance mod and profession tree can consume the choice without brittle GUI scraping.
6. Role restrictions work on **modded items/workstations** and show clear denial reasons; players may still buy essential items from other careers.
7. UI screenshots are inspected at 1920×1080 GUI scale 2/3 and 2560×1440 with larger scale: text, icons, scroll, side-by-side descriptions, preview and keyboard navigation.
8. An admin can recover from corrupted/accidental registration without resetting progression, losing inventory, or changing unrelated players.
9. No hidden tradeoffs conflict with the two-player civilian RPG; the system has no unavoidable PvP or fantasy race effects.
10. Save a decision document with **proven version** and test logs before changing the selected technology.

**Suggested responsibility split if successful:** Class Selection Mod owns the UI and initial faction/class identity; Pufferfish's Skills (if compatible) handles visual profession/general skill trees; RPG Core handles only stable city economy/story integration and whatever the existing mods do not already provide. If Class Selection Mod owns role XP as well, adapt or replace the proposed tree owner instead of accumulating XP in two systems.

## Future test automation

A legal/approved local copy of each mod JAR is needed for real Forge integration. CI can already test our own Core and compare candidate manifests but cannot certify third-party mods before installing their exact artifacts. After one provider passes, pin files and run a clean dedicated-server smoke and GameTest in a separate optional integration workflow, with no third-party binaries committed to Git.

Related: [existing-mod-first policy](reuse-first.md), [mod catalog](mod-candidates.csv), [RPG Core](../../rpg-core/README.md).
