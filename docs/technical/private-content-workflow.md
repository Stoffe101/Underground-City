# Private content: how to use it, where it lives, how it is checked

Status (October 21, 2026): **the infrastructure is implemented and tested with invented fixtures; no private content has been loaded, ported or tested.** Policy and permission: [private-content.md](../legal/private-content.md) (D-116).

## The idea in one paragraph

Private content lives in a folder **outside** the repository, as one or more Minecraft packs (`data/`, `assets/`, `pack.mcmeta`) each with a manifest. The game is told where the folder is (`-PucPrivateContent=<folder>` or the `UC_PRIVATE_CONTENT_DIR` environment variable) and loads every valid pack in it as an always-on pack at the top of the list, so its Lost Cities parts, palettes, buildings and city styles are read by the game's own loaders. Nothing is copied into the repository, a world or the jar; remove the folder and the content is gone.

## A private pack

```
<private root>\                      (outside every git checkout)
  c70-residential\                   one pack = one folder
    uc-private-pack.json             the manifest (below)
    pack.mcmeta                      a Minecraft pack header
    data\<namespace>\lostcities\...  Lost Cities buildings, parts, palettes, citystyles, worldstyles
    data\<namespace>\...             anything else a data pack holds
    assets\<namespace>\...           textures, models, lang (only if the manifest says assets)
    uc-private-index.json            optional: names, sizes and SHA-256 of every file (made by the tool)
```

```json
{
  "schema_version": 1,
  "id": "c70-residential",
  "title": "Residential buildings (ported)",
  "provides": { "data": true, "assets": false },
  "private_only": true,
  "permission": { "holder": "C70 and team (reported by the owner)", "summary": "private modpack for two players; private ports and modifications allowed", "evidence_kept_by_owner": true }
}
```

A pack is refused, with a logged reason, if the manifest is not valid JSON, has another schema, an id that is not lowercase letters, digits, `-` and `_`, does not say `private_only: true`, does not name who gave the permission and what it covers, or the folder has no `pack.mcmeta`. A private root inside a git work tree is refused unless it is that tree's ignored `private-content` folder. The log lists packs and file counts only.

`python scripts/private_pack_tool.py` makes (`init`), seals (`seal`, `verify`), backs up (`backup`, `restore`) and, for tests, invents (`fixture`) packs. It refuses destinations inside a git work tree and never downloads anything.

## On the owner's Windows 11 computer

1. Make the folder **outside** any checkout: `mkdir $env:USERPROFILE\UndergroundCityPrivate` (not inside `Underground-City\`).
2. Make the pack: `python scripts\private_pack_tool.py init $env:USERPROFILE\UndergroundCityPrivate\c70-residential --id c70-residential --title "Residential (ported)" --holder "C70 and team (reported by the owner)" --summary "private modpack, private ports allowed" --data`
3. Put the content (or its port) in that folder, then `python scripts\private_pack_tool.py seal ...` and `... backup ... $env:USERPROFILE\Backups\c70-residential.zip` (the original download is kept untouched next to it).
4. Run the game: `$env:UC_PRIVATE_CONTENT_DIR = "$env:USERPROFILE\UndergroundCityPrivate"` then `gradlew runClient` (or `gradlew runClient -PucPrivateContent=C:\Users\you\UndergroundCityPrivate`). The log shows `[UC-PRIVATE] pack 'c70-residential' (N files ...)`.
5. Once: `python scripts\install_git_hooks.py`, so that a commit or a push that would carry private content stops.
6. **A new world is needed** for new city generation: never apply changed generation rules to a world you want to keep (the old world is untouched; see [archipelago-world.md](archipelago-world.md)).

## In the Claude Code cloud

The container is **temporary** (reclaimed after inactivity; "anything worth keeping needs to be committed and pushed first", which is exactly what private content must not be). So:

* The owner uploads the private archive in the chat. It lands in the session's upload folder, outside the repository.
* The session unpacks it to a folder **outside** `/home/user/Underground-City` (for example `/home/user/uc-private/`), seals it, and runs with `UC_PRIVATE_CONTENT_DIR=/home/user/uc-private`. The guard keeps it out of every commit. Screenshots of it go only to a private page.
* When the session ends the content is gone from the cloud. The owner's copy is the original. A converted port made in the cloud must be downloaded by the owner (`backup` writes a zip outside the checkout) or it is lost.
* Public CI never has the content and must stay green without it.
* If the owner wants the cloud to be able to pick a port up again in a later session without re-uploading, the option is a **private repository or storage of the owner's own** (never this one): that is the owner's decision about where their private files sit and is not set up here.

## Checking

| Check | Where it runs |
| --- | --- |
| `python scripts/check_private_content.py` (tracked files, `.gitignore`, workflows, history) | CI `Repository checks`; before a push |
| `... --staged` | the `pre-commit` hook |
| `... --base origin/<base>` | what a branch adds on top of its base |
| `PrivateContentGameTests` (invented packs: valid, refused, inside a checkout, missing folder) | every GameTest run |
| `aConfiguredPrivatePackReachesTheGamesData` with `-PucPrivateContent=<fixture>` | the synthetic fixture run in CI: a pack in a folder outside the checkout changes the game's own data (a building-security class appears) |

## What was read from the reference repository, and what was not

Said exactly, so the record is honest. The public repository that holds the reference city-building content is a **Minecraft 1.18.2 server pack** (Forge 1.18.2, its readme says so). On October 21, 2026 the cloud session made a **blob-less, checkout-less clone** (the file tree only, no file contents) to a folder outside this repository, to learn how the content is organised, and read **one** file, a 661-byte building definition, to see the Lost Cities format. A request for more files was refused by the session's permission system and **was not worked around**. The one file was deleted. The tree shows: 218 buildings, 1,149 parts, 1,174 palettes, 25 multi-buildings, 13 scattered structures, 4 city styles and 1 world style under one namespace, with residential, apartment, condo, flat-apartment, convenience-store, cafe, hospital, community-centre, gallery, factory, warehouse, cabin and farmhouse families. **No content was copied into this repository, a world or a log, and none was converted.**

## What porting will involve (from the format and the research brief; not yet done)

* The reference is **1.18.2** data; this game is **1.20.1**. Lost Cities' data format is close, but block ids and block-state properties changed between the versions and have to be translated, palette by palette.
* Its palettes name blocks of several furniture mods (the research brief lists `pfm`, `cfm`, `embellishcraft`, `another_furniture`, `quark`) **and `create`**. Create is excluded from this project: every `create:` block must be replaced by an ordinary or furniture block before a port loads, and anything that needs Create is left out.
* Its buildings were made for a zombie setting: spawners, loot chests, mob markers and loot tables have to be removed or replaced (this game has no ordinary hostile creatures by default, and loot is per [item-source-audit.md](item-source-audit.md)).
* A building with `allowDoors: false` gets its doors from its palettes: the door guard ([building-protection.md](building-protection.md)) reads the placed door blocks, so a ported door is guarded without any change, and the guard's own checks (exterior side, leaf plane) apply.
* Each furniture mod used must be separately licence-checked and, if adopted, pinned as a runtime-only download ([dev-pack.md](dev-pack.md)).
