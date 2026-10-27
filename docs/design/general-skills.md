# General character skills

Independent from profession trees. General skills reward normal behavior and offer broad, modest utility; they **must not become a second mandatory grind**.

| Skill | Earn XP through | Example benefits | Exploit mitigation |
| --- | --- | --- | --- |
| Driving | Completing transport deliveries and varied route challenges | Better vehicle handling/maintenance or delivery licenses, where vehicle mods support it | No idle driving circles; distance alone is insufficient |
| Fitness | Active movement and work challenges | Controlled stamina/carry efficiency | Caps on repetitive sprint loops and AFK |
| Firearms proficiency | Verified hits in legitimate encounters/ranges | Stability, safe handling, limited accuracy assistance | No XP farming passive/invulnerable targets |
| Trading | Real buy/sell contracts and negotiations | More contract choices, slight fee reductions | Not self-trading loops |
| Social influence | Quests, aid, dialogue, diplomacy | Additional dialogue and trusted contracts | No repeat dialogue grinding |
| Technical literacy (candidate) | Legit repairs and security work | More terminal types and diagnostic hints | Bound to mission objectives |

## What is implemented (October 24, 2026; D-174)

| Skill | Earned in play from | What it changes | Evidence |
| --- | --- | --- | --- |
| Thievery (the Thief track, D-143) | entering buildings by tier, searches, safes, escapes, fence sales | its own 16-skill tree on the terminal's Thief page | L3 (thief run 12) |
| Trading | legal deliveries (credits / 8, at most 50 XP each) and goods sold to a shop (at most 30) | regional exchange prices 2% lower from level 3, 4% from 6, 5% from 10, inside the 10% cap shared with career skills | L2 |
| Social | civic contributions (40), Crew contract parts (30), a legal buyer's trust growing (25) | from level 3 a legal buyer counts one more step of trust when offering trusted work (today only the Valewood council's stall orders) | L2 |
| Fitness (D-200, October 27) | the straight line covered on foot in ten-second windows (one XP per 25 blocks) | 2% / 4% / 6% quicker on foot from level 3 / 6 / 10 | L2 |
| Driving (D-200) | the straight line covered at the controls of a vehicle or a boat (one XP per 50 blocks) | the vehicle dealers 3% / 6% / 8% cheaper, inside the shared 10% cap | L2 |
| Shooting (D-200) | hits on a target block from ten blocks or more (2 to 7 XP) | a shot leaves 20% / 35% / 50% of the way closer to the aim, speed and damage unchanged | L2 |

General XP uses the career curve (level 2 at 125 XP, 3 at 500, 5 at 2,000). The terminal's Skills page has a General tab with all five; level-ups arrive as notices and `/uc skills` lists each skill with what it gives. How Fitness, Driving and Shooting are earned, the anti-AFK rules and the limits: [technical/general-skills.md](../technical/general-skills.md).

**Where the build departs from the table above (October 27, owner to confirm):** Driving and Fitness are earned by distance. Circles and pacing pay next to nothing, because only the straight line across each window counts. Idling pays nothing, because the view must have turned in the last minute, and a teleport voids its window. Transport deliveries and route challenges, which the table names, do not exist yet. Shooting is earned at target blocks only, never at a person or a creature.

## System rules

- Distinguish general XP from career XP and city reputation in storage, UI, and rewards.
- Per-skill caps, clear unlock levels, sane progression curve; no hidden penalty for ignoring a skill.
- Skills increase options, not pure player power to an extent that invalidates combat balance.
- Server validates XP events and de-duplicates repeated identical actions.
- Permissions and licensing on city equipment are not solely bypassed by a level.
- Respec and level-cap rules are unresolved; test with two player archetypes.

## UX

One character screen with tabs **Profession / General Skills / Reputation**, visible level and next meaningful unlock, and accessible descriptions. No screen should obscure the game or bury progress behind nested scrolling panels.
