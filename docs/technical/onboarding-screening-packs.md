# Isolated first-login mod screening packs

**State:** Three generated pack *manifests*, not verified compatibility or working cities.

The workflow [Onboarding candidate profiles](../../.github/workflows/onboarding-screening.yml) builds three standalone CurseForge-format ZIP imports from the [pinned candidate registry](../../content/modpacks/onboarding-candidates.json). Each manifest references **one exact original-author mod file** and the launcher fetches the binary from CurseForge. None of the mod binaries or other creators' map assets are stored in our repository.

## Candidate profiles

| Pack | Pinned file | Test purpose |
| --- | --- | --- |
| Class Selection Mod 1.6.0 | [8602401](https://www.curseforge.com/minecraft/mc-mods/class-selection-mod/files/8602401) | Faction then class flow, potential TaCZ support |
| Class Enhancement 2.6 | [8467070](https://www.curseforge.com/minecraft/mc-mods/class-enhancement/files/8467070) | Configurable class/career GUI, no built-in city stage |
| Role Selection 1.0.1 | [8719333](https://www.curseforge.com/minecraft/mc-mods/role-selection/files/8719333) | One-time role picker, starter kits and initial protection |

All target Minecraft **1.20.1 / Forge 47.4.10**. The pinned release metadata is evidence of availability, not proof the mods work with one another or our Core.

## Getting the generated profiles

Open GitHub Actions, choose the latest green **Onboarding candidate profiles** run, and download its screening artifact. Unzip that artifact to obtain three separate CurseForge-format ZIP imports. Import **one at a time**, in a new launcher instance and disposable save, using a launcher that supports CurseForge ZIP imports. Your specific launcher compatibility has not been verified.

Or generate them locally with Python 3.11:

    python scripts/build_onboarding_candidates.py --output build/onboarding-screening

CI validates the original city/career catalog, the exact pinned file IDs and official source URLs, zip consistency, deterministic output, and exclusion of bundled third-party binaries. The existing modpack auditor then re-parses each generated ZIP.

## Candidate configurations included automatically

The **Class Selection Mod** ZIP now includes an *original* Forge config at:

    overrides/config/classmod-common.toml

It preconfigures our three city factions, chooses vanilla Minecraft icons, and disables the fantasy race step. This mod's actual class definitions are managed by its in-game administrator interface, so its default class selection is **not yet our four professions**. We will not pretend otherwise.

The **Class Enhancement** ZIP includes an original, minimal Forge config:

    overrides/config/class_enhancement.toml

It disables the fantasy default classes, enables four custom class slots, and defines our Farmer, Engineer, Gunsmith and Technician choices, their original descriptions and vanilla-item icons. Forge may add other missing defaults on initial launch, which is expected. A copy of the original profession fragment also lives at:

    overrides/underground-city/reference/class_enhancement_classes.toml

The **Role Selection** ZIP intentionally remains at default behavior: its per-role JSON5 schema is not yet grounded in generated runtime files and should not be guessed.

These configurations are independently authored for local private testing. An accepted config is **not evidence** that the GUI is readable or that the selected identity is interoperable with another mod's faction/economy state.

## Why RPG Core is not in these profiles

The experimental RPG Core presently prompts for its own registration. Installing it with an unrelated selection mod before testing an integration adapter risks **two screens, two identity stores, and duplicate rewards**. These imports are isolated *screening packs*, not playable Underground City instances.

## Acceptance gate before picking a mod

- Launch and quit/restart a disposable dedicated server with the pinned mod.
- Connect two distinct players and record first-join UI and independent saved selection.
- Verify that disconnects, deaths and role changes cannot duplicate starter kits.
- Validate registration and career IDs are available through a supported server command/API.
- Test any configured restrictions for modded items and workstations.
- Inspect the UI at multiple GUI scales and perform a safe rollback test.
- Later, introduce TaCZ, a skill tree and the chosen economy one at a time.

This workflow does not claim any such gameplay test has already passed. Private use does not automatically permit redistribution of original third-party mod binaries.

Related: [onboarding research](onboarding-reuse-spike.md), [test matrix](../production/onboarding-tests.md), and [reuse-first policy](reuse-first.md).

## Automated optional headless runtime checks

The [Forge third-party onboarding smoke workflow](../../.github/workflows/onboarding-compatibility.yml) provides a second tier of testing. It reads the **same pinned candidate registry** as the launcher ZIP generator, then executes a separate Forge 1.20.1 GameTestServer job for each candidate. The Gradle dependency resolves from CurseMaven and is used only temporarily on GitHub's runners. No copied mod JAR is committed or uploaded in a workflow artifact.

These headless tests can detect unresolved dependencies, classloading errors, dedicated-server crashes, and compatibility failures with our experimental Forge Core. **They do not prove** menu functionality, modded-item behavior, actual two-client gameplay, or the desirability of any specific profession UI.

A failed candidate job is a useful result: review its logs and pin an alternate legitimate release or decide to reject that candidate. Do not silently bypass that failure by reporting the whole shortlist as compatible.
