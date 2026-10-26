# Operator commands: `/uc admin` (D-191)

Status (October 26, 2026): **implemented.** What has been run, and what has not:

* `AdminGameTests` run the commands through the real command tree. They cover:
  * refusal to a player without permission level 2;
  * credits through Q Shop's wallet, with a removal below nothing and a zero both refused;
  * the career level, and operator points added and removed;
  * the Thief level and general XP, with an unknown skill refused;
  * the audit record, including across a save.

  They also check the point rules: operator points are kept apart, never take a skill away, and survive a save.
* One scripted real client ran `/uc admin credits` from inside the game (doors run 2). The change was logged and audited.
* The two-player dedicated-server session runs the commands from the server's console and restarts the server. Its result is in [the ninth-pass report](../production/ninth-pass-c70-critical-fixes.md).

No person has typed these commands yet.

## Who may use them

* Every command needs **permission level 2**: an operator, the dedicated server's console, or a single-player world with cheats on.
* A player who is not an operator does not see `/uc admin` at all. The game hides commands that need a permission the player lacks, so typing one gives "Unknown or incomplete command".
* Players must be **online**. The player argument is a name or a selector, with tab completion. Offline players are not supported: Q Shop's wallet is written only for an online player, and a change to an offline record would not reach their screen.

## The commands

| Command | What it does | Limits and refusals |
| --- | --- | --- |
| `/uc admin info <player>` | Shows city, trade, career and Thief level, XP, free and earned points (operator points shown separately), skills, credits, heat per city, a settlement in flight, the arrival record, position, respawn point, and whether the police are after them | Read-only |
| `/uc admin credits <player>` | Shows the balance | Read-only |
| `/uc admin credits <player> add <n>` | Adds credits through **Q Shop's wallet** (the only currency) | `n` is 1 to 1,000,000. The balance may not exceed 1,000,000,000 |
| `/uc admin credits <player> remove <n>` | Takes credits away | Refused with "has only N credits" if the balance would go below 0; nothing changes |
| `/uc admin credits <player> set <n>` | Sets the balance | 0 to 1,000,000,000 |
| `/uc admin career <player>` | Shows career level, XP and points | Read-only |
| `/uc admin career <player> xp add <n>` / `xp set <n>` | Changes career XP | Clamped to the XP cap. Refused if it would leave an unlocked skill below its level, or more points spent than earned |
| `/uc admin career <player> level <level>` | Sets the career level (sets XP to that level's start) | Level 1 to 30. Same refusal as above |
| `/uc admin career <player> points add <n>` / `points remove <n>` | Gives or takes **operator points**, kept apart from the points levels give | The total of operator points stays within ±100. Removing is refused if the points already spent would no longer be covered |
| `/uc admin thief <player>`, `... thief <player> xp add|set <n>`, `... thief <player> level <level>`, `... thief <player> points add|remove <n>` | The same for the Thief track (thievery XP, Thief level, Thief points) | Same rules, on the Thief tree |
| `/uc admin general <player>` | Lists every general skill's level and XP | Read-only |
| `/uc admin general <player> <skill> xp add <n>` / `xp set <n>` | Changes one general skill's XP (`trading`, `social`, `thievery`, ...) | An unknown skill is refused with the list of skills. Thievery follows the Thief rules |
| `/uc admin relocate <player> <city>` | Moves a registered player to that city's arrival welcome ([city-arrival.md](city-arrival.md)), using the same search and safety checks as a new citizen's arrival | Never changes citizenship and never registers anybody. Refused (nobody moved) if no safe ground is found |
| `/uc admin audit [count]` | The last changes made with these commands (default 10, at most 200) | Read-only |
| `/uc admin pregen start [scope] [margin]` | Generates chunks ahead of play, in the background, a dozen at a time ([test-worlds.md](test-worlds.md)). `islands` (the default) is every city, then the rest of the islands' land, then the roads and bridges; `all` is the cities' circles only; a city's id is that city's circle | `margin` is 0 to 256 blocks round each area (default 32). Only chunks that do not exist yet are generated; nothing stays loaded. Not written to the audit, as it changes no player |
| `/uc admin pregen status` / `pregen stop` | How far it is (chunks done, in progress, per second, any let go unfinished) / stops it. A stop or a restart carries on at the next `start` or server start | Read-only / stops only the generation |

The older developer commands still work as before, and nothing else replaces them:

* `/uc dev career_xp <player> <amount>` and `/uc dev general_xp <player> <skill> <amount>` add XP.
* `/uc_prepare` remains the playtest guide's Track B setup.

## What protects saves and money

* **Credits.** These go only through Q Shop's own wallet (`QShopEconomy`), never a second balance. A credits command is refused while the player has a work-order settlement or a Crew payment in flight between its two halves; trying again a moment later works. This keeps the crash-recovery records (`WorkOrderLedger`, `CrewLedger`) true.
* **Operator points.** These are stored on the citizen record as `admin_points` (`career`, `thief`), separately from the points levels give. They never change a level, so unlock requirements stay as they were. An older save loads with none.
* **Lowering a level, XP or points.** This is refused rather than taking skills away. If a citizen has a level-5 skill, they cannot be set to level 4. If they have spent 4 points, their operator points cannot be cut so that fewer than 4 are earned. An operator who really wants a skill removed must do so deliberately some other way.
* **The audit record.** Every change is written three times:
  * to the server log as `[UC-ADMIN] <who>: <what> on <player>: <before> -> <after>`;
  * to the world's audit record (`data/underground_city_admin_audit.dat`, the last 200 changes, kept across restarts);
  * to the player's chat ("An operator (...) changed your ...").
* **Numbers.** Every number is bounded by the command itself: a value outside its range is a syntax error, and nothing changes.

## Examples

### On Windows (single-player with cheats on, or as an operator on your own server)

In the game's chat (press `T`):

```text
/uc admin info Stoffe
/uc admin credits Stoffe add 500
/uc admin career Stoffe level 5
/uc admin thief Stoffe points add 2
/uc admin general Stoffe trading xp add 300
/uc admin relocate Stoffe valewood
/uc admin audit 20
```

### On a dedicated server's console (Windows PowerShell window or Linux terminal)

The console needs no leading slash:

```text
uc admin info Stoffe
uc admin credits Friend set 250
uc admin career Friend xp add 1200
uc admin thief Stoffe level 4
```

To make a player an operator on a dedicated server, type `op <name>` on the console. Keep the operator list short on a server other people can reach. Such a server stays `online-mode=true` with an allowlist ([the playtest guide](../production/playtest-guide-windows11.md), section 3b).

## What was tested

See the status at the top of this page. The console session (`scripts/run_duo_session.py`, phase 1) runs these commands:

* `info`;
* credits: add 150, remove 100000 (refused), set 75, add 0 (refused);
* career: level 4, then points add 2 and points remove 1;
* `thief level 3`;
* `general trading xp add 500`;
* refusals for an unknown skill and an unknown player;
* `audit`.

From the second client, which is not an operator, it checks that `/uc admin` is unreachable. Phase 2 restarts the server and checks that the credits, the level, the operator points and the audit entries are still there. Its outcome is recorded in the ninth-pass report, and only there.
