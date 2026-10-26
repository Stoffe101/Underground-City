package io.github.stoffe101.undergroundcity;

import net.minecraft.client.gui.screens.GenericDirtMessageScreen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

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
 * A burglary that breaks things, and the city putting them back (D-190), through a real client ({@code -PucPlaytest=wreck -PucPtRestoreminutes=1}, a new furnished archipelago world): a new
 * citizen picks the lock of an empty Valewood house on the real lock screen, goes in, breaks a window, a piece of decoration and an inner wall with real held clicks (nothing drops), tries the
 * outer wall (it holds), searches a piece of furniture, leaves; the game is saved, quit and reopened while the house waits; once the wait is over the house is put back, and the thief walks back
 * to find it as it was, its door shut and locked.
 *
 * <p>Scripted, each logged: the clock (13:00, stopped), the travel to Valewood, the choice of house, the lockpicks, standing beside each thing to break (the walk between rooms is not what this
 * tests) and the wait shortened to one minute of play ({@code -PucPtRestoreminutes=1}; the game's own is thirty).
 */
final class WreckScenario {
    private WreckScenario() {}

    private static final class Facts {
        final Map<BlockPos, BlockState> before = new LinkedHashMap<>();
        final List<BlockPos> broken = new ArrayList<>();
        volatile BlockPos shell, outside;
        volatile int stolenAfterSearch = -1;
        volatile long recordCells;
    }
    private static final Facts F = new Facts();

    static void build() {
        ThiefScenario.tag = "WRECK";
        Playtest.keepScreens = true;
        ThiefScenario.register(0, "the Farmer card", "1 a new citizen (a Farmer) registers");
        Playtest.queue("the clock: 13:00 with the daylight cycle stopped", () -> {
            Playtest.command("gamerule doDaylightCycle false");
            Playtest.command("time set 7000");
            scripted("the time of day was set to 13:00 and the daylight cycle stopped");
        });
        ThiefScenario.travel("to the middle of Valewood", () -> ThiefScenario.centre("valewood"), 4);
        ThiefScenario.chooseHouse();
        ThiefScenario.stopIfFailed("finding a house");
        ThiefScenario.prepareAtDoor();
        ThiefScenario.waitForClearStreet("valewood");
        ThiefScenario.pickTheDoor();
        ThiefScenario.stopIfFailed("the door");
        Playtest.queue("a quiet entry", () -> {
            boolean wanted = server(() -> PoliceResponse.wanted(me().getUUID()));
            Playtest.log("WRECK after the pick: wanted " + wanted + "; " + server(PoliceResponse::status) + "; " + server(CrimeWatch::status));
            if (!wanted) pass("4 an unwitnessed, unalarmed burglary: the lock picked with nobody in sight and no alarm, and nobody calls the police");
            else Playtest.log("WRECK somebody noticed the entry this time (the street was not empty of passers-by)");
        });
        chooseTargets();
        ThiefScenario.stopIfFailed("finding things to break");
        for (int i = 0; i < 3; i++) breakOne(i);
        tryTheShell();
        searchOne();
        leave();
        reopen();
        waitForRepair();
        revisit();
        Playtest.queue("summary", ThiefScenario::summary);
    }

    /** Inside the house: a window, a piece of decoration and an inner wall the thief may break (the game's own judgement), and a block of the outer wall that must hold. */
    private static void chooseTargets() {
        Playtest.queue("what in the house may be broken (on the server)", () -> {
            List<BlockPos> picked = server(() -> {
                ServerLevel level = level();
                CityBuildings.Building b = ThiefScenario.house();
                long now = LockService.clock(level.getServer());
                BlockPos glass = null, decor = null, wall = null, shell = null;
                for (int y = b.streetY(); y < b.streetY() + 12; y++) for (int x = 0; x < 16; x++) for (int z = 0; z < 16; z++) {
                    BlockPos p = new BlockPos((b.chunkX() << 4) + x, y, (b.chunkZ() << 4) + z);
                    BlockState s = level.getBlockState(p);
                    if (s.isAir()) continue;
                    BurglaryDamage.Verdict v = BurglaryDamage.judge(me(), level, b, p, now);
                    if (v == BurglaryDamage.Verdict.ALLOWED && BurglaryDamage.glass(s) && glass == null && ThiefScenario.standBeside(p, b) != null) glass = p.immutable();
                    else if (v == BurglaryDamage.Verdict.ALLOWED && s.is(BurglaryDamage.BREAKABLE) && decor == null && ThiefScenario.standBeside(p, b) != null) decor = p.immutable();
                    else if (v == BurglaryDamage.Verdict.ALLOWED && s.is(BurglaryDamage.BREAKABLE_WALLS) && wall == null && ThiefScenario.standBeside(p, b) != null) wall = p.immutable();
                    else if (v == BurglaryDamage.Verdict.SHELL && shell == null && y == b.streetY() + 2 && ThiefScenario.standBeside(p, b) != null) shell = p.immutable();
                }
                F.shell = shell;
                List<BlockPos> out = new ArrayList<>();
                for (BlockPos p : new BlockPos[]{glass, decor, wall}) if (p != null) out.add(p);
                return out;
            });
            for (BlockPos p : picked) F.before.put(p, server(() -> level().getBlockState(p)));
            F.broken.addAll(picked);
            Playtest.log("WRECK targets: " + F.before + "; a shell block " + (F.shell == null ? "none found" : F.shell.toShortString() + " " + server(() -> level().getBlockState(F.shell))));
            if (picked.size() < 2) Playtest.fail("fewer than two things in the house may be broken: " + picked);
        });
    }

    /** Stands beside thing {@code i} (scripted), looks at it and holds the attack button until it is gone. */
    private static void breakOne(int i) {
        final BlockPos[] stand = new BlockPos[1];
        final int[] t = {0};
        Playtest.queue("stand beside the thing to break " + (i + 1), () -> {
            if (i >= F.broken.size()) return;
            stand[0] = ThiefScenario.standBeside(F.broken.get(i), ThiefScenario.house());
            if (stand[0] == null) return;
            BlockPos at = stand[0];
            scripted("the player was put beside the thing to break " + (i + 1) + " at " + at.toShortString());
            mc().getSingleplayerServer().execute(() -> { me().teleportTo(at.getX() + 0.5, at.getY(), at.getZ() + 0.5); me().setDeltaMovement(Vec3.ZERO); });
        }, () -> i >= F.broken.size() || stand[0] == null || mc().player.blockPosition().equals(stand[0]), 200);
        Playtest.wait("settle", 10);
        Playtest.queue("an empty hand, then hold the attack button on thing " + (i + 1) + " until it breaks", () -> { t[0] = 0; Playtest.focusWindow(); Playtest.tap("8"); }, () -> {
            if (i >= F.broken.size() || stand[0] == null) return true;
            BlockPos b = F.broken.get(i);
            if (server(() -> level().getBlockState(b).isAir())) { Playtest.mouseButton(1, false); return true; }
            look(Vec3.atCenterOf(b));
            t[0]++;
            boolean onIt = mc().hitResult instanceof BlockHitResult hit && hit.getType() == HitResult.Type.BLOCK && hit.getBlockPos().equals(b);
            if (t[0] == 6 && onIt) Playtest.mouseButton(1, true);
            if (t[0] > 6 && !onIt && t[0] % 40 == 0) { Playtest.mouseButton(1, false); t[0] = 0; }
            return false;
        }, 600);
        Playtest.queue("let go", () -> Playtest.mouseButton(1, false));
        Playtest.wait("the notice", 15);
        if (i == 0) shot("1-broken-window");
        Playtest.queue("thing " + (i + 1) + " broken", () -> {
            if (i >= F.broken.size()) return;
            BlockPos b = F.broken.get(i);
            boolean gone = server(() -> level().getBlockState(b).isAir());
            int drops = server(() -> level().getEntitiesOfClass(ItemEntity.class, new AABB(b).inflate(6)).size());
            long cells = server(() -> { var r = BurglaryDamage.store(level().getServer()).records.get(ThiefScenario.house().key()); return r == null ? 0L : (long) r.cells.size(); });
            F.recordCells = cells;
            Playtest.log("WRECK broke " + F.before.get(b) + " at " + b.toShortString() + ": gone " + gone + ", items on the ground " + drops + ", cells recorded " + cells + "; police " + server(PoliceResponse::status));
            if (!gone) Playtest.fail("a held attack did not break " + F.before.get(b) + " at " + b.toShortString());
            else if (drops > 0) Playtest.fail("breaking dropped " + drops + " item(s)");
            else if (cells < i + 1) Playtest.fail("the break was not recorded for putting back");
            else pass((i + 2) + "b broke " + F.before.get(b).getBlock().getName().getString() + " with a real held click: gone, nothing dropped, recorded to be put back");
        });
    }

    /** The outer wall holds, whatever the thief tries. */
    private static void tryTheShell() {
        final int[] t = {0};
        Playtest.queue("stand beside a block of the outer wall", () -> {
            if (F.shell == null) return;
            BlockPos at = ThiefScenario.standBeside(F.shell, ThiefScenario.house());
            if (at == null) { F.shell = null; return; }
            scripted("the player was put beside the outer wall at " + at.toShortString());
            mc().getSingleplayerServer().execute(() -> { me().teleportTo(at.getX() + 0.5, at.getY(), at.getZ() + 0.5); me().setDeltaMovement(Vec3.ZERO); });
        });
        Playtest.wait("settle", 10);
        Playtest.queue("hold the attack button on the outer wall for five seconds", () -> { t[0] = 0; if (F.shell != null) { look(Vec3.atCenterOf(F.shell)); Playtest.mouseButton(1, true); } }, () -> {
            if (F.shell == null) return true;
            look(Vec3.atCenterOf(F.shell));
            return ++t[0] > 100;
        }, 140);
        Playtest.queue("let go", () -> Playtest.mouseButton(1, false));
        Playtest.wait("the action bar", 6);
        Playtest.queue("the outer wall held", () -> {
            if (F.shell == null) { Playtest.log("WRECK no block of the outer wall in reach to try"); return; }
            boolean held = !server(() -> level().getBlockState(F.shell).isAir());
            if (!held) Playtest.fail("the outer wall gave way to the thief");
            else pass("5 the outer wall holds against a held attack (the shell is never broken)");
        });
    }

    private static void searchOne() {
        Playtest.queue("stand beside the first piece of furniture", () -> {
            List<BlockPos> spots = ThiefScenario.spots();
            if (spots.isEmpty()) return;
            BlockPos at = ThiefScenario.standBeside(spots.get(0), ThiefScenario.house());
            if (at == null) return;
            scripted("the player was put beside the first piece of furniture at " + at.toShortString());
            mc().getSingleplayerServer().execute(() -> { me().teleportTo(at.getX() + 0.5, at.getY(), at.getZ() + 0.5); me().setDeltaMovement(Vec3.ZERO); });
        });
        Playtest.wait("settle", 10);
        ThiefScenario.searchClick("right-click the furniture until the search starts", () -> ThiefScenario.spots().isEmpty() ? null : ThiefScenario.spots().get(0), 400);
        until("the search ends", () -> !ClientBurglary.searching() && !server(() -> BurglarySearch.searching(me().getUUID())), 400);
        Playtest.wait("the notice", 10);
        Playtest.queue("what the search found", () -> {
            F.stolenAfterSearch = ThiefScenario.stolen()[0];
            Playtest.log("WRECK search: carrying " + F.stolenAfterSearch + " stolen unit(s); police " + server(PoliceResponse::status));
            pass("6 searched a piece of furniture with a real click: " + F.stolenAfterSearch + " stolen unit(s) carried");
        });
    }

    /** Out of the door and 40 blocks down the street, as a thief leaves (the walker walks it). */
    private static void leave() {
        Playtest.queue("a street point 40 blocks from the house", () -> {
            DoorWalk.Spot door = ThiefScenario.door();
            Vec3 out = door.stand().add(door.out().getStepX() * 40.0, 0, door.out().getStepZ() * 40.0);
            F.outside = BlockPos.containing(out);
        });
        ThiefScenario.walkOrTeleport("leave the house and walk 40 blocks down the street", () -> {
            BlockPos o = F.outside;
            if (o == null) return null;
            BlockPos ground = server(() -> CityArrival.safeStand(level(), o, 8, 6, 6).orElse(o));
            return Vec3.atBottomCenterOf(ground);
        }, 2.0, 1600);
        Playtest.queue("away from the house", () -> Playtest.log("WRECK left the house; police " + server(PoliceResponse::status) + "; " + server(CrimeWatch::status)));
    }

    /** A save, a quit and a reopen while the house waits to be put back: the record must survive it. */
    private static void reopen() {
        Playtest.queue("save and quit to the title screen, then open the world again", () -> {
            var mc = mc();
            String folder = System.getProperty("uc.playtest.world", "UC-Playtest");
            mc.level.disconnect();
            mc.clearLevel(new GenericDirtMessageScreen(Component.translatable("menu.savingLevel")));
            mc.setScreen(new TitleScreen());
            mc.createWorldOpenFlows().loadLevel(mc.screen, folder);
        }, () -> mc().player != null && mc().level != null && Scenarios.groundBelowPlayer(), 6000);
        Playtest.wait("settle after the reopen", 60);
        Playtest.queue("the record after the reopen", () -> {
            long cells = server(() -> { var r = BurglaryDamage.store(level().getServer()).records.get(ThiefScenario.house().key()); return r == null ? -1L : (long) r.cells.size(); });
            boolean stillBroken = server(() -> level().isLoaded(F.broken.get(0)) ? level().getBlockState(F.broken.get(0)).isAir() : true);
            Playtest.log("WRECK after the reopen: " + cells + " cells waiting to be put back (" + F.recordCells + " before), the first still broken " + stillBroken);
            if (cells < F.recordCells) Playtest.fail("the record of what was broken did not survive the reopen (or was put back already)");
            else pass("7 saved, quit and reopened while the house waits: the record of " + cells + " broken cells is still there");
        });
    }

    private static void waitForRepair() {
        until("the house is put back (a minute of play, the house unoccupied)", () -> server(() -> !BurglaryDamage.store(level().getServer()).records.containsKey(ThiefScenario.house().key())), 20 * 60 * 4);
        Playtest.queue("put back", () -> Playtest.log("WRECK " + server(() -> BurglaryDamage.status(level().getServer()))));
    }

    private static void revisit() {
        ThiefScenario.walkOrTeleport("walk back to the house's door", () -> ThiefScenario.door().stand(), 1.5, 1600);
        Playtest.queue("face the house", () -> { look(Vec3.atCenterOf(ThiefScenario.door().door()).add(0, 1.0, 0)); });
        Playtest.wait("the chunks draw", 60);
        shot("2-put-back");
        Playtest.queue("as it was", () -> {
            List<String> wrong = new ArrayList<>();
            for (var e : F.before.entrySet()) {
                BlockState now = server(() -> level().getBlockState(e.getKey()));
                if (now.getBlock() != e.getValue().getBlock()) wrong.add(e.getKey().toShortString() + " is " + now + " (was " + e.getValue() + ")");
            }
            boolean doorShut = !server(() -> DoorWalk.doorOpen(level(), ThiefScenario.door()));
            int stolen = ThiefScenario.stolen()[0];
            Playtest.log("WRECK revisit: " + (wrong.isEmpty() ? "everything broken is back" : "not back: " + wrong) + "; door " + (doorShut ? "shut" : "open") + "; stolen units carried " + stolen + " (" + F.stolenAfterSearch + " after the search)");
            if (!wrong.isEmpty()) Playtest.fail("the house was not put back as it was: " + wrong);
            else if (!doorShut) Playtest.fail("the house's door stands open after it was put back");
            else if (F.stolenAfterSearch >= 0 && stolen != F.stolenAfterSearch) Playtest.fail("what the thief carries changed while the house was put back: " + stolen + " for " + F.stolenAfterSearch);
            else pass("8 walking back after the wait: the window, the decoration and the wall are back as they were, the door is shut, and the loot carried is unchanged (nothing copied)");
        });
    }
}
