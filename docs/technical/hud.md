# The status display (D-109)

Status: **implemented and tested without a person**: GameTests prove the placement arithmetic, the wire format and the server's snapshot; the settings screen is captured by the real client at four viewports. **The stack has not been seen in a real world beside the minimap and the survival bars, and nobody has played with it.** The evidence section says exactly what was and was not measured.

## What the owner's first playtest found

There was no persistent display: the only things on the screen were the accepted-order card and the next-step card at the top left. Where the player was, how much money they had, whether their rent was due and how closely the city watched them were visible only on the terminal, which is a screen you have to stand at.

## What it shows

One card, then (optionally) the accepted order and the next step of the guided first hours, in one stack that the player places:

| Line | Source on the server | Shown when |
| --- | --- | --- |
| Place and clock | the city circle the player stands in (`CityRegions`), else "Near X" within 300 blocks of a city, else "Open road"; the client's own day time | always (each can be switched off); "(visiting)" when the city is not the player's home city |
| Credits and the Crew bank | Q Shop's wallet (`QShopEconomy.balance`), the Crew ledger | registered citizen; the bank only with a Crew; the wallet only when Q Shop and its currency are present |
| Home and rent | the Crew ledger's first held home (the one the player is in, else the one whose bill comes first): title, charge per hour, cycles paid ahead, minutes of **play** until the next charge, arrears | registered; "No home yet" and the key that opens the Home page when there is none; **late and overdue are coloured and say what is owed** |
| Trade | the citizen record: trade, level, progress to the next level, unspent skill points | registered |
| Heat | the heat ledger, for the city the player is in (else their home city) | only when above zero |

**Where the next step points (October 23).** The next-step card draws an arrow and a named line when its step is a place: the employer for "Meet your employer", and now the road out for "Travel to a rival city" (`Journey.roadOut`): in the player's home city, the nearest end of a road that leaves it ("Road to Valewood", "Road to Blackwater"); outside every city, the end ahead where a road enters a rival city; in a rival city already, nothing (the step is a terminal there). A scripted journey had walked about 720 blocks from Founders Row to the nearest road's end with nothing on the screen saying where it was. A world whose road chunks are not generated yet uses the configured roads' geometry. GameTested (`RoadMarkerGameTests.theTravelStepPointsAtTheRoadOutOfTheCity`); seen in journey run 47's frame at the road's start ("Road to Valewood", with the arrow).

All of it is the server's number (`HudState`, `HudService`). The client never computes a price, a rent or a level; it draws the snapshot and its own clock. A snapshot is built for each real player at most every two seconds (players are staggered across those two seconds), sent only when it differs from the last one sent, and discarded on login, logout, respawn and a change of dimension. Fake players (tests, machines) get none.

## Where it stands, and why there (measured, not assumed)

The default is the **top right, below the status-effect row**. The reasons, each one a fact about something that is in the pack:

* **Xaero's minimap is at the top left by default.** Read from the pinned jar (1.20.1, 26.6.0): its built-in HUD presets are `top_left`, `top_right`, `bottom_left` and `bottom_right`, and a module with no saved position takes the top-left one. Xaero also moves itself away from boss bars and status effects (its push boxes). The old order and next-step cards were at the top left and would have sat on it.
* The game draws status effects at the top right (two rows of 24 pixels): the stack starts 52 GUI pixels down.
* Boss bars and Jade's tooltip are at the top centre; the chat is at the bottom left; the hotbar, health, hunger, Thirst Was Taken's thirst bar, AppleSkin's overlay and the held-item name are at the bottom centre; TaCZ's ammunition read-out is at the bottom right. `HudLayout.reserved` keeps a stack anchored to those places clear of them (the bottom left leaves 110 pixels for the chat).

A player who moves Xaero's minimap to the top right gets the status effects pushed and our stack on top of it; the settings screen moves ours (seven anchors: top left, top centre, top right, left side, right side, bottom left, bottom right).

## Corner notices (D-135)

The game draws its toasts (our notices, a recipe unlocked, an advancement) at the top right, one under another, where the stack stands by default. A real client's screenshots (the alarm-panel scenario, 1280x720 at GUI scale 2) showed two notices over the status card, and two over the trace screen's pads while the signal was flashing. Now:

* **While the stack is drawn**, the whole toast column is moved out of its way (`HudLayout.toastShift`, pure arithmetic): **below the stack** when the space under it holds about three notices and stays off the hotbar and the bottom-right read-outs, else **beside it on its left**, at the top; on a window too small for either it stays where the game puts it. A stack on the left, or in the middle of the right side below the toasts, moves nothing. Beside the stack, a notice still slides in from the window's edge, so the column is cut at the stack's left edge and a notice comes out from behind the stack (October 24: heist run 8 caught two notices mid-slide over the status card).
* **On a screen** (a terminal, the inventory, the chat) the stack is not drawn and the toasts stay in their corner, as the game places them, **unless that corner is the screen's panel** (October 23). The final journey run's screenshots at 1280x720 showed three notices (delivered, level 2, step done) over the civilian counter's prices and buy buttons just after a delivery, and two over the terminal's header and an exchange row's buy buttons. While one of our screens or a container screen is open and the toast column would stand over its panel (`HudLayout.toastsCoverPanel`), nothing is drawn there: our notices wait and appear when it closes, as over the timed screens below, and the game's own are in the chat. A panel centred on a large window (a terminal at 3440x1440) leaves the corner free and the notices show as before. Tested by `HudGameTests.cornerNoticesWaitRatherThanCoverAScreensButtons`; seen in journey run 47's frames (the counter right after a delivery, clear) and development run 40's (the Valewood exchange, clear).
* **Nothing is drawn over the lock screen or the trace screen.** A notice that arrives while one is open is held and shown when it closes (at most six are kept; a flood drops the oldest); one that was already showing when the screen opened is not drawn over it (its clock keeps running, and its words are also in the chat). Those screens are timed, and a notice over a pad costs the player the round (the first alarm-panel screenshots showed two over pads 3 and 4).
* **The stack is drawn above the chat.** At 1280x720 and GUI scale 3 the game's default chat (320 GUI pixels wide) reaches under a right-hand stack, and long lines were drawn across the next-step card. The stack is drawn after the chat and **200 deep** (October 24): the chat draws a line 50 deep and its letters 50 deeper, and the font draws letters 0.03 over their shadow, so a stack drawn after it at depth 0 (and at exactly 100, the first attempt) lost the depth test to every letter, which showed through the lower card for the ten seconds a line stays on screen (patrol runs 1, 2 and 6). In patrol run 7, the same arrest scene, the chat's lines stop at the card's edge. The chat screen (T) shows every line in full, and the stack is hidden while it is open.

How: Forge has no event around the toast pass, so a client-only mixin (`ToastComponentMixin`, official and Searge names, optional like the others) shifts the pose before the game draws its toasts and restores it after. The arithmetic is tested at the eight window sizes of the stack's own test (`HudGameTests.cornerNoticesStayOffTheStatusStack`): moved notices never cover the stack, stay on the screen and off the hotbar. What the screenshots show after the change is in the sprint report.

## Settings

Mods, Underground City, Config opens the settings screen (`HudSettingsScreen`), as does the HUD settings key (unbound by default). It has: on or off, the anchor (click for the next, Shift-click for the previous), size (0.6 to 1.5), solidity (0.3 to 1), and a switch for each line and for the order cards. A live preview of the real stack is drawn at the real place beside the panel. Every change is saved at once to `config/underground_city-client.toml` (a client file; a server never reads it). **H** hides the status in play, **O** hides the order cards as before.

## Small windows

`HudLayout.plan` is pure arithmetic (no Minecraft types). Given the screen's GUI size, the anchor and the cards' heights, it shrinks the stack to fit (never below 0.6 of full size) and, when even that does not fit, drops the least important card: the accepted order first, then the status, and the next step last (a new player needs it most). It never moves a card off the screen and never into the reserved edge. At 1280x720 with GUI scale 3 (427 x 240 GUI pixels) all three cards do not fit and the accepted order is dropped; at 3440x1440 with GUI scale 2 (1720 x 720) all three show at full size.

## Evidence

* GameTests (`HudGameTests`): for every anchor, eight window sizes (1280x720, 1920x1080 and 3440x1440 at GUI scales 2 and 3, and two very small ones) and three wanted sizes, no card leaves the screen or overlaps another, a top card is below what the game draws there, a bottom card above it, right-hand cards are flush with the right margin; the next step always outlasts the other cards; a roomy window shows everything at the wanted size; a snapshot crosses the wire intact; the server says whether a player is inside a city, on its outskirts or in the open, and who they are; a fake player is sent nothing.
* The settings screen with the real stack beside it: `uc-hud-settings`, `uc-hud-left` (left side at 120 percent) and `uc-hud-minimal` (two lines at 80 percent and 60 percent solidity), captured by the real client at 1280x720 (GUI 2), 1920x1080 (GUI 2 and 3) and 3440x1440 (GUI 2); the sprint report lists what the frames showed and what was changed because of them.

## What it is not

* Not shown in a world with the minimap, the survival bars or a vehicle's gauges: those mods are in the development pack and are tested for starting, not for how their HUDs lie against ours.
* The "minutes until the next rent" are minutes of **play** of the household (billing runs only while someone from the household is on), not wall-clock minutes; the line says "of play".
* No police or wanted *system* exists: the heat line shows the existing heat number, which raises search chances at checkpoints and nothing else yet.
* The wallet line needs Q Shop 1.8.4 and its credits currency; without them the line is not drawn.
