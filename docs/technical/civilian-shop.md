# The civilian counter (D-110)

Status: **implemented; the data model and the journey path are tested; captured by the real client at four viewports; nobody has used it.**

## What the owner's first playtest found

Opening the civilian shop from the Trade page showed Q Shop's own screen: a grid of tiles with a confirmation dialog, a different visual language from every other Underground City screen, and no way to see at a glance what is left today or why a purchase is not possible.

## Design: replace the picture, keep the transaction

Q Shop 1.8.4 (all rights reserved) owns the shop definition, its daily and per-player limits, the wallet and every trade. Core must not become a second authority over any of them (D-037, D-039), so the counter changes only what the player sees:

1. The server opens a shop exactly as before (`qshop open uc_starter_supply`, after the terminal's own checks). Q Shop sends its `OpenShopPacket` to the client and the client constructs Q Shop's `ShopScreen` from it.
2. `CivilianShopHook` (a screen-opening event) asks `CivilianShopBridge` whether the new screen is Q Shop's for one of **Core's own shops (the id starts `uc_`)**. If so, the bridge reads the packet out of the screen (it is in a package-private field and no hook exists to take a screen's place, so this is reflection, for the one pinned version) and converts it into `CivilianShopData`, a plain record in Core's types.
3. `CivilianShopScreen` shows it. A press sends Q Shop's own `TradePacket` (shop id, tab index, entry index, lots); **the server validates the shop, the entry, the limits, the wallet and the inventory exactly as for Q Shop's screen**. After a trade Q Shop sends the shop again; the new screen keeps the section and the scroll and says whether the wallet changed ("done", "sold", or "nothing changed, the reason is in the chat").

Fallbacks, each of which leaves Q Shop's own screen up: Q Shop is not 1.8.4; the screen's field is missing (a log line, once); the shop has a barter or a command entry; an entry is in another currency than its shop; the player is editing the shop (creative operator); `-Duc.civilianShop=false`.

## The screen

* Sections (Q Shop's tabs) on a rail, with the item as the icon; a single section has no rail.
* A row is the layout of the terminal's Trade page (`TradeLayout`: arithmetic proved disjoint at every width and used by both): the goods and its lot size, the price (**each** for goods on sale, **paid** for goods the shop buys), what is left today (or this week or month) or one status in its place ("Need 40 more", "You have 3 of the 8 wanted", "Sold out"), and two buttons: one lot and up to four.
* The wallet in the footer; the wheel scrolls a long list, with a scroll bar; a name is cut with an ellipsis and carried whole in the tooltip, which also says why a button is off.
* A second press is held back for 8 ticks so a slow server reply is never a second purchase; a lot button never asks for more than the limit leaves.

Q Shop hides an entry whose limit is spent rather than showing it sold out; the counter says so when a section empties ("Nothing in this section is left today").

## Evidence

* `HudGameTests.theCivilianCounterNamesWhatIsLeftAndWhenItComesBack`: the limit arithmetic (the player's limit and the shop's, daily, weekly, none), a spent entry, an empty counter, the price formatting.
* The economy journey's client script (`QShopJourneyActions`, run by the loopback journey job) was changed to press the counter's buttons with real mouse clicks (buy the first entry, deliver logs); the server's checks afterwards are unchanged. **It has not been run since the change** (this line is replaced by the result when it has).
* Six captures by the real client: `uc-civilian-shop-counter`, `-poor`, `-scrolled` (18 goods with very long names and prices up to four digits), `-deliveries`, `-empty`, `-limited`, at 1280x720 (GUI 2), 1920x1080 (GUI 2 and 3) and 3440x1440 (GUI 2). Q Shop's own screens remain captured (`uc-qshop-*`) as evidence of the provider.

## Not shown

A person buying with it; a connected client in the cloud journey since the change (the CI job runs the loopback journey); any other Q Shop version; Q Shop's own sounds and toasts on a trade (they still play: they come from the provider's packets).
