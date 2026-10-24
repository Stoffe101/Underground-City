package io.github.stoffe101.undergroundcity;

import net.minecraft.world.phys.Vec3;

import java.util.Locale;

import static io.github.stoffe101.undergroundcity.JourneyScenario.mc;
import static io.github.stoffe101.undergroundcity.JourneyScenario.me;
import static io.github.stoffe101.undergroundcity.JourneyScenario.server;
import static io.github.stoffe101.undergroundcity.JourneyScenario.until;
import static io.github.stoffe101.undergroundcity.ThiefScenario.pass;
import static io.github.stoffe101.undergroundcity.ThiefScenario.scripted;
import static io.github.stoffe101.undergroundcity.ThiefScenario.shot;
import static io.github.stoffe101.undergroundcity.ThiefScenario.stopIfFailed;

/**
 * The police on the beat (D-155) through a real client ({@code -PucPlaytest=patrol}, a new furnished archipelago world): a new citizen with lockpicks stands at the street door of a Valewood house at
 * noon and waits, in the street, for the city's first patrol; when an officer on the beat is in sight they start to pick the lock in front of them. The officer must see it: the lock is put away with
 * nothing lost, the officer comes at once, and a thief who stands still is arrested for trying to break in.
 *
 * <p>Scripted rather than played, each logged as SCRIPTED: the time of day (13:00, the daylight cycle stopped), the travel to Valewood (a teleport), the choice of house, the lockpicks, and standing at
 * the door. The patrol is not: it comes at Valewood's own pace, on a beat the game lays out, and the officer walks it. It is evidence of one scripted thief who chose to be seen, not of how often a
 * careful one is.
 */
final class PatrolScenario {
    private PatrolScenario() {}

    private static final class Facts {
        volatile long arrivedAt, firstPatrolAt, caughtAt;
        volatile int heardMark, arrestsBefore;
        volatile String officer = "";
    }

    private static final Facts F = new Facts();

    static void build() {
        ThiefScenario.tag = "PATROL";
        Playtest.keepScreens = true;
        ThiefScenario.register(0, "the Farmer card", "1 a new citizen (a Farmer) registers");
        Playtest.queue("the clock: 13:00 with the daylight cycle stopped", () -> {
            Playtest.command("gamerule doDaylightCycle false");
            Playtest.command("time set 7000");
            scripted("the time of day was set to 13:00 and the daylight cycle stopped");
        });
        ThiefScenario.travel("to the middle of Valewood", () -> ThiefScenario.centre("valewood"), 4);
        ThiefScenario.chooseHouse();
        stopIfFailed("finding a house");
        ThiefScenario.prepareAtDoor();
        Playtest.queue("in Valewood now", () -> { F.arrivedAt = clock(); Playtest.log("PATROL at the door of a Valewood house; " + server(PolicePatrols::status)); });
        waitForTheBeat();
        stopIfFailed("an officer on the beat");
        pickInSight();
        arrested();
        Playtest.queue("summary", ThiefScenario::summary);
    }

    private static long clock() { return server(() -> LockService.clock(me().server)); }
    private static long arrests() { return server(() -> PoliceResponse.arrests); }

    /** The officer of Valewood's patrol within 18 blocks of the player with a line of sight, or null. Server thread. */
    private static TownspersonEntity officerInSight() {
        PolicePatrols.Patrol patrol = PolicePatrols.patrolOf("valewood");
        if (patrol == null) return null;
        for (var id : patrol.officers) {
            if (me().serverLevel().getEntity(id) instanceof TownspersonEntity officer && officer.distanceTo(me()) <= 18 && officer.hasLineOfSight(me())) return officer;
        }
        return null;
    }

    /** Stands at the door in the street until an officer on the beat is in sight: the first patrol comes half Valewood's pace after somebody arrives, on a beat round them. */
    private static void waitForTheBeat() {
        final int[] t = {0};
        Playtest.queue("wait in the street for an officer on the beat to come into sight", () -> t[0] = 0, () -> {
            t[0]++;
            String seen = server(() -> { TownspersonEntity o = officerInSight(); return o == null ? null : o.getName().getString() + " at " + o.blockPosition().toShortString(); });
            if (F.firstPatrolAt == 0 && server(() -> PolicePatrols.patrolOf("valewood") != null)) { F.firstPatrolAt = clock(); Playtest.log("PATROL a patrol came out " + (F.firstPatrolAt - F.arrivedAt) / 20 + " s of active play after arriving; " + server(PolicePatrols::status)); }
            if (t[0] % 400 == 0) Playtest.log("PATROL waiting " + t[0] / 20 + " s: " + server(PolicePatrols::status));
            if (seen != null) { F.officer = seen; Playtest.log("PATROL in sight: " + seen); return true; }
            return false;
        }, 12000);
        Playtest.queue("an officer in sight", () -> { if (F.officer.isEmpty()) Playtest.fail("no officer on the beat came into sight in ten minutes: " + server(PolicePatrols::status)); });
        // the photograph looks at the officer (run 1's faced the door)
        Playtest.queue("look at the officer", () -> {
            Vec3 at = server(() -> { TownspersonEntity o = officerInSight(); return o == null ? null : o.getEyePosition(); });
            if (at != null) JourneyScenario.look(at);
        });
        Playtest.wait("turned", 4);
        shot("1-officer-on-the-beat");
    }

    /** Takes the lockpicks and works the door in the officer's sight: the lock screen opens, and the officer must see it within seconds. */
    private static void pickInSight() {
        final int[] t = {0};
        Playtest.queue("the lockpicks in hand (slot 9); right-click the door until the lock screen opens", () -> {
            t[0] = 0;
            F.heardMark = Playtest.heardCount();
            F.arrestsBefore = (int) arrests();
            Playtest.focusWindow();
            Playtest.tap("9");
        }, () -> {
            var player = mc().player;
            t[0]++;
            if (mc().screen instanceof LockpickScreen) return true;
            // the lock can open and be put away by the officer between two looks at the screen (runs 3 and 4 clicked on, and opened it again): the server's word or the officer's is enough
            if (Playtest.heardSince(F.heardMark, "saw you at the lock") != null || server(() -> LockService.busy(me().getUUID()))) return true;
            DoorWalk.Spot door = ThiefScenario.door();
            JourneyScenario.look(Vec3.atCenterOf(door.door()).add(0, 0.4, 0));
            if (t[0] % 40 == 5) Playtest.click(3);   // one click, then time for the screen to open: two in flight opened the lock twice (run 3)
            return t[0] > 200 && player != null;
        }, 240);
        until("the lock screen opens, or the officer has already put it away", () -> mc().screen instanceof LockpickScreen || Playtest.heardSince(F.heardMark, "saw you at the lock") != null, 200);
        stopIfFailed("starting the lock");
        until("the officer sees the thief at the lock", () -> Playtest.heardSince(F.heardMark, "saw you at the lock") != null, 600);
        Playtest.wait("draw", 8);
        shot("2-seen-at-the-lock");
        Playtest.queue("seen at the lock", () -> {
            F.caughtAt = clock();
            // the lock's own note is shown on its screen as it closes, not in the chat; the officer's line is in the chat
            String said = Playtest.heardSince(F.heardMark, "saw you at the lock");
            boolean screen = mc().screen instanceof LockpickScreen;
            int picks = mc().player.getInventory().countItem(UndergroundCity.item("lockpick"));
            String status = server(PoliceResponse::status) + "; " + server(PolicePatrols::status);
            Playtest.log("PATROL seen: told " + said + "; lock screen " + (screen ? "still open" : "put away") + "; picks " + picks + "; " + status);
            if (said == null) Playtest.fail("the officer on the beat did not see the lock being picked in front of them");
            else if (picks != 5) Playtest.fail("putting the lock away cost a pick: " + picks + " left of 5");
            else if (screen) Playtest.fail("the lock screen stayed over the street after the officer saw the thief");
            else pass("2 an officer on the beat in sight sees the lock being picked: the lock is put away with nothing lost and the thief is told (\"" + said + "\")");
        });
    }

    /** Stands still: the officer who saw it walks over and arrests the thief for trying to break in. */
    private static void arrested() {
        until("the officer reaches the thief", () -> arrests() > F.arrestsBefore || server(PoliceResponse::dispatches) == 0, 1200);
        Playtest.wait("draw", 10);
        shot("3-arrested");
        Playtest.queue("the arrest", () -> {
            String charge = Playtest.heardSince(F.heardMark, "under arrest");
            Playtest.log(String.format(Locale.ROOT, "PATROL arrest after %d s: %s; %s", (clock() - F.caughtAt) / 20, charge, server(PoliceResponse::status)));
            if (arrests() <= F.arrestsBefore || charge == null) Playtest.fail("the officer who saw the lock picked did not arrest the thief who stood still");
            else if (!charge.contains("trying to break in")) Playtest.fail("the arrest was not for trying to break in: " + charge);
            else pass("3 the officer walks over and arrests the thief who stood still (\"" + charge + "\")");
        });
    }
}
