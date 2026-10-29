# Opportunities on the Guide page (twelfth pass)

**Status:** implemented; L2 (`OpportunitiesGameTests`); capture cases `uc-guide-opportunities` and `uc-guide-opportunities-advanced` in the visual harness (the captures are recorded below once taken); not yet seen through a scripted client on a live terminal; no person has used it. The current branch and its recent gameplay systems have not received human playtesting.

The owner asked for optional guidance in the Operations Terminal: what is worth doing now, achievable routes for a beginner and advanced work for an experienced player, and why something is not open. The Guide page now has two tabs:

* **Opportunities**: what is worth doing now. It opens by default once the first-half-hour steps are done.
* **First half hour**: the existing first steps, ticked off as they are done (the default before then).

## What is listed

`Opportunities.of` builds at most eight lines when a terminal opens, from the server's state only. Nothing is invented and nothing is a tutorial. In order:

| Kind | From | Shown when |
| --- | --- | --- |
| deliver | the accepted order on this terminal | the player carries everything it asks for |
| shortage | a shop's shortage contract for this trade (D-207) | one is open and not taken |
| ready | an open legal order | the player already carries its goods |
| work | the best-paying open legal orders (two, or one while an order is accepted) | the player can take them now (skill, level, trust) |
| service | the Technician's open panel calls, the Smith's stopped drives, in this city | the career is one of the two and calls are open |
| skill | unspent skill points | any |
| home | the player's homes | none held (press K) |
| underground | the best-paying open underground order, marked as off the books with what can go wrong | the career's underground skill is held |
| locked | the next order a skill (and a level) would open, if it pays more than what is open | one exists |
| travel | an open order whose buyer is in another city | at a city terminal |
| standing | the city's good name (D-204) | under 50 |

A line with a page says which (Work, Skills) and a click opens it. Underground lines are drawn in the underground colour; locked ones with a lock. A wayside desk or a shop counter shows none. A failure to build the list is logged and leaves the list empty, never the terminal closed.

## Not done

Crew contracts, the business counter's stock and takings, station upgrades, a vehicle to buy and the exchanges' shortages of raw goods are not listed yet. The lines are computed once per opening, not live.
