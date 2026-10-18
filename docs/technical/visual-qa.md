# Visual QA: real client screenshots, analysis, regression, and cleanup

**Actual status:** the first five-tab Operations Terminal exists as original Forge code in PR #11. Cloud captures now run successfully and have been visually inspected. Each CI run still needs its own artifact inspection before cleanup.

## Mandatory policy for GUI changes

Every currently integrated Minecraft screen/panel must have a named capture case in [GUI coverage manifest](../../content/qa/gui-coverage.json). The integration includes every tab, every resolution profile, and critical modal or menu state once implemented. The manifest is a maintained source of truth, not a magic discovery of all GUIs from installed mods.

**Coverage scope:** The manifest covers 36 integrated surfaces per viewport: the three steps of the first-login registration screen (home city, profession, confirm), five terminal tabs, ten contract/exchange states (including the regional exchange, low funds, outside any city, a regional order and a visit-locked order), three installed Q Shop states, the Skills page (a farmer page with every node state, plus a full page for Smith, Gunsmith and Technician built from the real datapack trees), the legal/underground contract filter, and the Technician's signal-trace screen (watching, repeating, running out of time). Other third-party GUIs and Q Shop admin/editor/search variants remain explicitly pending. NPC dialogue (it is chat text today), the order tracker overlay (checked by scripted-player screenshots, not by this manifest), TaCZ parts screen, FTB Quests, the Household Safe's vanilla chest screen (needs an in-world client), police briefing, etc. must gain automated client capture when their mod becomes installed and its UI can actually be driven; otherwise **do not claim full modpack coverage**. The workflow fails if any *declared integrated* screenshot is missing.

## Capture pipeline

1. A changed Core UI or visual QA test triggers [Visual QA - real Minecraft screens](../../.github/workflows/visual-qa.yml). Minecraft Forge is launched in a real headless virtual display (Xvfb + software GL) with a client-only harness gated by `-Duc.visualCapture=true`.
2. The harness opens the actual `TerminalScreen` and `ChallengeScreen` and cycles through 33 controlled screen states, capturing each rendered Minecraft framebuffer with `Screenshot.grab`, **not a fabricated Pillow mockup**.
3. CI runs at **1920×1080 GUI scale 3** and **3440×1440 GUI scale 2**. If the runner's software-rendered GL environment cannot initialize a client, the job must fail and explain the blocker, **not upload fake images**.
4. [Mechanical checker](../../scripts/verify_gui_gallery.py) verifies PNGs, actual dimensions, content contrast, tabs not being blank/identical, no screenshots outside the manifest, and a JSON report. It is not a semantic, usability, artistic, or pixel-perfect reference comparison.
5. GitHub uploads one short-lived screenshot artifact per resolution profile, with an **absolute one-day retention**.
6. Inspect all actual images (hosted runs also print panel-cropped review copies into the job log via `scripts/emit_review_images.py`, decodable with `scripts/decode_review_images.py`, for environments that cannot download artifacts): title and label positioning, card spacing, contrast, font sizes, visual hierarchy, modal/clipping, helpful empty states, hover/focus/click behavior, legibility at scales, and consistency with game design. Add issues/corrections and run the screenshots **again** after UI changes. Always judge the *new* artifact, never an old screenshot.
7. After the images have actually been reviewed and issues recorded, either add the exact inspected run/source/gallery IDs to `content/qa/reviewed-runs.json` (its push triggers source-checked cleanup), or run [Delete reviewed visual screenshot artifacts](../../.github/workflows/clean-reviewed-screenshots.yml) with the reviewed **GitHub Actions run ID** and `review_completed=true`. That workflow has `actions:write` only to delete `uc-visual-gallery-*` artifacts from that exact run. It does **not** delete mod JARs, other workflow artifacts, or workflow logs.
8. If no one completes manual cleanup, GitHub automatically expires screenshot artifacts after **one day**. A later rerun updates the review evidence. The pipeline cannot truthfully promise that an assistant visually inspects CI images unprompted in the background.

## Native terminal rendering and tests

The `operations_terminal` is one placeable original low-poly block, with no separately crafted monitor, case, power supply or keyboard. The right-click interaction is server-owned; it sends an explicit **server→client read-only open packet**. Five tabs are native Forge/Minecraft UI: Contracts, Imports, Deliveries, Black Market, and Raids.

Contracts and civilian trade now have real server-backed actions. Economy readiness, job level, held goods, shared demand and accepted order are read-only server snapshots. Shipments, escrow and raids remain unavailable. Selected tabs have a teal indicator; missing goods and locks are shown explicitly.

## Existing computer reuse research

- **[CC: Tweaked](https://tweaked.cc/)** supports Forge 1.20.1 and a fully placeable programmable computer. It could run small scripted Lua interfaces, and an adapter might expose economy peripherals. **However**, its terminal-focused UI is not ideal for the more graphical market and raid screens, and forcing these interactions through a text console would make UX worse. Keep optional as future programmable computers, not a hard dependency for our civic terminal.
- **[Noish's Furniture: Electronics](https://www.curseforge.com/minecraft/mc-mods/noishs-furniture-electronics)** offers 1.20.1 Forge laptops/monitor blocks and decor, but is **All Rights Reserved** and does not expose a documented public native multi-tab UI API. Do **not** copy, modify, or redistribute its models/code without permission; it can coexist as a separate optional visual-decor candidate.
- [IdeaMineCreator's Gaming Furniture](https://www.curseforge.com/minecraft/mc-mods/ideaminecreator-gaming-furniture) also offers large PC setups, but they're primarily decorations. The user's preferred device is a single static placed PC. A small self-authored 3D block and native Forge screen are the lowest-dependency route.

Nothing about the original compact PC prevents later swapping the **model** for a legally reusable asset; keep the block registry and GUI API stable.

## Remaining QA work

This initial pipeline is a **foundation**. Required next: capture actual in-world placed-block view and right-click handoff, registered first-join screens when integrated, GUI click/tab focus/accessibility, at least one modal and scroll list per provider, real test data cases including long strings/low wallet stock, and baseline comparison after the first images receive visual approval.

Do not delete screenshot evidence *before* inspecting it. Do not keep unnecessary screenshots beyond actual review. The explicit human/assistant visual review loop is still required.

## First operational third-party GUI dependency

The current terminal's Imports panel includes an **OPEN CIVILIAN SUPPLY SHOP** button only when launched from a physically placed PC; clicking it asks the server to validate the block and citizen, then opens Q Shop's *own* civilian shop window if installed. The capture harness renders controlled read-only snapshots and Q Shop's actual installed ShopScreen, opening its low-funds trade modal through a public mouse event. Main/funded, zero-funds and modal states are included. They prove rendered UI, not transactions; dedicated runtime tests and the cloud client journey cover gameplay separately. Q Shop's public coordinate conversion handles its own zoom independently of Minecraft GUI scale.

## October 14 visual review

Inspected actual framebuffer captures at 1920×1080 GUI3 and 3440×1440 GUI2. Initial captures revealed a clipped long shop name, truncated per-cell currency text and a cropped modal limit summary. Original config now uses short **Supply**/**Cr** names; original English overrides use compact world/personal limit labels. Core adds a selected-tab indicator and an explicit missing-material count. Revised captures show readable labels, separate action/status/footer spacing, disabled delivery for missing materials, disabled level-locked orders and disabled offline economy. No protected Q Shop textures or implementation were modified.

Native terminal and Q Shop fixtures render from the main menu, with no world or fake transaction outcome. The client starts capture on render ticks (client gameplay ticks can wait for a loaded world), verifies asynchronous screenshots before marking completion, and exits cleanly. Hosted jobs retain galleries for one day and failure diagnostics separately for three days. Review every new gallery before deletion.

The final reviewed hosted run was [38642758272](https://github.com/Stoffe101/Underground-City/actions/runs/38642758272), source `05de987e5c2872f722c84395346e71b3fd821194`: all 26 captures were inspected. The required top-priority original text resource pack resolves the previously clipped limit summary. Recorded cleanup selects exact reviewed gallery IDs, verifies their workflow/source commits before deleting, and preserves diagnostics/build artifacts.

## October 15 visual review (regional exchange)

New states were captured **locally in the cloud container with the real Forge client** (Xvfb + software OpenGL) at 1920x1080 GUI 3 and 3440x1440 GUI 2, then the actual full-resolution PNGs were viewed. Passing the mechanical checker (18/18, distinct, non-flat) came first; the review then found and fixed:

- the exchange heading mixed case ("Ironhaven EXCHANGE") - now fully uppercase like every other heading;
- the subtitle and first row crowded each other (9 px apart) - rows now start 15 px below the subtitle and the fourth row (which would have clipped the panel) was removed; an overflow note names hidden lines;
- an unaffordable line gave no reason except a tooltip - it now reads `NEED n MORE` in the warning colour, sold-out lines are red, and disabled buttons are visibly dark;
- the Imports copy wrapped a single word ("city.") onto its own line - shortened;
- inconsistent capitalisation in the footer (`farmer`, `valewood`) - now `Farmer L3 | 120 credits`, `Valewood | at Ironhaven`.

Reviewed unchanged-layout regressions: accepted/ready contract, regional contract with 2/4 goods held, visit-locked contract, outside-any-city Imports. All text fits at both scales. At 3440x1440 GUI 2 the fixed 420x268 panel is small on an ultrawide display; that sizing is pre-existing and legible at native resolution. This local review does **not** correspond to a hosted artifact ID, so it is not recorded in `content/qa/reviewed-runs.json`, which lists only artifacts actually downloaded and inspected.

### Native city tour lessons (real frames)

The first hosted tour frames passed the automated blank/duplicate check but were poor evidence: a camera aimed mostly at sky with the ground beyond the six-chunk render distance. Viewing the actual PNGs also revealed that Lost Cities chunks can report unprimed heightmaps (-64), which put a top-down camera underground, and that queue-idle is not a reliable "terrain meshed" signal. The tour now samples real blocks for camera height and waits for a stable rendered-section count before each of six shots (a top-down view and an oblique skyline per city).

### Bridge and road frames

The native tour also reads the survey report to find the widest water crossing and the longest ground stretch on each built road, and photographs three views per road: the bridge from 75 blocks to the side and 14 above the deck, a walker's view from the deck 60 blocks before the middle of the crossing, and a walker's view along the plain road. These frames, like the city frames, are review copies printed into the hosted job log and one-day gallery artifacts; the automated check only rejects blank, wrong-size, unexpected or duplicate images. Whether a bridge looks right is decided by looking at it.

Looking at the first local frames found two things the numbers could not: the parapet walls stood as unconnected posts (they are placed without neighbour updates; a second pass now joins them), and a high, steep oblique camera spent more than half the frame on the unrendered sky beyond the render distance (the camera is now low and far, with render distance 8 for every tour shot, and the bridge frame waits for at least 100 rendered sections: the first hosted road frame was captured while the larger view was still meshing).

Review copies in the job log are quantized (64 colours) and cropped to the panel, so they under-represent colour accents such as the teal tab indicator and warning text; judge colour from a full-resolution capture, and layout, clipping and wording from the copies. The crop is chosen for the terminal panel, so at 3440x1440 the installed Q Shop screens (a different size and position) appear only partly in their review copies (for example the bottom of its trade modal is cut off); judge those from the full-resolution gallery artifact or a local capture.


## October 16 visual review (skills, trace, contract filter)

New surfaces: the Skills page for all four careers, the legal/underground contract filter and the three trace states. Captured locally on a real Forge client (Xvfb + software OpenGL) at 1920x1080 GUI 3 and 3440x1440 GUI 2, 33 PNGs per resolution, and the hosted visual job passes the same 33-surface assertion. All full-resolution PNGs of the changed surfaces were viewed, in three rounds. What the first round found (the mechanical checker passed all of it):

- long node titles clipped at the button edge ("Controlled-Crop Licence", "Greenhouse Management"); every node now has an optional `short_title` in the datapack (at most 20 characters, shown only when the full title does not fit) and an ellipsis fallback so that nothing is silently cut;
- the skill detail text overflowed the panel and ran over the unlock button; the node grid and detail block were tightened (15 px row step) so the description, its requirement line and the button each have their own space;
- the selected contract filter ("LEGAL" or "UNDERGROUND") was only visible through a faint colour change; the selected filter now has an underline in its own colour;
- in the trace screen, a dark digit with a dark drop shadow on the lit pad smudged into a blob; the digit is now drawn unshadowed;
- the trace pads are numbered 1-4 and also have four distinct colours, so the puzzle never depends on colour alone, and the keys 1-4 work.

The second round showed all four career pages inside the panel at both resolutions; a third pass found that widening the margin between a node's marker and its text made "Grey Market Contacts" end in an ellipsis, which was corrected with a short title ("Market Contacts") and "Unstamped Production" (tight against the marker) became "Unstamped Output"; a fourth capture at both resolutions confirmed all four career pages without an ellipsis or clipped label. The layout is the same in GUI units at both scales, so 1440p confirms the geometry rather than finding new problems; at 3440x1440 GUI 2 the fixed 420x268 panel is small on an ultrawide display, which is the pre-existing sizing noted in October 15.

Not covered: no captured screen has been clicked in CI (the harness poses each state), the trace screen's timing is only seen through frozen frames, the Household Safe's vanilla chest screen and any future first-login or NPC dialogue screen have no capture, and the images were judged by looking, not against a pixel baseline. These local reviews do not correspond to a hosted artifact ID, so they are not recorded in `content/qa/reviewed-runs.json`, which lists only artifacts actually downloaded and inspected.

## October 18 review: the Crew screen and the survival frames

The Crew screen (12 poses, `uc-crew-*`) was captured with the shared harness at 1080p (GUI scale 3), 1440p ultrawide (scale 2) and 720p (scale 2), 53 surfaces each, and the Crew frames were opened and read. Defects found by looking and fixed: role chips drawn over the last-seen time, truncated member names and subtitles, a section heading hidden behind a row, a no-Crew form overlap, clipped consent and disband text, and a consent banner cut off mid-sentence. The final text was captured again at 1080p and 720p. A defect that **no frame could show** was found by the two-client journey instead: typing never re-evaluated the buttons (the captures preset the text). Survival frames (HUD with the thirst bar, inventory, the backpack's own screen) come from the scripted player (`-PucPlaytest=survival`), not from the capture harness. These are local reviews and do not correspond to a hosted artifact ID, so they are not recorded in `content/qa/reviewed-runs.json`.

### Follow-up review (PR #23, October 18): shared finances, spending rights and the wayside desk

Four new poses join the Crew and terminal sets (`crew-money-shared`, `crew-money-shared-nospend`, `crew-rules-mode-active`, `exchange-wayside-desk`; 57 states in all, listed in `content/qa/gui-coverage.json`). The whole set was captured again with the final code at **1280x720 (GUI scale 2), 1920x1080 (scale 3) and 3440x1440 (scale 2)** and the money, rules, home, members, consent and wayside frames were opened and read. Defects found by looking and fixed in this pass: the Rules page's three mode cards had their Choose buttons straddling the card borders; the consent banner needed three lines; **"Q" was left alone at the end of a line and "Shop's" started the next** (the wrap helper now keeps the product name together); the wayside desk's subtitle ran past the panel's right edge (it is shortened and fitted to the room it has). **Limit:** the wayside frame is drawn with stand-in item icons (a potion and leather, labelled so) because the capture harness does not load the optional survival mods, so the real waterskin and water-bowl icons have **not** been inspected, and every judgement here is mine, not the owner's. As before, the frames are local review copies, not a hosted artifact.
