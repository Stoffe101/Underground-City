# The SecurityCraft trial (D-140)

Status: **experimental, opt-in, not adopted (D-152: decided with a measurement of camera viewing, below).** SecurityCraft v1.10.2.1 for Minecraft 1.20.1 is pinned as the dev-pack set `securitycraft` (runtime-only, hash-checked, never committed) and no default launch includes it. Core's adapter (`SecurityCraftTrial`) is GameTest-free by design (SecurityCraft is not on the GameTest classpath); it is exercised by one scripted real-client scenario, `-PucPlaytest=security` (`SecurityScenario`), which passed on October 23 (run 11, below). D-121 (SecurityCraft not adopted as a second authority) still stands; whether to adopt anything from this trial is the owner's decision.

## Why a trial, and what was looked at first

The owner asked for a serious trial rather than a rejection on paper (October 23). Before anything was installed, every one of SecurityCraft's 737 classes was disassembled and read (the jar's SHA-512 checked against Modrinth; nothing from it is in the repository). The findings that shaped the adapter:

* **Ownership is by UUID string**, set with `IOwnable.setOwner(uuid, name)`; allowlists are **player names**, not UUIDs.
* **Codes are salted and hashed** on a worker thread; nobody can read one back, so Core keeps the codes it issues.
* **SecurityCraft fires no event of its own** for a correct code, a wrong code, a keycard or a codebreak. A keypad door that opens emits the vanilla game event `BLOCK_OPEN` with the player as its cause, which Forge reports as `VanillaGameEvent`; that is Core's seam.
* **A laser emits a signal only with a Redstone Module fitted** (15 for 50 ticks), and it fires for any living entity, our townspeople included.
* **Reinforced blocks cannot be broken, blasted or pushed by a non-owner**, and Core's lockpick does nothing to a reinforced door.
* **Viewing a camera force-loads (2 x view distance + 1)^2 chunks** (289 at view distance 8) and generates any that do not exist yet; camera feeds in frames force-load without limit by default.
* Configuration can switch off the codebreaker, the admin tool, in-world reinforcing and the camera feeds, and can zero most damage, but **cannot switch off sentries, mines, the I.M.S., tasers or the inventory scanner**, and the keypad's Harming Module cannot do less than 1 damage.
* No mixin shares a target method with ours or with the pinned pack's (read from the jars; proven only by booting, below).

## What the trial does

Core is the authority throughout. SecurityCraft supplies the physical devices, their screens and their signals.

| Piece | How | Where |
| --- | --- | --- |
| Install | `/uc_security install` (operator, looking at a city building's street door): the door becomes a **keypad door**; a **laser pair** crosses the hall two steps in, where the walls leave room, with a Redstone Module; a **siren** (SecurityCraft's Alarm), a **camera** and a **motion light** hang over the inside of the door. Every device is placed as one system owner (`UC_Security`, a fixed name-based UUID) through the block's own placement, so no player owns, re-codes or removes one. | `SecurityCraftTrial.install` |
| The code | Six digits, issued by Core and kept by Core (SecurityCraft stores only its salted hash). | `Install.code` |
| A correct code | A **keyholder** Core named (`/uc_security keyholder <player>`) comes in as an ordinary entry (the door's open-on-purpose mark keeps Core's door watch from shutting it). Anybody else who types the same code has **broken in by code**: Core posts the same `BuildingBreachEvent` a picked lock posts (a quiet one), so its police, witnesses, alarm panel, heat (+1 in the city) and stash rules answer exactly as for a clean pick. | `SecurityCraftTrial.opened`, `entered` |
| Leaving | From inside, a click opens the keypad door without a code (Core opens it for the person leaving); only the street side asks. A keypad door that stands open shuts on a click, as any door does (SecurityCraft would ask for the code again). | `BuildingGuard.use` |
| A lockpick | Still works on a keypad door: it is a door, and Core's lock handles it as any locked street door. | `BuildingGuard.use` |
| The laser | Core reads it every tick: SecurityCraft's own signal, or anybody standing in the beam's row of blocks. A keyholder walking their own hall raises nothing; anybody else rings the building's alarm through Core's dispatch (`PoliceResponse.raise`) and sounds SecurityCraft's siren for 30 seconds. One trip per 10 seconds per install. | `SecurityCraftTrial.tick`, `tripped` |
| The camera | Its Allowlist Module names the keyholders (written by Core, rewritten when a keyholder is added): only they can view it. | `allowlist`, `rewriteAllowlist` |
| What is kept out | Every SecurityCraft **recipe is removed** while the set is loaded (at server start and after a datapack reload; devices come only from Core). A survival player **cannot place** a SecurityCraft block, **cannot use** a SecurityCraft item other than the manual and keycards (the camera monitor was allowed until D-152), and **no SecurityCraft creature or projectile** (sentry, its bullets, mines, the I.M.S., security boats) enters the world. | `SecurityCraftTrial.place`, `useItem`, `useOnBlock`, `joined`, `removeRecipes` |
| Configuration | `content/integrations/securitycraft/config/defaultconfigs/securitycraft-server.toml` (new worlds): codebreaker off, admin tool off, in-world reinforcing off, frame feeds and their force-loading off, every damage that configuration reaches at zero, a one-second passcode cooldown. | staged by the development launch |

Nothing in the trial punishes anybody offline, destroys belongings, or hurts anybody. It installs nothing by itself: an operator chooses the building.

## Results

**Security run 11 (October 23): `SECURITY PASS`, `RESULT pass`.** A new furnished archipelago world with the `world,furnished,scarcity,securitycraft` sets, one scripted real client (software-rendered, 1280 x 720), real key presses and clicks. Core fitted SecurityCraft's devices to the street door of a generated Ironhaven tenement (`ih_flats`, class `flat_block`): a keypad door, a laser pair set into the hall's side walls two steps in, a siren, a camera and a motion light over the inside of the door.

| What was done | What happened |
| --- | --- |
| No SecurityCraft recipe on the client | 0 recipes |
| A stranger walks to the door and right-clicks it | SecurityCraft's own code screen opened (`CheckPasscodeScreen`) |
| The stranger types the six digits with real keys and presses Enter | the field held 6 characters; the door opened; Core counted a **break-in by code**: heat 0 to 1 in Ironhaven, the "In by the code" notice, a quiet entry (the building is wired; the right code did not set it off) |
| The stranger walks on across the hall | Core saw them in the laser's field and raised the building's alarm: 1 laser trip, the siren sounding, 1 police dispatch out (no officer had reached the door when the run moved on) |
| From inside, one click on the shut keypad door | it opened with no code screen |
| An operator makes the same person a keyholder; they walk up, click, type the code | the code screen, the door open, **1 keyholder entry, no new break-in**; the camera's allowlist module names them |
| The keyholder walks across the laser | no trip, the siren quiet, no new dispatch |
| The keyholder leaves from inside | the door opened with no code screen |
| A sentry and a keypad door from a creative give | no sentry in the world; the keypad door could not be placed |

What is scripted rather than played: the registration (a command), the install and the keyholder (operator commands), the choice of building, the player's place in front of the door (a teleport), the walk held on the door's line (a sideways correction of a tenth of a block, logged), the view turned to the door before each click, and the creative gives. The code screen, the typing, the door, the laser, the siren and the refusals are the real game.

**Runs 1 to 10 found, in order** (each fixed before the next): no laser pair fitted in a narrow tenement hall (the lasers are now set into its side walls); the exit click asked for the code while the break-in window was open (Core now opens a keypad door for the person leaving before anything else); SecurityCraft shuts a keypad door three seconds after it opens, so the walker lost it while a photograph was taken; a laser check every five ticks let a walker through (now every tick); a field widened by half a block reached into the doorway and counted the stranger at the door before they were in (now the beam's own row); and a click held for four ticks used the door twice, opening it and then bringing SecurityCraft's code screen back on the open door (an open keypad door now shuts on a click). Three were faults in the test's own input, not the game: moving the pointer to the middle of the window turned the view (one exit click landed on the floor), the client had not yet received the door's chunk when the first stranger walked (they walked through a door the client did not have; the scenario now waits for it, which took more than 30 seconds while the city generated), and a nudge of a fifth of a block while waiting caught the keyholder on the column beside the step.

The run's frames are private (not committed). Looked at: the keypad screen (SecurityCraft's own, grey, with digits and a "Show Passcode" box), the hall after the break-in (the open keypad door, the siren and the motion light over it, the laser block low on the wall, the two notices and the alarm line in chat, the side status display and the employer arrow unchanged). One frame showed the quiet-entry notice cut off at three lines ("…it did not h…"); its wording is now shorter (three lines by the font's own character widths; not photographed since).

## What a person still has to judge

* Whether the keypad screen, the laser and the siren feel like part of the game or like a second game's furniture.
* Whether a code that a stranger types should count as a break-in (the trial says yes in a city building; a tenant sharing their own code with a friend would need a rule of its own).
* Camera viewing was not exercised (it force-loads 289 chunks per viewer at our view distance and needs a performance measurement before it is offered).
* Keycards, reinforced doors with a Core relay, and a Technician bypass of a keypad door are designed (see the seams in the research notes) but not built.
* Two players, a dedicated server, and SecurityCraft beside the firearms, vehicles and survival sets were not run. No officer reached the door in the run's time, so an arrest at a SecurityCraft-fitted building was not seen.

## Recommendation

Adopt nothing by default yet. If the owner wants physical security devices, the trial shows that SecurityCraft's keypad door, laser and siren can sit under Core's authority with a small adapter and four guards, provided Core places every device. The alternative remains original Core devices (as the alarm panel of D-128 is), which need no second ownership model at all.

## Camera viewing, measured (D-152, October 23)

`-PucPlaytest=sccamera` (`CameraMeasureScenario`), a new furnished archipelago world with the `securitycraft` set: Core fits the trial's devices to an Ironhaven tenement, the player goes 800 blocks away so the camera's surroundings unload, and the camera is viewed through SecurityCraft's own `SecurityCameraBlock.mountCamera` (what its camera monitor calls on the server). The server's numbers are read once a second without waiting for the server thread. Run 2, on a cloud machine with four cores and software rendering (server view distance 12, so SecurityCraft forces 625 chunks round the camera):

| | Before | While one player viewed the camera | After stopping |
| --- | --- | --- | --- |
| The mount call | | **held the server thread for 120,388 ms** (the server advanced 29 ticks in two minutes) | |
| Chunks the server held | 3,137 | up to 5,532 (+2,395: SecurityCraft's forced square and the viewer's own view moved to the camera) | 3,717 |
| Chunks generated for the first time | | 959 | |
| Longest tick in a 20-tick window | 32.7 ms | 43 to 342 ms | 26 to 163 ms |

Run 1 had waited for the mount on the client with a 20-second limit and timed out, which is how the freeze was first seen. A faster machine would be faster, but not by two orders of magnitude, and a server with several players and cameras would multiply it. **Camera viewing is therefore refused in the trial (the camera monitor is no longer usable), and SecurityCraft is not adopted (D-152).** Core's cameras (D-146, D-149) are watched by Core with no remote feed and load nothing.
