# A new citizen's arrival in their city (D-187)

Status (October 26, 2026): **implemented and verified through real clients.** A person has not yet played it.

* GameTests: `CityArrivalGameTests`.
* Three scripted real-client runs, one per city: `-PucPlaytest=citystart -PucPtCity=<city>`.

The owner's playtest found that a new citizen who chose Valewood or Blackwater still started at the world spawn in Ironhaven. This page describes the fix.

## What happens

1. The player registers on the first-login screen and chooses a city and a trade. `RegistrationService.choose` saves the citizen, then asks `CityArrival` to move them.
2. **The move happens once.** It is recorded in the world's `underground_city_arrivals` save data as pending, then as placed. A reconnect, a restart, a death or a second registration attempt never moves anybody again. A citizen who registered before this existed has no record, and is never moved.
3. **The server finds the welcome.** Each city's welcome is data (`data/underground_city/arrivals/<city>.json`):
   * Ironhaven: the gate of Founders Row.
   * Valewood: the General Store.
   * Blackwater: the Corner Store.

   The server looks up where that building stands in Lost Cities' own plan of the city. If the building is missing it uses a street chunk near the city's centre. The lookups are done once when the server starts, while nothing is generating.
4. **The server loads the ground first.** It loads the destination with a short-lived chunk ticket and waits until the chunk is really there. It never reads a chunk that is still generating.
5. **It looks for a safe place to stand.** It searches outward from the welcome's door for a cell with:
   * a solid, sturdy floor that is not leaves, magma or a campfire;
   * feet and head both empty and dry, with no fire, berry bush, powder snow or cobweb;
   * open sky above, so the player never lands inside a building or under a roof.

   If nothing near the door qualifies, it tries the streets around the city's centre. If that fails too, nobody is moved and the log says "no safe ground".
6. **It moves the player.** The player is dismounted and teleported, facing the door. Their speed and fall distance are cleared.
7. **It sets a personal respawn, only if the player has none.** The respawn is set to the arrival point. A bed or a home set later replaces it as usual. The world spawn is never changed, so other players are unaffected.
8. **It greets them.** The player gets a chat line and a notice: "Welcome to Valewood". The objective panel then points at the next step:
   * a Founders Row citizen is pointed at their employer;
   * elsewhere, a "Find work" step points at the nearest employer's door.

A player already in their chosen city is not moved. Choosing Ironhaven while standing at Founders Row counts as "already there". They still get a respawn point if they had none.

## The admin move

`/uc admin relocate <player> <city>` ([admin-commands.md](admin-commands.md)) runs the same search and safety checks for a citizen who is already registered. It never changes citizenship.

## What was tested

* **GameTests.** `CityArrivalGameTests` covers:
  * that the move happens once;
  * that a FakePlayer and a refused registration are not moved;
  * ground that must be refused: water, a wall, a roof, air, leaves, magma, fire, a pond;
  * that the respawn is set only when there is none, and the world spawn stays as it was;
  * that the shipped welcomes data is valid.
* **Real clients, each a new furnished archipelago world registered on the real screen.** All three passed 5 of 5:

  | Run | City | Where the citizen landed |
  | --- | --- | --- |
  | 4 | Valewood | 1623, 72, 224, in front of the General Store's south door |
  | 6 | Ironhaven | already there: the Row |
  | 7 | Blackwater | 711, 72, 1568, at the Corner Store |

  Each run checked:
  * the landing spot: a civic city, standable, dry, not in a wall;
  * the respawn: `/kill`, then a real click on Respawn, came back to the citizen's own respawn point;
  * persistence: saving, quitting and reopening left the citizen where they were, with no second move.
* **Two players on a dedicated server.** Phase 1 of the two-player dedicated-server session (`scripts/run_duo_session.py`) checks that each of two players lands in the city they chose. The session's results are in the ninth-pass report.

## Known limits

* The welcome is a building's door, or a street near the city's centre. If a world's own generation leaves neither (an unusual seed or a changed layout), the move is refused, logged, and the player stays at the world spawn.
* A new world is needed only for the welcomes that are buildings of the furnished package (Valewood's and Blackwater's stores). In a world without that package, the street fallback is used.
