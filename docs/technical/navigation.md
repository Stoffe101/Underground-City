# Tracked destinations and Xaero's Minimap waypoints (D-161)

**Status (October 24, 2026, fifth pass): in Core and GameTested; scripted real clients went through a Technician's tracked service calls with Xaero's Minimap 26.6.0 and World Map 1.47.0 installed (technav run 5), and without them (tech run 7), each passing all 18 steps. Nobody has played it. No run has had two real clients at once.**

## What it does

A player can **track** one destination at a time. Today that is a Technician's service call or job (Track on the personal computer's service board) or a known place from the Places book (Track there). While it is tracked:

* the status display's next-step card names it and points an arrow at it (as before, the fallback for everybody);
* **with Xaero's Minimap installed** on that player's client, it is also **one waypoint on the minimap**, in Xaero's waypoint list and on Xaero's World Map, named after the job ("Alarm repair · 3 Willow Way"). It has initials by kind (T a service call, P a place, D a delivery, H a home) and a colour by kind;
* arriving does not end it; finishing the work does. Somebody else finishing the same call ends it too, with a line saying so;
* a call or job that expires, or a place that leaves the book, ends it;
* it is **restored after a restart or a reconnect**, once, without a duplicate.

## How it is built

* **Server: `Navigation`.** A saved store (`underground_city_navigation`) of one destination per player: kind, reference, title, detail, dimension, position, city and since when.
  * The server builds the destination from the source's own data. A service call's position is its panel or street door; a place's is the street door in the player's book.
  * The client sends only a kind and a reference: `NavigationNetwork.Track(kind, ref)` or `Stop`.
  * Every five seconds the server checks that each destination is still valid, and ends those that are not. It sends the destination again on login.
  * `Navigation.finished(kind, ref, by, ...)` is called by the work that finishes it. `TechWork.succeed` calls it for a repair, an install, an upgrade or back-channel work.
* **Personal:** a destination is sent only to its own player (`PacketDistributor.PLAYER`). Another player's markers never reach a client, and nothing goes through chat.
* **Client: `ClientNavigation`** holds what the server sent and drives the bridge from the client tick.
  * The tick is called from `ClientPlaces.Ticks`. A nested `@Mod.EventBusSubscriber` class of its own was never invoked by Forge in the real client (tech runs 1 to 3): it was not public.
* **The bridge: `XaeroWaypoints`, client only and optional.**
  * **What Xaero offers:** Xaero's Minimap 26.6.0 keeps waypoints that other mods supply apart from the player's own. Each world container has a third-party manager with one set per origin, which its own Waystones support fills. The minimap draws these sets and World Map 1.47.0 lists them.
  * **What Core adds:** one waypoint under the origin `underground_city:tracked`, id `tracked`. Core changes nothing else.
  * **Reflection:** Xaero publishes no API for this. The calls are looked up by reflection, once, and only when `xaerominimap` is loaded in the 26.x line they were read from (26.6.0 tested).
  * **Failure:** any failure switches the bridge off for the session with one log line. The status display's own arrow keeps working.
  * **Without Xaero:** no Xaero class is loaded, and the log says "Xaero's Minimap not installed".
  * **Reconciling:** twice a second the bridge makes Xaero's set match the destination. It removes our waypoint when nothing is tracked, re-adds it after a world or container change, and replaces it when the destination changes.
  * **A deleted waypoint:** if the player deletes our waypoint in Xaero, the bridge respects that (`isThirdPartyDeleted`) and does not put it back until the destination changes.
* **Never touched:** the player's own waypoint sets and files. Third-party waypoints are not written to Xaero's files, so nothing accumulates; the server resends on every join instead.
* **The status display:** while Xaero shows the waypoint and the player is near the target, the card says "On your minimap · N m" with a pin instead of the arrow.

## What the brief asked and where it stands

| Asked | Where it stands |
| --- | --- |
| A waypoint appears for a tracked job | Real client: shown on the minimap, in the waypoint list and on the world map (tech runs with navigation, steps 6b, 9b, 11a) |
| It disappears when the job is done | Real client: gone after the repair, the install and on finishing (8b, 10b) |
| It survives a restart, without a duplicate | Real client: after a save, quit and reopen, the open call is tracked again, one waypoint, no duplicate (step 12) |
| Personal | By construction (sent to one player); GameTested (`TrackingGameTests`). **No two-client run** |
| Points at a usable entrance | The panel beside the street door, or the street door of a place |
| The player's own waypoints are untouched | Real client: counted before and after each step (0 → 0 in the runs; a run with player-made waypoints was not done) |
| Works without Xaero | Real client: tech run 7, without the navigation set, passed all 18 steps on the status display's arrow; nothing failed in the bridge |
| The server needs no client mod | By construction: the server side has no Xaero dependency |

## Evidence

* **GameTests (`TrackingGameTests`, 3):** a destination's save round trip; a service call built, ended and finished for two players at once; personal destinations, and a place that ends when it is no longer in the book.
* **Real client:**
  * **Technician runs with Xaero:** `-PucPack=world,furnished,scarcity,navigation`, a new archipelago world. Run 5 passed every step, `RESULT pass`, including the minimap, the list and world-map captures, arrival, the repair ending the tracking, a second call, the reopen restore and no duplicate.
  * **Technician run without Xaero:** `-PucPack=world,furnished,scarcity`, a new archipelago world. Run 7 passed all 18 steps, `RESULT pass`, 12 logged shortcuts. At each tracked call the status display's arrow pointed the way. The bridge logged "Xaero's Minimap is not installed; tracked destinations are shown on the status display", and no Xaero class was loaded or failed. Run 6 had stopped on the scenario's own second install job, since fixed.
* **Captures:** the minimap with the waypoint, Xaero's waypoint list and its world map (`uc-tech-6b-minimap`, `6c-xaero-waypoints`, `6d-xaero-world-map` in the run's screenshots, inspected).

## Limits

* Nobody has played it.
* No run has had two real clients. Personal markers rest on the code path (one player's packet), not on a two-client capture.
* The reflection follows Xaero's internal classes in 26.6.0. A future Xaero line may move them, and the bridge then switches itself off.
* Only service calls and places are trackable. Deliveries and homes have initials and colours ready, but no Track button yet.
* **Rights:** Xaero's Minimap and World Map are all-rights-reserved mods. They are runtime-only pinned downloads in the optional `navigation` set (`content/modpacks/dev-pack.json`), never committed or redistributed, and Core neither modifies nor bundles them.
