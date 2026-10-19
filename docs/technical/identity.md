# Identity: who a player is, and what the server can prove

Status: **implemented and tested in the cloud; not yet exercised with real launcher accounts** (D-090, October 18, 2026). Unit and GameTests prove that no Crew, bank, home or payout decision looks at a player's *name*; a protocol check proves that the production-style server demands authentication; a person with a Microsoft account has not yet joined it.

## The rule

Everything Underground City keeps about a person is keyed by the **account UUID**. A name is only a label shown to other people and updated at login.

| Kept per person | Where | Key |
| --- | --- | --- |
| citizenship, career, skills, journey | `CitizenState` (world data) | UUID |
| credits | the Q Shop wallet, inside the player's own data file | the player entity (UUID) |
| work orders, contracts, trust, heat, purchases | `WorkOrderLedger` journal | UUID |
| Crew membership, role, consent, the roster | `CrewLedger` (`data/underground_city_crews.dat`) | UUID (names are display copies) |
| the Crew bank, owed payouts, in-flight transfers and purchase holds | `CrewLedger` | Crew id, UUID |
| homes: owner, guests, private containers | `CrewLedger` residences | UUID or Crew id |

Nothing looks a player up by name to decide a right. The one place a name is used to *find* somebody is when a player invites another by typing their name (and when an operator adds a name to the white-list): that resolves, once, to the UUID of the player who is online with that name, and from then on only the UUID matters.

## What authentication changes

* **`online-mode=true`** (the production-style launch): Minecraft makes each client prove, through Mojang's session server, that it owns the account whose UUID it claims. The UUID is a random version-4 UUID owned by one account. Nobody can act as somebody else, so nobody can reach another player's Crew, bank, homes, wallet or progress. A player who renames their Minecraft account keeps everything (the UUID is the same; the display name is refreshed at login).
* **`online-mode=false`** (the developer launch, `gradlew runServer` after editing `server.properties`, and every hosted cloud test): the server believes whatever name the client sends and derives the UUID from it (the version-3 UUID of `OfflinePlayer:<name>`). Anybody who types a Crew member's name **is** that member: their Crew, their bank, their homes and private containers, their wallet and progress. This is fine on a closed network among people who trust each other and is the only way the scripted test clients can join; it must never be the setup for a server others can reach.

The server says which it is: a line in the log at start (`[UC] identity: ...`), `/uc_identity` for an operator, and a notice to each Crew member at login on a server that does not verify accounts.

## The two identities of one person are two different people

A world played in offline mode records people under name-derived UUIDs. The same person on an authenticated server has a different UUID, so **to the server they are a stranger**: their citizenship, Crew, wallet and homes do not follow them. Nothing migrates automatically, and nothing here will ever rewrite a UUID silently:

* at start, an authenticated server counts the registered citizens and Crew members whose UUID is the one an unauthenticated server derives from their name, warns, and lists a few names (`/uc_identity` repeats it). It changes nothing;
* there is **no migration tool**. An explicit, audited one would have to move the player data file (and with it the Q Shop wallet), the citizen record, the work-order journal entry, and every Crew membership, ownership, claim, owed payout and invitation, in one crash-safe step, and has not been built. Until it exists the supported way to start playing with real accounts is a **fresh world**. A world with several hours of offline-mode progress that you want to keep should stay on its trusted network;
* the other direction (an authenticated world moved to offline mode) strands everybody the same way and is not supported.

## What is tested

| Claim | Evidence |
| --- | --- |
| A player with the same *name* as a member gets none of the member's Crew, bank, payout, home, private container, invitation or wallet; a login under that name changes nothing of the member's record | GameTest `crewMembershipMoneyHomesAndPayoutsBelongToTheAccountNotTheName` (an impostor named like the founder and one named like an invitee try deposit, withdrawal, payout claim, lease, invite, rules, disband, the home and the founder's private container, and the invitation) |
| A renamed account keeps its Crew, role, private container and the bank | the same test (login of the same UUID under a new name) |
| Name-derived UUIDs are recognised, and the audit changes nothing | GameTest `offlineDerivedIdentitiesAreRecognisedAndNeverMigratedSilently`; Java's `UUID.nameUUIDFromBytes` values pinned in `tests/test_authentication.py` |
| The production-style server demands authentication | `scripts/run_auth_check.py`: a real Forge server booted with the pack's `online-mode=true` answers a login with an **Encryption Request**; the same server with `online-mode=false` lets the login go on for any claimed name. Run in the cloud and in the hosted `production-boot` job. It proves the server refuses unproven identities; it does not prove a launcher account gets in |
| The server pack is authenticated by default | `tests/test_authentication.py` (`online-mode=true`, `enforce-secure-profile=true`, `white-list=true`, `enforce-whitelist=true`, `pvp=false`) |

**Not tested, and why:** a successful login by a real Microsoft account (the cloud has none; it is step 1 of the Windows playtest), Mojang's session server itself (vanilla, not ours), and the two-client scripted journeys on an authenticating server (their dev clients use offline profiles, which an authenticating server correctly refuses; they run on `online-mode=false` and are labelled so).

## Launching an authenticated server

See the [Windows 11 playtest guide](../production/playtest-guide-windows11.md#authenticated-launch-for-real-accounts). In short: `python scripts/make_server_pack.py --sets world firearms vehicles boats --output build/server-pack --whitelist <names>`, accept the Minecraft EULA yourself, start `start-server.bat`, run the `whitelist add` commands once, and have each player install the same pinned mods in the normal Minecraft launcher.
