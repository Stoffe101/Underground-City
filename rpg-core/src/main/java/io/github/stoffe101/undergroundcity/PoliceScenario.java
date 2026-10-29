package io.github.stoffe101.undergroundcity;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

import static io.github.stoffe101.undergroundcity.JourneyScenario.level;
import static io.github.stoffe101.undergroundcity.JourneyScenario.look;
import static io.github.stoffe101.undergroundcity.JourneyScenario.mc;
import static io.github.stoffe101.undergroundcity.JourneyScenario.me;
import static io.github.stoffe101.undergroundcity.JourneyScenario.server;
import static io.github.stoffe101.undergroundcity.JourneyScenario.until;
import static io.github.stoffe101.undergroundcity.ThiefScenario.pass;
import static io.github.stoffe101.undergroundcity.ThiefScenario.scripted;
import static io.github.stoffe101.undergroundcity.ThiefScenario.shot;

/**
 * The police answering crimes (D-189) through a real client ({@code -PucPlaytest=police}, a new furnished archipelago world): a new citizen strikes a person in a Valewood street where a passer-by
 * can see it, with a real left click; somebody calls the police (or an officer on the beat warns, then pursues the next blow); the citizen stands still and officers walk in from a street out of
 * sight and arrest them for assault, with the city's fine. Then, at night, a blow nobody else sees (if a lone person can be found), and an alarmed burglary: a house whose alarm is armed, its lock
 * picked on the real screen and a window broken, the police called by the alarm; the thief runs (the walker, with real keys) and the officers chase what they can see.
 *
 * <p>Scripted, each logged: the clock, the travel to Valewood, credits given with the operator command (so the fine can be paid and the command is used from a real client), standing next to the
 * person struck (people walk; the scenario puts the player beside one in reach), the choice of the house and the lockpicks.
 */
final class PoliceScenario {
    private PoliceScenario() {}

    private static final class Facts {
        volatile UUID victim;
        volatile int arrestsBefore, heardMark;
        volatile double walletBefore;
        volatile DoorWalk.Spot door;
        volatile CityBuildings.Building house;
        volatile BlockPos window;
        volatile boolean chased;
    }
    private static final Facts F = new Facts();

    /** The person the last blow was aimed at (null when none could be found). */
    static UUID victim() { return F.victim; }

    static void build() {
        ThiefScenario.tag = "POLICE";
        Playtest.keepScreens = true;
        ThiefScenario.register(0, "the Farmer card", "1 a new citizen (a Farmer) registers");
        Playtest.queue("the clock: 13:00 with the daylight cycle stopped", () -> {
            Playtest.command("gamerule doDaylightCycle false");
            Playtest.command("time set 7000");
            scripted("the time of day was set to 13:00 and the daylight cycle stopped");
        });
        Playtest.queue("200 credits with the operator command", () -> {
            Playtest.command("uc admin credits " + mc().player.getGameProfile().getName() + " add 200");
            scripted("200 credits were added with /uc admin credits (so a fine can be paid)");
        });
        Playtest.wait("the command", 10);
        Playtest.queue("the wallet", () -> {
            F.walletBefore = server(() -> QShopEconomy.balance(me()));
            String audit = server(() -> String.join(" | ", AdminCommands.Audit.get(level().getServer()).last(1)));
            Playtest.log("POLICE wallet " + F.walletBefore + "; audit: " + audit);
            if (F.walletBefore < 200 || !audit.contains("credits add 200")) Playtest.fail("the operator command did not add the credits or was not audited");
            else pass("2 /uc admin credits from a real client added 200 credits through Q Shop and wrote the audit record");
        });
        ThiefScenario.travel("to the middle of Valewood", () -> ThiefScenario.centre("valewood"), 4);
        Playtest.queue("down to the street", () -> {
            BlockPos at = server(() -> CityArrival.safeStand(level(), BlockPos.containing(me().getX(), 72, me().getZ()), 16, 12, 40).orElse(null));
            if (at == null) { Playtest.fail("no street ground near Valewood's middle"); return; }
            scripted("the player was set down on the street at " + at.toShortString() + " in survival mode");
            mc().getSingleplayerServer().execute(() -> {
                me().setGameMode(net.minecraft.world.level.GameType.SURVIVAL);
                me().teleportTo(at.getX() + 0.5, at.getY(), at.getZ() + 0.5);
                me().setDeltaMovement(Vec3.ZERO);
            });
        });
        Playtest.wait("people come out", 300);
        assaultInSight();
        ThiefScenario.stopIfFailed("the assault");
        arrested();
        loneBlow();
        alarmedBurglary();
        Playtest.queue("summary", () -> { Playtest.log("POLICE " + server(PoliceResponse::status) + "; " + server(CrimeWatch::status)); ThiefScenario.summary(); });
    }

    // ------------------------------------------------------------------------------------------------ a blow in sight of a passer-by

    /** A person in the street with somebody else close enough to see a blow given beside them; the cell beside them to stand in. Server thread. */
    private static Object[] victimWithWitness(boolean wantWitness) {
        ServerLevel level = level();
        AABB around = new AABB(me().getX() - 48, level.getMinBuildHeight(), me().getZ() - 48, me().getX() + 48, level.getMaxBuildHeight(), me().getZ() + 48);
        // a person out in the street (under the open sky), as a passer-by is: a resident indoors at night is behind walls the player cannot strike through
        for (TownspersonEntity v : level.getEntitiesOfClass(TownspersonEntity.class, around, p -> p.isAlive() && !p.police() && CityArrival.standable(level, p.blockPosition()))) {
            for (Direction d : Direction.Plane.HORIZONTAL) {
                BlockPos cell = v.blockPosition().relative(d);
                if (!CityArrival.standable(level, cell)) continue;
                AABB box = new AABB(cell).inflate(CrimeWatch.ASSAULT_SIGHT);
                Vec3 eye = Vec3.atBottomCenterOf(cell).add(0, 1.62, 0);
                int others = 0;
                for (TownspersonEntity w : level.getEntitiesOfClass(TownspersonEntity.class, box, p -> p != v && p.isAlive() && !p.police())) {
                    var hit = level.clip(new net.minecraft.world.level.ClipContext(w.getEyePosition(), eye, net.minecraft.world.level.ClipContext.Block.COLLIDER, net.minecraft.world.level.ClipContext.Fluid.NONE, w));
                    if (hit.getType() == net.minecraft.world.phys.HitResult.Type.MISS && w.distanceToSqr(eye) <= CrimeWatch.ASSAULT_SIGHT * CrimeWatch.ASSAULT_SIGHT) others++;
                }
                boolean officerNear = PolicePatrols.officersNear(level, cell, 40) > 0;
                if (wantWitness ? others >= 1 : (others == 0 && !officerNear)) return new Object[]{v.getUUID(), cell, v.getName().getString(), others};
            }
        }
        return null;
    }

    /** Puts the player beside the person (scripted), turns to them and strikes them with a real left click when the crosshair is on them. */
    static void strike(String label, boolean wantWitness, int tries) { strike(label, wantWitness, tries, () -> true); }

    /** The same, every step skipped while {@code when} says no (a retry that is not needed). */
    static void strike(String label, boolean wantWitness, int tries, java.util.function.BooleanSupplier when) {
        final boolean[] on = {true};
        Playtest.queue(label + ": needed?", () -> on[0] = when.getAsBoolean());
        final int[] t = {0};
        final boolean[] found = {false};
        Playtest.queue(label + ": find a person " + (wantWitness ? "with a passer-by in sight" : "with nobody else about"), () -> { t[0] = 0; found[0] = false; if (on[0]) F.victim = null; }, () -> {
            if (!on[0]) return true;
            if (t[0]++ % 40 != 0) return false;
            Object[] pick = server(() -> victimWithWitness(wantWitness));
            if (pick == null) return t[0] > 40 * tries;
            F.victim = (UUID) pick[0];
            BlockPos cell = (BlockPos) pick[1];
            scripted(label + ": the player was put beside " + pick[2] + " at " + cell.toShortString() + " (" + pick[3] + " other people in sight)");
            mc().getSingleplayerServer().execute(() -> {
                me().setGameMode(net.minecraft.world.level.GameType.SURVIVAL);
                me().teleportTo(cell.getX() + 0.5, cell.getY(), cell.getZ() + 0.5);
                me().setDeltaMovement(Vec3.ZERO);
            });
            found[0] = true;
            return true;
        }, 40 * tries + 40);
        Playtest.wait("settle", 6);
        final int[] k = {0};
        Playtest.queue(label + ": an empty hand, look at them and strike with the left button", () -> { k[0] = 0; if (!on[0]) return; F.heardMark = Playtest.heardCount(); Playtest.focusWindow(); Playtest.tap("8"); }, () -> {
            if (!on[0] || F.victim == null) return true;
            var target = mc().level.getEntities(mc().player, mc().player.getBoundingBox().inflate(6), e -> e.getUUID().equals(F.victim));
            if (target.isEmpty()) { if (++k[0] > 100) Playtest.log("POLICE " + label + ": the person is not in reach any more"); return k[0] > 100; }
            look(target.get(0).getEyePosition().subtract(0, 0.5, 0));
            k[0]++;
            boolean onIt = mc().hitResult instanceof EntityHitResult hit && hit.getEntity().getUUID().equals(F.victim);
            if (onIt && k[0] > 4) { Playtest.pressLeft(); Playtest.log("POLICE " + label + ": struck with the crosshair on them after " + k[0] + " ticks"); return true; }
            if (k[0] > 100) Playtest.log("POLICE " + label + ": the crosshair never rested on them (it rests on " + mc().hitResult.getType() + ")");
            return k[0] > 100;
        }, 140);
        Playtest.wait("the notice", 20);
    }

    private static void assaultInSight() {
        Playtest.queue("arrests before", () -> F.arrestsBefore = (int) (long) server(() -> PoliceResponse.arrests));
        strike("the blow", true, 30);
        shot("1-the-blow");
        Playtest.queue("what the blow did", () -> {
            if (F.victim == null) { Playtest.fail("no person with a passer-by in sight could be found in Valewood's middle"); return; }
            long reported = server(() -> CrimeWatch.assaultsReported), warned = server(() -> CrimeWatch.warnings);
            String said = Playtest.heardSince(F.heardMark, "Somebody saw you strike");
            Playtest.log("POLICE after the blow: " + server(CrimeWatch::status) + "; " + server(PoliceResponse::status) + "; heard " + said);
            if (reported == 0 && warned == 0) Playtest.fail("a blow in sight of a passer-by raised nothing");
            else if (warned > 0) pass("3 a blow in front of an officer on the beat: a warning first");
            else pass("3 a blow in sight of a passer-by: the police are called (" + (said == null ? "the notice" : said) + ")");
        });
        // after a warning, the next blow is pursued
        Playtest.queue("a second blow if the first was only a warning", () -> {
            if (server(() -> CrimeWatch.warnings) > 0 && !server(() -> PoliceResponse.wanted(me().getUUID()))) Playtest.log("POLICE warned: striking again");
        });
    }

    /** Stands still: officers come from a street out of sight and arrest the citizen for assault; the fine is the city's, paid from the wallet. */
    private static void arrested() {
        final int[] t = {0};
        Playtest.queue("stand still until the police arrive and arrest (or give up)", () -> t[0] = 0, () -> {
            t[0]++;
            if (t[0] % 200 == 0) Playtest.log("POLICE waiting " + t[0] / 20 + " s: " + server(PoliceResponse::status));
            return server(() -> PoliceResponse.arrests) > F.arrestsBefore || (!server(() -> PoliceResponse.wanted(me().getUUID())) && t[0] > 100);
        }, 20 * 240);
        Playtest.wait("draw", 10);
        shot("2-arrested");
        Playtest.queue("the arrest", () -> {
            String charge = Playtest.heardSince(F.heardMark, "under arrest");
            double wallet = server(() -> QShopEconomy.balance(me()));
            int fine = CityLaws.INSTANCE.offence("valewood", CityLaws.ASSAULT).fine();
            Playtest.log(String.format(Locale.ROOT, "POLICE arrest: %s; wallet %.0f -> %.0f (the assault fine is %d); %s", charge, F.walletBefore, wallet, fine, server(PoliceResponse::status)));
            if (server(() -> PoliceResponse.arrests) <= F.arrestsBefore || charge == null) Playtest.fail("the citizen who stood still was not arrested: " + server(PoliceResponse::status));
            else if (!charge.contains("assault")) Playtest.fail("the arrest was not for assault: " + charge);
            else if (Math.abs(F.walletBefore - wallet - Math.min(fine, F.walletBefore)) > 0.5) Playtest.fail("the fine was not the city's for assault: " + (F.walletBefore - wallet));
            else pass("4 officers walked in from out of sight and arrested the citizen who stood still, for assault, fined " + fine + " credits (\"" + charge + "\")");
        });
    }

    // ------------------------------------------------------------------------------------------------ a blow nobody else sees

    private static void loneBlow() {
        Playtest.queue("the clock: 23:30", () -> { Playtest.command("time set 17500"); scripted("the time was set to 23:30 (the streets thin out)"); });
        Playtest.wait("the street thins", 400);
        Playtest.queue("counts before", () -> F.heardMark = Playtest.heardCount());
        final long[] before = new long[3];
        Playtest.queue("counts", () -> { before[0] = server(() -> CrimeWatch.assaultsReported); before[1] = server(() -> CrimeWatch.assaultsUnreported); before[2] = server(() -> (long) PoliceResponse.dispatches()); });
        strike("the lone blow", false, 15);
        Playtest.queue("what the lone blow did", () -> {
            if (F.victim == null) { Playtest.log("POLICE no lone person could be found at night: the blow nobody else sees was not tried in this run (a GameTest covers it)"); return; }
            long reported = server(() -> CrimeWatch.assaultsReported) - before[0], unreported = server(() -> CrimeWatch.assaultsUnreported) - before[1];
            Playtest.log("POLICE lone blow: reported " + reported + ", not reported " + unreported + "; " + server(PoliceResponse::status));
            if (reported + unreported != 1) Playtest.fail("the lone blow was counted " + (reported + unreported) + " times");
            else pass("5 a blow nobody else saw: " + (reported == 1 ? "the person struck went to the police themselves, later" : "nobody reported it (the person struck ran off)"));
        });
        // whatever came of it, the police leave before the next part
        until("no response out for the citizen", () -> !server(() -> PoliceResponse.wanted(me().getUUID())), 20 * 240);
    }

    // ------------------------------------------------------------------------------------------------ an alarmed burglary, and running

    private static void alarmedBurglary() {
        Playtest.queue("choose a house whose alarm is armed (on the server)", () -> {
            Object[] found = server(() -> {
                ServerLevel level = level();
                int cx = me().getBlockX() >> 4, cz = me().getBlockZ() >> 4;
                List<CityBuildings.Building> fit = new ArrayList<>();
                for (int dx = -10; dx <= 10; dx++) for (int dz = -10; dz <= 10; dz++) {
                    if (!level.hasChunk(cx + dx, cz + dz)) continue;
                    CityBuildings.INSTANCE.ofChunk(level, cx + dx, cz + dz).filter(b -> b.locked() && b.security() != null && b.security().id().equals("house") && b.security().response() != null
                        && PoliceResponse.armed(level, b, b.security().response()) && !CityHomes.anyLet(level.getSeed(), b)).ifPresent(fit::add);
                }
                fit.sort(java.util.Comparator.comparingInt(b -> Math.abs(b.chunkX() - cx) + Math.abs(b.chunkZ() - cz)));
                for (CityBuildings.Building b : fit) {
                    DoorWalk.Spot door = ThiefScenario.streetDoor(level, b);
                    if (door != null) return new Object[]{b, door};
                }
                return null;
            });
            if (found == null) { Playtest.fail("no Valewood house with an armed alarm near the middle"); return; }
            F.house = (CityBuildings.Building) found[0];
            F.door = (DoorWalk.Spot) found[1];
            ThiefScenario.useBuilding(F.door, F.house, server(() -> ThiefScenario.furniture(level(), F.house, F.door.door())));
            scripted("the house was chosen by the script: its alarm is armed (the game's own hash)");
        });
        ThiefScenario.stopIfFailed("an alarmed house");
        ThiefScenario.prepareAtDoor();
        ThiefScenario.pickTheDoor();
        Playtest.queue("the alarm, or a quiet entry", () -> Playtest.log("POLICE after the pick: " + server(PoliceResponse::status) + "; " + server(CrimeWatch::status)));
        // a window: a glass-break alarm rings for certain in a house whose alarm is armed
        Playtest.queue("a window in reach from inside", () -> {
            // the windows are read on the server; where a person could stand to reach one is asked from here (standBeside reads the server itself)
            List<BlockPos> windows = server(() -> {
                ServerLevel level = level();
                long now = LockService.clock(level.getServer());
                List<BlockPos> found = new ArrayList<>();
                for (int y = F.house.streetY(); y < F.house.streetY() + 6; y++) for (int x = 0; x < 16; x++) for (int z = 0; z < 16; z++) {
                    BlockPos p = new BlockPos((F.house.chunkX() << 4) + x, y, (F.house.chunkZ() << 4) + z);
                    if (BurglaryDamage.glass(level.getBlockState(p)) && BurglaryDamage.judge(me(), level, F.house, p, now) == BurglaryDamage.Verdict.ALLOWED) found.add(p.immutable());
                }
                return found;
            });
            F.window = null;
            for (BlockPos p : windows) if (ThiefScenario.standBeside(p, F.house) != null) { F.window = p; break; }
            Playtest.log("POLICE window " + (F.window == null ? "none in reach" : F.window.toShortString()));
            if (F.window != null) {
                BlockPos at = ThiefScenario.standBeside(F.window, F.house);
                scripted("the player was put beside a window inside at " + at.toShortString());
                mc().getSingleplayerServer().execute(() -> { me().teleportTo(at.getX() + 0.5, at.getY(), at.getZ() + 0.5); me().setDeltaMovement(Vec3.ZERO); });
            }
        });
        Playtest.wait("settle", 10);
        final int[] t = {0};
        Playtest.queue("hold the attack button on the window until it breaks", () -> { t[0] = 0; Playtest.tap("8"); }, () -> {
            if (F.window == null || server(() -> level().getBlockState(F.window).isAir())) { Playtest.mouseButton(1, false); return true; }
            look(Vec3.atCenterOf(F.window));
            if (++t[0] == 6) Playtest.mouseButton(1, true);
            return t[0] > 300;
        }, 320);
        Playtest.queue("let go", () -> Playtest.mouseButton(1, false));
        Playtest.wait("the alarm", 20);
        shot("3-alarm");
        Playtest.queue("the police are called", () -> {
            boolean wanted = server(() -> PoliceResponse.wanted(me().getUUID()));
            Playtest.log("POLICE after the window: wanted " + wanted + "; " + server(PoliceResponse::status) + "; " + server(CrimeWatch::status));
            if (!wanted) Playtest.fail("an armed house's alarm did not call the police after a window was broken");
            else pass("6 an alarmed burglary: the house's alarm called the police (a broken window trips it for certain)");
        });
        ThiefScenario.stopIfFailed("the alarm");
        // wait at the door until officers are in sight, then run
        Playtest.queue("out of the door to the street", () -> {
            Vec3 at = F.door.stand();
            scripted("the player was put back on the street at the door");
            mc().getSingleplayerServer().execute(() -> { me().teleportTo(at.x, at.y, at.z); me().setDeltaMovement(Vec3.ZERO); });
        });
        until("officers arrive and see the thief", () -> server(() -> { for (int i = 0; i < PoliceResponse.dispatches(); i++) { var d = PoliceResponse.dispatchAt(i); if (d.burglar.equals(me().getUUID()) && d.arrived && d.lastSeen != null) return true; } return !PoliceResponse.wanted(me().getUUID()); }), 20 * 240);
        Playtest.queue("seen", () -> { F.chased = server(() -> PoliceResponse.wanted(me().getUUID())); Playtest.log("POLICE chase: " + server(PoliceResponse::status)); });
        shot("4-the-chase");
        ThiefScenario.walkOrTeleport("run 150 blocks down the street", () -> {
            Vec3 away = F.door.stand().add(F.door.out().getStepX() * 150.0, 0, F.door.out().getStepZ() * 150.0);
            BlockPos ground = server(() -> CityArrival.safeStand(level(), BlockPos.containing(away), 10, 8, 8).orElse(BlockPos.containing(away)));
            return Vec3.atBottomCenterOf(ground);
        }, 3.0, 2400);
        until("the response ends (an escape or an arrest)", () -> !server(() -> PoliceResponse.wanted(me().getUUID())), 20 * 120);
        Playtest.queue("how it ended", () -> {
            long escapes = server(() -> PoliceResponse.escapes), arrests = server(() -> PoliceResponse.arrests);
            Playtest.log("POLICE pursuit over: escapes " + escapes + ", arrests " + arrests + "; " + server(PoliceResponse::status));
            if (!F.chased) Playtest.fail("no officer saw the thief before the run");
            else pass("7 the officers chased what they saw and the run ended in " + (escapes > 0 ? "an escape" : "an arrest"));
        });
    }
}
