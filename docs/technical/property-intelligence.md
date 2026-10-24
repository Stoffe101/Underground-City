# Property intelligence: reading and comparing buildings (D-157)

**Status (October 24, 2026): implemented in Core; GameTested; one scripted real client (places run 3) went through it in a new furnished archipelago world. Nobody has played it.**

A city has hundreds of buildings. This is how a player finds out which ones suit them without trying dozens of doors, and how the tier they read matches the security they meet.

## The grade: every city has easy, middling and hard targets

A building's class (cottage, apartment block, house, shop, workshop, warehouse, office, tower, club) has a tier (1 Shabby to 6 Exceptional). Since D-157 a class may have a **grade**, a range its buildings' tiers move in, and a **category** (residential, commercial, industrial, office, special, public). Data: `data/underground_city/building_security/classes.json` and, for the furnished buildings, the generated `building_security/furnished.json`.

* Where a building stands moves its tier: near its city's centre it is often one better, at the edge often one worse, two either way only at the extremes, and a hash of the building decides the rest (`CityBuildings.gradeOffset`, a pure function the GameTests pin).
* A **city lean** (`city_grade_bias`) shifts a whole city: Blackwater -0.6 (its buildings stand near its centre, and its towers and offices left almost no easy targets), Valewood +0.25.
* A graded tier takes the **lock, stash and alarm of that tier and category** (`tier_security`), so the tier a thief reads is what they meet at the door: a tier-1 apartment block has a latch and no alarm, a tier-5 house a high-security lock and an alarm that is usually armed.
* The cameras and alarm panels a furnished building's plan carries follow its class's own tier (they are part of how its chunks generate). Valewood's white-brick houses became **townhouses** (tier IV, grade III to V) with a camera and an alarm panel, so a wealthier house *looks* better protected.

Measured with `/uc_places survey` (an operator command that asks Lost Cities' plan about every chunk of every city, a slice of 15 ms per tick, and writes `<world>/underground_city/property-survey.json`) in places run 3's brand-new furnished archipelago world:

| City | Buildings | Easy (I-II) | Middling (III-IV) | Hard (V-VI) |
| --- | --- | --- | --- | --- |
| Blackwater | 299 | 40 | 212 | 47 |
| Ironhaven | 360 | 93 | 258 | 9 |
| Valewood | 183 | 47 | 120 | 16 |

Before the city lean and the townhouses (runs 1 and 2), Blackwater had 3 easy targets and Valewood none hard. The survey took 11.5 s of work over 3,163 chunks; **its worst slice was 484 ms**, because one first question to Lost Cities about a chunk can take that long. It is an operator's measurement tool, not something a player triggers; gameplay asks about buildings one at a time as players come near, as before.

## The glance: a card under the crosshair

Looking at a door within six blocks for about a third of a second, the client asks the server about it (`PlacesNetwork`, at most once per door per five seconds; the server answers at most every three ticks per player) and draws a small card under the crosshair while the player keeps looking:

* what the place is and its address ("Shop · 93 Hayward Street");
* its tier as pips, a word and a numeral ("Well-off IV / VI"), never colour alone;
* whether the player could try the lock now ("You could try it", "Needs a Pro Pick Set", "No lockpick on you"), or "Open now" for a shop in its hours;
* whether they have cased it or been in, or the key that cases it;
* for a home: "Your home", "A player's home" or "To let"; for a public building: "Public building".

The card goes as soon as the player looks away, so walking down a street full of doors draws nothing; in places run 3, ten seconds of turning round a street made the client ask once. When the status display stands beside the crosshair (1280x720 at GUI scale 3), the card ends just left of it.

## Casing in layers

Casing is the same crouched click with an empty hand as before, or the new **Inspect key (I)** while looking at the door. It reads:

| Layer | Needs | Reads |
| --- | --- | --- |
| 0 | nothing | what the place is, its category and address, its tier, the lock and whether the player could try it |
| 1 | Casing | the alarm (none, not armed, armed, armed with a panel), cameras, people in now, and the **risk now** as a word with its main reason |
| 2 | Know Their Routines | when it next empties, the safe, whether it was broken into lately, and every reason behind the risk |
| 3 | Routines, Camera Sense and Thief level 10 | the risk as a number, how long the police would take, how often a patrol comes, how many pieces of furniture are worth searching |

**The risk now** is separate from the tier. It is an estimate from the rules that decide a break-in (`PropertyIntel.risk`): an armed alarm (one a clean pick slips, one with a panel's entry delay, one that rings), the snapped-pick alarm of the lock, people in the street who could see the door and the city's witness chance with the player's heat, people at home, cameras, an officer on the beat in sight or near, and a household watchful after a break-in. The real rolls are made when it happens.

## The book and the Places screen

Every glance at a target and every casing is remembered per player (`PropertyIntel.Book`, saved, 400 places, the oldest unmarked forgotten first): the address, city, kind, tier, and what the last casing told (lock, alarm, cameras, risk, watchful) and when. It never holds what is inside a building. **Player and Crew homes never enter a book.**

The **Places screen (J)** lists the book with filters (city, kind, tier band, cased or not, marked, "you could try") and sorting (nearest, tier, latest); the selected place shows what was learned. **Track** points the status display's next-step card at it with an arrow and a distance until the player gets there; **Mark** keeps it at the top and safe from forgetting; **Forget** removes it. In places run 3 the book held the same places after a save, quit and reopen.

## What it does not do

* Nobody has played it. Places run 3 is a script; its shortcuts were the clock, the travel to Valewood, standing at each door, and the skills, XP and lockpick of a developed thief and an expert.
* Xaero's map does not show the places. Its own waypoint sharing reads chat messages that start with `xaero-waypoint:`; whether one sent by Core works is untested, so nothing uses it.
* Nothing reveals a place a player has not looked at: no tip-offs or bought information yet.
* The interiors of a graded building are the same as its class's; only its lock, stash, alarm and tier change.

## Files

`PropertyIntel.java` (the glance, the risk, the book, the survey), `PlacesNetwork.java`, `ClientPlaces.java` (the card, the keys, tracking), `PlacesScreen.java`, `Casing.java` and `CasingScreen.java` (the layers), `CityBuildings.java` (the grade), `PropertyIntelGameTests.java`, `PlacesScenario.java` (`-PucPlaytest=places`).
