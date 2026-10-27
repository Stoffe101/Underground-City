package io.github.stoffe101.undergroundcity;

import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import static io.github.stoffe101.undergroundcity.JourneyScenario.aimedAt;
import static io.github.stoffe101.undergroundcity.JourneyScenario.click;
import static io.github.stoffe101.undergroundcity.JourneyScenario.clickAt;
import static io.github.stoffe101.undergroundcity.JourneyScenario.closeScreen;
import static io.github.stoffe101.undergroundcity.JourneyScenario.level;
import static io.github.stoffe101.undergroundcity.JourneyScenario.look;
import static io.github.stoffe101.undergroundcity.JourneyScenario.mc;
import static io.github.stoffe101.undergroundcity.JourneyScenario.me;
import static io.github.stoffe101.undergroundcity.JourneyScenario.server;
import static io.github.stoffe101.undergroundcity.JourneyScenario.until;
import static io.github.stoffe101.undergroundcity.JourneyScenario.use;
import static io.github.stoffe101.undergroundcity.ThiefScenario.pass;
import static io.github.stoffe101.undergroundcity.ThiefScenario.scripted;
import static io.github.stoffe101.undergroundcity.ThiefScenario.shot;
import static io.github.stoffe101.undergroundcity.ThiefScenario.stopIfFailed;
import static io.github.stoffe101.undergroundcity.ThiefScenario.thiefXp;

/**
 * Scenario B of the Thief brief, "an advanced burglary", through a real client ({@code -PucPlaytest=heist}, a new furnished archipelago world): a seasoned thief learns on the terminal's Thief page
 * the skills a serious job takes, cases the Blackwater club (tier 5: a high-security iron door, cameras over the entry, a wall safe), finds that lockpicks are no use on its door, picks it with a Pro
 * Pick Set on the real lock screen, is warned by Camera Sense and then held by a camera that calls the police, cracks the wall safe, takes what is in it (marked stolen with the thief's name) with
 * shift-clicks, and keeps out of sight inside until the officers give up.
 *
 * <p>Scripted rather than played, each logged as SCRIPTED: the Thief XP of a seasoned thief (level 14; the climb from level 1 is scenario A's, {@link ThiefScenario}), the travel (teleports), the time
 * of day (13:00 with the daylight cycle stopped, then the first hour from 03:00 when the club is neither a bar nor a fence's, which open its door to anybody), the choice of club (one whose stash the game's own roll gives a safe, from the same pure function), lockpicks and a Pro Pick Set put in the hotbar,
 * and any walk the path walker could not finish. Every click, key and screen is the real one. It is evidence of one scripted thief, not of how a heist feels to a person.
 */
final class HeistScenario {
    private HeistScenario() {}

    /** What a serious job takes, in the order the tree allows (13 Thief points: Thief level 14). */
    static final List<String> SKILLS = List.of("thief.casing", "thief.steady_hands", "thief.feel_for_pins", "thief.pro_tools", "thief.safecracking",
        "thief.routines", "thief.camera_sense", "thief.soft_steps", "thief.clean_getaway");
    static final int LEVEL = 14;

    private static final class Facts {
        volatile BlockPos terminal, safe;
        volatile DoorWalk.Spot door;
        volatile CityBuildings.Building club;
        volatile boolean looked;
        volatile int cameras, xpBefore, xpEntered, xpSafe, xpEscaped, heardMark, stolenBefore;
        volatile long closedAt;
        volatile long callsBefore;
        volatile String outcome;
        final List<String> officerLog = new ArrayList<>();
    }

    private static final Facts F = new Facts();

    static void build() {
        ThiefScenario.tag = "HEIST";
        Playtest.keepScreens = true;
        ThiefScenario.register(1, "the Vintner card", "1 a new citizen (a Vintner) starts at Thief level 1 with no Thief skill");
        seasoned();
        learnSkills();
        stopIfFailed("learning the skills");
        Playtest.queue("the clock: 13:00 with the daylight cycle stopped", () -> {
            Playtest.command("gamerule doDaylightCycle false");
            Playtest.command("time set 7000");
            scripted("the time of day was set to 13:00 and the daylight cycle stopped");
        });
        ThiefScenario.travel("to the middle of Blackwater", () -> ThiefScenario.centre("blackwater"), 4);
        chooseClub();
        stopIfFailed("finding the club");
        closedHour();
        stopIfFailed("finding an hour the club is shut");
        prepareAtDoor();
        caseIt();
        lockpicksRefused();
        ThiefScenario.waitForClearStreet("blackwater");
        pickWithProSet();
        stopIfFailed("the club's door");
        inside();
        crackSafe();
        takeLoot();
        escape();
        Playtest.queue("summary", ThiefScenario::summary);
    }

    // ------------------------------------------------------------------------------------------------ the thief

    private static void seasoned() {
        Playtest.queue("a seasoned thief: Thief XP to level " + LEVEL + " (scripted)", () -> {
            int target = ProgressionRules.thiefXpAtLevel(LEVEL), have = thiefXp();
            server(() -> Thievery.award(me(), target - have, "A seasoned thief.", false));
            scripted("the Thief XP of a seasoned thief was given by the script (" + target + " XP, Thief level " + LEVEL + "): the climb from level 1 is scenario A's");
        });
        Playtest.wait("settle", 10);
        Playtest.queue("Thief level and points", () -> {
            CitizenState.Citizen c = ThiefScenario.citizen();
            int points = server(() -> SkillService.thiefPointsAvailable(c));
            Playtest.log("HEIST Thief level " + c.thiefLevel() + ", " + c.thiefXp() + " XP, " + points + " Thief points");
            if (c.thiefLevel() != LEVEL || points != LEVEL - 1) Playtest.fail("the seasoned thief is level " + c.thiefLevel() + " with " + points + " points, not level " + LEVEL + " with " + (LEVEL - 1));
        });
    }

    private static boolean learned(String id) {
        return server(() -> CitizenState.get(mc().getSingleplayerServer()).find(mc().player.getUUID()).map(c -> c.skills().contains(id)).orElse(false));
    }

    /** Every skill of {@link #SKILLS} on the terminal's Thief page: the wheel turned until its card is in view, the card clicked, Unlock clicked. */
    private static void learnSkills() {
        Playtest.queue("find the Founders Row terminal", () -> {
            F.terminal = server(() -> {
                BlockPos row = DistrictBuilder.originOf(level(), "founders_row");
                if (row == null) return null;
                BlockPos guess = row.offset(4, 0, 2);
                for (BlockPos p : BlockPos.betweenClosed(guess.offset(-3, -2, -3), guess.offset(3, 2, 3))) if (level().getBlockState(p).is(UndergroundCity.TERMINAL.get())) return p.immutable();
                return null;
            });
            if (F.terminal == null) Playtest.fail("no terminal on Founders Row");
        });
        stopIfFailed("finding the terminal");
        ThiefScenario.travel("to Founders Row", () -> F.terminal == null ? null : new Vec3(F.terminal.getX() + 0.5, F.terminal.getY() + 30, F.terminal.getZ() + 2.5), 2);
        Playtest.queue("stand in front of the terminal", () -> {
            var server = mc().getSingleplayerServer();
            server.execute(() -> {
                Direction facing = level().getBlockState(F.terminal).getValue(net.minecraft.world.level.block.HorizontalDirectionalBlock.FACING);
                BlockPos stand = F.terminal.relative(facing);
                me().setGameMode(net.minecraft.world.level.GameType.SURVIVAL);
                me().teleportTo(level(), stand.getX() + 0.5, stand.getY(), stand.getZ() + 0.5, facing.getOpposite().toYRot(), 20f);
                me().setDeltaMovement(Vec3.ZERO);
            });
            scripted("the player was put in front of the Founders Row terminal");
        }, () -> mc().player.position().distanceTo(Vec3.atBottomCenterOf(F.terminal)) < 2.5, 600);
        Playtest.wait("settle", 25);
        use("right-click the terminal", () -> F.terminal, () -> mc().screen instanceof TerminalScreen, 400);
        until("the terminal opens", () -> mc().screen instanceof TerminalScreen, 100);
        click("Thief", true);
        Playtest.wait("draw", 12);
        shot("1-thief-page");
        for (String id : SKILLS) {
            final int[] t = {0};
            Playtest.queue("turn the wheel until " + id + " is in view", () -> { t[0] = 0; Playtest.focusWindow(); }, () -> {
                if (!(mc().screen instanceof TerminalScreen screen)) return true;
                int where = screen.skillBeyond(id);
                if (where == 0 || where == 2) return true;
                if (t[0]++ % 6 == 0) Playtest.click(where > 0 ? 5 : 4);   // X11 buttons 4 and 5 are the wheel
                return false;
            }, 200);
            clickAt("the " + id + " card", () -> mc().screen instanceof TerminalScreen screen ? screen.skillCentre(id) : null);
            Playtest.wait("select", 8);
            click("Unlock", true);
            until("learned " + id, () -> learned(id), 200);
            Playtest.queue("checked " + id, () -> { if (!learned(id)) Playtest.fail(id + " was not learned by its card and Unlock"); });
        }
        Playtest.wait("draw", 10);
        shot("2-skills-learned");
        Playtest.queue("the skills", () -> {
            CitizenState.Citizen c = ThiefScenario.citizen();
            int left = server(() -> SkillService.thiefPointsAvailable(c));
            long have = SKILLS.stream().filter(HeistScenario::learned).count();
            Playtest.log("HEIST learned " + have + " of " + SKILLS.size() + " skills; Thief points left " + left);
            if (have == SKILLS.size()) pass("2 learn " + SKILLS.size() + " Thief skills (Pro Tools, Safecracking, Camera Sense, Clean Getaway and what they need) on the terminal's Thief page with the wheel, the cards and Unlock");
        });
        closeScreen("close the terminal");
    }

    // ------------------------------------------------------------------------------------------------ the club

    /** A Blackwater club near the middle whose stash the game's own roll gives a safe (the roll is a pure function of the building and the seed), with a usable street door. */
    private static void chooseClub() {
        Playtest.queue("choose the club (on the server thread)", () -> {
            var server = mc().getSingleplayerServer();
            int cx = mc().player.getBlockX() >> 4, cz = mc().player.getBlockZ() >> 4;
            server.execute(() -> {
                try {
                    ServerLevel level = server.overworld();
                    long time = HouseholdService.dayTime(level);
                    StringBuilder seen = new StringBuilder();
                    List<CityBuildings.Building> clubs = new ArrayList<>();
                    for (int dx = -12; dx <= 12; dx++) for (int dz = -12; dz <= 12; dz++) {
                        if (!level.hasChunk(cx + dx, cz + dz)) continue;
                        CityBuildings.INSTANCE.ofChunk(level, cx + dx, cz + dz).filter(b -> b.type().startsWith("underground_city:bw_club") && b.locked() && b.security() != null).ifPresent(clubs::add);
                    }
                    clubs.sort(java.util.Comparator.comparingInt(b -> Math.abs(b.chunkX() - cx) + Math.abs(b.chunkZ() - cz)));
                    for (CityBuildings.Building b : clubs) {
                        double roll = BuildingStash.roll(level, b, 1);
                        boolean safe = roll < b.security().stashChance() && !PropertyLocks.get(server).furnished(b.key());
                        int cameras = SecurityDevices.cameras(level, b).size();
                        seen.append(String.format(Locale.ROOT, " [club %d,%d: stash roll %.2f of %.2f, cameras %d, in now %d, armed %s]", b.chunkX(), b.chunkZ(), roll, b.security().stashChance(), cameras,
                            Casing.peopleIn(level, b, time), b.security().response() != null && PoliceResponse.armed(level, b, b.security().response())));
                        if (F.club != null || !safe || cameras == 0) continue;
                        // a door with a straight way in for five steps (the club's back door opens behind the DJ booth: a person walks round it, the scripted walk goes straight)
                        DoorWalk.Spot door = ThiefScenario.streetDoor(level, b, spot -> {
                            for (int k = 1; k <= 5; k++) {
                                BlockPos cell = spot.door().relative(spot.out().getOpposite(), k);
                                if (!level.getBlockState(cell).getCollisionShape(level, cell).isEmpty() || !level.getBlockState(cell.above()).getCollisionShape(level, cell.above()).isEmpty()) return false;
                            }
                            return true;
                        });
                        if (door == null) continue;
                        F.club = b; F.door = door; F.cameras = cameras;
                    }
                    Playtest.log("HEIST clubs near chunk " + cx + "," + cz + ":" + seen);
                } finally { F.looked = true; }
            });
        }, () -> F.looked, 2400);
        Playtest.queue("the club", () -> {
            if (F.club == null) { Playtest.fail("no Blackwater club with a safe to come, a camera and a usable street door near the city's middle"); return; }
            Playtest.log("HEIST target: " + F.club.type() + " (" + F.club.security().id() + ", tier " + F.club.security().tier() + ", lock " + F.club.security().lockProfile() + ", safe "
                + F.club.security().safeProfile() + ") at chunk " + F.club.chunkX() + "," + F.club.chunkZ() + ", door " + F.door.door().toShortString() + ", cameras " + F.cameras);
            scripted("the club was chosen by the script: one the game's own stash roll gives a safe, with a camera");
        });
    }

    /**
     * From 18:00 to 03:00 the club is a bar (a shop while its bartender is in, D-146, its hours since D-185) and from 21:00 a fence's back office too (D-147): its door opens to anybody then, and nothing is searched or cracked
     * inside. The job is done in the hours between: the clock is moved an hour at a time from 03:00 until the club is shut to the public (scripted, logged).
     */
    private static void closedHour() {
        final long[] hours = {21000, 22000, 23000, 0, 1000, 2000};   // 03:00 to 08:00
        final int[] k = {0}, t = {0};
        final boolean[] openAtOne = {false};
        Playtest.queue("the club at 13:00", () -> openAtOne[0] = server(() -> BuildingGuard.openToPublic(level(), F.club, null)));
        Playtest.queue("move the clock an hour at a time until the club is shut to the public", () -> { k[0] = 0; t[0] = 0; }, () -> {
            if (k[0] >= hours.length) return true;
            int phase = t[0]++ % 12;
            if (phase == 0) Playtest.command("time set " + hours[k[0]]);
            if (phase == 10) {
                boolean open = server(() -> BuildingGuard.openToPublic(level(), F.club, null));
                if (!open) return true;
                k[0]++;
            }
            return false;
        }, 12 * hours.length + 20);
        Playtest.queue("the hour", () -> {
            if (k[0] >= hours.length) { Playtest.fail("the club was open to the public at every hour from 03:00 to 08:00"); return; }
            long hour = (hours[k[0]] / 1000 + 6) % 24;
            F.closedAt = hour;
            scripted(String.format(Locale.ROOT, "the clock was set to %02d:00, the first hour from 03:00 the club is shut to the public (at 13:00 it was %s)", hour, openAtOne[0] ? "open, a bar" : "shut"));
            if (!openAtOne[0]) Playtest.log("HEIST the club was not open at 13:00: no bartender in");
        });
    }

    private static void prepareAtDoor() {
        Playtest.queue("on the street in front of the club, five lockpicks in hotbar slot 9, slots 7 and 8 empty, no Pro Pick Set", () -> {
            var server = mc().getSingleplayerServer();
            server.execute(() -> {
                ServerPlayer player = me();
                var inventory = player.getInventory();
                for (int slot : new int[]{6, 7, 8}) {
                    ItemStack held = inventory.getItem(slot);
                    if (held.isEmpty()) continue;
                    inventory.setItem(slot, ItemStack.EMPTY);
                    if (!inventory.add(held)) player.drop(held, false);
                }
                inventory.setItem(8, new ItemStack(UndergroundCity.item("lockpick"), 5));
            });
            ThiefScenario.standAt(F.door);
            scripted("five lockpicks were put in hotbar slot 9 and slots 7 and 8 emptied");
        }, () -> mc().player.position().distanceTo(F.door.stand()) < 1.5, 600);
        Playtest.wait("settle", 25);
    }

    /** Crouches with an empty hand at the club's door until the casing card opens; with Casing and Routines it must read the cameras and the safe. */
    private static void caseIt() {
        final int[] t = {0};
        Playtest.queue("an empty hand (slot 8) and crouch", () -> { Playtest.focusWindow(); Playtest.tap("8"); Playtest.keyDown("shift"); });
        Playtest.wait("crouched", 10);
        Playtest.queue("right-click the club's door while crouched until the casing card opens", () -> t[0] = 0, () -> {
            if (mc().screen instanceof CasingScreen) return true;
            look(Vec3.atCenterOf(F.door.door()).add(0, 0.4, 0));
            t[0]++;
            boolean onDoor = mc().hitResult instanceof BlockHitResult hit && hit.getType() == HitResult.Type.BLOCK
                && (hit.getBlockPos().equals(F.door.door()) || hit.getBlockPos().equals(F.door.door().above()));
            if (t[0] % 12 == 6 && onDoor) Playtest.click(3);
            if (t[0] % 60 == 59 && !onDoor) Playtest.keyDown("w");
            if (t[0] % 60 == 5) Playtest.keyUp("w");
            return false;
        }, 600);
        Playtest.queue("stand up", () -> { Playtest.keyUp("shift"); Playtest.keyUp("w"); });
        Playtest.wait("the card draws", 12);
        shot("3-casing");
        Playtest.queue("what the card says", () -> {
            if (!(mc().screen instanceof CasingScreen screen)) { Playtest.fail("the casing card did not open at the club"); return; }
            Casing.Report r = screen.report();
            Playtest.log("HEIST casing: " + String.join(" | ", Casing.lines(r)));
            if (r.tier() != F.club.security().tier() || r.cameras() != F.cameras || r.safe() != 2 || r.canTry()) Playtest.fail("the club's card is wrong: " + Casing.lines(r));
            else pass("3 case the club: tier " + r.tier() + " (" + Thievery.tierWord(r.tier()) + "), " + r.cameras() + " camera(s), a wall safe, and a door this thief cannot try yet (no Pro Pick Set)");
        });
        closeScreen("close the card");
    }

    /** With lockpicks in hand the high-security door does not open a lock screen; the player is told it needs a Pro Pick Set. */
    private static void lockpicksRefused() {
        final int[] t = {0};
        Playtest.queue("the lockpicks in hand (slot 9); right-click the door", () -> { t[0] = 0; F.heardMark = Playtest.heardCount(); Playtest.focusWindow(); Playtest.tap("9"); }, () -> {
            look(Vec3.atCenterOf(F.door.door()).add(0, 0.4, 0));
            if (++t[0] % 16 == 8 && aimedAt(F.door.door()) || t[0] % 16 == 8 && aimedAt(F.door.door().above())) Playtest.click(3);
            return mc().screen != null || Playtest.heardSince(F.heardMark, "Pro Pick Set") != null || t[0] > 160;
        }, 200);
        Playtest.wait("the answer", 10);
        Playtest.queue("refused", () -> {
            String told = Playtest.heardSince(F.heardMark, "Pro Pick Set");
            boolean open = server(() -> DoorWalk.doorOpen(level(), F.door));
            Playtest.log("HEIST lockpicks on the club's door: screen " + (mc().screen == null ? "none" : mc().screen.getClass().getSimpleName()) + ", door " + (open ? "OPEN" : "shut") + ", told: " + told);
            if (mc().screen instanceof LockpickScreen || open) Playtest.fail("lockpicks worked a high-security door");
            else if (told == null) Playtest.fail("the player was not told why the lockpicks were no use");
            else pass("4 lockpicks on the club's high-security door: no lock screen, and the player is told \"" + told + "\"");
        });
    }

    private static void pickWithProSet() {
        Playtest.queue("a Pro Pick Set in hotbar slot 8 (scripted)", () -> {
            server(() -> { me().getInventory().setItem(7, new ItemStack(UndergroundCity.item(LockService.PRO_SET))); return null; });
            F.xpBefore = thiefXp();
            F.callsBefore = server(() -> SecurityDevices.calls);
            scripted("a Pro Pick Set was put in hotbar slot 8 (crafted, or bought now and then at the cannery den)");
        });
        Playtest.wait("settle", 6);
        final int[] t = {0};
        Playtest.queue("the Pro Pick Set in hand; right-click the door until the lock screen opens", () -> { t[0] = 0; Playtest.focusWindow(); Playtest.tap("8"); }, () -> {
            if (mc().screen instanceof LockpickScreen) return true;
            look(Vec3.atCenterOf(F.door.door()).add(0, 0.4, 0));
            if (++t[0] % 16 == 8 && (aimedAt(F.door.door()) || aimedAt(F.door.door().above()))) Playtest.click(3);
            return false;
        }, 400);
        Playtest.wait("the screen draws", 12);
        shot("4-high-security");
        BurglaryScenario.solve("the club's high-security lock", false);
        Playtest.wait("the door opens", 15);
        Playtest.queue("the door is open and getting in paid Thief XP by the club's tier", () -> {
            boolean open = server(() -> DoorWalk.doorOpen(level(), F.door));
            F.xpEntered = thiefXp();
            int expected = Thievery.ENTRY_XP_PER_TIER * F.club.security().tier();
            int wear = server(() -> { ItemStack set = LockService.proSet(me()); return set.isEmpty() ? -1 : set.getDamageValue(); });
            Playtest.log("HEIST club door " + (open ? "OPEN" : "shut") + "; Thief XP " + F.xpBefore + " -> " + F.xpEntered + " (expected +" + expected + "); Pro Pick Set wear " + wear + "; police " + PoliceResponse.status());
            if (!open) Playtest.fail("the high-security lock was solved but the door is shut");
            else if (F.xpEntered - F.xpBefore != expected) Playtest.fail("getting into the club paid " + (F.xpEntered - F.xpBefore) + " Thief XP, not " + expected);
            else pass("5 pick the club's high-security lock with the Pro Pick Set on the real lock screen (the set took " + wear + " wear); the iron door opens and pays " + expected + " Thief XP");
        });
        shot("5-door-open");
    }

    // ------------------------------------------------------------------------------------------------ inside

    /** Walks into the room under the camera: Camera Sense warns, the thief keeps walking, the camera calls the police. */
    private static void inside() {
        Playtest.queue("where the safe stands (placed when the door was first picked)", () -> {
            F.safe = server(() -> {
                ServerLevel level = level();
                for (int y = F.club.minY(); y < F.club.maxY(); y++) for (int x = 0; x < 16; x++) for (int z = 0; z < 16; z++) {
                    BlockPos p = new BlockPos(F.club.chunkX() * 16 + x, y, F.club.chunkZ() * 16 + z);
                    if (level.getBlockEntity(p) instanceof PropertyCacheBlockEntity) return p.immutable();
                }
                return null;
            });
            Playtest.log("HEIST the safe: " + (F.safe == null ? "none placed" : F.safe.toShortString() + " (" + server(() -> level().getBlockEntity(F.safe) instanceof PropertyCacheBlockEntity c ? c.label() : "?") + ")"));
            if (F.safe == null) Playtest.fail("the game's roll promised the club a safe and none was placed");
        });
        stopIfFailed("the safe");
        final int[] t = {0};
        Playtest.queue("walk in through the door and on into the room, past the camera's wall (real keys)", () -> {
            t[0] = 0;
            Playtest.focusWindow();
            Playtest.tap("9");
            mc().player.setYRot(F.door.yaw());   // the way into the building, as the door was picked facing it
            mc().player.setXRot(0);
            Playtest.keyDown("w");
        }, () -> {
            mc().player.setYRot(F.door.yaw());
            t[0]++;
            if (F.door.past(mc().player.position()) > 5.0) Playtest.keyUp("w");   // five blocks in: into the field of the camera that watches the way in
            long calls = server(() -> SecurityDevices.calls);
            return calls > F.callsBefore || t[0] > 200;
        }, 240);
        Playtest.queue("stop", () -> Playtest.keyUp("w"));
        Playtest.wait("settle", 10);
        shot("6-camera");
        Playtest.queue("the camera", () -> {
            Long warned = server(() -> SecurityDevices.warnedAt(me().getUUID()));
            long calls = server(() -> SecurityDevices.calls);
            String told = Playtest.heardSince(F.heardMark, "camera has you");
            int dispatches = server(PoliceResponse::dispatches);
            Playtest.log("HEIST inside at " + mc().player.blockPosition().toShortString() + ": Camera Sense warning " + (warned == null ? "none" : "at active tick " + warned) + ", camera calls " + F.callsBefore + " -> "
                + calls + ", told: " + told + ", dispatches " + dispatches + "; " + PoliceResponse.status() + "; " + server(SecurityDevices::status));
            if (warned == null) Playtest.fail("no Camera Sense warning before the camera held the thief");
            else if (calls <= F.callsBefore || dispatches == 0) Playtest.fail("walking through the camera's field called nobody");
            else pass("6 under the camera: Camera Sense warns first, the thief walks on, and the camera calls the police through the building's alarm (\"" + told + "\")");
        });
    }

    private static void crackSafe() {
        final BlockPos[] stand = new BlockPos[1];
        Playtest.queue("a place beside the safe", () -> {
            stand[0] = ThiefScenario.standBeside(F.safe, F.club);
            if (stand[0] == null) Playtest.fail("no cell with a clear view of the safe at " + F.safe.toShortString());
        });
        stopIfFailed("a place by the safe");
        ThiefScenario.walkOrTeleport("walk to the safe", () -> Vec3.atBottomCenterOf(stand[0]), 0.8, 600);
        final int[] t = {0};
        Playtest.queue("right-click the safe until the lock screen opens", () -> { t[0] = 0; Playtest.focusWindow(); Playtest.tap("8"); }, () -> {
            if (mc().screen instanceof LockpickScreen) return true;
            Vec3 c = Vec3.atCenterOf(F.safe);
            look(t[0] / 24 % 2 == 0 ? c : c.add(0, 0.3, 0));
            if (++t[0] % 24 == 12 && aimedAt(F.safe)) Playtest.click(3);
            if (t[0] % 96 == 95) Playtest.log("HEIST at the safe: standing at " + mc().player.blockPosition().toShortString() + ", aimed " + aimedAt(F.safe));
            return false;
        }, 600);
        Playtest.wait("the screen draws", 12);
        shot("7-wall-safe");
        Playtest.queue("before the safe", () -> { F.xpSafe = thiefXp(); F.stolenBefore = ThiefScenario.stolen()[0]; });
        BurglaryScenario.solve("the club's wall safe", false);
        until("the safe opens", () -> mc().screen instanceof AbstractContainerScreen<?>, 200);
        Playtest.wait("draw", 10);
        shot("8-safe-open");
        Playtest.queue("the safe is open and cracking it paid Thief XP by the club's tier", () -> {
            int xp = thiefXp();
            int expected = Thievery.SAFE_XP_PER_TIER * F.club.security().tier();
            Playtest.log("HEIST safe: screen " + (mc().screen == null ? "none" : mc().screen.getClass().getSimpleName()) + "; Thief XP " + F.xpSafe + " -> " + xp + " (expected +" + expected + ")");
            if (!(mc().screen instanceof AbstractContainerScreen<?>)) Playtest.fail("the wall safe was solved but did not open");
            else if (xp - F.xpSafe != expected) Playtest.fail("cracking the wall safe paid " + (xp - F.xpSafe) + " Thief XP, not " + expected);
            else pass("7 crack the club's wall safe on the real lock screen with Safecracking; it opens and pays " + expected + " Thief XP");
            F.xpSafe = xp;
        });
    }

    /** Shift-clicks every stack out of the safe (its nine slots come before the player's own), then checks each is marked stolen with the thief's name. */
    private static void takeLoot() {
        final int[] slot = {0}, t = {0};
        Playtest.queue("shift-click every stack out of the safe", () -> { slot[0] = 0; t[0] = 0; Playtest.keyDown("shift"); }, () -> {
            if (!(mc().screen instanceof AbstractContainerScreen<?> screen)) return true;
            List<Slot> slots = screen.getMenu().slots;
            while (slot[0] < slots.size() && (slots.get(slot[0]).container instanceof Inventory || !slots.get(slot[0]).hasItem())) slot[0]++;
            if (slot[0] >= slots.size()) return true;
            Slot s = slots.get(slot[0]);
            int x = screen.getGuiLeft() + s.x + 8, y = screen.getGuiTop() + s.y + 8;
            switch (t[0]++ % 8) {
                case 0 -> Playtest.moveGui(x, y);
                case 4 -> Playtest.pressLeft();
                case 7 -> { if (!s.hasItem()) slot[0]++; }
                default -> { }
            }
            return t[0] > 400;
        }, 440);
        Playtest.queue("let go of shift", () -> Playtest.keyUp("shift"));
        Playtest.wait("settle", 10);
        Playtest.queue("what was taken", () -> {
            int[] s = ThiefScenario.stolen();
            int left = server(() -> level().getBlockEntity(F.safe) instanceof PropertyCacheBlockEntity c ? (int) java.util.stream.IntStream.range(0, c.getContainerSize()).filter(i -> !c.getItem(i).isEmpty()).count() : -1);
            Playtest.log("HEIST from the safe: stolen units carried " + F.stolenBefore + " -> " + s[0] + " (" + s[1] + " with the thief's name); stacks left in the safe " + left);
            if (s[0] <= F.stolenBefore) Playtest.fail("nothing came out of the safe (stacks left " + left + ")");
            else if (s[1] != s[0]) Playtest.fail("what came out of the safe is not all marked with the thief's name (" + s[1] + " of " + s[0] + ")");
            else pass("8 take what was in the safe with shift-clicks: " + (s[0] - F.stolenBefore) + " unit(s), every one marked stolen with the thief's name");
        });
        closeScreen("close the safe");
    }

    // ------------------------------------------------------------------------------------------------ getting away

    /** Stays inside, out of the officers' sight, until they give up; officers do not pass a door. Logs where they are every ten seconds. */
    private static void escape() {
        final int[] t = {0};
        Playtest.queue("wait inside, crouched, until the police give up or take the thief", () -> { t[0] = 0; F.xpEscaped = thiefXp(); Playtest.keyDown("shift"); }, () -> {
            t[0]++;
            if (t[0] % 200 == 1) {
                String line = server(() -> {
                    List<TownspersonEntity> officers = level().getEntitiesOfClass(TownspersonEntity.class, new AABB(me().blockPosition()).inflate(64), e -> e.police() && e.isAlive());
                    StringBuilder b = new StringBuilder(officers.size() + " officer(s)");
                    for (TownspersonEntity o : officers) b.append(String.format(Locale.ROOT, " [%s at %s, %.1f blocks, sees the thief %s]", o.getName().getString(), o.blockPosition().toShortString(), o.distanceTo(me()), o.hasLineOfSight(me())));
                    return b + "; dispatches " + PoliceResponse.dispatches();
                });
                F.officerLog.add(line);
                Playtest.log("HEIST at " + (t[0] / 20) + " s: " + line);
            }
            if (Playtest.heardSince(F.heardMark, "under arrest") != null) { F.outcome = "arrested"; return true; }
            if (t[0] > 100 && server(PoliceResponse::dispatches) == 0) { F.outcome = "the response ended"; return true; }
            return false;
        }, 6400);
        Playtest.queue("stand up", () -> Playtest.keyUp("shift"));
        Playtest.wait("the notice", 20);
        shot("9-got-away");
        Playtest.queue("the outcome", () -> {
            int xp = thiefXp();
            int expected = Thievery.ESCAPE_XP_PER_TIER * F.club.security().tier();
            String away = Playtest.heardSince(F.heardMark, "police");
            Playtest.log("HEIST outcome: " + F.outcome + "; Thief XP " + F.xpEscaped + " -> " + xp + " (an escape pays +" + expected + "); " + PoliceResponse.status() + "; last heard about the police: " + away);
            if ("arrested".equals(F.outcome)) Playtest.fail("the thief was arrested while hiding inside the club");
            else if (F.outcome == null) Playtest.fail("the police response had not ended after more than five minutes");
            else if (xp - F.xpEscaped != expected) Playtest.fail("slipping the police paid " + (xp - F.xpEscaped) + " Thief XP, not " + expected);
            else pass("9 keep out of sight inside until the officers give up (they do not pass a door); slipping the response pays " + expected + " Thief XP");
            CitizenState.Citizen c = ThiefScenario.citizen();
            Playtest.log("HEIST Thief XP across the job: " + F.xpBefore + " -> " + xp + " (entry " + (F.xpEntered - F.xpBefore) + ", safe " + (F.xpSafe - F.xpEntered) + ", escape " + (xp - F.xpEscaped) + "); level " + c.thiefLevel());
        });
    }
}
