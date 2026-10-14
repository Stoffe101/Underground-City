# October 19 test readiness: offline development batch

**Goal:** build a verifiable, legally redistributable, two-player Underground City prototype before the player's next local test opportunity, October 19, 2026. This is a **development sequence**, not a promise of future autonomous background work.

## Priority 1: actually connected mods

- Forge **1.20.1**, **47.4.10** and Java 17.
- Q Shop **1.8.4** (Forge file **9060558**) as the **candidate single economy wallet**; pinned in `content/modpacks/world-mod-candidates.json`. Test its full command registration and vanilla starter shop with RPG Core under `runGameTestServer`. Do not bundle the ARR QShop JAR in our repo/artifacts.
- Original `operations_terminal` block: right-click opens our five-tab preview. The **Imports** page has one real optional `OPEN CIVILIAN SUPPLY SHOP` action if Q Shop is present.
- That action crosses a client→server packet with a **single block location, not arbitrary strings**. The server checks the block exists in this dimension, the player is within 8 blocks, the player has an existing `CitizenState` identity, and Q Shop has registered its command. Only then it executes **`/qshop open uc_starter_supply` as the player**.
- The Q Shop starter shop contains ONLY low-tier vanilla goods. It lets players earn Q Shop credits by selling wheat, wood or cobblestone and spend credits on ordinary food/building resources. It **does not bypass rival-city progression** or grant exclusive weapon parts. No untrusted player-provided price or object ID.
- The original QShop template in `content/integrations/qshop/config/qshop` must be installed **before** Q Shop creates its first world. `scripts/install_qshop_templates.py --server-root <folder>` never overwrites existing world configs or user custom credits. Existing worlds require backup and a reviewed manual migration.

## Priority 2: real item and profession contract

- Lock and boot selected **onboarding + quest + economy** candidates together. One city owner, one profession owner, one wallet.
- Implement **one Farmer/Smith or Gunsmith work order** that consumes real items, pays through Q Shop and grants RPG Core career XP exactly once, with save/rejoin and co-op checks.
- Only after one such roundtrip works, map the 9 conceptual advanced city materials to existing mod item IDs and wire 12 recurring recipes. Keep ordinary iron and survival resources available everywhere.

## Priority 3: map world and one crime alternative

- Verify actual Lost Cities predefined-city generation and one safe traversable route on a disposable seed. Never equate the current JSON/SVG sketch with a real playable city or bridge.
- Populate ONE supplier with inventory and ONE real civilian house/locked safe with loot. Ensure two clients share property stock and it restocks gradually.
- Once actual items and shops are real, test the first illegal vs legal acquisition path and faction consequences before building raids.

## Priority 4: visual QA evidence

- Native terminal screenshots at 1920×1080 GUI3 and 3440×1440 GUI2; inspect **each actual captured image**. Fix spacing, overflow, focus and disabled states, then recapture.
- When installed, expand `content/qa/gui-coverage.json` to cover Q Shop's main, modal, editor and low-funds scenarios plus onboarding and contract UIs. The 5 existing terminal captures do **not** count as complete pack-wide coverage.
- Delete *only reviewed* screenshot artifacts with the opt-in cleanup workflow, expire unattended galleries after one day.
- If Xvfb software rendering cannot boot a real Minecraft client on hosted runners, fail visibly and try a different display strategy, not fake PNG mockups.

## Distinct evidence types

- **Source/logic tests:** deterministic economy simulation, replay/receipt guards, config schemas.
- **Headless Forge GameTests:** actual Forge registered block, loaded Q Shop command and persistent saved data, no client UI claims.
- **Minecraft client screenshots:** rendered UI proof, no implication that shop credit/stock works.
- **Two-player hands-on:** ONLY this confirms the entire workflow, actual profession economy, usable city, real hostile NPCs and saved-world behavior.

## On-return test session

1. Install/pull PR's validated branch or a specifically packaged trial build. Back up any previous save.
2. Verify Java 17 / Forge 47.4.10, start dedicated server with the exact candidate mod set.
3. Register one player `/uc register valewood farmer`; second player as a different role.
4. Place `underground_city:operations_terminal` from the Functional Blocks creative category.
5. Right-click → Imports → **OPEN CIVILIAN SUPPLY SHOP**. Verify Q Shop wallet, sell 8 wheat/logs for credits, buy one starter item, close/reopen and restart. Check no duplication or free grant.
6. Attempt with unregistered player, standing more than 8 blocks away, a fake/non-PC block, and without Q Shop installed; all should reject cleanly.
7. Inspect all five terminal tabs at your normal GUI scales; compare to CI screenshots and report issues.
8. Once the first contract and cities exist, test one genuinely cross-city supplier roundtrip and one legal/illegal chest.

**Not completed yet:** a full installable combined modpack, any real NPC city, complete crafting job, advanced regional crafting, criminal raid, cargo crash, or Q Shop screenshot review.
