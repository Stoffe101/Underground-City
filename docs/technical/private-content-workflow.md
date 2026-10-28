# Private content: how to use it, where it lives, how it is checked

Status (October 26, 2026, ninth pass): **the owner's private pack has been ported, wired into Valewood and generated in a disposable world in the cloud.** It was never committed, uploaded or shown on a public page. Public CI still runs only the invented fixtures. Policy and permission: [private-content.md](../legal/private-content.md) (D-116). Results: [the ninth-pass report](../production/ninth-pass-c70-critical-fixes.md).

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

## The porter

**D-131, D-192. Implemented. Unit-tested on invented fixtures (`tests/test_port_lostcities_pack.py`, 10 cases). The owner's pack was ported with it in the ninth pass (above).**

`python scripts/port_lostcities_pack.py port <private pack> <new folder> [--registry block-registry.json] [--mapping map.json] [--keep-mod NS]` reads a private pack (outside every checkout, with a `private_only` manifest) and writes a converted pack beside it with its own manifest (`<id>-port`), a 1.20.1 `pack.mcmeta` and `uc-port-report.json` (counts and block ids only). Every palette entry (the palette files and the inline palettes of parts) is converted: 1.18.2 ids that changed are renamed; every `create:` block is replaced (structural blocks by their nearest vanilla look, machinery by air; a mapping file decides first); a block of a mod that is not kept is replaced by air and listed; spawners become air and `mob` markers and `loot` tables are dropped (the chest stays, empty). With `--registry` (dumped by a real 1.20.1 game: `CityProbe --registry`) every resulting block is checked and a port with problems is marked **not loadable** (exit code 1). Slices, buildings, multibuildings and city styles are copied unchanged; world styles are copied and counted for a person to review. `python scripts/port_lostcities_pack.py fixture <dir>` makes the invented test pack.

### What the owner needs to provide, and the steps

1. The private archive, uploaded in the chat or kept on the owner's computer (never in this repository): the 1.18.2 pack's `data/<namespace>/lostcities/` folder (buildings, parts, palettes, multibuildings, city styles, world styles) is what the porter reads.
2. The list of furniture or decoration mods its palettes name, with their versions (the porter's report lists every namespace it had to replace). For each one the owner wants kept: a licence check and a pinned runtime-only download ([dev-pack.md](dev-pack.md)), then `--keep-mod <namespace>`; otherwise a mapping file of replacements.
3. `private_pack_tool.py init` a pack folder for the original, `port_lostcities_pack.py port` into a second folder, then a registry dump from a game with the kept mods and a second `port --registry` until the report says loadable.
4. A **new world** with the ported pack loaded (`UC_PRIVATE_CONTENT_DIR`), a city probe and photographs that stay private. Wiring a ported building into a city (a `city_character` rule naming its building ids) is the owner's choice and is done in the private pack, not here.

## One command (eleventh pass, D-205)

**Implemented. L1: `tests/test_private_c70.py` (16 cases on invented packs) and `tests/test_windows_scripts.py` (the launchers under PowerShell 7 on GitHub's Linux runners). Private, in the cloud: the owner's original staged in 6 seconds with the registry dumped earlier. Not run on Windows.**

The tested workflow below, run by one tool: `python scripts/private_c70.py stage --source <original folder or zip> --out <runtime folder>` (on Windows `scripts\windows\uc-private-stage.ps1 -Source ...`, whose default `--out` is `%USERPROFILE%\UndergroundCityPrivate\runtime`).

1. **The source.** A folder or a zip. A zip's members are checked before anything is unpacked (no absolute path, drive or `..`, no link, at most 200,000 members and 2 GiB). The folder that holds `data/<namespace>/lostcities/buildings` is found up to four levels down, as in a server pack, and only the `lostcities` data under it is copied, unchanged, into `<runtime>-work/original`. A server pack's loot tables, recipes and spawn rules are never copied. The manifest is the original's own, or one made from `--holder` and `--summary`, the owner's statement, asked for once and reused at the next staging.
2. **The registry.** `--registry`, or the one dumped at the last staging while the pinned mods of the world, furnished and furniture sets are the same (`registry.key`), or a new dump: a disposable dedicated server through `run_city_probe.py --registry` (`gradlew.bat` on Windows) with no private content loaded.
3. **The port**, keeping the furniture set's mods. A port that is not loadable stops here. One case is a registry that has no block of a kept mod at all, as a registry dumped without the furniture set would be. Nothing is installed.
4. **The selection.** `--selection <file>` is used as written. Without one, `auto_select` chooses from each building's id and plan:
   * a use from the words of its id (house, flats, workshop, warehouse, office, public building, shop, club; "public" is never a pub);
   * a city by use (D-193: Valewood residential, Ironhaven industry and flats, Blackwater offices and towers; shops and public buildings in turn);
   * ring weights, a family's rotations shared out when a use has fewer families than cities, and the public ladder's terms for a listing of the same category;
   * the public generic houses and office left out where private ones of that use stand in every ring of that city, and the public functional buildings kept at weight 1.

   A building whose id names no known use is left out and listed with the reason, and so is a house without a bed, a street door or a roof, flats without a storey with a bed, and a building whose parts use a character no palette defines. Staging stops when nothing could be chosen.
5. **Wire, seal, record.** The pack gains `uc-private-stage.json`: the porter's and the wiring's versions (`PORTER_VERSION`, `WIRING_VERSION`), the sets it needs, Core's commit and counts.
6. **Install** as `<runtime>/c70-port`. The pack it replaces moves to `<runtime>-work/previous`; the last three are kept.

`python scripts/private_c70.py check <runtime folder> --sets <sets>` is what every Windows launcher runs before it passes `-PucPrivateContent`. It refuses, with the reason:

* a folder that is missing, inside a checkout (except the checkout's ignored `private-content` folder, as the game itself allows), or a pack itself (the photo run 12 mistake);
* a manifest the game would refuse;
* an unported original: a 1.18.2 pack format, or Lost Cities buildings without the porter's report;
* a port its own report calls not loadable;
* a pack staged by an older porter or wiring than this checkout's minimums;
* a launch whose sets leave out one the pack needs.

A file that differs from the seal is a warning. A hand-made port without `uc-private-stage.json` loads with a warning, because its versions cannot be checked.

On the owner's original in the cloud, the automatic selection chose every building the hand-made C70-first selection had chosen and 26 more, and left out 24 for the reasons above. The port's palettes and parts were identical to the port that photo run 13 walked through. Only the plans of the newly chosen buildings and the city files differ.

### What changed in the porter and the wiring (eleventh pass)

* **Porter 12: Create's stones are no longer holes.** The owner's pack used Create's decorative stones (cut, polished cut, layered, bricks, slabs, stairs and walls of calcite, scoria, dripstone, diorite, tuff, deepslate and granite) for walls, floors and steps. Every one that the old rules did not name became air: 87 kinds of Create block in all, about 800 uses of these stones, so walls of cut calcite were generated as gaps in the owner's houses. Each stone now becomes the vanilla block of the nearest look in the same shape, keeping a slab's half, a stair's facing and a wall's sides. A whole glass block stays whole and a seat becomes a carpet. Machinery is still air. The port was checked against the real 1.20.1 registry: loadable, no problems.
* **Wiring 12: dwellings** (D-206, [dwellings.md](dwellings.md)): a multi-chunk building's homes are read from its rooms and written to `city_dwellings`; a single block's flats are let one by one. `uc-private-stage.ps1 -Dwellings <file>` names the dwellings of a building yourself.
* **Porter 13 and wiring 13: a building of one structure is built once.** Lost Cities 7 builds storeys 0 to `floors` and puts a part with no conditions up on every one of them, cellars included. Porter 12 had also raised such buildings to two floors. In a private generated world a cabin on its own mound came out as five copies, six blocks apart, and each copy's mound buried the bedrooms of the one below: four of its five bedrooms were full of dirt, with their beds' data left behind on the dirt. 25 of the owner's 218 buildings are one part with no conditions (cabins, farmhouses, a tree house, a food truck). They now get storey 0 alone and no cellars unless the building names them, and the wiring reads them the same way. The staging check asks for porter 13 and wiring 13, so stage again.
* A launch refuses a pack staged by porter 11 or wiring 11: stage again.

## The tested workflow (ninth pass, D-192)

1. **Validate the archive and extract it outside the checkout.** Check its paths and integrity first, and keep the original untouched in its own folder.
2. **Dump the real registry.** Boot a disposable dedicated server with the furniture set:

   ```text
   python scripts/run_city_probe.py --name reg --sets world,furnished,furniture --registry <out>/registry.json --namespaces minecraft,modernlife,underground_city,embellishcraft,mapperbase,pfm,another_furniture,simplylight,buildersdelight,cfm,farmersdelight --output <out>/probe.json --radius 1 --cities valewood
   ```
3. **Port:**

   ```text
   python scripts/port_lostcities_pack.py port <original> <port> --registry <out>/registry.json --keep-mod embellishcraft --keep-mod mapperbase --keep-mod pfm --keep-mod another_furniture --keep-mod simplylight --keep-mod buildersdelight --keep-mod cfm --keep-mod farmersdelight
   ```

   The report must say `loadable: true`. What the port does:
   * A block of a mod that is not loaded becomes its nearest look (`block_lookalikes.py`, the public rules in `content/integrations/private-import/block-lookalikes.json`), else air, and is listed.
   * A block that a kept mod dropped by 1.20.1 becomes its sibling in the same mod.
   * A property the mod renamed is renamed; one 1.20.1 does not accept is dropped and listed.
   * **World styles are left out.** Lost Cities 7 loads them as a registry, and the 1.18.2 one named another mod's biomes, so the first private probe could not load its world at all.
4. **Wire.** Write a selection file beside the pack. It names each building, its city, its ring, its use, its security class, an optional listing and an optional shop counter. Then run `python scripts/private_wiring.py wire <port> <selection.json>`. This writes, inside the private pack:
   * the building's plan (doors, beds, stoves, seats, counters and a home's box, read from its own parts);
   * the city's building choice (the public file plus the chosen buildings);
   * a security rule, a listing and a counter.

   `plan` shows one building's plan without writing anything.
5. **Generate in a new disposable world:** `python scripts/run_city_probe.py ... --sets world,furnished,furniture --private <folder holding only the port>`. The log shows:
   * the pack loading;
   * the resource stack (the private city file above the furnished package's);
   * how many of the chosen buildings generated.
6. **Walk it through a real client:** `-PucPlaytest=building -PucPtBuilding=<namespace:building> -PucPtCity=<city> -PucPrivateContent=<folder> -PucPack=...,furniture`. This:
   * finds the nearest generated copy;
   * logs what the game makes of it;
   * photographs it outside and on every floor, keeping the frames in the run folder (private);
   * tries its street door and picks its lock on the real screen.

## What porting will involve (from the format and the research brief)

* The reference is **1.18.2** data; this game is **1.20.1**. Lost Cities' data format is close, but block ids and block-state properties changed between the versions and have to be translated, palette by palette.
* Its palettes name blocks of several furniture mods (the research brief lists `pfm`, `cfm`, `embellishcraft`, `another_furniture`, `quark`) **and `create`**. Create is excluded from this project: every `create:` block must be replaced by an ordinary or furniture block before a port loads, and anything that needs Create is left out.
* Its buildings were made for a zombie setting: spawners, loot chests, mob markers and loot tables have to be removed or replaced (this game has no ordinary hostile creatures by default, and loot is per [item-source-audit.md](item-source-audit.md)).
* A building with `allowDoors: false` gets its doors from its palettes: the door guard ([building-protection.md](building-protection.md)) reads the placed door blocks, so a ported door is guarded without any change, and the guard's own checks (exterior side, leaf plane) apply.
* Each furniture mod used must be separately licence-checked and, if adopted, pinned as a runtime-only download ([dev-pack.md](dev-pack.md)).
* A building's palette is merged over the city style's in its chunk, so a palette (or a part's inline palette) that gives one of Lost Cities' style characters (`y w S b B x + Z 9 v`: retaining walls, streets, park ground, corridors, spheres, bridge supports) a block of its own redraws those around the building. Our own buildings walled raised lots with sofas and beds this way until October 23 ([furnished-cities.md](furnished-cities.md#how-it-is-checked-d-119)). Since the ninth pass the porter **renames** them. Each style character a pack's palette takes is given a free character instead, in that palette and in the slices of every part that reads it. A part that uses the character without any palette of the pack defining it means the style's block, and keeps it. The report lists the renaming under `style_characters_renamed_to`; `style_characters_redefined` keeps only what is left, normally nothing. The owner's pack had taken all ten characters in 1,018 palettes; 993 parts were renamed.
* A 1.18.2 pack's city styles merge a shared palette under every building, and its parts lean on it: the owner's pack's styles all use one `common` palette, and 218 of its buildings take characters from it that no palette of their own defines. This game's city styles replace the pack's, so such a character fell through to Lost Cities' own style palette or to nothing (measured, October 27):
  * `!`, which the pack meant as a spawner (air after the port), became Lost Cities' vault block: iron with some diamond, gold and emerald blocks (175 diamond, 224 gold and 158 emerald blocks in the C70 building chunks of one pre-generated world);
  * `z` and `=` became stone-brick stairs and smooth-stone slabs instead of stone and concrete;
  * `H` is defined by no palette of our styles: Lost Cities threw ("Could not find entry 'H' in the palette for part ...") and left all 68 lots of one factory empty.

  Since then the porter **gives** each building the characters its parts take from the pack's own style palettes (`styles/*.json`) when no palette of the building or of those parts defines them (618 characters to 218 buildings in the owner's pack). The building's palette sits between the style's and the part's, so a part's own definition still wins. A building that already references a palette gets a copy of it with the additions (Lost Cities reads a building's inline palette *instead of* its referenced one). Lost Cities' street characters (`y w S b B x + Z 9 v`) stay the city style's. A character no palette of the pack defines is listed under `buildings_with_undefined_characters` in the report: such a building's lot stays empty, so leave it out of a selection. Inline palettes are read in both forms Lost Cities accepts (`"palette": [...]` and `"palette": {"palette": [...]}`), by the porter and by `building_audit.py`.
