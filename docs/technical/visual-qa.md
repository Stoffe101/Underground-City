# Visual QA: real client screenshots, analysis, regression, and cleanup

**Actual status:** the first five-tab Operations Terminal exists as original Forge code in PR #11. The screenshot harness and workflow are now configured, but each CI run must genuinely complete before calling those screenshots visually reviewed.

## Mandatory policy for GUI changes

Every currently integrated Minecraft screen/panel must have a named capture case in [GUI coverage manifest](../../content/qa/gui-coverage.json). The integration includes every tab, every resolution profile, and critical modal or menu state once implemented. The manifest is a maintained source of truth, not a magic discovery of all GUIs from installed mods.

**Coverage scope:** Core has five operations-terminal tabs. All currently *unintegrated* third-party mod GUIs are explicitly listed as pending. A first-login menu, NPC dialogue, TaCZ parts screen, FTB Quests, safe, police briefing, etc. must gain automated client capture when their mod becomes installed and its UI can actually be driven; otherwise **do not claim full modpack coverage**. The workflow fails if any *declared integrated* screenshot is missing.

## Capture pipeline

1. A changed Core UI or visual QA test triggers [Visual QA - real Minecraft screens](../../.github/workflows/visual-qa.yml). Minecraft Forge is launched in a real headless virtual display (Xvfb + software GL) with a client-only harness gated by `-Duc.visualCapture=true`.
2. The harness opens the actual `TerminalScreen` and cycles through five tabs, capturing each rendered Minecraft framebuffer with `Screenshot.grab`, **not a fabricated Pillow mockup**.
3. CI runs at **1920×1080 GUI scale 3** and **3440×1440 GUI scale 2**. If the runner's software-rendered GL environment cannot initialize a client, the job must fail and explain the blocker, **not upload fake images**.
4. [Mechanical checker](../../scripts/verify_gui_gallery.py) verifies PNGs, actual dimensions, content contrast, tabs not being blank/identical, no screenshots outside the manifest, and a JSON report. It is not a semantic, usability, artistic, or pixel-perfect reference comparison.
5. GitHub uploads one short-lived screenshot artifact per resolution profile, with an **absolute one-day retention**.
6. Inspect all actual images: title and label positioning, card spacing, contrast, font sizes, visual hierarchy, modal/clipping, helpful empty states, hover/focus/click behavior, legibility at scales, and consistency with game design. Add issues/corrections and run the screenshots **again** after UI changes. Always judge the *new* artifact, never an old screenshot.
7. After the images have actually been reviewed and issues recorded, run [Delete reviewed visual screenshot artifacts](../../.github/workflows/clean-reviewed-screenshots.yml) with the reviewed **GitHub Actions run ID** and `review_completed=true`. That workflow has `actions:write` only to delete `uc-visual-gallery-*` artifacts from that exact run. It does **not** delete mod JARs, other workflow artifacts, or workflow logs.
8. If no one completes manual cleanup, GitHub automatically expires screenshot artifacts after **one day**. A later rerun updates the review evidence. The pipeline cannot truthfully promise that an assistant visually inspects CI images unprompted in the background.

## Native terminal rendering and tests

The `operations_terminal` is one placeable original low-poly block, with no separately crafted monitor, case, power supply or keyboard. The right-click interaction is server-owned; it sends an explicit **server→client read-only open packet**. Five tabs are native Forge/Minecraft UI: Contracts, Imports, Deliveries, Black Market, and Raids.

Because the backend operations still exist only in source-level simulation, no client-side purchasing or fake listing buttons are enabled. All five tabs clearly announce a preview/offline state. Trading, jobs, shipments, escrow and raids must be implemented with a single server-side economy authority before these panels get working buttons.

## Existing computer reuse research

- **[CC: Tweaked](https://tweaked.cc/)** supports Forge 1.20.1 and a fully placeable programmable computer. It could run small scripted Lua interfaces, and an adapter might expose economy peripherals. **However**, its terminal-focused UI is not ideal for the more graphical market and raid screens, and forcing these interactions through a text console would make UX worse. Keep optional as future programmable computers, not a hard dependency for our civic terminal.
- **[Noish's Furniture: Electronics](https://www.curseforge.com/minecraft/mc-mods/noishs-furniture-electronics)** offers 1.20.1 Forge laptops/monitor blocks and decor, but is **All Rights Reserved** and does not expose a documented public native multi-tab UI API. Do **not** copy, modify, or redistribute its models/code without permission; it can coexist as a separate optional visual-decor candidate.
- [IdeaMineCreator's Gaming Furniture](https://www.curseforge.com/minecraft/mc-mods/ideaminecreator-gaming-furniture) also offers large PC setups, but they're primarily decorations. The user's preferred device is a single static placed PC. A small self-authored 3D block and native Forge screen are the lowest-dependency route.

Nothing about the original compact PC prevents later swapping the **model** for a legally reusable asset; keep the block registry and GUI API stable.

## Remaining QA work

This initial pipeline is a **foundation**. Required next: capture actual in-world placed-block view and right-click handoff, registered first-join screens when integrated, GUI click/tab focus/accessibility, at least one modal and scroll list per provider, real test data cases including long strings/low wallet stock, and baseline comparison after the first images receive visual approval.

Do not delete screenshot evidence *before* inspecting it. Do not keep unnecessary screenshots beyond actual review. The explicit human/assistant visual review loop is still required.
