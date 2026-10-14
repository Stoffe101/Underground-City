# October 19 readiness and October 14 implementation evidence

The **starter profession/economy slice is playable** with Minecraft 1.20.1, Forge 47.4.10, Java 17, original Core and separately obtained Q Shop 1.8.4. This is not the full three-city modpack. Development and all recorded testing took place in Codex Cloud or GitHub-hosted Ubuntu runners; no personal computer, local network or self-hosted runner was used.

## What works

- Command registration persists home city/career. One placeable PC opens five native tabs.
- Twelve real vanilla work orders cover Farmer, Smith, Gunsmith and Technician. Accept → produce/obtain goods → deliver actual inventory → earn Q Shop credits and Core career XP → unlock later orders → repeat.
- Four shared demand slots per job, one active reservation per player, five-minute online-human replenishment, cancellation refund, server-generated order UUIDs and replay rejection.
- A fsynced prepared-settlement journal coordinates exact inventory, the provider wallet and XP. Connected-player inventory/capability saves are verified before settling. Login recovery applies absolute targets; no duplicate increment or second wallet.
- Real Q Shop civilian buy/sell UI with finite global/personal world-time daily limits; short Supply/Cr labels. A text-only built-in compatibility resource pack gives compact modal labels explicit priority, rather than modifying protected mod assets.
- OP `/uc_clerk` spawns one named persistent vanilla supplier beside a real PC over a clear solid floor. Its saved UUID/dimension/PC binding opens the same terminal. Supply clerk routines reuse vanilla AI, beds and lecterns; normal emerald trading is intercepted only for that clerk.
- Safe installers preserve existing templates and reject live/renamed/client saves. Fresh-world setup stages original city assets plus a source-checked Lost Cities 7.5.5 profile.

See [integration/setup/recovery guide](../technical/economy-integration.md), [visual QA](../technical/visual-qa.md), and [world prototype](../technical/three-city-prototype.md).

## Recorded verification

| Evidence | Result and practical limit |
| --- | --- |
| Cloud Java/Forge build | Passed; official Q Shop API JAR checksum verified; no third-party classes/assets bundled |
| Repository tests | 79 tests passed, documentation/catalog validation passed |
| Forge provider tests | Eight required tests passed in [hosted economy run](https://github.com/Stoffe101/Underground-City/actions/runs/38642758185): actual currency/items, farmer production recipe, fresh Smith tools, missing/protected goods, stale receipts, shared demand, journal disk reload/recovery, registered PC and named-clerk binding |
| Core-only Forge | Passed without Q Shop; provider-only cases explicitly skip |
| Two actual cloud clients | Farmer and Smith connected to a loopback-only dedicated server, used real terminal mouse actions and Q Shop buy/sell modals; both ended with **28 credits, 125 XP, level 2, four seeds, zero logs and one completion receipt** |
| Dedicated-server restart ([hosted reproduction](https://github.com/Stoffe101/Underground-City/actions/runs/38642758171)) | Same world restarted; both actual clients reconnected and retained those values without another reward. `journey-phase1.json` and `journey-phase2.json` report passed |
| Visual review | Thirteen actual framebuffer states per viewport, 1920×1080 GUI3 and 3440×1440 GUI2. Inspected captures; corrected clipped names/currency, missing-item feedback, selected-tab emphasis, modal coordinate conversion and label-pack priority |
| Native city generation | [Dedicated survey](https://github.com/Stoffe101/Underground-City/actions/runs/38644583100) passed: nine actual chunks per city, 1,040 / 1,505 / 1,492 window blocks at Ironhaven / Valewood / Blackwater. This does not verify roads, population or visual quality. |
| Hosted baseline checks | [Forge build](https://github.com/Stoffe101/Underground-City/actions/runs/38634409102), [visual capture/check](https://github.com/Stoffe101/Underground-City/actions/runs/38642758272), [mod smoke matrix](https://github.com/Stoffe101/Underground-City/actions/runs/38634408704), [content checks](https://github.com/Stoffe101/Underground-City/actions/runs/38634408972) and [world packaging](https://github.com/Stoffe101/Underground-City/actions/runs/38634409167) passed |

The automated client fixture supplies materials to isolate transactions. Vanilla crafting recipe lookup/production is tested separately; this is not a full survival crop-growing or furnished-city playthrough. The reusable [two-client runner](../../scripts/run_cloud_journey.py) and hosted workflow reproduce the journey in a fresh disposable checkout. Its server binds **127.0.0.1**, uses synthetic offline test names, and never changes production authentication settings. Normal gameplay does not enable the fixture's JVM flags.

## Failures investigated and corrected

1. Existing capture startup waited on gameplay ticks before a world was loaded. Render-tick startup now captures real screens and exits after asynchronous PNG completion.
2. The first hosted 1080p gallery checker rejected funded/zero-funds Q Shop screens as identical: its narrow crop omitted the shortened left wallet. The comparison now includes the full centered interface, preserving duplicate detection. Both galleries were inspected before cleanup. Final reviewed run 38642758272 passed and its exact two gallery artifacts were deleted by [reviewed cleanup](https://github.com/Stoffe101/Underground-City/actions/runs/38644576621); failure diagnostics were retained.
3. The two-client run exposed fresh vanilla pickaxes carrying only `Damage: 0`. The original NBT protection rejected them; the guard now allows that specific vanilla tag and still protects other tags/capabilities, names, enchantments and damaged tools. Added a real Smith turn-in GameTest, then both clients passed.
4. Mod resource ordering sometimes let Q Shop's original long labels override compact text. An original high-priority text-only resource pack fixes that through the supported resource system.
5. Cloud authentication-key/update checks could not reach optional external endpoints; offline loopback tests still connected and traded. Software-rendered clients produced transient server tick delays, so these runs are functional evidence, not a city performance benchmark.

Windows runtime and full power-loss/process-kill recovery with connected clients remain untested; prepared partial settlement/replay is exercised in Forge tests and ordinary dedicated restart is proven. A duplicate local GameTest run was cancelled to free resources while running the two actual clients; the final hosted eight-test provider suite passed.

6. The new dedicated-world probe found the original low-code package requesting Lost Cities `[7,)`, while the pinned Forge JAR declares **`1.20-7.5.5`**. Earlier isolated GameTests had not exercised this physical `run/mods` package. The dependency now pins the actual published version, with a regression check and a dedicated native-block survey.

7. The first loaded-world probe found no buildings at Ironhaven. Standard ocean/river city-factor penalties can suppress fixed anchors; the fresh installer now selects an original unrestricted-biome world style and explicitly maps the Overworld to the profile. The revised native test passed at all three anchors; actual client environment captures provide a separate visual gate.

## Still incomplete

No fully surveyed three-city terrain, traversable inter-city bridges, populated district, regional suppliers, advanced Create/TaCZ items/recipes, skill trees, city treasuries/taxes, police/factions, burglary/refill containers, imported shipments, crash recovery, black-market escrow/dropboxes or raid locations. The corresponding Python models remain design simulations. Gunsmith starter orders use archery/range equipment, not guns. The clerk is one NPC prototype with no automatic death/replacement recovery; provide a safe room and backup the save.

Q Shop editor/search/item-picker states and uninstalled onboarding/FTB/TaCZ/NPC dialogue/safe/faction interfaces remain outside the current capture manifest. Small-window/long-localization and controller/accessibility interaction coverage needs expansion.

## October 19 hands-on checklist

1. Back up existing saves. Use a new instance with exact Core/Q Shop/Forge versions and install original templates before creating its world. Existing worlds require an intentional backed-up migration.
2. Register different roles/cities; reconnect and confirm no repeated registration/grants.
3. Place/craft the PC. Farmer: accept 16-wheat harvest, grow/obtain it, deliver, confirm 24 credits/125 XP/level 2. Craft eight bread and deliver the unlocked bakery order for 36 credits/180 XP.
4. Smith: craft three undamaged stone pickaxes in separate slots; deliver them, retain named/enchanted/damaged personal tools, then inspect the level-2 iron-tool job.
5. Both players buy four seeds for eight credits and sell eight logs for twelve. Restart and verify wallet, remaining inventory, XP and job state.
6. Try missing materials, duplicate clicks, an unregistered citizen, a broken/fake terminal and distance beyond eight blocks. No reward or item loss should result. Check a partner's same-career reservation reduces shared demand.
7. Provide a safe floor/room beside a PC, spawn `/uc_clerk` as OP, add a bed/lectern and test its interaction/routines/save persistence. Keep it protected from mobs.
8. Review all PC tabs/Q Shop modal at normal GUI scale, including compact limit labels and disabled states. Test keyboard focus, practical readability and transaction feedback.
9. For terrain experimentation only, use `python scripts/install_world_prototype.py --server-root <fresh-instance>` and obtain Lost Cities separately. Survey each planned center and every crossing; no road/bridge claim is made until actual terrain is inspected.

## Next development priorities

Complete terrain/route inspection and build one functional district within the now-tested three-anchor world. Then bind actual local/foreign suppliers to server-verified geography and permits, select verified existing machinery/electronic/agricultural/TaCZ item IDs, and connect repeatable regional consumption to higher-tier jobs. Follow with one properly funded shared locked property, gradual refill and evidence-driven consequences. Add a proven NPC/quest framework and broader UI captures before scaling population, imports or raids.
