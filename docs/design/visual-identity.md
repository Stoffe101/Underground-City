# Visual identity: "municipal enamel"

Status: **implemented in part** (October 17 sprint). Everything below names the file that realises it; anything not yet applied to a screen or asset is listed under "Not yet applied". This document and the code are kept in step: change a colour here and in [`UcUi.java`](../../rpg-core/src/main/java/io/github/stoffe101/undergroundcity/UcUi.java) together.

## Concept

Underground City is a semi-modern, grounded world of rival cities, trades and an underground economy. Its interface is the registry office and the street kiosk, not a sci-fi HUD: **stamped enamel and steel, brass rivets, paper forms, chalk boards and lit terminals**. Three material layers carry the hierarchy:

1. **Chrome** (frame, header, buttons, rail): blue-slate enamel panels with a lit top edge and brass rivets. Quiet, structural.
2. **Wells** (reading surfaces): recessed near-black panels that hold lists, numbers and previews.
3. **Paper** (forms, contract sheets, dialogue): warm cream with ink-brown text. Paper is what the *city says to you* (a registration form, an order sheet); the dark UI is what *you operate*. The contrast makes the two feel different without a decorative gradient.

Every state colour carries a second signal (an icon, a label or a shape), never colour alone.

## Colour language

| Role | Hex | Use |
| --- | --- | --- |
| Legal / unlocked | `#4fd1b8` teal | legal contracts, unlocked skills, "ready" |
| Underground / prohibited | `#e2574c` red (text `#ff8f7e`) | underground branch, restricted goods, danger buttons |
| Money / attention | `#e8b04a` amber | credits, primary buttons, notices |
| Success | `#7bd88f` | completed steps, confirmations |
| Text | `#eaf2f8` / `#b9cbd9` / `#8aa2b5` / `#5b7083` | primary, soft, dim, faint |
| Paper | `#e8dfc6`, ink `#2a2118` | forms and dialogue |
| Ironhaven / Valewood / Blackwater | `#e07b39` / `#86c45a` / `#46a9e0` | city identity (header stripe, banners, chips) |
| Trades | Farmer `#86c45a`, Vintner `#c279d6`, Smith `#e07b39`, Gunsmith `#d9b455`, Technician `#46c9e0` | badges and trade pages |

Panels: surface `#1b2b3b` to `#121d2a`, edge `#34506a`, highlight `#4f7088`, well `#0d1620`. These live in `tools/art/gen_chrome.py` (textures) and `UcUi` (code).

## Typography

* **Body**: Minecraft's own font. It is the most legible face at our sizes and keeps the game's voice.
* **Display**: *UC Display*, a 5 x 7 capitals-and-figures pixel face drawn for this project (`tools/art/gen_font.py` writes `font/uc_display.png`), used for titles, tabs, chips, numbers and field labels. Always upper case (`UcUi.display` upper-cases). Never used for sentences longer than a line.
* Hierarchy: screen title (display, 8 px) / section heading (display, amber or accent) / body (default, soft) / detail (default, dim). No text smaller than the default font.

## Layout rules (responsive, not scaled)

`UcScreen.layoutPanel` sizes one panel from the free GUI area: about 74% of the width and 88% of the height, clamped per screen (registration 430-560 x 290-372, terminal larger). On 1920x1080 at GUI 3 (640x360 GUI units) a panel is about 470x316; on 3440x1440 at GUI 2 (1720x720 units) it grows to its maximum so the screen is used rather than shrunk to a postage stamp, and body lists show more rows instead of bigger text. Text is never scaled by a fractional factor (that smears pixel art); GUI scale stays the player's choice. A screen that cannot fit its content at a small GUI area shows fewer decorations first (the intro line, the banner height), never clips text: `UcUi.fit` ellipsises, `UcUi.wrapped` ends a long paragraph with an ellipsis.

Panel anatomy: 30 px header (icon, display title, status chips on the right) + 2 px accent stripe; body; optional 24 px footer status bar; 10 px padding.

## Components (all in `UcUi`, `UcButton`, `UcScreen`)

* `panel`, `well`, `card` (normal / hover / selected / disabled), `paper`, `bar`, `chip`, `tooltip`.
* `UcButton`: PLATE (neutral), PRIMARY (amber, the one action a screen wants), DANGER (red), RAIL (navigation with icon, label and an optional count), CHIP (filters). Hover brightens and shows a teal lit edge; pressed sinks one pixel; disabled is dark and flat and **explains itself in a tooltip**.
* Icons: 32 white-and-grey 16 x 16 glyphs in `textures/gui/icons.png`, tinted by state colour (`tools/art/gen_icons.py`).
* Feedback: a UI click sound on every press, a level-up chime on registration success; toasts for contract, skill, payment and discovery events (see the report for the ones implemented).

## Item, block and plant art

* **16 x 16** for items and blocks, one resolution everywhere. Each sprite is a recognisable object with a 1 px outline in a dark tone of its own hue, light from the upper left, hue-shifted ramps (shadows cool, highlights warm), and **rarity carried by the drawing** (gold foil, a wax seal, a hot seam, a spark) as well as colour.
* Models are element JSON (Blockbench-compatible), with real depth: a terminal has a stand, screen, keyboard and back; a safe has a door, dial, handle, hinges and feet; the ledger is a board on posts with a roof. Block fronts carry the detail; sides stay calm (row-grain brushed metal, not per-pixel noise).
* Cannabis: flat leaf quads rooted on the stem, 16 x 16 palmate leaf sprites mapped onto 3.5-8.5 unit quads (about 2 x the vanilla texel density, a documented exception for plants), four growth models plus a "fed" variant with a nutrient stake. See "Cannabis" below.
* Sources are scripts in `tools/art/` (Python with Pillow), run with `python3 tools/art/build_all.py`; the generated PNG and JSON are committed so a normal build needs no tools. `tools/art/preview.py` renders block models without a game client for quick iteration; the real client is still the judge (`-PucPlaytest=gallery` photographs plants, blocks and items in the world).

## Cannabis (the Farmer's restricted crop; called Cannabis before D-142)

Drawn to read as a cannabis-like plant in the game's fiction, from general botanical anatomy only: **palmately compound leaves** with an odd number of narrow, pointed, **serrated** leaflets fanned from one point (3 on seedlings, 5 mid-growth, 7 on mature plants), the middle leaflet longest and the outer pair smallest, a paler midrib on each leaflet; leaves held on slender petioles from a straight, ridged stem, opposite pairs turning a quarter-turn at each node, lower leaves large and drooping and upper leaves small and rising; and on the mature plant **flowering clusters (colas)** of tightly stacked bracts with thin pale-orange hairs, one on the stem top and smaller ones on side branches. The four stages differ in height, leaf count, leaf size and leaflet count, not just scale. No cultivation procedure is depicted or implied; the game mechanics stay abstract (items, light and nutrient doses).

## Accessibility and interaction

Teal / red / amber are always paired with an icon or word (LEGAL, UNDERGROUND, LOCKED). No flashing; animation is limited to hover brightening and the trace pads' lit state. Keyboard: number keys select cards, Enter confirms, Backspace goes back, Esc closes; every button is a vanilla `Button` underneath, so focus and narration work.

## Review standard

A screen or asset is called finished only after it has been captured in the real client at 1920x1080 GUI 3 and 3440x1440 GUI 2 (and 1280x720 GUI 2 where practical), **looked at**, criticised and fixed. The sprint report lists what was viewed and what was rejected.

## Not yet applied

Listed in the sprint report (section C and D) for every screen and asset this sprint did not reach.
