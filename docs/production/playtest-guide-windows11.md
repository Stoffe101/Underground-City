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
git checkout feature/crews-shared-homes-and-finances
.\gradlew.bat runServer -PucAcceptEula=true
```

* The first run takes 10 to 25 minutes. When the console says `Done`, the server is up. `-PucAcceptEula=true` writes `eula.txt`; that is your acceptance of the Minecraft EULA.
* On a brand-new world, the server builds **Founders Row and the Row Flats** (three leasable homes) near the spawn and moves the spawn to the Row's gate. The log says `[UC-DEV] Founders Row built` and `[UC-DEV] Row Flats built`.
* **Let your friend join with a launcher-less dev client:** stop the server, open `rpg-core\run-dev\server.properties`, set `online-mode=false`, save, start the server again. (Dev clients use offline profiles. Do this only on a network you trust.)
* Open the firewall prompt for Java on a private network if Windows asks; the server uses port 25565.

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

Optional survival set (backpack, thirst, kitchen, sleeping bags): add `-PucExtraSets=survival` to **every** command (server and clients), for example `.\gradlew.bat runClient -PucExtraSets=survival`. It adds the four opt-in mods to the complete pack and keeps the developer launch (the dev world and the Founders Row and Row Flats bootstrap) as it is. See section 6 for what is verified.

## 5. The Crew test (about 30 minutes)

Work through this with your friend in the world. Write down what happens when it is not what the step says; screenshots (F2) help.

| # | Do this | You should see |
| --- | --- | --- |
| 1 | Alice presses **K** (Crew screen), types a name, presses *Found Crew* | the Crew page with Alice as Founder; the Found button works as soon as the name has 3 letters |
| 2 | Alice finds Bob under *Invite a friend* and presses *Invite* | Bob appears under *Open invitations* |
| 3 | Bob presses **K**: an invitation is listed (the screen refreshes by itself every 5 seconds); *Accept* | both now see the same two members, with their own professions and levels |
| 4 | Each plays their own profession for a few minutes (take a work order, do a task) | XP, skills, wallet and heat stay personal; nothing from one appears on the other |
| 5 | Walk to the **Row Flats** (east of Founders Row, a short walk from the gate). Alice opens *Home* | *Homes for lease nearby*: two flats and the Row House with prices; the first lease costs the **starter price** |
| 6 | *Money* page: both **deposit** credits; then Alice leases the **Row House** with *Crew bank* | the bank shows the sum; the house shows as a **Crew** home on both screens |
| 7 | Step inside the Row House. Both use the chest, the furnace, the crafting table and the smithing table. Then try the chest of a flat that nobody has leased | the Crew can use its own house; an unleased flat lets you look but not touch |
| 8 | Bob looks at a chest in the house and presses *Make container private* on the *Home* page | only Bob can open that chest now; Alice cannot, and cannot break it |
| 9 | Alice on *Rules*: switch *Withdraw* on for members. Bob withdraws 10 | Bob's wallet goes up by 10 and the bank down by 10; the history says who did it |
| 10 | Both click *Deposit* quickly at the same time, or Alice clicks twice | each press is one transfer; the bank equals the sum of what really left the wallets |
| 11 | Both leave the game (log out). Stop the server with `stop`. Start it again (`runServer`) | the server starts without errors |
| 12 | Rejoin as Alice, then as Bob. Press **K** | same Crew, same members and roles, same bank, same homes, Bob's private chest still private, wallets as they were |
| 13 | Try the edge cases you like: Bob *Leave Crew*; Alice invites him again; *Rules > Money mode*; *Rules > Crew > Disband* (it asks for the Crew's name) | leaving keeps Bob's wallet and career; a disband splits the bank and passes Crew homes to the founder |
| 14 | Lease a flat for yourself alone (*For me*), then let the other player try its chest and door | refused with a line on the action bar; your Crew membership did not give them your flat |

Things that deserve a careful look: does anything feel unfair or confusing; are the buttons where you expect them (is the text readable at your screen size); did a transfer ever show the wrong number even for a moment; did a restart lose anything.

## 6. What works, and what does not

**Built and tested in the cloud (not by a person):** registration, the terminal, work orders, the regional exchange, the Public Ledger, skill trees, checkpoints and searches, firearms crafting with blueprints (with the firearms set), Founders Row, the Row Flats, Crews with a bank, shared homes and a server-side guard, the road waymarkers and wayside shelters. Two scripted clients have played the Crew steps above against a dedicated server that was then **killed without a stop sequence and restarted** (see the sprint report for the exact result).

**Not built, so do not look for it:** rent, evicting a tenant, moving a home between a person and a Crew, Crew contracts, burglary, raids, police patrols, populated daily routines, shops at wayside shelters, vehicle fuel, boat customs, per-player loot. The guard does not stop pistons, fluids, fire, mobs or item frames at a home's walls. Q Shop's own shop screens always spend your own wallet, even when the Crew shares finances.

**Survival set:** see the sprint report for what was run. It is opt-in; nothing in the default pack depends on it.

## 7. Logs, screenshots and bug reports

* Server log: `rpg-core\run-dev\logs\latest.log`; crash reports: `rpg-core\run-dev\crash-reports\`.
* Client log: `rpg-core\run-dev\logs\latest.log` of the client's own checkout; screenshots (F2): `rpg-core\run-dev\screenshots\`.
* The Crew ledger's unresolved transfers (there should never be any): in the server console, `uc_crew pending`.
* Please report (GitHub issue or message): what you did, what you expected, what happened, the time, both players' names, and attach the two `latest.log` files and screenshots. Do **not** attach the world folder unless asked; it is large.
* If a transfer ever looks wrong: do not keep playing in that world. Stop the server, back the whole `run-dev` up, and report.

## 8. After the session (five questions)

1. Did the first ten minutes tell you what to do? What was the first moment you did not know?
2. Did the Crew screen explain itself? Which button did you not understand?
3. Did you want a shared bank, or would you rather keep your own money? Why?
4. Did a shared home feel like it was yours, or like a menu?
5. What would you do next in this world if you could? (This is the most useful answer.)
