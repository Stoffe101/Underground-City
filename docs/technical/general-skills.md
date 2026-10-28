# General skills: earned by doing, used where it is felt (D-174, D-200)

**Status (October 27, 2026): implemented and GameTested; not played by a person, and not yet run through a real client.**

There are five general skills beside the career and the Thief track. Each uses the same level curve as a career (`ProgressionRules`: level 3 at 500 XP, level 6 at 3,125, level 10 at 10,125). Each is shown on the terminal's Skills page, General tab, with its level, what it gives now, the next step and how it is earned. Nothing here touches Q Shop: no skill pays credits.

| Skill | Earned by | Gives |
| --- | --- | --- |
| Trading (D-174) | legal deliveries (an eighth of the pay, up to 50 XP) and goods sold to a shop (up to 30) | exchange prices 2% / 4% / 5% lower from level 3 / 6 / 10 |
| Social (D-174) | civic contributions (40), Crew contract parts (30), a legal buyer's trust growing (25) | from level 3 a legal buyer counts one step more trust when offering trusted work |
| **Fitness** (D-200) | ground covered on foot | 2% / 4% / 6% quicker on foot from level 3 / 6 / 10 (a walking-speed modifier of its own, `GeneralActivity.FITNESS_SPEED`) |
| **Driving** (D-200) | ground covered at the controls of a vehicle or a boat | the vehicle dealers' lines (`regional_stock/vehicles/`) 3% / 6% / 8% cheaper from level 3 / 6 / 10 |
| **Shooting** (D-200) | hits on a target block from ten blocks or more | a steadier hand: from level 3 an arrow or a TaCZ bullet leaves 20% / 35% / 50% of the way closer to where the shooter looks (level 3 / 6 / 10); its speed and damage are unchanged |

All the discounts together (career skills, Trading, Driving at a dealer) are held to the shared 10% cap (`SkillNode.MAX_TOTAL_DISCOUNT`). A vehicle at 1.8 times its materials therefore never costs less than 1.62 times, so buying still never beats making (`economy_model.py`'s rule).

## How Fitness and Driving are earned, and what does not count

`GeneralActivity` samples every registered player once a second. A window of ten samples pays for the straight line from its first position to its last:
- **Fitness:** one XP per 25 blocks, at most 70 blocks a window.
- **Driving:** one XP per 50 blocks, at most 400 blocks a window.

The fraction left over is carried to the next window. A window pays nothing when:

- **the player has not turned their view** by five degrees or more in the last minute. An idle player in a water stream or on a moving platform does not move the mouse. At the controls of a vehicle, the vehicle's own turning counts as well (eleventh pass): a driver steers with keys while the camera may stay put, so a driver who steers along a road earns, and a boat drifting straight with a still view does not (`GeneralActivityGameTests.aDriverWhoSteersEarnsDrivingWithAStillCameraAndADriftingBoatDoesNot`).
- **it holds a teleport**: a step of more than 40 blocks in one second.
- **the way of moving changed** during it. On foot means no vehicle, not flying, not gliding, not a spectator, not in creative. Driving means a vehicle that is not a living mount and not a minecart, with the player at its controls or its first passenger.
- **the hourly cap is reached**: 600 XP of Fitness and 600 of Driving in an hour of the server's time.

Because only the straight line counts, walking in circles or pacing back and forth earns next to nothing. Real walking pays about 1.7 to 2.2 XP every ten seconds (4.3 to 5.6 blocks a second), so Fitness 3 takes about 50 minutes of walking and Fitness 10 about 17 hours. Fitness and Driving XP are given quietly: a notice comes only when a level is reached.

## How Shooting is earned

A hit on a `minecraft:target` block anywhere pays: by a TaCZ bullet (TaCZ's own server event, `AmmoHitBlockEvent`, through `RangeService`) or by an arrow the player shot (`ProjectileImpactEvent`). It pays 2 XP for the hit and up to 5 more for the centre of the face (`RangeService.score`), only from ten blocks or more. The same block pays once in two seconds, and at most 300 XP in an hour. A shot at a person or a creature never pays. Inside the operator-built firing range the range's own score and best are still kept.

## Limits

- What each skill has earned in its hour is kept with the world (`HourlyCaps`, eleventh pass; `GeneralActivityGameTests.aCapReachedBeforeARestartOrAReloginIsStillReachedAfterIt`). Neither a restart nor logging out and in again starts a new hour any more (both used to). The hour is one of the world's active time, the clock that runs while somebody plays, so time away neither fills nor empties a cap. A city's good name (D-204) keeps its hourly cap the same way. Only the ten-second movement window is in memory.
- The Shooting effect on **TaCZ bullets** is applied to the bullet entity when it joins the world (`EntityJoinLevelEvent`). It is GameTested on the vector maths only and has not been measured through a real client with TaCZ. Whether TaCZ's client shows the bullet's tracer on the corrected path is unverified.
- A vehicle mod that seats its driver in a way Forge cannot see (no vehicle entity) earns no Driving. Immersive Vehicles' and Modern Life's vehicles were not driven in a test.
- Fitness changes the walking speed attribute. A mod or effect that sets the same attribute stacks with it as Minecraft stacks modifiers.

## Tests

`GeneralActivityGameTests`:
- a straight walk pays and pacing back and forth does not;
- a player who never turns their view earns nothing;
- a teleport is not a walk;
- the hourly cap holds and the next hour pays again;
- target hits pay by distance, once per block in two seconds, and nothing to a stranger;
- Fitness 3 is one 2% modifier however often it is applied;
- Driving takes 6% off a vehicle at level 6 and nothing off bread, and with Trading never passes the cap;
- Shooting turns a shot toward the aim with its speed kept;
- all five skills say how they are earned.
