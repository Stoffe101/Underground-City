# Crews, shared homes and cooperative finances

Owner direction (October 18, 2026): a cooperative system inspired by Stardew Valley's shared farms and finances and by a GTA-style crew. **Optional**, two players first, room for more friends, never a gate on solo play. The money and architecture are specified in [the Crew account document](../technical/crew-economy.md); decisions D-081 to D-084.

**Status key.** *Accepted* = the owner asked for it. *Implemented and tested* = in Core, covered by a named automated test. *Scripted* = driven by a script in a real client, not by a person. *Not built* = design only. **No human has used any of this.**

## 1. What a Crew is (and is not)

A Crew is a named group of up to eight citizens with one founder. It adds: a shared roster with roles, a shared bank, shared homes with permission-controlled doors, storage and workstations, and (later) shared contracts. It does **not** merge careers: each member keeps their own career, XP, skills, heat, trust, wallet, inventory and personal homes. A Crew can be left, disbanded, or never founded, and two Farmers or two Gunsmiths work exactly as well as a mixed Crew (no rule looks at a member's profession).

Law stays individual: a Crew never inherits a member's heat, searches or guilt (D-051). A shared bank is not evidence; nothing in the law model reads it.

## 2. Roles and permissions

Three roles: **Founder**, **Manager**, **Member** (a founder always may everything and is the only one who can rename, change rules and money mode, promote, demote, hand over or disband). Nine permissions, defaults chosen for a small group of friends so that nothing basic needs approval:

| Permission | Manager | Member |
| --- | --- | --- |
| Invite players | yes | no |
| Remove members (below your rank) | yes | no |
| Enter the Crew home | yes | yes |
| Build and furnish | yes | yes |
| Use shared storage | yes | yes |
| Use workstations | yes | yes |
| Withdraw from the bank | yes | no |
| Spend Crew money | yes | no |
| Lease homes | yes | no |

Anybody can **deposit**. The founder edits the table (Rules page). *Reserved, not built:* "business management" (nothing exists to manage yet).

## 3. Membership

| Action | Rule | Status |
| --- | --- | --- |
| Create | citizen without a Crew; 3 to 24 characters; unique ignoring case | implemented and tested (`crewNamesAndInvitations`) |
| Invite | by an online registered citizen without a Crew; at most 6 open invitations and 8 places in all; expire after 7 days; answered with Accept or Decline on the invitee's own screen (press K) | implemented and tested |
| One Crew per player | a player in a Crew cannot be invited, and founding one cancels their invitations | implemented and tested |
| Roles | founder promotes and demotes between Manager and Member | implemented and tested (`crewRolesPermissionsAndLeadership`) |
| Remove | only members who rank below you; their wallet, career and personal homes are untouched | implemented and tested |
| Leave | any member except the founder, who must hand over first (or disband) | implemented and tested |
| Hand over leadership | founder to any member; the old founder becomes a Manager | implemented and tested |
| Founder inactive | after **30 days** unseen, a Manager (or, with none, a member) can **claim** leadership; never automatic | implemented and tested with an injected clock |
| Disband | founder only, two deliberate steps (arm it, then type the Crew's name), refused while a money transfer is unresolved; the bank is split equally with the remainder to the founder as owed payouts claimed into wallets (automatically at login); **every Crew home becomes the founder's personal home**; everyone keeps wallet, career and homes | implemented and tested (`disbandPaysEveryMemberOnceAndKeepsHomes`) |
| Persistence | UUIDs and Crew ids; survives a restart; offline members stay listed with "last seen" | implemented and tested (reload from disk); **journey:** two real clients, then the dedicated server halted without a stop sequence and restarted, both clients saw the same Crew, roles, bank, homes and private container ([sprint report](../production/sprint-2026-10-18.md#c-crews)) |

## 4. Homes

A **residence** is a registered region with an explicit owner: a player or a Crew, never both and never "nobody" once leased. Joining a Crew never transfers a personal home; leaving never takes one.

**Where homes come from.** Today only the **Row Flats** (`/uc_district build row_flats`, placed beside Founders Row by the development launch): two flats (120 credits, 60 for a first-time tenant) and a Row House (260, or 140), each with a door, a bed or two, a chest, a crafting table, a furnace, an Operations Terminal as the home computer, and (the house) a smithing table, an anvil, a grindstone and a brewing stand. Prices are district data. Homes are **finite**: a unit is leased once; nothing creates a free apartment, and founding a Crew gives none. A player's *starter price* is a one-time discount tied to the person, so founding Crews repeatedly buys nothing. A player can hold two homes of their own and a Crew four.

**How a Crew takes one** (the "starter apartment" flow): found the Crew, put credits in the bank, stand within 64 blocks of the unit, open the Home page and press *Crew bank* (needs "Lease homes" and "Spend Crew money"), or *Crew (mine)* to pay from your own wallet, or *For me* for a personal lease. Bank-paid leasing is one atomic ledger write.

**What the guard does** (server side, `ResidenceGuard`): an unleased unit is a **showroom** (look around, open nothing, break nothing). A leased home's **interior** is what the tenants may change; the one-block **shell** (walls, floor, ceiling) belongs to the city, so nobody can open a neighbour's wall. Inside, a tenant (or a Crew member by their rights) may break and place blocks, open doors, open containers and use workstations (a tag: vanilla crafting, furnace, anvil, smithing, grindstone, brewing, enchanting and, when installed, TaCZ's gun tables). Everyone else is refused with a line on the action bar. Explosions spare homes. Creative-mode players are never held back. A Household Safe keeps its own lockpick and evidence rules and is outside the guard.

**Private storage in a shared home.** *Make container private* claims the chest or barrel you look at (up to four per home): only you may open it, and nobody can break it. When you leave or are removed, you keep a **three-day retrieval window** (door and your own containers only); after that the contents become the household's. The vanilla ender chest is always yours.

**Implemented and tested:** leasing rules, atomicity and crash recovery (`leasingIsFiniteAtomicAndNotFree`, `crewLeaseFromBankIsOneAtomicWrite`, `walletLeaseCrashRecoversToOwnedOrFreeNeverBoth`); the decision matrix and real Forge events (`guardDecisionMatrix`, `guardCancelsRealForgeEvents`); the real Row Flats (`rowFlatsAreRealHomesThatCanBeLeasedGuardedAndNotUndoneFromUnderTheirTenants`).

**Not guarded yet (known gaps):** pistons, fluids, fire, mobs, and entities such as item frames and armor stands; a Crew-owned vehicle; hoppers pulling from a container across the shell; a home inside a Lost Cities building (the registration is data, but no generated building registers homes yet); rent and eviction (a lease is a one-time price); transferring a home between a player and a Crew after the fact (only the disband path moves one).

## 5. Money

See [crew-economy.md](../technical/crew-economy.md). In short: three modes (separate, personal plus bank, fully shared), the default is the bank; deposits by anyone; withdrawals and spending by permission; a history; every movement crash-safe and replay-proof; fully shared finances need **every** member's consent, take effect only while it lasts, sweep the pay of work orders into the bank and pay exchange purchases from it first, and cannot touch Q Shop's own shop screens.

## 6. Cooperative production (the reason for Crews)

*Today:* a Crew home is a real place where the members share a furnace, a crafting table, an anvil, a chest and (where installed) a TaCZ table, with a bank that pays for homes and, if everyone agrees, for exchange goods. *Not built:* a Crew contract that several professions fill together, rewards split by a stated rule, shared production equipment that runs on its own, Crew vehicles, and several properties in several cities. The order for those is in the [expansion plan](../production/expansion-plan.md).

## 7. What happens when... (the cases the owner listed)

| Case | Result | Status |
| --- | --- | --- |
| A member leaves, or is removed | loses Crew rights at once; keeps wallet, career, personal homes; three-day retrieval of private containers; credits they deposited stay in the bank | implemented and tested |
| The founder is inactive | a Manager may claim leadership after 30 days | implemented and tested |
| Leadership changes | the permission table, the bank and the homes are unchanged | implemented and tested |
| The Crew is disbanded | see section 3 | implemented and tested |
| A member owns personal homes | untouched by joining, leaving or disbanding | implemented and tested |
| A Crew owns homes | pass to the founder on disband; never orphaned; the district cannot be undone while a home is leased | implemented and tested |
| A player holds Crew equipment | items are items: the Crew owns no items, only homes and credits; nothing is clawed back | by design |
| Members change financial mode | no credit moves; shared finances need everyone's consent and stop when one withdraws it | implemented and tested |
| The server crashes in a transaction | recovery decides from the wallet, at server start and at login, without a write to an offline wallet | implemented and tested (simulated crash and reload; and in the journey, a real server process halted with one transfer in the wallet and one stopped after its prepare record, recovered at the restart before any login); an external `kill -9` and a power cut are not tested |
| Two players use shared resources at once | the server serialises requests; the second withdrawal that would overdraw is refused | implemented and tested |

## 8. Open questions for the owner

1. Should leaving a *fully shared* Crew return the leaver's share of what their income put in? Today it does not (they keep their wallet; the bank stays with the Crew), and the Rules page says so.
2. Rent: a one-time lease, or a periodic charge paid in **active play time** only (never while offline)? The design rule is no offline punishment, so a rent that ticks only while a member is online is the candidate.
3. Should a Manager be allowed to promote? Today only the founder can (fewer ways to lose control of a shared bank).
4. Should Crew homes be shareable with a friend outside the Crew (a guest list)? The retrieval window already works that way for a departing member.
5. Open Parties and Claims bridge (shared chunk claims for rural properties), or Core-only protection?
