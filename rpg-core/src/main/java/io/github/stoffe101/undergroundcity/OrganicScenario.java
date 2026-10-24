package io.github.stoffe101.undergroundcity;

import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.BarrelBlock;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

import java.util.Locale;
import java.util.function.Supplier;

import static io.github.stoffe101.undergroundcity.JourneyScenario.aimedAt;
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
 * A burglary by day through a real client ({@code -PucPlaytest=organic}, a new furnished archipelago world), with no spatial shortcut once the thief is on the street (D-159): in at an open
 * bakery's front door on foot, refused out on the shop floor, the stock room's staff door found locked, picked on the lock screen, the stock room searched out of the baker's sight, and out again to
 * the street on foot. Whether anybody saw, and what the police did, is logged as it happened.
 *
 * <p>Scripted rather than played, each logged as SCRIPTED: the clock, the travel to Valewood, the start on the street outside the bakery, and one lockpick (a den sells them). Every step inside
 * the building is walked with the real keys by the path walker, and a walk that does not arrive is a failure, not a teleport.
 */
final class OrganicScenario {
    private OrganicScenario() {}

    private static final class Facts {
        CityBuildings.Building shop;
        DoorWalk.Spot street;
        BlockPos staffDoor, publicCell, staffCell, floorPiece, floorStand, stockPiece, takings;
        int stolenBefore, dispatchesBefore;
        long warnedBefore;
    }

    private static final Facts F = new Facts();

    static void build() {
        ThiefScenario.tag = "ORGANIC";
        Playtest.keepScreens = true;
        ThiefScenario.register(0, "the Farmer card", "1 a new citizen (a Farmer) registers");
        Playtest.queue("the clock: 13:00 with the daylight cycle stopped", () -> {
            Playtest.command("gamerule doDaylightCycle false");
            Playtest.command("time set 7000");
            scripted("the time of day was set to 13:00 and the daylight cycle stopped");
        });
        ThiefScenario.travel("to the middle of Valewood", () -> ThiefScenario.centre("valewood"), 6);
        choose();
        ThiefScenario.stopIfFailed("an open bakery with a staff door");
        Playtest.queue("start on the street outside the bakery, with a lockpick (scripted)", () -> {
            scripted("the player was put on the street outside the bakery's front door at " + F.street.door().toShortString() + " in survival, with one lockpick");
            ThiefScenario.standAt(F.street);
            server(() -> { me().getInventory().add(new ItemStack(UndergroundCity.item("lockpick"))); return null; });
            F.stolenBefore = ThiefScenario.stolen()[0];
            F.dispatchesBefore = server(PoliceResponse::dispatches);
            F.warnedBefore = StaffZones.warned;
        }, () -> mc().player.position().distanceTo(F.street.stand()) < 0.8, 200);
        Playtest.wait("settle", 20);
        shot("1-street");
        walk("walk in at the front door to the counter", () -> Vec3.atBottomCenterOf(F.floorStand), 0.7, 900);
        floor();
        walk("walk to the stock room's door", () -> Vec3.atBottomCenterOf(F.publicCell), 0.6, 600);
        staffDoor();
        Playtest.queue("stop if the staff door is shut", () -> {
            if (server(() -> !level().getBlockState(F.staffDoor).getValue(DoorBlock.OPEN))) { Playtest.log("ORGANIC stopping: the staff door is shut, and the rest depends on it"); ThiefScenario.summary(); Playtest.finish(); }
        });
        walk("walk through into the stock room", () -> Vec3.atBottomCenterOf(F.staffCell), 0.6, 300);
        stockRoom();
        walk("walk out to the street", () -> F.street.stand(), 1.0, 900);
        Playtest.wait("settle", 20);
        shot("6-out");
        Playtest.queue("out, and what was noticed", () -> {
            boolean outside = server(() -> me().level().canSeeSky(me().blockPosition()) || !BuildingGuard.protectedAt(level(), me().blockPosition()).isPresent());
            int dispatches = server(PoliceResponse::dispatches) - F.dispatchesBefore;
            long warned = StaffZones.warned - F.warnedBefore;
            Playtest.log("ORGANIC out: under the open sky " + outside + "; police dispatches since the start " + dispatches + "; told to leave a staff area " + warned + " time(s)");
            if (!outside) Playtest.fail("the thief did not get back out to the street");
            else pass("6 out by the front door to the street on foot" + (dispatches > 0 ? " (somebody saw, and the police were called)" : ", unseen: nobody called the police"));
        });
        Playtest.queue("summary", ThiefScenario::summary);
    }

    /** A walk with the real keys; a walk that does not arrive is a failure (the point of this scenario is that nothing inside is a teleport). */
    private static void walk(String label, Supplier<Vec3> target, double within, int budget) {
        final PathWalker[] walker = new PathWalker[1];
        final int[] ticks = {0};
        Playtest.queue(label, () -> { ticks[0] = 0; walker[0] = new PathWalker(label, target, within, 1.0); }, () -> {
            walker[0].tick();
            if (++ticks[0] > budget && !walker[0].done()) walker[0].stop();
            return walker[0].done() || ticks[0] > budget;
        }, budget + 20);
        Playtest.queue(label + ": arrived", () -> {
            if (walker[0] != null) walker[0].stop();
            Vec3 goal = target.get();
            double left = mc().player.position().distanceTo(goal);
            Playtest.log(String.format(Locale.ROOT, "ORGANIC %s: %.1f blocks from the goal; %s", label, left, walker[0] == null ? "" : walker[0].summary()));
            if (left > within + 0.6) Playtest.fail(label + ": the walk stopped " + String.format(Locale.ROOT, "%.1f", left) + " blocks short");
        });
        Playtest.wait("settle", 10);
    }

    // ------------------------------------------------------------------------------------------------ the bakery

    private static void choose() {
        Playtest.queue("an open bakery with a staff door near the middle of Valewood", () -> server(() -> {
            ServerLevel level = level();
            BlockPos here = me().blockPosition();
            for (int r = 0; r <= 8 && F.shop == null; r++) for (int dx = -r; dx <= r && F.shop == null; dx++) for (int dz = -r; dz <= r && F.shop == null; dz++) {
                if (Math.max(Math.abs(dx), Math.abs(dz)) != r) continue;
                int cx = (here.getX() >> 4) + dx, cz = (here.getZ() >> 4) + dz;
                if (!level.hasChunk(cx, cz)) continue;
                CityBuildings.Building b = CityBuildings.INSTANCE.ofChunk(level, cx, cz).orElse(null);
                if (b == null || !b.type().endsWith("vw_bakery")) continue;
                boolean open = ShopCounters.openNow(level, b);
                StaffZones.Zones z = StaffZones.of(level, b);
                Playtest.log("ORGANIC bakery at chunk " + cx + "," + cz + ": open " + open + ", public cells " + z.publicCells().size() + ", staff doors " + z.staffDoors().size());
                if (!open || z.staffDoors().isEmpty()) continue;
                DoorWalk.Spot street = ThiefScenario.streetDoor(level, b);
                if (street == null) continue;
                BlockPos door = BlockPos.of(z.staffDoors().iterator().next());
                BlockState state = level.getBlockState(door);
                Direction facing = state.getValue(DoorBlock.FACING);
                BlockPos pub = z.isPublic(door.relative(facing)) ? door.relative(facing) : door.relative(facing.getOpposite());
                BlockPos staff = pub.equals(door.relative(facing)) ? door.relative(facing.getOpposite()) : door.relative(facing);
                BlockPos barrel = null;
                for (BlockPos c : BlockPos.betweenClosed(staff.offset(-3, 0, -3), staff.offset(3, 1, 3))) {
                    if (level.getBlockState(c).getBlock() instanceof BarrelBlock && !StaffZones.onPublicFloor(level, b, c) && c.distSqr(staff) <= 2) { barrel = c.immutable(); break; }
                }
                // a piece out front, and a cell of the floor beside it with a clear view of it, within reach (worked out here: the scenario's own helper asks the server itself)
                BlockPos piece = null, stand = null;
                for (BlockPos p : ThiefScenario.furniture(level, b, pub)) {
                    if (p.getY() != pub.getY() || !StaffZones.onPublicFloor(level, b, p)) continue;
                    for (BlockPos c : BlockPos.betweenClosed(p.offset(-2, 0, -2), p.offset(2, 0, 2))) {
                        if (!z.isPublic(c)) continue;
                        Vec3 eye = Vec3.atBottomCenterOf(c).add(0, 1.62, 0);
                        var hit = level.clip(new net.minecraft.world.level.ClipContext(eye, Vec3.atCenterOf(p), net.minecraft.world.level.ClipContext.Block.OUTLINE, net.minecraft.world.level.ClipContext.Fluid.NONE, null));
                        if (hit.getType() == net.minecraft.world.phys.HitResult.Type.BLOCK && !hit.getBlockPos().equals(p)) continue;
                        stand = c.immutable();
                        break;
                    }
                    if (stand != null) { piece = p; break; }
                }
                BlockPos till = null;
                for (BlockPos p : ThiefScenario.furniture(level, b, staff)) if (!StaffZones.onPublicFloor(level, b, p) && p.distSqr(staff) <= 9) { till = p; break; }
                if (barrel == null || piece == null) { Playtest.log("ORGANIC   no barrel beside the staff side or no piece out front (" + barrel + ", " + piece + ")"); continue; }
                F.takings = till;
                F.shop = b; F.street = street; F.staffDoor = door; F.publicCell = pub; F.staffCell = staff; F.stockPiece = barrel; F.floorPiece = piece; F.floorStand = stand;
            }
            return null;
        }));
        Playtest.queue("the bakery chosen", () -> {
            if (F.shop == null) { Playtest.fail("no open bakery with a staff door, a stock-room barrel and furniture out front near the middle of Valewood"); return; }
            Playtest.log(String.format(Locale.ROOT, "ORGANIC chose the bakery at chunk %d,%d: front door %s, staff door %s (public side %s, staff side %s), out front %s, in the stock room %s",
                F.shop.chunkX(), F.shop.chunkZ(), F.street.door().toShortString(), F.staffDoor.toShortString(), F.publicCell.toShortString(), F.staffCell.toShortString(),
                F.floorPiece.toShortString(), F.stockPiece.toShortString()));
        });
    }

    // ------------------------------------------------------------------------------------------------ out front

    private static void floor() {
        final int[] mark = {0}, t = {0};
        final boolean[] started = {false};
        Playtest.queue("right-click the counter out on the floor", () -> { mark[0] = Playtest.heardCount(); t[0] = 0; Playtest.focusWindow(); Playtest.tap("1"); }, () -> {
            Vec3 c = Vec3.atCenterOf(F.floorPiece);
            look((t[0] / 24) % 2 == 0 ? c : c.add(0, -0.3, 0));
            // the counter has the till on it: the crosshair may rest on either, and both are furniture out on the floor
            if (++t[0] % 24 == 12 && (aimedAt(F.floorPiece) || aimedAt(F.floorPiece.above()))) Playtest.click(3);
            if (t[0] % 48 == 47) Playtest.log("ORGANIC at the counter: the crosshair on " + (mc().hitResult instanceof net.minecraft.world.phys.BlockHitResult hit ? hit.getBlockPos().toShortString() : "nothing") + ", the piece at " + F.floorPiece.toShortString());
            started[0] |= ClientBurglary.searching();
            return t[0] >= 160 || Playtest.heardSince(mark[0], "Not out on the floor") != null;
        }, 200);
        shot("2-floor-refused");
        Playtest.queue("the floor's answer", () -> {
            String said = Playtest.heardSince(mark[0], "Not out on the floor");
            Playtest.log("ORGANIC at the counter: " + (said == null ? "nothing said" : said) + "; a search started " + started[0]);
            if (said == null || started[0]) Playtest.fail("a piece of furniture out on the shop floor was searched, or nothing was said, while the shop was open");
            else pass("2 out on the floor of the open bakery, the counter is not searched: \"" + said + "\"");
        });
    }

    // ------------------------------------------------------------------------------------------------ the staff door

    private static void staffDoor() {
        final int[] mark = {0}, t = {0};
        Playtest.queue("right-click the staff door with an empty hand", () -> { mark[0] = Playtest.heardCount(); t[0] = 0; Playtest.focusWindow(); Playtest.tap("9"); }, () -> {
            look(Vec3.atCenterOf(F.staffDoor).add(0, 0.4, 0));
            if (++t[0] == 16 && (aimedAt(F.staffDoor) || aimedAt(F.staffDoor.above()))) Playtest.click(3);
            return t[0] >= 40 || Playtest.heardSince(mark[0], "Staff only") != null;
        }, 120);
        Playtest.queue("the staff door's answer", () -> {
            String said = Playtest.heardSince(mark[0], "Staff only");
            boolean shut = server(() -> !level().getBlockState(F.staffDoor).getValue(DoorBlock.OPEN));
            Playtest.log("ORGANIC at the staff door: " + (said == null ? "nothing said" : said) + "; shut " + shut);
            if (said == null || !shut) Playtest.fail("the staff door of the open bakery was not locked to the public side");
            else pass("3 the stock room's door is a staff door: shut to the shop floor and locked (\"" + said + "\")");
        });
        shot("3-staff-door");
        final int[] k = {0};
        Playtest.queue("pick the staff door: the lockpick in hand, a right click", () -> {
            k[0] = 0;
            int slot = -1;
            for (int i = 0; i < 9; i++) if (LockService.isPick(mc().player.getInventory().getItem(i))) slot = i;
            if (slot < 0) { Playtest.fail("the lockpick is not in the hotbar"); return; }
            Playtest.tap(String.valueOf(slot + 1));
        }, () -> {
            if (mc().screen instanceof LockpickScreen) return true;
            look(Vec3.atCenterOf(F.staffDoor).add(0, 0.4, 0));
            if (++k[0] % 24 == 12 && (aimedAt(F.staffDoor) || aimedAt(F.staffDoor.above()))) Playtest.click(3);
            return false;
        }, 400);
        Playtest.wait("the lock screen draws", 12);
        shot("4-staff-lock");
        BurglaryScenario.solve("the staff door", false);
        until("the staff door opens", () -> server(() -> level().getBlockState(F.staffDoor).getValue(DoorBlock.OPEN)), 200);
        Playtest.queue("the staff door is open", () -> pass("4 the staff door picked on the lock screen with real input"));
    }

    // ------------------------------------------------------------------------------------------------ the stock room

    private static void stockRoom() {
        Playtest.queue("in the stock room", () -> {
            boolean staffArea = server(() -> StaffZones.inStaffArea(level(), F.shop, me().blockPosition()));
            Playtest.log("ORGANIC in the stock room at " + mc().player.blockPosition().toShortString() + ": a staff area " + staffArea);
            if (!staffArea) Playtest.fail("the stock room is not a staff-only area");
        });
        ThiefScenario.searchClick("right-click the stock-room barrel until the search starts", () -> F.stockPiece, 400);
        Playtest.wait("the bar fills a little", 14);
        shot("5-searching");
        until("the search ends", () -> !ClientBurglary.searching() && !server(() -> BurglarySearch.searching(me().getUUID())), 400);
        Playtest.wait("the barrel opens", 12);
        Playtest.queue("what the search did", () -> {
            boolean opened = mc().screen instanceof AbstractContainerScreen<?>;
            boolean marked = server(() -> !BurglarySearch.records(level().getServer()).ready(F.stockPiece.asLong(), LockService.clock(level().getServer()), Long.MAX_VALUE));
            int stolen = ThiefScenario.stolen()[0] - F.stolenBefore;
            Playtest.log("ORGANIC the stock-room barrel: opened " + opened + ", recorded as searched " + marked + ", stolen units carried since the start " + stolen);
            if (!marked) Playtest.fail("the barrel in the stock room could not be searched while the shop was open");
            else pass("5 in the stock room, out of the baker's sight, the barrel is searched while the shop is open" + (opened ? " and opens" : ""));
        });
        JourneyScenario.closeScreen("close the barrel");
        // the day's takings at the back: a second search in the same room
        Playtest.queue("the takings", () -> { if (F.takings == null) Playtest.log("ORGANIC no desk screen in the stock room (an older bakery plan)"); else F.stolenBefore = ThiefScenario.stolen()[0]; });
        ThiefScenario.searchClick("right-click the desk screen at the back until the search starts", () -> F.takings, 400);
        until("the second search ends", () -> F.takings == null || !ClientBurglary.searching() && !server(() -> BurglarySearch.searching(me().getUUID())), 400);
        Playtest.wait("the notice", 10);
        shot("5b-takings");
        Playtest.queue("what the second search found", () -> {
            if (F.takings == null) return;
            boolean marked = server(() -> !BurglarySearch.records(level().getServer()).ready(F.takings.asLong(), LockService.clock(level().getServer()), Long.MAX_VALUE));
            int[] got = ThiefScenario.stolen();
            Playtest.log("ORGANIC the takings at " + F.takings.toShortString() + ": searched " + marked + ", stolen units now " + got[0] + " (" + (got[0] - F.stolenBefore) + " new), " + got[1] + " marked with the thief");
            if (!marked) Playtest.fail("the desk screen in the stock room could not be searched while the shop was open");
            else pass("5b the desk screen at the back is searched as the shop's till: " + (got[0] - F.stolenBefore) + " stolen unit(s), every one marked with the thief");
        });
    }
}
