package io.github.stoffe101.undergroundcity;

import net.minecraft.core.BlockPos;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.Vec3;

import java.util.List;

import static io.github.stoffe101.undergroundcity.JourneyScenario.level;
import static io.github.stoffe101.undergroundcity.JourneyScenario.mc;
import static io.github.stoffe101.undergroundcity.JourneyScenario.me;
import static io.github.stoffe101.undergroundcity.JourneyScenario.server;
import static io.github.stoffe101.undergroundcity.JourneyScenario.until;
import static io.github.stoffe101.undergroundcity.ThiefScenario.pass;
import static io.github.stoffe101.undergroundcity.ThiefScenario.scripted;
import static io.github.stoffe101.undergroundcity.ThiefScenario.shot;

/**
 * Trade premises through a real client ({@code -PucPlaytest=premises}, D-169): a Smith prepared with the guide's operator command (level 5, 1,000 credits, and the iron, nuggets and redstone a crate
 * of fittings takes, all logged) rents a commercial workshop in Ironhaven on the Home page, walks in with the key, sets the Machinist's Bench and the kit's terminal down inside, sees the bench run
 * two batches at a time a quarter faster, makes a crate of fittings, takes and delivers the bulk order only premises may take, and saves and reopens the world.
 */
final class PremisesScenario {
    private PremisesScenario() {}

    private static final class Facts {
        volatile CityBuildings.Building workshop;
        volatile DoorWalk.Spot door;
        volatile String premisesId = "", category = "";
    }

    private static final Facts F = new Facts();
    private static final StationSteps.Station BENCH = new StationSteps.Station("machinist_bench");
    static final String BULK = "underground_city:bulk_depot_fittings", NS = UndergroundCity.MOD_ID + ":";

    static void build() {
        ThiefScenario.tag = "PREMISES";
        Playtest.keepScreens = true;
        ThiefScenario.register(2, "the Smith card", "1 a new citizen registers as a Smith through the registration screen");
        Playtest.queue("prepared with /uc_prepare (scripted)", () -> {
            Playtest.command("gamerule doDaylightCycle false");
            Playtest.command("time set 7000");
            Playtest.command("uc_prepare level @s 5");
            Playtest.command("uc_prepare credits @s 1000");
            Playtest.command("uc_prepare kit @s smith_crate");
            scripted("PREPARED with the operator command the guide's session 3 uses (/uc_prepare, D-179): Smith level 5, 1,000 credits and the smith_crate kit (20 iron ingots, 48 iron nuggets, 2 redstone: a crate of fittings' worth from the Hardware desk); the clock set to 13:00 and stopped");
        });
        until("the command took", () -> { var c = ThiefScenario.citizen(); return c != null && c.careerXp() >= ProgressionRules.xpAtLevel(5) && JourneyScenario.count(Items.IRON_NUGGET) >= 48; }, 200);
        Playtest.queue("the prepared state", () -> {
            var c = ThiefScenario.citizen();
            Playtest.log("PREMISES prepared: career XP " + (c == null ? 0 : c.careerXp()) + ", wallet " + JourneyScenario.wallet() + ", iron " + JourneyScenario.count(Items.IRON_INGOT) + ", nuggets " + JourneyScenario.count(Items.IRON_NUGGET));
            if (c == null || JourneyScenario.wallet() < 1000) Playtest.fail("/uc_prepare did not raise the level and add the credits");
            else pass("1b /uc_prepare level, credits and kit took (an operator's prepared start, logged as PREPARED in chat and the server log)");
        });
        ThiefScenario.travel("to the middle of Ironhaven", () -> ThiefScenario.centre("ironhaven"), 8);
        Playtest.queue("a workshop with premises to let", () -> {
            F.workshop = server(() -> {
                var level = level();
                BlockPos here = me().blockPosition();
                for (int r = 0; r <= 16; r++) for (int dx = -r; dx <= r; dx++) for (int dz = -r; dz <= r; dz++) {
                    if (Math.max(Math.abs(dx), Math.abs(dz)) != r) continue;
                    int cx = (here.getX() >> 4) + dx, cz = (here.getZ() >> 4) + dz;
                    if (!level.hasChunk(cx, cz)) continue;
                    CityBuildings.Building b = CityBuildings.INSTANCE.ofChunk(level, cx, cz).orElse(null);
                    if (b != null && b.type().endsWith("ih_workshop") && CityHomes.anyLet(level.getSeed(), b)) return b;
                }
                return null;
            });
            if (F.workshop != null) F.door = server(() -> ThiefScenario.streetDoor(level(), F.workshop));
            Playtest.log("PREMISES workshop " + (F.workshop == null ? "none" : F.workshop.chunkX() + "," + F.workshop.chunkZ()));
            if (F.workshop == null || F.door == null) Playtest.fail("no workshop with premises to let within sixteen chunks of the middle of Ironhaven");
        });
        ThiefScenario.stopIfFailed("premises to let");
        Playtest.queue("start at the workshop's door (scripted)", () -> { scripted("the player was put at the workshop's street door " + F.door.door().toShortString()); ThiefScenario.standAt(F.door); },
            () -> mc().player.position().distanceTo(F.door.stand()) < 0.8, 200);
        Playtest.wait("settle", 30);
        JourneyScenario.pressUntil("press K for the Crew and Home screen", "k", () -> mc().screen instanceof CrewScreen, 200);
        Playtest.wait("draw", 10);
        JourneyScenario.clickAt("the Home rail", () -> mc().screen instanceof CrewScreen s ? s.centreOf("rail_home") : null);
        Playtest.wait("draw", 8);
        JourneyScenario.clickAt("the Find a home tab", () -> mc().screen instanceof CrewScreen s ? s.centreOf("home_tab_1") : null);
        Playtest.wait("draw", 10);
        final int[] index = {-1};
        Playtest.queue("the premises' listing", () -> {
            if (!(mc().screen instanceof CrewScreen s)) { Playtest.fail("the Home page is not open"); return; }
            var nearby = s.snapshot().nearby();
            for (int i = 0; i < nearby.size() && index[0] < 0; i++) if (nearby.get(i).id().contains("ih_workshop") && nearby.get(i).offer().canRent()) index[0] = i;
            Playtest.log("PREMISES Find a home lists " + nearby.stream().limit(8).map(l -> l.title() + " (" + l.offer().category() + ")").toList());
            if (index[0] < 0) { Playtest.fail("Find a home does not list the workshop's premises"); return; }
            F.premisesId = nearby.get(index[0]).id();
            F.category = nearby.get(index[0]).offer().category();
        });
        ThiefScenario.stopIfFailed("the listing");
        final int[] t = {0};
        Playtest.queue("page to the premises", () -> t[0] = 0, () -> {
            if (!(mc().screen instanceof CrewScreen s)) return true;
            if (s.centreOf("rent_" + index[0]) != null) return true;
            if (t[0]++ % 10 == 0) { int[] next = s.centreOf("find_next"); if (next != null) Playtest.clickGui(next[0], next[1]); }
            return t[0] > 200;
        }, 240);
        JourneyScenario.clickAt("Rent on the premises", () -> mc().screen instanceof CrewScreen s ? s.centreOf("rent_" + index[0]) : null);
        until("rented", () -> server(() -> { var r = CrewService.need(mc().getSingleplayerServer()).residence(F.premisesId); return r != null && me().getUUID().equals(r.owner); }), 200);
        Playtest.queue("the premises are rented", () -> {
            var r = server(() -> CrewService.need(mc().getSingleplayerServer()).residence(F.premisesId));
            Playtest.log("PREMISES " + F.premisesId + " (" + F.category + "): " + (r == null ? "none" : r.tenure + ", charge " + r.charge + ", deposit " + r.deposit) + ", wallet " + JourneyScenario.wallet());
            if (r == null) Playtest.fail("the premises were not rented");
            else pass("2 the workshop's premises rented on the Find a home tab with a real click (" + F.category + "): deposit " + r.deposit + ", " + r.charge + " an hour of play");
        });
        shot("1-premises-rented");
        JourneyScenario.closeScreen("close the Home page");
        StationSteps.enter("the workshop", () -> F.door);
        placeInside(BENCH, UndergroundCity.WORKSHOP_ITEMS.get("machinist_bench").get());
        ThiefScenario.stopIfFailed("the bench inside");
        StationSteps.open(BENCH, "the premises' bonus");
        Playtest.queue("the bench in trade premises", () -> {
            if (!(mc().screen instanceof WorkshopScreen s)) { Playtest.fail("the bench's screen did not open"); return; }
            var v = s.view();
            Playtest.log("PREMISES bench: " + v.tierName() + ", " + v.batch() + " at a time, " + v.speedPercent() + "% speed; note \"" + v.note() + "\"");
            if (v.batch() < 2 || v.speedPercent() < 120) Playtest.fail("the bench inside the premises does not run one more batch a quarter faster");
            else pass("3 the Machinist's Bench set down inside the premises runs " + v.batch() + " batches at a time at " + v.speedPercent() + "% speed (trade premises, D-169)");
        });
        shot("2-premises-bench");
        // a crate's worth of fittings with the second batch
        for (int i = 0; i < 4; i++) twoBatches("fasteners", NS + "fasteners", 16, "4." + (i + 1) + " two batches of fasteners at once");
        for (int i = 0; i < 3; i++) twoBatches("steel_bracket", NS + "steel_bracket", 6, "5." + (i + 1) + " two batches of brackets at once");
        twoBatches("springs", NS + "springs", 8, "6 two batches of springs at once");
        JourneyScenario.closeScreen("close the bench");
        // the workshop's own public terminal by the bay door, inside the premises, and the bulk order only premises may take
        final BlockPos[] terminal = new BlockPos[1];
        Playtest.queue("the workshop's public terminal", () -> {
            terminal[0] = server(() -> StationSteps.terminalIn(F.workshop));
            Playtest.log("PREMISES public terminal " + (terminal[0] == null ? "none" : terminal[0].toShortString()));
            if (terminal[0] == null) Playtest.fail("the workshop has no public terminal");
        });
        ThiefScenario.walkOrTeleport("walk to the terminal", () -> terminal[0] == null ? mc().player.position() : StationSteps.standBeside(terminal[0]), 0.8, 900);
        JourneyScenario.use("open the terminal", () -> terminal[0], () -> mc().screen instanceof TerminalScreen, 400);
        StationSteps.learn("smith.fundamentals", "Forge Fundamentals");
        JourneyScenario.click("Work", true);
        Playtest.wait("draw", 12);
        final int[] w = {0};
        Playtest.queue("turn the wheel until the bulk order is in view", () -> w[0] = 0, () -> {
            if (!(mc().screen instanceof TerminalScreen screen)) return true;
            int i = JourneyScenario.jobIndex(BULK);
            if (i >= 0 && screen.jobCentre(i) != null) return true;
            if (w[0]++ % 6 == 0) Playtest.click(5);
            return w[0] > 180;
        }, 200);
        JourneyScenario.clickAt("the bulk order", () -> { int i = JourneyScenario.jobIndex(BULK); return i < 0 || !(mc().screen instanceof TerminalScreen s) ? null : s.jobCentre(i); });
        shot("3-bulk-order");
        final double[] before = {0};
        Playtest.queue("before the bulk order", () -> before[0] = JourneyScenario.wallet());
        JourneyScenario.click("Accept order", true);
        until("accepted", () -> server(() -> WorkOrderLedger.get(me().server).player(me().getUUID()).hasUUID("order")), 200);
        JourneyScenario.click("Deliver", true);
        until("delivered", () -> server(() -> !WorkOrderLedger.get(me().server).player(me().getUUID()).hasUUID("order")), 200);
        Playtest.queue("paid for the crate", () -> {
            double paid = JourneyScenario.wallet() - before[0];
            Playtest.log("PREMISES bulk order paid " + paid);
            if (paid < 400) Playtest.fail("the bulk order was not delivered and paid");
            else pass("7 the bulk order only trade premises may take, accepted and delivered at a terminal inside them: +" + Math.round(paid) + " credits");
        });
        JourneyScenario.closeScreen("close the terminal");
        StationSteps.reconnect(() -> List.of(JourneyScenario.wallet(), server(() -> { var r = CrewService.need(mc().getSingleplayerServer()).residence(F.premisesId); return r == null ? "none" : r.tenure + " " + me().getUUID().equals(r.owner); }),
            server(() -> level().getBlockState(BENCH.pos).getBlock() instanceof WorkshopBlock)), (b, a) -> {
            if (!b.equals(a)) Playtest.fail("the premises, the bench or the wallet changed across the save and reopen");
            else pass("8 saved, left and reopened: the premises still rented, the bench where it stood, the wallet unchanged");
        });
        Playtest.queue("summary", ThiefScenario::summary);
    }

    /** A station set down with a real right click on free floor inside the rented premises, aimed from two blocks off across a clear cell. */
    private static void placeInside(StationSteps.Station station, net.minecraft.world.item.Item item) {
        final Vec3[] stand = new Vec3[1];
        Playtest.queue("free floor inside the premises for the " + station.id, () -> {
            Object[] found = server(() -> {
                var r = CrewService.need(mc().getSingleplayerServer()).residence(F.premisesId);
                if (r == null) return null;
                var level = level();
                Vec3 me = mc().player.position();
                Object[] best = null;
                double bestD = Double.MAX_VALUE;
                for (BlockPos c : BlockPos.betweenClosed(r.min, r.max)) {
                    if (c.getY() != r.min.getY() || !StationSteps.standable(level, c) || level.getBlockState(c.below()).getBlock() instanceof WorkshopBlock) continue;
                    for (net.minecraft.core.Direction d : net.minecraft.core.Direction.Plane.HORIZONTAL) {
                        BlockPos between = c.relative(d), s2 = c.relative(d, 2);
                        if (!r.insideInterior(level.dimension().location().toString(), s2) || !StationSteps.standable(level, s2) || !level.getBlockState(between).isAir() || !level.getBlockState(between.above()).isAir()) continue;
                        double dist = Vec3.atBottomCenterOf(s2).distanceToSqr(me);
                        if (dist < bestD) { bestD = dist; best = new Object[] {c.immutable(), Vec3.atBottomCenterOf(s2)}; }
                    }
                }
                return best;
            });
            if (found != null) { station.pos = (BlockPos) found[0]; stand[0] = (Vec3) found[1]; }
            Playtest.log("PREMISES the " + station.id + " goes at " + (station.pos == null ? "nowhere" : station.pos.toShortString()));
            if (station.pos == null) Playtest.fail("no free floor inside the premises for the " + station.id);
        });
        ThiefScenario.walkOrTeleport("walk across the premises", () -> stand[0] == null ? mc().player.position() : stand[0], 0.8, 1200);
        StationSteps.hold("the " + station.id, item);
        JourneyScenario.use("set the " + station.id + " down with a right click", () -> station.pos == null ? null : station.pos.below(),
            () -> station.pos != null && server(() -> level().getBlockState(station.pos).getBlock() instanceof WorkshopBlock), 600, true);
        JourneyScenario.closeScreen("close anything the placing click opened");
    }

    /** One run of two batches (the x1 button pressed to x2 first), Start, the time, Take. */
    private static void twoBatches(String recipe, String output, int expect, String passText) {
        JourneyScenario.clickAt("the " + recipe + " row", () -> mc().screen instanceof WorkshopScreen s ? s.rowCentre(recipe) : null);
        Playtest.wait("select", 4);
        final int[] presses = {0};
        Playtest.queue("press x1 until it reads x2", () -> presses[0] = 0, () -> {
            if (!(mc().screen instanceof WorkshopScreen s)) return true;
            if (s.batches() == 2) return true;
            int[] at = s.batchButtonCentre();
            if (at != null && presses[0]++ % 10 == 0) Playtest.clickGui(at[0], at[1]);
            return presses[0] > 60;
        }, 80);
        Playtest.queue("two batches", () -> { if (!(mc().screen instanceof WorkshopScreen s) || s.batches() != 2) Playtest.fail("the batch button does not reach x2"); });
        StationSteps.batch(BENCH, recipe, output, expect, passText);
    }
}
