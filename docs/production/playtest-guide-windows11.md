# Playtest guide: two players on Windows 11

For the owner and one friend. Status: **written in the cloud, not yet followed on a Windows machine.** Every command below is the one the repository documents and the cloud has run on Linux; the Windows spelling (`.\gradlew.bat`) is the documented equivalent. If a step fails, that is the first thing to report.

You are testing a **pre-production slice**. What works is listed in section 6; what does not exist is listed too, so that you do not lose time looking for it. Nobody has played any of this yet: the cloud has run scripted players and two scripted clients, which is not the same as a person enjoying or being confused by it.

## 1. What you need

| Item | Why |
| --- | --- |
| Windows 11, 16 GB RAM or more (24 GB if both players use one PC) | each Minecraft client is started with 4 GB; the server wants 3 GB more |
| **JDK 17** (Temurin 17 is fine) on the `PATH`, `java -version` shows 17 | Minecraft 1.20.1 / Forge 47.4.10 |
| Git for Windows | to fetch the branch |
| About 12 GB free disk space | Minecraft, Forge and about 170 MB of pinned third-party mods are downloaded; none of it is committed to the repository |
| An internet connection for the first run | ForgeGradle prepares Minecraft; the mods come from their authors' pages (Modrinth, Q Shop's GitHub release) and are verified against pinned SHA-512/SHA-256 hashes. A mismatch stops the build with the file name |
| A second computer for the second player (recommended), or a second copy of the repository folder on the same PC | the cloud tests give every client its own copy of the project; two clients started from one folder were not tried |

You accept the Minecraft EULA yourself (section 3 says where). The mods are for private development use; do not share the downloaded jars.

## 2. Back up first

Your normal Minecraft is not touched: everything below lives in `rpg-core\run-dev\` inside the repository folder. Still, before you start and after every session:

1. Stop the server (type `stop` in its window) and close the clients.
2. Copy the whole `rpg-core\run-dev\` folder to somewhere safe (for example `run-dev-backup-2026-10-18`).
3. A world is only a folder: `rpg-core\run-dev\world` (server) and `rpg-core\run-dev\saves\UC-Dev-5908` (single player). Deleting it makes a fresh world on the next start. **The Crew ledger is inside it** (`world\data\underground_city_crews.dat`): back the whole folder up, never that file alone.

## 3. Get the branch and start the server

In PowerShell:

```
git clone https://github.com/Stoffe101/Underground-City.git
cd Underground-City
git checkout feature/tenth-pass-living-world
.\gradlew.bat runServer -PucAcceptEula=true
```

* The first run takes 10 to 25 minutes. When the console says `Done`, the server is up. `-PucAcceptEula=true` writes `eula.txt`; that is your acceptance of the Minecraft EULA.
* On a brand-new world, the server builds **Founders Row and the Row Flats** (three leasable homes) near the spawn and moves the spawn to the Row's gate. The log says `[UC-DEV] Founders Row built` and `[UC-DEV] Row Flats built`.
* **Two ways to let a friend join - pick one on purpose.**
  * **Developer launch (this section), `online-mode=false`:** the gradle dev clients have no Minecraft account, so they join with an *offline profile*. For that, stop the server, open `rpg-core\run-dev\server.properties`, set `online-mode=false`, save, start the server again. **This turns authentication off: the server believes any name a client types, so anybody who can reach the port can type a Crew member's name and become that member - their Crew, bank, homes, private containers, wallet and progress.** Use it only on your own PC or a home network where everybody is a person you trust, never behind a port forward or on a public address, and never for a world you want to keep for real accounts later (offline and authenticated identities are different people to the server; see [identity](../technical/identity.md)).
  * **Authenticated launch (section 3b), `online-mode=true`:** the way to run a server other people reach. Minecraft proves which account owns each connection, and the Crew, bank, homes and wallets belong to that account, not to the name.
* Open the firewall prompt for Java on a private network if Windows asks; the server uses port 25565.

## 3b. Authenticated launch for real accounts

This is the supported launch for anything beyond the two of you at one desk. It starts a **production-style server** from a separate, untracked folder (not `run-dev`), with `online-mode=true`, `enforce-secure-profile=true` and a white-list. **Tested in the cloud:** the server pack's settings, a real Forge server answering a login with an Encryption Request (so it refuses an unproven identity), and the identity rules in code. **Not tested:** a person with a Microsoft account joining it (the cloud has no account). Step 1 below is that test.

1. **Build the Core jar and the server folder** (PowerShell, Python 3 needed for the helper scripts):
   ```
   .\gradlew.bat -p rpg-core build
   python scripts\make_server_pack.py --sets world firearms vehicles boats survival --output build\server-pack --whitelist Alice Bob
   ```
   Use the exact Minecraft names of the people who will play. Add `vintner` and `farming` to `--sets` if you want those mods; the clients must have **the same sets**.
2. **Accept the EULA yourself.** Read https://aka.ms/MinecraftEULA and set `eula=true` in `build\server-pack\server\eula.txt` (or add `--accept-eula` to the command above).
3. **Start the server:** `build\server-pack\server\start-server.bat`. On the first start type the lines of `whitelist-commands.txt` into the server window (`whitelist add Alice`, ...). Minecraft turns each name into the account's UUID once; from then on only the UUID matters. Nobody else can join.
4. **Every player sets up their own normal-launcher profile with the same mods.** Install Minecraft 1.20.1 and Forge 47.4.10 (the official Forge installer from files.minecraftforge.net), create a launcher profile for it and give it its own *game directory* (for example `C:\UC-Client`, so your normal Minecraft is untouched). Then, from a checkout of **the same commit as the host** (`git checkout` the same branch), each player runs, with `<mods>` meaning `C:\UC-Client\mods`:
   ```
   .\gradlew.bat -p rpg-core build
   python scripts\fetch_dev_pack.py --sets world firearms vehicles boats survival --output <mods>
   python scripts\build_world_prototype.py --output <mods>\underground-city-world-prototype.jar
   copy rpg-core\build\libs\underground_city-0.1.0.jar <mods>
   ```
   and downloads **Q Shop 1.8.4** from its author's release page (https://github.com/QLNPLUS/Q-shop/releases/tag/v1.8.4-forge-1.20.1) into `<mods>`, checking that the file's SHA-256 is `ab32e33a7190bbcb7b4b10d9fe6ae4a38f3e65eba5ea1ac9f315d3bd798c5199` (`Get-FileHash`). `fetch_dev_pack.py` refuses any jar whose hash differs from the pin; `build\server-pack\client-mods.json` lists everything. The `--sets` must be the ones the server was built with. **Do not hand the third-party jars to anyone**: most are all-rights-reserved and for private use after you fetched them from their authors yourself.
   *Honest limit:* the server side of this (the pack, the boot, the authentication handshake) was run in the cloud; the **normal-launcher client with these jars was not**. It is the ordinary Forge setup, but if the client refuses to connect ("mod mismatch"), the first thing to compare is the mod list in `latest.log` on both sides.
5. **Connect** with *Multiplayer > Direct Connection* to the server's address (your local address on a home network; port forwarding makes it reachable from the internet - do that only with the white-list on, which the pack does).

**What your account means.** Everything the game keeps about you is attached to your account's UUID: citizenship and career, credits, work orders, Crew membership and role, the Crew's bank, homes and private containers. Renaming your Minecraft account keeps all of it. Another player cannot act as you, take your wallet or your Crew's bank, or open your private chest by typing your name.

**Moving from the developer launch.** There is **no tool that moves a world played in offline mode to real accounts**, and nothing converts identities silently. On an authenticated server a person who played offline is a stranger: no citizenship, no Crew, no wallet, no homes. The server warns at start and `/uc_identity` lists the offline-derived identities it found. Start the authenticated world **fresh**; keep the developer world on your own network if you want to keep it.

## 3c. The scripts (PowerShell, in `scripts\windows`)

The commands in this guide are wrapped in a few PowerShell scripts, so that a session needs no typing of gradle flags. They run in the repository folder and never touch your normal Minecraft. If PowerShell refuses to run them ("running scripts is disabled"), allow scripts for this window only: `Set-ExecutionPolicy -Scope Process Bypass`.

| Script | What it does |
| --- | --- |
| `uc-update.ps1` | fetches the playtest branch and fast-forwards to it; refuses when you have local changes; prints the commit both players must share |
| `uc-server.ps1 -Sets furnished,scarcity -World UC-S2 [-LanOfflineMode] [-AcceptEula] [-Pregen] [-C70] [-PrivateContent <folder>]` | backs up the game folder, then starts the dev server; a new `-World` name makes a new world beside the old one (never replacing it). Offline mode must be asked for on every start, and only for a home network. `-Pregen` generates the three islands once in the background ([test worlds](../technical/test-worlds.md); about 13,500 chunks). `-C70`: the owner's buildings first (section 6k). Client-only mods (Embeddium, Xaero's maps, the client helpers) are left out of the server |
| `uc-client.ps1 -Name Alice -Sets furnished,scarcity [-Join] [-Xaero] [-World UC-S1] [-C70] [-PrivateContent <folder>]` | starts a client: `-Join` for a server, otherwise single player; `-Xaero` adds Xaero's Minimap and World Map (client only); `-C70 -Join` loads the sets the owner's buildings need, not the private folder |
| `uc-new-world.ps1 -Name Alice -World UC-S1 [-Pregen] [-C70]` | a single-player client in a NEW world with the furnished cities and scarcity (and with `-C70` the furniture set and the owner's staged buildings); refuses a name that exists. `-Pregen` generates the islands in it once, in the background |
| `uc-private-stage.ps1 -Source <the C70 folder or zip>` | ports, chooses, wires and seals the owner's private buildings into `%USERPROFILE%\UndergroundCityPrivate\runtime`, outside the repository (section 6k) |
| `uc-second-copy.ps1` | copies the repository to `..\Underground-City-P2` for a second player on the same PC |
| `uc-backup.ps1` | copies `rpg-core\run-dev` (worlds, saves, configs) to `backups\run-dev-<date-time>`; stop the server and clients first |
| `uc-logs.ps1` | packs the logs, crash reports and screenshots (never a world) into `reports\uc-report-<date-time>.zip` for a bug report. After a session with private content it leaves the screenshots out and replaces the private buildings' ids in the logs with `<private>` |

**Tested in the cloud:** a test (`tests/test_windows_scripts.py`) runs the server, client, new-world, logs and staging scripts with PowerShell 7 on GitHub's Linux runners against a stand-in for gradle (and, for the staging script, for Python): the commands they build, the server settings they write, and their refusals (an unknown set, a bad name, an existing world, offline mode not asked for, `-C70` with nothing staged, a private folder that is a pack itself or needs a set the launch leaves out). The same test checks that the scripts accept exactly the sets `build.gradle` knows; the `furniture` set was missing from them until the eleventh pass. **Not tested:** any of it on Windows (PowerShell 5.1 or 7 on Windows 11), `robocopy` (the backup and the second copy), the real gradle launches through the scripts, and Python on Windows. Run `uc-backup.ps1` once by hand and look at the folder it made before you trust it.

**Both PCs must run the same pack.** Every start prints `Underground City pack fingerprint: <12 characters>` (Core's commit and every mod both sides need, with its pinned version). The server and both clients should print the same; when a client's differs, that player sees *Your game differs from the server's* in the game and the server's log names both. Fix it with `uc-update.ps1` on both PCs and the same sets (or the same `-C70`) on every start.

## 4. Start the clients

Player 1 (same PC as the server, or another):

```
.\gradlew.bat runClient -PucAutoWorld=false -PucUsername=Alice
```

Player 2 needs **their own checkout** (or a copy of the folder) and a different name:

```
.\gradlew.bat runClient -PucAutoWorld=false -PucUsername=Bob
```

In Minecraft: *Multiplayer*, *Add Server* (or *Direct Connection*), address `localhost` (or the server PC's local address, for example `192.168.1.20`). On the first join each player sees the **registration screen**: pick a home city, then a profession, then confirm. Pick **different professions** (for example Farmer and Gunsmith); the Crew should work with any mix.

Single player instead: `.\gradlew.bat runClient` opens straight into the dev world `UC-Dev-5908`.

Optional survival set (backpack, thirst, kitchen, sleeping bags): add `-PucExtraSets=survival` to **every** command (server and clients), for example `.\gradlew.bat runClient -PucExtraSets=survival`. It adds the opt-in mods to the complete pack and keeps the developer launch (the dev world and the Founders Row and Row Flats bootstrap) as it is. See section 6 for what is verified.

**Installing Sophisticated Backpacks (nothing to do by hand).** The backpack is **Sophisticated Backpacks 3.26.12.2206 with Sophisticated Core 1.5.12.2412**. They are *all rights reserved* mods: this repository does not contain them and you must not copy them into it. `-PucExtraSets=survival` makes Gradle download exactly those two files from their author's Modrinth page into the build cache and refuse any file whose SHA-512 differs from `content/modpacks/dev-pack.json`. Do **not** also put a backpack mod of your own, or Traveler's Backpack, into a `mods` folder: the pack tests only one backpack mod, and the server logs an error if both are present. The generated settings (smaller sizes, fewer upgrades) are copied into `run-dev\defaultconfigs` for you and apply to a **new** world; a world you already have keeps the settings it was made with, and the server log says so at every start (`[UC-BACKPACKS] ... matches the Underground City policy`, or one line per difference).

**The world has no ordinary monsters.** Underground City is a simulation of a working world, not a monster-survival pack. Out of the box there are **no zombies, skeletons, creepers, spiders, endermen, witches or any other hostile creature** (not at night, not in caves, not from spawner blocks); the game is **not** on Peaceful and animals, villagers and pets are normal. Hunger, thirst, falls, fire and drowning still hurt. This applies to the dev world, to a dedicated server and to a single-player or LAN world, with no file to edit: the default is in the code. The server log shows `[UC-SIMULATION] hostile creatures: SIMULATION` at every start. If you *want* the base game's monsters for a test, stop the server and set `hostileMobs = "CLASSIC"` in `world\serverconfig\underground_city-server.toml` (it takes effect at once; set it back to `SIMULATION` to remove them again). See [simulation-world.md](../technical/simulation-world.md).

**If you have an older world with Traveler's Backpack items in it:** do not open it with the current pack until you have read [backpack-migration.md](../technical/backpack-migration.md). Nothing converts the items for you; the document says exactly what the server did in the cloud experiment and how to keep them.

## 5. The Crew test (about 30 minutes)

Work through this with your friend in the world. Write down what happens when it is not what the step says; screenshots (F2) help.

| # | Do this | You should see |
| --- | --- | --- |
| 1 | Alice presses **K** (Crew screen), types a name, presses *Found Crew* | the Crew page with Alice as Founder; the Found button works as soon as the name has 3 letters |
| 2 | Alice finds Bob under *Invite a friend* and presses *Invite* | Bob appears under *Open invitations* |
| 3 | Bob presses **K**: an invitation is listed (the screen refreshes by itself every 5 seconds); *Accept* | both now see the same two members, with their own professions and levels |
| 4 | Each plays their own profession for a few minutes (take a work order, do a task) | XP, skills, wallet and heat stay personal; nothing from one appears on the other |
| 5 | Walk to the **Row Flats** (east of Founders Row, a short walk from the gate). Alice opens *Home*, then the tab *Find a home* | the homes within 64 blocks, each with its **rent per hour of play and deposit**, its **purchase price**, and a *Rent* and a *Buy* button (section 6d walks through rent in detail). The first lease of the Row House costs the **starter price** |
| 6 | *Money* page: both **deposit** credits; then Alice leases the **Row House** with *Crew bank* | the bank shows the sum; the house shows as a **Crew** home on both screens |
| 7 | Step inside the Row House. Both use the chest, the furnace, the crafting table and the smithing table. Then try the chest of a flat that nobody has leased | the Crew can use its own house; an unleased flat lets you look but not touch |
| 8 | Bob looks at a chest in the house and presses *Make private* on the *Home* page (*Your homes* tab) | only Bob can open that chest now; Alice cannot, and cannot break it |
| 9 | Alice on *Rules*: switch *Withdraw* on for members. Bob withdraws 10 | Bob's wallet goes up by 10 and the bank down by 10; the history says who did it |
| 10 | Both click *Deposit* quickly at the same time, or Alice clicks twice | each press is one transfer; the bank equals the sum of what really left the wallets |
| 11 | Both leave the game (log out). Stop the server with `stop`. Start it again (`runServer`) | the server starts without errors |
| 12 | Rejoin as Alice, then as Bob. Press **K** | same Crew, same members and roles, same bank, same homes, Bob's private chest still private, wallets as they were |
| 13 | Try the edge cases you like: Bob *Leave Crew*; Alice invites him again; *Rules > Money mode*; *Rules > Crew > Disband* (it asks for the Crew's name) | leaving keeps Bob's wallet and career; a disband splits the bank and passes Crew homes to the founder |
| 14 | Lease a flat for yourself alone (*For me*), then let the other player try its chest and door | refused with a line on the action bar; your Crew membership did not give them your flat |

Things that deserve a careful look: does anything feel unfair or confusing; are the buttons where you expect them (is the text readable at your screen size); did a transfer ever show the wrong number even for a moment; did a restart lose anything.

## 6. What works, and what does not

**Built and tested in the cloud (not by a person):** registration, the terminal, work orders, the regional exchange, the Public Ledger, skill trees, checkpoints and searches, firearms crafting with blueprints (with the firearms set), Founders Row, the Row Flats, Crews with a bank, shared homes and a server-side guard, the road waymarkers and wayside shelters. Two scripted clients have played the Crew steps above against a dedicated server that was then **killed without a stop sequence and restarted** (see the sprint report for the exact result).

**Not built, so do not look for it (October 25):** vehicle fuel, vehicle repair or insurance, boat customs, a harbour, per-player loot beyond the roadside crates (D-186), a medical item, any tax, a buyer-to-buyer property market, factions or gang wars and story quests. All ten property categories now have a building (the Blackwater lofts and the Valewood villa were added on October 25, D-176). Crew contracts, burglary, one raid, police patrols and daily routines **do** exist now (sections 6e to 6i). Evicting a tenant by force does not exist: unpaid rent only withdraws comforts (section 6d). **The home guard** now stops pistons, hoppers, fluids, fire, mobs, item frames, armor stands and modded containers at a leased home's walls (section 6b); it does not stop fire that spreads in from outside, lightning, or mods that move blocks with their own code. **Q Shop's own shop screens always spend your own wallet, even when the Crew shares finances**; only the Underground City exchange (the Operations Terminal in a city, the provisions desk in a wayside shelter) can use the Crew bank, and only for a member who holds the *Spend* right.

**Survival set** (`-PucExtraSets=survival`, opt-in): backpack, thirst, clean and dirty water, canteens and flasks, the kitchen and sleeping bags. The goods are **for sale** (section 6b), finite and shared like every other supplier line, and the cloud has driven the mods' own code for each mechanic. Nothing in the default pack depends on it.

## 6b. Survival and shared-finance test (about 25 minutes, with `-PucExtraSets=survival` on server and clients)

Everything here is also covered by scripts; this list is for **your** eyes and hands. Report anything that feels wrong, not only anything that fails.

| # | Do this | You should see |
| --- | --- | --- |
| 1 | In a city, open an **Operations Terminal**, press the exchange tab, find *Waterskin*, *Backpack* (**70 credits**, at most four on the shelf, one more every 45 minutes), *Cutting board*, *Cooking pot*, *Sleeping bag* | goods with a price in credits and a number of units left; buying takes credits from **your** wallet and moves the stock for everybody |
| 2 | Alice (founder): *Crew > Rules*, choose **Fully shared finances**. Bob: open *Rules* and press *Agree* | a banner explains the change; it takes effect only when **everybody** has agreed; no credit moves because of the switch |
| 3 | Alice: *Rules > Members may spend Crew money* on. Both deposit credits. Bob buys something that costs **more than the bank holds** | the bank pays what it holds and Bob's wallet pays the rest; the success line and the Crew history (*Purchase*) say how it was split |
| 4 | Switch *Spend* off for Bob and try to buy again; then try something nobody can afford | Bob pays from his own wallet only; the unaffordable purchase is refused with a line saying why, and **no credit has moved anywhere** (check the bank and your wallet) |
| 5 | Open a **Q Shop shop** (not the exchange) and buy | it spends **your** wallet only; the Crew screen says so beside the finance mode |
| 6 | Walk out of the city to a **wayside shelter** (the lanterned roadside pavilion or well). Find the small terminal desk | a *provisions desk*: bread, torches, water bowls, a waterskin; it shows no work orders and sells the nearest city's wayside lines from the same finite stock. Shelters built before this change have no desk until they are undone and rebuilt |
| 7 | Wear a backpack (open the inventory with **E** and shift-click the pack, or drag it onto the chest armour slot), press **B** to open it, put things in it, log out, restart the server, log in | the backpack, its contents and the thirst bar are as you left them. See section 6c for the full backpack list |
| 8 | Fill a canteen or waterskin at a pond (use it looking at the water); drink; check that boiling dirty water in a furnace makes it safe | servings go down by one per drink and thirst goes up; dirty water makes you ill, boiled water does not |
| 9 | Cook something in a cooking pot over a heat source (a campfire, magma or a lit furnace below) | a meal that feeds; |
| 10 | Sleep in a **sleeping bag**, then die or use /kill and respawn | you respawn at your bed or the world spawn, **not** at the sleeping bag; a vanilla bed does set it |
| 11 | Break a block of the Row House from outside the Crew: a friend who is not in the Crew (or you, from a second account) tries to open the chest, push a block through the wall with a piston, hopper-drain a chest, place lava or a bucket, break an item frame | each is refused; the same things work for the Crew inside |

Things to write down: did the shared-finance words make sense before you pressed anything; was it ever unclear who paid; was a price silly; did you run out of a good and not know why.

## 6c. Backpack and monster-free world test (about 20 minutes, with `-PucExtraSets=survival` on server and clients)

The backpack is **Sophisticated Backpacks**; its keys are **B** (open the worn backpack), **C** (the inventory-interaction key, used by the deposit and restock upgrades) and **Z** / **X** (switch an upgrade). Everything below was run by scripts in the cloud (a Forge GameTest per rule; one scripted client for the screens). Nobody has done it by hand.

| # | Do this | You should see |
| --- | --- | --- |
| 1 | Buy the plain *Backpack* for 70 credits at a city terminal. Look at the other goods on the same screen: string, slime balls, ender pearls, blaze rods | the pack has **27 slots and one upgrade slot**; string (3 to 4 credits), a slime ball (14), an ender pearl (**120**, four on the shelf) and a blaze rod (**200**, three on the shelf) are on sale because there are no spiders, slimes, endermen or blazes |
| 2 | Wear it, press **B**, put in a stack of food, a tool and a few ingots; close it; take it off and put it on the ground (sneak and right-click a block); open the placed pack with a right-click | the contents are the same in the worn and the placed pack; sneaking with an empty hand and right-clicking the placed pack picks it up with its contents |
| 3 | Craft or buy an upgrade (*Pickup*, *Filter*, *Magnet*, *Feeding*, *Restock*, *Deposit*, *Crafting* and *Jukebox* are switched on, and the first *Stack* upgrade, one per pack; every other upgrade is off) and put it into the pack's upgrade slot (the tab on the pack screen's left edge). Walk over dropped items with the Pickup upgrade on | the item is picked up into the pack. The *Magnet* has range 3 and works only while worn. *Feeding* eats real food from the pack when you are hungry and works **only in a worn pack** |
| 4 | Try to open a **second** backpack while wearing one, carry three, then four | the screens work; more than two packs slow you down |
| 5 | In a Crew home: put a backpack on the floor in the shared room. Let the other player open it, then take it away; then make it private (*Home > Make private*) and let the other player try | a Crew member with the storage right can open and pick it up; after it is private only you can; a visitor who is not in the Crew can do neither, and a hopper placed beside it by someone without the right is refused |
| 6 | Die with a full backpack on (`/kill`), respawn, find the grave of items | the pack and its contents are on the ground where you died, and the same contents come back when you pick it up |
| 7 | Stop the server, start it, log in | the worn pack and its contents are as you left them. The server log has the line `[UC-BACKPACKS] the running Sophisticated Backpacks configuration matches the Underground City policy` |
| 8 | Wait for a night (`/time set night` as an operator is fine) and walk outside, into a cave, into a building | **no zombies, skeletons, creepers, spiders or other hostile creatures**; animals, villagers and your pets are normal. The log line `[UC-SIMULATION] hostile creatures: SIMULATION` is at the top of the server log. Hunger and thirst still drain and a fall still hurts |
| 9 | As an operator, `/uc_simulation status`, then `/uc_simulation purge` | the profile, how many hostile creatures were kept out since the start, and (after the purge) none in the loaded chunks |

Things to write down: did the pack feel like a convenience or like a trap; was 70 credits a lot or little for your first hour; did you miss the disabled upgrades (smelting, tanks, void, infinite stacks); was the world too quiet at night; did anything take longer than a second to open.

## 6d. Rent, ownership, skills, checkpoint and vehicle test (about 40 minutes, `-PucExtraSets=survival` optional)

**Written for the October 20 pass. Everything below passed as scripts, Forge GameTests and photographed screens in the cloud; no person has done any of it.** The prices are the model's ([economy-model.md](../technical/economy-model.md)), not a measurement of play, so *whether they feel right* is exactly what this section is for. The time rent is charged in is **active play time** (an hour of rent is 60 minutes during which someone who lives there is online), so for the test you will want to let some time pass; the notes say how to shorten it.

| # | Do this | You should see |
| --- | --- | --- |
| 1 | Alice walks to the **Row Motel** (beside the Row Flats), opens an *Operations Terminal > Crew > Home > Find a home* | *Row Motel Room 1..4*: *Rent 10/h*; the first two hours are **free** (the city's welcome, once per person), no deposit |
| 2 | Alice presses *Rent* on a room, then opens *Your homes* | *RENTED*, *PAID UP*, "Paid ahead 2 h", the minutes to the next payment; two containers, one of them may be private |
| 3 | Stay in the room 5 minutes, then log out for 10 minutes and back in | the minutes to the next payment **went down only while you were online**; nothing was charged while you were away |
| 4 | Alice earns about 100 credits on a work order, then rents **Row Flat 1** (*Rent 40/h, deposit 80* in Valewood; other cities differ) | the deposit and the first hour are taken from **her** wallet; the page shows deposit, containers (5), what the flat has (bed, crafting, furnace, terminal) |
| 5 | Press *Pay 3 h*, then *Auto: on* | three hours are added ahead; with auto on, each hour is paid when it falls due **if the wallet has it**, and simply stays unpaid if it does not (nothing is taken beyond the rent) |
| 6 | Let the flat go unpaid for an hour of active play at a time (leave *Auto* off, keep your wallet low) and watch the page | *OVERDUE*, then *FINAL NOTICE*, then *LAPSED* on the fourth unpaid hour. **Your chests and your door stay yours**; you can no longer build or use heavy workstations there; paying all that is owed restores everything at once |
| 7 | Press *Move out* twice (it asks "Really?") while a chest in the flat still holds something | refused with a line saying how many stored things remain: **nothing is thrown away with a home**. Empty the chest and try again: the deposit comes back, less any unpaid rent |
| 8 | Bob (in the Crew) rents or buys a home *For: the Crew*, paying from the *Crew bank* (founder: *Rules* lets the bank be spent for homes), then both log in and out | one bill for the home, paid from the bank, however many of you are online; the clock runs while **at least one** of you is online, and not at all while both are away. Both can live there; a plain Member can use their storage rights but cannot pay or move out |
| 9 | Disband the Crew, or have Bob leave | Bob's own homes are untouched; Crew homes go to the founder with their debts and deposit, and **automatic payment is switched off** because the bank is gone |
| 10 | Buy a flat (about 2,000 credits) or the Row House (about 7,000; the model needs hours of play for that): note *Upkeep*, *Sell back*, the three **Upgrades** (*Locking storage racks*, *Workshop fit-out*, *Running water*) | an owned home has a small upkeep and can be improved; *Fit* is greyed out unless you can afford it; selling back pays 60 percent of the price paid, less anything owed |
| 11 | Place a ninth container in a home that allows eight, place a fire or lava, use an anvil in a flat without a workshop | each is refused with the reason; with the matching upgrade it works |
| 12 | *Skills* page: scroll the tree with the wheel, the arrow keys and by dragging; open a skill and read its **effect line** | one row per notch, no card cut in half at the top, "More below" in the middle heading; each skill says what it does ("+6 percent on underground contracts", "+10 percent bales", "upgrades cost 8 percent less"); a locked skill says why |
| 13 | Learn a skill that raises payouts; complete the same order before and after | the credits shown on the order card are the credits paid; the receipt in the Ledger says so |
| 14 | With the **vehicles and boats** sets: look at *Bicycle* (60 credits) and *Motorboat* (600 credits, Valewood only, one in stock) on a Valewood terminal | priced; limited stock shared with everybody; the cars are *not* sold (a bench-built vehicle is a decision for the owner, see the roadmap). Whether the vehicle needs fuel is **not** implemented |
| 15 | As an *underground* career player, carry the **restricted crop** through a **checkpoint** with it in a **backpack**, and then with a *legal* item in the same pack | the search opens the backpack, takes **only** the prohibited goods, shows what and why on the screen, records the reason, and leaves the legal items alone |
| 16 | Open a **home chest** and a **neighbour's flat** while a checkpoint is nearby | **nothing is scanned or seized from a home**: only what you carry through a checkpoint is searched |

**Waiting:** the game has no command that speeds the rent clock; the cloud tests used an adjustable test clock that is not exposed. To see the later steps (*OVERDUE*, *FINAL NOTICE*, *LAPSED*) without a long wait, stay with a very low wallet and play normally; each step is one more hour of active play at the home. The first two steps of the motel are on the welcome hours, so they cost nothing.

Things to write down: was rent a pleasant pressure or an annoyance; was the motel's welcome enough to understand it; did *LAPSED* feel fair or frightening; did you ever not know who paid; did a skill's effect feel real or like a number; did the checkpoint ever take something that was legal.

## 6e. Furnished cities, burglary, police, quarry and residents (about 45 minutes, a NEW world only)

**Written for the October 21 pass (D-117 to D-124). Everything below passed as GameTests, a scripted client or photographs in the cloud; no person has done any of it, no furnished building has been walked through by a person, and the numbers (alarm odds, police times, quarry yield) are first guesses.** The furnished and scarcity packages change how *new chunks* generate, so they need a **new world with its own name**. The game refuses to start an existing world with different packages (`uc_generation_packs.json`), so an old world cannot be altered by mistake, but make a backup of `run-dev` first anyway (section 2) and use a different `-PucDevWorldName`.

Start (Windows spelling; the extra sets are the furnished cities with their Modern Life furniture, and the world with no natural ore):

```text
.\gradlew.bat runClient -PucWorldLayout=archipelago -PucPack=world,furnished,scarcity -PucDevWorldName=UC-Living-Test
```

(For two players, start the dedicated server with the same `-PucPack` and the same world, as in section 3, and both clients with the same sets.)

| # | Do this | You should see |
| --- | --- | --- |
| 1 | Arrive, register, walk to Valewood (a bridge away from the landing) and along its streets by day | houses of two storeys with fenced gardens, a shop with a striped awning, cottages; different from Ironhaven's brick tenements and workshops and Blackwater's concrete apartments, offices and club. Note anything that looks empty, floating or wrong |
| 2 | Look through a house window; stand at its door | furniture inside, lit lamps; a locked door (two thirds of the buildings are sealed). Nobody is inside by day unless a resident is at home |
| 3 | Return to the same house **at night** (let the day pass, or use `/time set 18000`) and look through the window | a resident beside a bed, if one is home tonight (not every night). Speak to a resident you can reach: a line about their evening |
| 4 | In **survival**, with 5 lockpicks, pick the front door of a house or a flat block (**read the lock screen**: press where the needle feels *close*, hold until the pin sets) | the lock opens; the notice says *Lock picked cleanly* if your pick never strained far, *Lock picked* if it did |
| 5 | Try buildings until you hear a **bell** and see *An alarm went off* (or: *Something in here is wired to an alarm. Your pick was clean and it did not hear you*) | the bell rings at the door for twenty seconds; the notice says the police have been called |
| 6 | Find the stash (a safe, if the building has one), pick it, take something | the item has a red line *Stolen goods (Valewood)* |
| 7 | Leave and stand in the street. Count the time since the alarm | officers (named "Officer ...", in a guard's outfit) come from a street nearby: about two minutes after the alarm in a flat block, less in a tower, an office or a club. They say "Police! Stay where you are." |
| 8 | Stand still: let an officer reach you | an arrest: the stolen item is gone, honest goods stay, a fine (up to your wallet), heat rises by 3 in that city. *Heat* shows on the status display |
| 9 | Repeat, but **run** (sprint) away from the officers, or hide out of sight for two to three minutes | *You got away* / *The police have gone*; nothing is taken |
| 10 | Repeat, but go in **at night** and again **by day while people are in the street** | by day, a passer-by or a resident may *see you* and call the police without any alarm; at night in the street, fewer do |
| 11 | Log out while officers are on the way, log back in | nothing is pending: **nobody is punished for being away** |
| 12 | Walk east from the landing about 150 blocks to **the old quarry** (a stepped pit with a sign). Mine an iron, a coal and a copper seam with a pickaxe; smelt them | ore and ingots. Come back after **20 minutes of play** (not 20 minutes away): the seams you mined have grown back. `/uc_site status` (operator) shows how many are waiting |
| 13 | Buy raw materials from the shelves (the Operations Terminal's trade page: iron, coal, copper, redstone, lapis, gold, diamond), then try to buy more than the shelf holds | prices from 2 credits (charcoal) to 150 (a diamond); the shelf is small and refills slowly; it is shared with every player |
| 14 | Dig anywhere in the countryside for ore | none: the new world has no natural ore (caves, stone and everything else are normal) |

Operator commands that help: `/uc_police status`, `/uc_household status`, `/uc_site status`, `/uc_population status`. Do not use an old world for any of this.

Things to write down: was the lock screen readable; did a quiet pick feel like a skill; was the alarm a surprise or fair; was two minutes too long or too short; did the officers look like police and arrive from somewhere believable; did being arrested feel fair, annoying or ignorable; did anything look empty, floating or broken in the furnished houses; did a resident look like they lived there; was the quarry worth the walk; did the shelves make you feel you could not get iron.

## 6f. The first hour, shops, homes in the cities, fences and alarm panels (about 60 minutes, a NEW world only)

**Written for the October 22 to 23 pass (D-125 to D-137).** A script played most of the first hour through a real client (the sprint report, [gameplay-completion-pass.md](gameplay-completion-pass.md), lists every step and which parts were set up by commands); **no person has done any of it**. The first hour is the most important thing to try: the script cannot tell whether it is clear, slow, confusing or fun.

Start exactly as in 6e (a new world with the furnished cities and no natural ore):

```text
.\gradlew.bat runClient -PucWorldLayout=archipelago -PucPack=world,furnished,scarcity -PucDevWorldName=UC-First-Hour
```

| # | Do this | You should see |
| --- | --- | --- |
| 1 | Arrive, register (any city, **Smith** to follow the script), and follow the arrow on the side display | the employer's office in Founders Row; the side display and the arrow stay clear of notices (they slide in below or beside the display; while a terminal or a shop covers their corner they wait and appear when it closes) |
| 2 | Speak to the employer, open the orders, try the iron-tools order, then accept the stone-tools one | the iron order is greyed (level); the stone order is accepted and shows on the display |
| 3 | Chop a tree, walk east to **the old quarry** (a stepped pit), mine cobblestone from its walls | logs and cobblestone; the quarry's seams grow back after 20 minutes of play |
| 4 | Craft planks, a crafting table, sticks and three stone pickaxes; deliver them at the employer | 24 credits and career XP |
| 5 | On the terminal's TRADE page open the civilian shop and buy rations; try something you cannot afford | bread for 20 credits; the unaffordable click changes nothing |
| 6 | Press **K**, open HOME, then **Find a home** | the free motel rooms are listed **first** (the city's welcome: two free hours); rent one, walk into it, put something in its barrel |
| 7 | Spend the skill point on the SKILLS page; save and quit, reopen | everything is as you left it, no second registration |
| 8 | After the second order's step says **Travel to a rival city**, follow its arrow ("Road to Valewood" or "Road to Blackwater") through Ironhaven to the road, up the ramp onto it, and across the bridge | the road is about 720 blocks from the landing, through Ironhaven's blocks (**time it: how long, and was the way obvious?**); about four minutes on the road; at each end a cut ramp joins the road to the city; Valewood's houses have gardens and porches |
| 9 | In a furnished **shop, pub, corner store or bakery**, speak to the person behind the counter | their shop's goods on the Trade page, from a small shelf shared with that city's shops of the kind |
| 10 | Between 08:00 and 08:40 (game time) watch a shop's street door; between 17:00 and 17:40 a house's or a tenement's | the shopkeeper walks up the street and in (and out again after 18:00); residents come home from five o'clock, each at their own minute |
| 11 | Ask for **Find a home** inside Ironhaven's tenements, the pub, the bakery or the corner store | flats and rooms to let on the Row's terms; a home is never emptied of your things for not paying |
| 12 | (Technician with **Bypass Tooling** and a **Bypass Kit**) pick the street door of a tenement or a shop with a strained pick; a panel by the door beeps. Right-click it, watch the signal, key it back with 1 to 4 | the panel's light goes blue: nobody is called for ten minutes. Key it wrongly on purpose in another building: the alarm rings and heat rises |
| 13 | After 21:00 take a stolen item to **The Yard** (Ironhaven's warehouse office) or **The Back Room** (Blackwater's club office) | the fence's screen: 45 or 50 percent of the item's honest price, less for goods stolen in its own city; a short budget says on each row what it can pay now |

Operator commands that help: `/uc_police status`, `/uc_household status`, `/uc_site status`, `/time set 18000`. Do not use an old world.

Also look, wherever a building stands higher than the ground beside it, at its retaining wall: it should be the city's stone bricks (before October 23 such walls were sofas, beds or bookshelves).

Things to write down: where you were lost in the first ten minutes; whether the arrow and the side display were enough; whether the walk from the landing to the road was obvious; whether the shopkeepers and the people walking in and out made the streets feel alive; whether the alarm panel was readable and fair; whether the fence's prices felt worth the risk.

## 6g. The roads: roadside places, encounters, the road ends, and (optional) the SecurityCraft trial (about 45 minutes, a NEW world only)

**Written for the October 23 second pass (D-138 to D-141).** A script walked the Ironhaven-Valewood road through a real client, took a courier's letter (set up by an operator command), ate at the Crossroads Diner and was paid for the letter in Valewood ([living-world-roadside.md](living-world-roadside.md)); **no person has done any of it**.

Start a new world as in 6f (`-PucWorldLayout=archipelago -PucPack=world,furnished,scarcity`). The places and encounters exist only in a world started this way.

| # | Do this | You should see |
| --- | --- | --- |
| 1 | Walk out of Ironhaven onto the Valewood road and keep walking | a farm with a stand and a farmhouse (Harrow Farm) beside the first stretch of land, later the Crossroads Diner with a forecourt; each built the first time you come near (a short hitch is possible then) |
| 2 | Step off at the diner and speak to Dot Mallory, the cook | hot food over the counter at the city's prices or a little more; nothing is free |
| 3 | Keep travelling the roads for a while (five minutes of road travel or so earns an encounter) | sooner or later, beside the road ahead: a stranded driver, a courier with a letter, goods off a lorry (only on the two roads to Blackwater) or a spilled load. Walk past one: nothing happens. Talk to one: it says what it wants |
| 4 | Take a courier's letter to the city it names and hand it in at a terminal or a shopkeeper's counter there | its fee, once; a second copy is worth nothing |
| 5 | Walk off each road end into its city, especially the two ends at Blackwater | a ramp from the road up or down to the city's ground; no step higher than one block |
| 6 | Visit Kessler Salvage (Ironhaven-Blackwater road) and break a scrap heap; come back an hour of play later | nuggets, sometimes copper or a part; the heaps grow back while you play, never while you are away |
| 7 | Rent a Saltmarsh Motel room or ask about Harrow Farm's farmhouse (Find a home near them) | the rooms and the farmhouse on the usual terms |
| 8 | Optional, a separate new world started with `-PucPack=world,furnished,scarcity,securitycraft`: an operator looks at a city building's street door and runs `/uc_security install` (it prints the door's code) | a keypad door, a laser, a siren, a camera and a light. As a stranger, type the code: you are in, but it counts as a break-in. Walk across the laser: the siren and the police. `/uc_security keyholder <you>`: then nothing rings |

Things to write down: whether the places look like they belong; whether stopping was worth it; whether encounters came too often, too rarely or at bad moments; whether the SecurityCraft devices feel like part of this game.

## 6h. Becoming a thief: casing, locks, searching rooms, fences and the Thief page (about 45 minutes, a NEW world only)

**Written for the October 23 to 24 third pass (D-142 to D-156).** Scripts played one new burglar through a real client, from casing a Valewood house to learning the first Thief skill (Thief run 12, `RESULT pass`; the report lists its 22 shortcuts), an advanced burglary of the Blackwater club (heist runs 8 and 10), an officer on the beat catching a thief at a lock (patrol runs 2 and 6) and a depot manifest delivered (freight run 5); **no person has done any of it**. The Thief track is every career's: play it as a Farmer, a Smith or anyone.

Start a new world as in 6f (`-PucWorldLayout=archipelago -PucPack=world,furnished,scarcity`). Lockpicks are crafted (two iron nuggets and a stick make two) or bought in the Old Cannery's den on the Valewood-Blackwater road, which also sells a Pro Pick Set now and then.

| # | Do this | You should see |
| --- | --- | --- |
| 1 | Press **K** and open the **Thief** page | Thief level 1, no Thief points, the tree in three columns (Locks, Stealth, Fencing) with Casing at the top; career points are separate |
| 2 | By day in Valewood, crouch with an empty hand and right-click a house's street door | the casing card: how well off the place is (six pips: Comfortable for a house), the lock (a deadbolt) and what you could read with Casing. A house **to let** shows nothing: it is a vacant listing, not a target |
| 3 | Pick the door (lockpicks in hand, right-click) | the lock screen; three pins. A notice: +75 Thief XP for the first time inside a comfortable house. Heat rises in the city |
| 4 | Right-click the furniture: night stands, kitchen cupboards and drawers, the fridge, bookshelves, the bin | a short bar and "Found in the …": room-fitting finds, marked stolen. Try the same piece again: "Somebody has been through this already" (it refills after four hours of play). A resident in the room may see you |
| 5 | Try a **shop** in its hours: walk in by the street door; try to search its furniture | the door opens for a customer; the search is refused while the place is open |
| 6 | After 21:00 take the goods to the Blackwater club (**The Back Room**) or Ironhaven's warehouse (**The Yard**) | the club's iron door opens for somebody carrying stolen goods (no pick); upstairs in the back office the fence's screen pays half of what the goods are worth; paper and bread are not bought |
| 7 | Open the Thief page again | Thief level 2 after one house and one sale (80 XP); spend the point on **Casing** and case a house again: now the alarm, the cameras and who is in |
| 8 | In Blackwater's apartment tower, the club or Ironhaven's warehouse, look above the street door inside | a camera. Walk in front of it in a building that is not open: "A camera has you", and the police are called |
| 9 | Let an officer see you, then run and break their line of sight (round a corner, through a door) and keep going | the officers go to where they last saw you and search round it; out of their sight for 30 seconds and 20 blocks away: "You got away", and Thief XP |
| 10 | Rent a flat in an Ironhaven tenement (Find a home) and go in and out by its iron street door | the iron door opens for you (and nobody else); the building's camera ignores you |
| 11 | Stand in a Valewood street for about two minutes and watch | an officer walks a beat near you (a name over their head) and goes again after two to three minutes; Ironhaven sends two, more often; Blackwater one, less often (D-155) |
| 12 | Start to pick a house's door while that officer can see you (do this on purpose once) | the lock is put away at once (no pick lost), "… is on the beat and saw you at the lock", and the officer comes for you: stand still and you are arrested "for trying to break in"; run and break their line of sight and you can still get away |
| 13 | Pick a door with an officer near but round a corner | a notice, "Footsteps": an officer on the beat is close and cannot see you yet |
| 14 | By day, try the Blackwater club's back-office safe while the club is open as a bar | refused: nobody cracks a safe in a building while it is open. At night, with Safecracking and a Pro Pick Set, it opens on the lock screen |
| 15 | On the Ironhaven-Blackwater road, at the Northgate Freight Depot, go into the office and ask Mara Quint "Any freight going out?" | a sealed manifest for another city with its fee on it; asked again, "Deliver that one first"; a terminal or counter in that city pays it once (D-156) |

Things to write down: whether casing told you what you needed before you broke in; whether the lock felt like skill or luck; whether a house's finds felt worth the risk and the walk to a fence; whether the police felt fair (could you see why they found or lost you? did you notice the officer on the beat in time?); whether the first Thief point came at a good moment.

## 6i. Three sessions for the alpha (a NEW world each time)

**Written for the October 25 seventh pass (D-175 to D-179) and brought up to date for the eighth (D-182 to D-186: the Smith's turning and machinery, batches inside and outside homes, business hours, roadside crates).** The cloud drove each career's trade, trade premises, homes, the back channel, an unmarked gun and the Smith's engineering through scripted clients, and a Crew contract with two scripted clients on a dedicated server (`seventh-pass-alpha-readiness.md` and `eighth-pass-living-sandbox.md` say which runs passed and list every shortcut). **The current branch and its recent gameplay systems have not received human playtesting.** These three sessions are what the alpha milestone is waiting for (criterion 12 in `roadmap.md`).

Two kinds of play, never mixed up in a report:

* **Track A, organic:** no commands, no teleports, no time skips, no operator rights. You start with what registration gives you. Sessions 1 and 2 are Track A.
* **Track B, prepared:** an operator sets the character up with `/uc_prepare` (career or Thief level, credits, a named kit). Every use is written in chat and in the server log as `PREPARED (Track B)`, so a screenshot or a log shows which kind of play it was. Session 3 is Track B.

Use the furnished cities and the scarcity package (`-Sets furnished,scarcity`, the default of `uc-new-world.ps1`). Xaero's Minimap is optional (`-Xaero`): with it, a tracked destination is also a waypoint on the minimap.

### Session 1: the first hour on your own (30 to 60 minutes, Track A, each player alone)

Start: `.\scripts\windows\uc-new-world.ps1 -Name <you> -World UC-S1-<you>` (each player on their own PC or copy).

| # | Do this | You should see |
| --- | --- | --- |
| 1 | Register: a home city, then a career (Farmer, Vintner, Smith, Gunsmith or Technician) | your career's kit: a station (Mill, Fermentation Vat, Machinist's Bench, Gunsmith's Bench) or the Technician's toolkit and computer, and its recipes already in the recipe book |
| 2 | Right-click a public **Operations Terminal** (one stands inside Valewood's General Store, Ironhaven's workshop and Blackwater's corner store) and read the **Journey** tab | what to do first, in order; the **Work** tab lists orders your level can take |
| 3 | Set your station down outside a building and make your first batch (Start, wait, **Take**) | the time left counts down on the screen and **Take** lights by itself when the batch is done; a part you lack says where it is sold (a shop, the exchange or another trade) |
| 4 | Buy what you lack from a shopkeeper: walk in by day, right-click the person behind the counter, **What do you sell?** | the shop's own shelf; buying greys the buttons for a moment, then they light again. Stepping into a back room makes the household (or a camera) say *Staff only*; leave and nothing happens |
| 5 | Sell or deliver what you made (a shop that buys it, or a work order) | credits in your wallet, career XP; your first skill point by about level 2 |
| 6 | Learn a skill on the **Skills** tab (the **General** tab shows Trading and Social, earned by playing) | the node's effect is written on its card; a level 3 legal node costs one point |
| 7 | Rent the cheapest home you can (press **K**, **Home**, **Find a home**) | the deposit and the hourly rent are stated before you agree; rent is charged only while somebody of the household is online |
| 8 | Optional: case a shabby house at night and try a lock | it is your choice; police chase only what they see |
| 9 | **If you are a Smith:** on the Machinist's Bench pick the drive shaft and press **Turn** | a drawing: click a section (Shift- or right-click another to stretch it), pick Roughing or Finishing, set the line with + and -, **Cut**. Rough to one step above each line, then finish. A section cut too far turns red: **Sleeve** it (a nugget). Close takes nothing. Fewer passes, better grade; the bench keeps a jig |
| 10 | **If you are a Smith in a city:** wait for a **Service call** notice (one every 10 to 16 minutes of play) | right-click in the air with the Engineer's Toolkit for the list, **Track** the call, right-click the machine's drive housing beside its street door with the toolkit, replace the red parts, gear the open stage to the plate, **Run test**. Paid once; the machine runs again |

Write down: the first moment you did not know what to do; whether your career felt like a trade or a menu; whether you ever felt stuck for money or for a part.

### Session 2: a co-op sandbox (60 to 120 minutes, Track A, two players, a dedicated server)

Start, with real accounts (the recommended way, and the only one for a server anybody reaches from outside your home): the authenticated server pack of section 3b, `online-mode=true` and a white-list of your two names, with the furnished and scarcity sets on server and clients alike. Two developer clients at home instead: the host runs `.\scripts\windows\uc-server.ps1 -Sets furnished,scarcity -World UC-S2 -LanOfflineMode -AcceptEula` (offline mode: on your own home network only, never port-forwarded or otherwise reachable from the internet; section 3 explains why), and each player `.\scripts\windows\uc-client.ps1 -Name <you> -Sets furnished,scarcity -Join` (the second player from a second copy, `uc-second-copy.ps1`). Pick **different careers**, for example Farmer and Smith.

| # | Do this | You should see |
| --- | --- | --- |
| 1 | Make a Crew (K, **Crew**) and invite the other player | both on the roster, each with their own wallet and career; the Crew bank is optional |
| 2 | Play your own careers for twenty minutes | separate orders, separate stations; neither of you is ever charged while away |
| 3 | Open **Work** in Ironhaven: the **Crew** line for the depot canteen contract | its parts listed per career; your part shows **Deliver**, the other trade's part says it is theirs or that you may **Supplier** it |
| 4 | Each delivers their part; one of you presses **Supplier** on the part nobody makes | each paid at once; the supplier's price is stated first and comes out of the asker's wallet; when the last part lands both get an equal share of the bonus, once |
| 5 | One player disconnects in the middle and comes back | nothing lost or paid twice; a share earned while offline arrives at the next login |
| 6 | Valewood: contribute to *Reopen the Valewood market* | after the fifth contribution two stalls stand in the street and residents talk about them |
| 7 | Rent a home together or a commercial workshop (trade premises) and set a station in it | a station in trade premises runs one more batch a quarter faster; three bulk orders accept you |
| 8 | Stop the server (`stop`), start it again with the same command, both rejoin | homes, stations, the Crew, wallets and orders exactly as they were |
| 9 | Walk a road between two cities and look round each roadside place and shelter for a **battered crate** | each of you finds something in it once (different things); going back finds nothing more |
| 10 | Go out at 20:00: the Blackwater club opens 18:00 to 03:00, the Ironhaven pub 12:00 to 02:00, shops 08:00 to 18:00 | the glance card at a street door says the hours; the street round an open bar is busier at night |
| 11 | One of you starts a batch at a station inside your home, the other at a station outside on the street, and both log off | inside a home a batch is never anybody else's; outside it waits for two hours of its maker's own play, then anybody may take it and the maker is told who did |

Write down: did the Crew contract feel like working together; was the split fair; did one of you wait on the other; was anything confusing about who owns what.

### Session 3: prepared advanced play (about 60 minutes, Track B)

Start a new world as in session 1 (single player has commands on) or use the session 2 server with `op <name>` typed into the server window. Then, for the player who tries each part:

| Part | Setup (logged as PREPARED) | Then, without commands |
| --- | --- | --- |
| Trade premises and a bulk order (Smith) | `/uc_prepare level @s 5`, `/uc_prepare credits @s 1000`, `/uc_prepare kit @s smith_crate` | rent Ironhaven's commercial workshop on the Home page, walk in with the key, set the Machinist's Bench down inside, make two batches at a time, learn Forge Fundamentals and deliver *Fittings by the crate* at the workshop's terminal |
| A registered and an unmarked gun (Gunsmith, firearms set) | `/uc_prepare level @s 8`, `/uc_prepare credits @s 450`, `/uc_prepare kit @s gunsmith` | learn Unregistered Builds, buy parts, make an unmarked parts kit at the bench and build the Glock 17 at TaCZ's table; the gun has no serial and a checkpoint treats it as restricted |
| The back channel (Technician) | `/uc_prepare level @s 7`, `/uc_prepare kit @s technician` | learn Bypass Tooling, take a quiet job on the computer, track it, crouch and use the toolkit on the building's utility panel and solve the grid; its cameras are looped for a while and the kit is used up |
| The bonded store (Thief) | `/uc_prepare thief @s 8`, `/uc_prepare kit @s thief` | learn Professional Tools, take Kessler's job in Blackwater, track it, case and open the store after 03:00, find the seized shipment and deliver it |
| A villa and its upgrade | `/uc_prepare credits @s 26000` | buy the Valewood villa on **Find a home** and fit an upgrade on **Your homes** |
| A machine beyond a new Smith (Smith) | `/uc_prepare level @s 8`, `/uc_prepare credits @s 400` | learn Millwright on the Skills page, wait for (or find on the toolkit's list) a tier III or IV call, buy the parts at the Builders' Yard if you lack them, and repair it: two gear stages, a fourth test run |

Write down for each part: did it feel prepared for, dangerous or rewarding; what was unclear; what would you have done next.

**The question that matters most, after every session: Would you voluntarily keep playing, and what would you want to do next?**

## 6j. Tenth pass: clean streets, the five general skills, shortages and a supply chain (about 60 minutes, a NEW world)

**Written for the October 27 tenth pass (D-196 to D-204, `tenth-pass-living-world.md`). The current branch and its recent gameplay systems have not received human playtesting.** The street and road fixes change only chunks generated after them: use a **new** world, never an old one (and never `UC-Alpha-Test-01`).

Get the branch on both PCs: `.\scripts\windows\uc-update.ps1 -Branch feature/tenth-pass-living-world` (compare the commit it prints). Then the host: `.\scripts\windows\uc-server.ps1 -Sets furnished,scarcity -World UC-T10 -LanOfflineMode -AcceptEula`; each player: `.\scripts\windows\uc-client.ps1 -Name <you> -Sets furnished,scarcity -Join`. One player registers as a **Farmer in Valewood**, the other as a **Vintner in Blackwater** (Track A: no commands).

| # | Do this | You should see |
| --- | --- | --- |
| 1 | Walk the streets of your city and the road out of it to the next city | paved streets with no grass blocks, dirt, stone, mushrooms or flowers on the paving and no bare rock verges; the road's end walks onto the city's ground |
| 2 | Look at the ground floors and cellars of a few buildings | no patches of dirt, gravel, granite, diorite or andesite in the floors |
| 3 | Walk for five minutes, then open the terminal's **Skills** page, **General** tab | Fitness XP has grown (about 2 XP each ten seconds of real walking); standing still or walking in a tight circle adds next to none |
| 4 | Craft a target block (hay bale and redstone), place it, and shoot it from ten blocks or more with a bow (or a TaCZ gun with the firearms set) | Shooting XP for each hit, more near the centre; the same block pays once in two seconds; nothing from closer than ten blocks |
| 5 | The Farmer founds a Crew on the Crew screen (K) and invites the Vintner; the Vintner accepts | both in one Crew |
| 6 | The Farmer: grows sweet berries and beetroot outside the city (the city's own gardens belong to their households), mills four beetroot into three sugar at the Mill | sugar from the Mill and Farmer XP when you **Take** it |
| 7 | The Farmer travels to the Vintner and drops berries and sugar with **Q**; the Vintner picks them up | the goods move from one inventory to the other |
| 8 | The Vintner: a batch of berry wine at the Fermentation Vat (six minutes of play), then, after 18:00, talk to the barkeeper of a Blackwater club and ask "What do you sell?" | the counter shows the wine line as **"short: buys at 19"** (on a narrow screen **"short: 19"**) while the shelf holds three or fewer; selling three pays about 57 credits (more for a Fine bottle) and the shelf count rises |
| 9 | The Vintner pays a share into the Crew bank (Crew screen, Money); the Farmer withdraws it | both wallets and the bank change by exactly the amounts |
| 10 | Stop the server (type `stop`), start it again, both rejoin | wallets, the Crew bank, the XP, the inventories and the two stations are as you left them |
| 11 | Hurt yourself a little (a fall), buy a **First Aid Kit** at the terminal (18 credits) and hold right click with it | three hearts back; a second one waits thirty seconds; at full health it does nothing |
| 12 | Talk to the same shopkeeper after three trades | they greet you as a regular |

**Private buildings (the owner's PC only):** section 6k.

## 6k. The owner's buildings first (C70), on the owner's PC

**Eleventh pass (D-205). The staging ran on the owner's pack in the cloud and the scripts ran under PowerShell 7 on Linux against stand-ins; none of it has been run on Windows. The current branch and its recent gameplay systems have not received human playtesting.** The private pack, its port, the work folder, the selection and every screenshot of it stay on your PC: never in this repository, a GitHub issue or a public upload.

1. **Once:** JDK 17 (section 1), Python 3.10 or newer from python.org with *Add python.exe to PATH* ticked, and `python scripts\install_git_hooks.py` (a commit that would carry private content then stops).
2. **Stage the buildings** (again after every `uc-update.ps1`; a launch refuses a pack staged by an older porter or wiring):

   ```
   .\scripts\windows\uc-private-stage.ps1 -Source "<the C70 folder or zip>" -Holder "C70 and team" -Summary "<what the permission covers, in your words>"
   ```

   `-Holder` and `-Summary` are needed only the first time, and only when the download has no `uc-private-pack.json`. The script finds the Lost Cities data in the folder or zip (it checks the zip's paths first and copies nothing but the `lostcities` data), keeps the original untouched, and the first time boots a disposable server for a few minutes to read the furniture set's blocks (nothing private is loaded in it). Then it ports, chooses the buildings, wires them into the three cities, seals the result and installs it as `%USERPROFILE%\UndergroundCityPrivate\runtime\c70-port`. The pack it replaces is kept in `runtime-work\previous`.
3. **The choice of buildings.** Without `-Selection` the script chooses from what each building is called and what its plan holds: houses for Valewood, flats for Ironhaven and Blackwater, workshops and warehouses for Ironhaven, offices for Blackwater, shops, cafés and public buildings spread over the three. A house needs a bed, a street door and a roof; flats need a storey with a bed. Anything it cannot place for certain (a building whose name names no use it knows, a house without a bed) is left out and listed with the reason in `runtime-work\selection-auto-report.json`. The selection it wrote is `runtime-work\selection-auto.json`: copy it, change it and stage again with `-Selection <your copy>`. On the cloud's copy of the pack it chose every building the hand-made selection had chosen and 26 more, and left 24 out.
4. **Play in a NEW world.** Alone: `.\scripts\windows\uc-new-world.ps1 -Name <you> -World UC-C70-1 -C70`. Two players: the host runs `.\scripts\windows\uc-server.ps1 -C70 -World UC-C70-1 -LanOfflineMode -AcceptEula`, and each player runs `.\scripts\windows\uc-client.ps1 -Name <you> -C70 -Join`. Only the host needs the private folder; the other player needs the same sets, which `-C70` gives. A world that already exists keeps what its chunks were generated with.
5. **What is checked before anything starts** (`scripts\private_c70.py check`): the folder exists and is outside the repository; it holds packs and is not a pack itself; every manifest is one the game accepts; no pack is the unported original; the porter and wiring that staged it are new enough; and the sets include the ones the pack needs (furnished and furniture). Anything else stops the launch with the reason, and nothing is started.
6. **Homes in the owner's buildings** (D-206, [dwellings](../technical/dwellings.md)). A house over two chunks is one home, a terrace two, a block's flats one each behind its own door, premises one lease. A building whose rooms leave that uncertain (a hall open onto a courtyard, a tower with lifts) is not let, and `runtime-work\wiring-report.json` says why; `-Dwellings <file>` on `uc-private-stage.ps1` names its homes yourself. Worth trying with two players: rent a house that spans two chunks, check that its far rooms are yours, that the other player's key does not open its door, and that a flat's neighbour cannot open the flat.
7. **Reports.** `uc-logs.ps1` after such a session leaves the screenshots out (`-IncludeScreenshots` keeps them) and writes `<private>` for every block or building id of the private pack in the logs. Share the zip only with the owner and the people the permission covers.

## 6l. Eleventh pass: your own business and shortage contracts (about 45 minutes, a NEW world)

**Written for the October 28 eleventh pass (D-207, D-208, [player-business.md](../technical/player-business.md)). GameTested and run through scripted real clients only; the current branch and its recent gameplay systems have not received human playtesting.** The same launch as 6j (`-Sets furnished,scarcity`, a new world).

| # | Do this | You should see |
| --- | --- | --- |
| 1 | One player rents the **workshop's or the warehouse's premises** in Ironhaven on the Home page (K, Home, Find a home). Craft a **Business Counter** (three smooth stone slabs over planks, a chest, planks) | the premises are yours; the counter is in your inventory |
| 2 | Stand inside the premises and set the counter down facing the free floor where customers will stand | "Your business is set up in ..." |
| 3 | Hold goods a city's shop also sells (berry wine, bread, fasteners) and right-click the counter | they go onto the counter at the city's price (the chat says the price; 12% of it is tax). Dirt, goods over 60 credits, a named item or stolen goods are refused |
| 4 | Crouch and right-click it with an empty hand | "Open for business"; if it says why customers will not come (the hours, the rent owed, an empty counter), fix that |
| 5 | Between 08:00 and 18:00, wait nearby for a few minutes with the street busy | street people walk in through the premises' door to the counter, look round and buy one thing each; at most eight an hour of play |
| 6 | Right-click the counter with an empty hand (or type `/uc business`) | the statement: what sold, the tax, the till; then the till goes into your wallet, once |
| 7 | Let the rent fall behind (or move out), then look again | the counter sells nothing while the rent is owed; the stock and the till are still yours (`/uc business withdraw` takes the stock back) |
| 8 | The Vintner: leave a shop's wine line short for five minutes of play (its customers buy it down; the barkeeper says "We're nearly out") and look at a terminal in that city | a **Shortage** order for the Vintner ("Shortage: 4 Berry Wine for The Anvil"), with a notice when it was posted |
| 9 | Take it, then the other Vintner tries to take it | the second player is told somebody else has it |
| 10 | Deliver it at a terminal in the shop's city within thirty minutes of play | paid once, half as much again a bottle as the shop pays; the shop's shelf fills and the keeper stops asking; delivered in another city it is refused |
| 11 | Stop and restart the server with a contract open | the contract and its time left are kept |

## 7. Logs, screenshots and bug reports

* Server log: `rpg-core\run-dev\logs\latest.log` (authenticated pack: `build\server-pack\server\logs\latest.log`); crash reports: `rpg-core\run-dev\crash-reports\`.
* Client log: `rpg-core\run-dev\logs\latest.log` of the client's own checkout; screenshots (F2): `rpg-core\run-dev\screenshots\`.
* The Crew ledger's unresolved transfers (there should never be any): in the server console, `uc_crew pending`.
* After a session with the owner's private buildings, send the report privately, never as a GitHub issue (section 6k).
* Please report (GitHub issue or message): what you did, what you expected, what happened, the time, both players' names, and attach the two `latest.log` files and screenshots. Do **not** attach the world folder unless asked; it is large.
* If a transfer ever looks wrong: do not keep playing in that world. Stop the server, back the whole `run-dev` up, and report.

## 8. After the session (six questions)

1. **Would you voluntarily keep playing, and what would you want to do next?** (This is the most useful answer.)
2. Did the first ten minutes tell you what to do? What was the first moment you did not know?
3. Did your career feel like a trade, with choices, or like a list of orders?
4. Did the Crew screen explain itself? Which button did you not understand?
5. Did a home or the premises feel like yours, or like a menu?
6. Was anything unfair: a price, a camera, the police, a refusal you did not understand?
