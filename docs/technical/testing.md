# Testing and evidence strategy

Cloud Forge runtime tests exercise the starter economy, the regional exchange and the Household Safe on a dedicated server (54 GameTests with real Q Shop as of October 15); real-client screenshot tests cover 18 controlled interface states per viewport. Two actual cloud clients trade, buy from the regional exchange and reconnect after a dedicated restart; native blocks are observed at all three city anchors, a dedicated-server survey reads the real blocks of two generated inter-city roads, and the native client tour renders real city frames plus two views of each bridge. These are not two-human-client or complete modpack acceptance. See [October 19 evidence](../production/oct19-test-readiness.md).

| Level | Evidence required |
| --- | --- |
| Repository | Required docs, local links, candidate metadata and unsafe committed binary checks |
| Content | Schema, prereqs, recipes, pricing, loot, contracts, localization |
| Unit | Tax timing, reward idempotency, ledger conservation, XP caps, migration |
| Client/server | Exact mod versions, clean Forge boot, two clients connect, rejoin and restart |
| Gameplay | Registration, early production, Blueprint unlock, shop trade, first tax, Lootr and NPC quest |
| World | Import seam inspection, pathfinding, collision, physical loot, backups and rollback |
| Performance | TPS, memory, tick duration, active NPC count, FPS baseline |
| Visual UX | Screenshots of empty/locked/full/overflow states and GUI-scale checks |

## Non-negotiable examples

A contract only pays once. A reconnect does not regrant the starting kit. Two players' personal loot and skill XP are independent. No tax accrues offline. No critical progression depends on an absent human profession. Incompatible mods do not silently corrupt the only save. City AI does not attack citizens without a defined rule.

Store actual test evidence with commit SHA, Java/Forge/mod versions, server logs (redacted), steps, outcomes, screenshots and rollback notes. Keep raw player data and private saves out of public Git.

## Evidence from initial implementation

On October 14, 2026, Forge 47.4.10 compiled the initial Core in GitHub Actions and the headless GameTestServer reported **1 required test passed**. The test checked independent citizen records and a save-data NBT roundtrip; it did **not** test dedicated-server restart or two connected Minecraft clients. Offline Python tests verify packaged/exported modpack readers and pure-Java progression behavior. Real multiplayer and third-party mod compatibility remain open tasks.

## Cloud development loop (no personal computer)

Everything below runs in the Claude Code cloud container or on GitHub-hosted Ubuntu runners.

- **Build and GameTests:** `gradle -p rpg-core build` then `python scripts/install_qshop_templates.py --server-root rpg-core/run-economy` and `gradle -p rpg-core runGameTestServer -PucEconomyTest=true -PucRunDir=run-economy` (about two minutes warm; Maven Central occasionally answers HTTP 429 to a cold parallel build - retry with `--max-workers=2`).
- **Repository checks:** `python -m unittest discover -s tests -p 'test_*.py'`, `python scripts/validate_repo.py`, `python scripts/generate_regional_content.py --check`, and the pure-Java geometry test: `javac -d /tmp/uc rpg-core/src/main/java/io/github/stoffe101/undergroundcity/CorridorPlan.java rpg-core/src/test/java/io/github/stoffe101/undergroundcity/CorridorPlanHarness.java && java -cp /tmp/uc io.github.stoffe101.undergroundcity.CorridorPlanHarness`.
- **Road and seed tools (disposable `run-world-survey` only):** `-PucWorldSurvey=true` generates the road chunks on worker threads and reads the real blocks (`roads` section of `world-survey.json`); `-PucSeedScan=1,10000,300` ranks seeds in two stages (climate sampler, then real surface/ocean-floor heights along the roads) and writes `seed-scan.json`. A chunk ticket is used instead of `ServerLevel.setChunkForced`, which generates on the server thread and trips the 60 s watchdog.
- **Native client under Xvfb with software OpenGL** (`LIBGL_ALWAYS_SOFTWARE=1 GALLIUM_DRIVER=llvmpipe MESA_GL_VERSION_OVERRIDE=3.3 ALSOFT_DRIVERS=null SDL_AUDIODRIVER=dummy`, `xvfb-run -a -s "-screen 0 1920x1080x24"`): `gradle -p rpg-core runClient -PucEconomyTest=true -PucVisualCapture=true -PucVisualScale=3 -PucVisualWidth=1920 -PucVisualHeight=1080` writes the GUI gallery to `rpg-core/run/screenshots`. Local full-resolution captures can be reviewed directly; hosted runs print reviewable copies instead (below).
- **Lost Cities locally:** CurseMaven is not reachable from every cloud network, but the pinned 1.20-7.5.5 release is on Modrinth Maven (`maven.modrinth:the-lost-cities:1.20-7.5.5`). A Gradle init script that adds that repository and `runtimeOnly fg.deobf(...)` reproduced the hosted survey numbers exactly (1,040 / 1,505 / 1,492 window blocks), so local results are comparable with CI. The jar is never committed or uploaded.
- **Reading CI evidence when artifact downloads are blocked:** `scripts/emit_review_images.py` prints bounded, downscaled copies of screenshots (plus full-resolution statistics) into the job log; `scripts/decode_review_images.py <saved-log> --out <dir>` rebuilds them. The hosted world and visual workflows do this after capture. These are *review copies*, not the galleries; the uploaded one-day artifacts remain the full-resolution record.

## Evidence from the October 14-15 passes

- **Hosted runs on head 1718ee3 (all green):** [Forge build](https://github.com/Stoffe101/Underground-City/actions/runs/38676038014), [26 economy GameTests](https://github.com/Stoffe101/Underground-City/actions/runs/38676038218), [two-client journey](https://github.com/Stoffe101/Underground-City/actions/runs/38676037963), [Visual QA 1080p GUI 3 and 1440p GUI 2](https://github.com/Stoffe101/Underground-City/actions/runs/38676038148), [world smoke with the road survey and the 12-frame native tour](https://github.com/Stoffe101/Underground-City/actions/runs/38676037902), [offline tests and geometry harness](https://github.com/Stoffe101/Underground-City/actions/runs/38676037929). All 18 GUI states per viewport and all 12 world frames were viewed (review copies from the logs; see `content/qa/reviewed-runs.json`).
- **Roads and bridges (cloud, seed 579):** both generated highways report `continuous: true` over 1,252 and 1,117 centreline blocks with zero missing deck blocks, zero blocked headroom, zero steps above half a block and zero missing parapets. Two earlier surveys of the same seed found real bugs that the numbers exposed: the deck profile interpolated toward the city-circle sample (a 25-block cliff at each end) and the first full survey found headroom blocked on 9 and 4 centreline blocks (the cause was inferred to be foliage from neighbouring chunks, not itemised; the count became 0 after each chunk was made to repair the road across its eight neighbours). The boundary bug is covered by a geometry test; the foliage fix is covered only by the survey.

- **Hosted run on head 412622f (all green):** forge build, economy GameTests, onboarding smoke matrix, world smoke matrix (Lost Cities native survey and city tour), two-client journey with the regional click, Visual QA at 1920x1080 GUI 3 and 3440x1440 GUI 2, content tooling and world packaging.
- **Automated checks can pass bad evidence.** The first corrected city tour passed the "not blank, not duplicate" image check, but looking at the frames showed a camera aimed mostly at sky, then (after a heightmap-based fix) a camera placed underground because Lost Cities chunks report unprimed heightmaps. Capture now scans real blocks for camera height and waits for a stable rendered-section count. Treat any automated image check as a floor, never as approval.

## Evidence from the October 15 sprint (legal and underground careers, triangle layout, dev pack)

Recorded on the draft PR [#21](https://github.com/Stoffe101/Underground-City/pull/21); every row names what it does **not** prove.

| Evidence | Result and limit |
| --- | --- |
| Core GameTests with real Q Shop 1.8.4 | **54 of 54 required tests passed** on a fresh world, locally and in the hosted `economy` job. New cases cover skill trees and persistence, gated and located deliveries, buyer trust and rewards, gun requirements (plain factory guns only), contraband classification, city differences, checkpoint seizure and fines, licences versus prohibitions, no-reroll inspections, once-only exceptional finds, the Sable Leaf crop, the terminal snapshot wire format, legacy snapshot loading, the Technician's trace (skill, kit, sequence, replay, abandonment, distance, lock-out, stock refill), Founders Row (build, clerk binding, refusal over a container, undo) and the citizen save migration. Gameplay quality is not tested |
| The same tests with TaCZ and TaCZ Blueprints loaded | **54 of 54**, hosted and local, so the real-gun loot cases (a found gun keeps its `GunId`, a cache holds a real firearm) and the firearm contracts and blueprint rewards actually executed rather than skipped |
| Immersive Vehicles stack in the development environment | 54 of 54, but its content-pack items do not register in that environment (23 recipe parse errors), so this proves only that the engine loads beside Core |
| Production-style dedicated server with every pinned mod | **passed on a hosted runner**: all mods loaded, Core's catalogs loaded beside them, the Motorboat's 27-slot cargo round trip returned `ok` (see [development pack](dev-pack.md)). A boot is not driving, a client or two players |
| Python suite | 121 tests; plus `generate_regional_content.py --check`, `generate_district.py --check`, `plan_triangle_layout.py --check`, `validate_repo.py` and the balance validator |
| Native world survey, seed 5908 | all three roads continuous over real water (see the [three-city notes](three-city-prototype.md)); the identical numbers were produced locally and on a hosted runner for seed 166, which is the determinism evidence for the method |
| Real-client UI captures | 30 states per viewport at 1920x1080 GUI 3 and 3440x1440 GUI 2 captured in hosted CI; see [visual QA](visual-qa.md) for what was actually inspected |

### Failures the new tests found in my own work

Worth recording because the automated checks were right every time: the first run failed five tests (a stale citizen-save schema assertion, an off-by-one refill clock that started at world time zero, a secured-terminal answer for another terminal wrongly cancelling the session, a crop test judged before the light engine ran, and a district test written against an assumed-empty arena). Hosted CI then exposed three test assumptions a fast local machine hid: a crop cannot be planted without light, a freshly forced chunk is not yet entity-ticking, and a screening mod's world can hold block entities at arena height. Each was fixed in the test or the code, never by skipping a test.

### A server that fails to start can still exit 0

Forge's GameTest server exits 0 when a mod breaks registry loading (seen with Modern Life in the development environment). The dev-pack workflow therefore greps for "All N required tests passed" instead of trusting the exit code.
