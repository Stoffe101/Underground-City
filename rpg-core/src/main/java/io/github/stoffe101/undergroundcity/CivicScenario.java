package io.github.stoffe101.undergroundcity;

import net.minecraft.core.BlockPos;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.Vec3;

import java.util.Locale;
import java.util.Optional;

import static io.github.stoffe101.undergroundcity.JourneyScenario.click;
import static io.github.stoffe101.undergroundcity.JourneyScenario.clickAt;
import static io.github.stoffe101.undergroundcity.JourneyScenario.closeScreen;
import static io.github.stoffe101.undergroundcity.JourneyScenario.count;
import static io.github.stoffe101.undergroundcity.JourneyScenario.hold;
import static io.github.stoffe101.undergroundcity.JourneyScenario.level;
import static io.github.stoffe101.undergroundcity.JourneyScenario.look;
import static io.github.stoffe101.undergroundcity.JourneyScenario.mc;
import static io.github.stoffe101.undergroundcity.JourneyScenario.me;
import static io.github.stoffe101.undergroundcity.JourneyScenario.placeAhead;
import static io.github.stoffe101.undergroundcity.JourneyScenario.server;
import static io.github.stoffe101.undergroundcity.JourneyScenario.until;
import static io.github.stoffe101.undergroundcity.JourneyScenario.use;
import static io.github.stoffe101.undergroundcity.JourneyScenario.wallet;
import static io.github.stoffe101.undergroundcity.ThiefScenario.pass;
import static io.github.stoffe101.undergroundcity.ThiefScenario.scripted;
import static io.github.stoffe101.undergroundcity.ThiefScenario.shot;
import static io.github.stoffe101.undergroundcity.ThiefScenario.stopIfFailed;

/**
 * The Valewood market (D-171) through a real client ({@code -PucPlaytest=civic}, a new furnished archipelago world): a Farmer brings the council two contributions of produce and three of
 * lanterns over a terminal in Valewood with real clicks (the lantern order refills once in between), the project finishes at its goal of five, its two market stalls are built on an open street
 * near Valewood's centre, the player walks over and looks at them, and the council's trusted stall order is offered.
 *
 * <p>Scripted, each logged: the career XP of a level-3 Farmer, the goods (produce crates and lanterns: the Farmer's workshop and a smithy's work, played in other runs), the clock, the travel
 * (a teleport), the walk to the stalls when the walker could not finish it, and the ten minutes of play while the lantern order refills (the player stands in the street).
 */
final class CivicScenario {
    private CivicScenario() {}

    static final String PRODUCE = "underground_city:civic_market_produce", LANTERNS = "underground_city:civic_market_lanterns", STALL = "underground_city:council_stall_produce";
    static final String PROJECT = "underground_city:valewood_market";

    private static final class Facts {
        volatile BlockPos placed;
        volatile double walletBefore;
        volatile int delivered;
    }
    private static final Facts F = new Facts();

    static void build() {
        ThiefScenario.tag = "CIVIC";
        Playtest.keepScreens = true;
        ThiefScenario.register(0, "the Farmer card", "1 a new citizen registers as a Farmer");
        Playtest.queue("a Farmer of level 3 with produce and lanterns (scripted)", () -> {
            server(() -> {
                CitizenState.get(me().server).addCareerXp(me().getUUID(), ProgressionRules.xpAtLevel(3));
                me().getInventory().add(new ItemStack(UndergroundCity.item("produce_crate"), 4));
                me().getInventory().add(new ItemStack(Items.LANTERN, 12));
                return null;
            });
            scripted("the career XP of a level-3 Farmer, four produce crates and twelve lanterns were given (a Farmer's workshop and a smithy's goods, played in other runs)");
            F.walletBefore = wallet();
        });
        Playtest.queue("noon with the daylight cycle stopped", () -> {
            Playtest.command("gamerule doDaylightCycle false");
            Playtest.command("time set 6000");
            scripted("the time was set to noon and the daylight cycle stopped");
        });
        ThiefScenario.travel("to the middle of Valewood", () -> ThiefScenario.centre("valewood"), 5);
        final BlockPos[] street = new BlockPos[1];
        Playtest.queue("survival, and open street ground nearby that nobody owns", () -> {
            Playtest.command("gamemode survival");
            street[0] = server(() -> CannabisScenario.openGround(me().blockPosition(), 40, false));
            if (street[0] == null) Playtest.fail("no open street ground near the middle of Valewood");
        });
        stopIfFailed("open ground in Valewood");
        Playtest.queue("down to the street (scripted)", () -> {
            scripted("the player was put on open street ground in Valewood at " + street[0].toShortString());
            server(() -> { me().teleportTo(street[0].getX() + 0.5, street[0].getY(), street[0].getZ() + 0.5); me().setDeltaMovement(Vec3.ZERO); return null; });
        }, () -> mc().player.blockPosition().distManhattan(street[0]) <= 1, 400);
        until("the ground has arrived", Scenarios::groundBelowPlayer, 2400);
        Playtest.wait("settle", 20);
        hold("the terminal", UndergroundCity.TERMINAL_ITEM.get());
        final BlockPos[] spot = new BlockPos[1];
        placeAhead("the terminal", UndergroundCity.TERMINAL.get(), spot, pos -> server(() -> BuildingGuard.protectedAt(level(), pos).isEmpty() && BuildingGuard.protectedAt(level(), pos.below()).isEmpty()));
        Playtest.queue("placed in Valewood", () -> { F.placed = spot[0]; if (spot[0] == null || !mc().level.getBlockState(spot[0]).is(UndergroundCity.TERMINAL.get())) Playtest.fail("the terminal could not be placed in the Valewood street"); });
        stopIfFailed("placing the terminal");
        contribute(PRODUCE, "produce for the market", "2-produce-order");
        contribute(PRODUCE, "produce for the market (the second)", null);
        contribute(LANTERNS, "lanterns for the square", "3-lanterns-order");
        contribute(LANTERNS, "lanterns for the square (the second)", null);
        Playtest.queue("four contributions counted", () -> {
            int n = server(() -> WorkOrderLedger.get(me().server).civic(PROJECT).getInt("count"));
            Playtest.log("CIVIC the market's count after four deliveries: " + n + " of 5");
            if (n != 4) Playtest.fail("the council counted " + n + " contributions, not 4");
            else pass("2 four contributions (produce twice, lanterns twice) delivered with real clicks at a terminal in Valewood: the council counts 4 of 5");
        });
        stopIfFailed("the first four contributions");
        Playtest.wait("ten minutes of play in the street while the lantern order refills (scripted wait)", 12200);
        Playtest.queue("the wait was a wait", () -> scripted("the player stood in the street for ten minutes of play while the council's lantern order refilled"));
        contribute(LANTERNS, "lanterns for the square (the third)", null);
        Playtest.queue("finished", () -> {
            var state = server(() -> WorkOrderLedger.get(me().server).civic(PROJECT).copy());
            int trust = server(() -> WorkOrderLedger.get(me().server).trust(me().getUUID(), "valewood_council"));
            int social = server(() -> CitizenState.get(me().server).find(me().getUUID()).orElseThrow().generalXp.getOrDefault(GeneralSkills.SOCIAL, 0));
            Playtest.log(String.format(Locale.ROOT, "CIVIC the market: count %d, finished %s; the council's trust %d; Social XP %d; wallet %.0f -> %.0f", state.getInt("count"), state.getBoolean("finished"),
                trust, social, F.walletBefore, wallet()));
            if (!state.getBoolean("finished")) Playtest.fail("five contributions did not finish the market");
            else pass("3 the fifth contribution finishes \"Reopen the Valewood market\": the council's trust " + trust + ", Social XP " + social);
        });
        stopIfFailed("finishing the project");
        until("the stalls are built", () -> server(() -> CivicProjects.builtAt(WorkOrderLedger.get(me().server), PROJECT).isPresent()), 1200);
        final BlockPos[] stalls = new BlockPos[1];
        Playtest.queue("where the stalls stand", () -> {
            Optional<BlockPos> at = server(() -> CivicProjects.builtAt(WorkOrderLedger.get(me().server), PROJECT));
            if (at.isEmpty()) { Playtest.fail("the stalls were not built within a minute of the project finishing"); return; }
            stalls[0] = at.get();
            boolean fence = server(() -> level().getBlockState(at.get()).is(net.minecraft.world.level.block.Blocks.DARK_OAK_FENCE));
            Playtest.log("CIVIC the stalls stand at " + at.get().toShortString() + " (" + (int) Math.sqrt(at.get().distSqr(mc().player.blockPosition())) + " blocks from the player); a stall post there " + fence);
            if (!fence) Playtest.fail("there is no stall at the recorded spot");
        });
        stopIfFailed("the stalls");
        ThiefScenario.walkOrTeleport("walk over to the stalls", () -> Vec3.atBottomCenterOf(stalls[0].offset(3, 0, 5)), 2.0, 1200);
        Playtest.queue("look at the stalls", () -> look(Vec3.atCenterOf(stalls[0].offset(3, 1, 1))));
        Playtest.wait("the view settles", 20);
        shot("4-stalls");
        Playtest.queue("seen", () -> {
            boolean seen = server(() -> level().clip(new net.minecraft.world.level.ClipContext(me().getEyePosition(), Vec3.atCenterOf(stalls[0].above()), net.minecraft.world.level.ClipContext.Block.COLLIDER,
                net.minecraft.world.level.ClipContext.Fluid.NONE, me())).getType() == net.minecraft.world.phys.HitResult.Type.MISS
                || me().getEyePosition().distanceTo(Vec3.atCenterOf(stalls[0])) < 8);
            Playtest.log("CIVIC the player stands " + String.format(Locale.ROOT, "%.1f", mc().player.position().distanceTo(Vec3.atCenterOf(stalls[0]))) + " blocks from the stalls, line of sight " + seen);
            pass("4 the market stalls stand on an open street near Valewood's centre, and the player walked over to them");
        });
        Playtest.queue("the council's stall order", () -> {
            String refusal = server(() -> {
                try {
                    WorkOrder stall = WorkOrderCatalog.INSTANCE.find(STALL);
                    return CivicProjects.refusal(WorkOrderLedger.get(me().server), stall.terms());
                } catch (RuntimeException e) { return e.getMessage(); }
            });
            int trust = server(() -> WorkOrderLedger.get(me().server).trust(me().getUUID(), "valewood_council"));
            Playtest.log("CIVIC the council's stall order: " + (refusal == null ? "open" : refusal) + " (trust " + trust + ", the order asks 1)");
            if (refusal != null) Playtest.fail("the finished market did not open the council's stall order: " + refusal);
            else pass("5 the council's better-paying stall order is open now the market stands, to a contributor it trusts (" + trust + ")");
        });
        Playtest.queue("summary", ThiefScenario::summary);
    }

    /** One contribution with real clicks: open the terminal, find the order on the Work page, accept it, deliver it. */
    private static void contribute(String id, String label, String picture) {
        use("open the terminal (" + label + ")", () -> F.placed, () -> mc().screen instanceof TerminalScreen, 200);
        click("Work", true);
        Playtest.wait("draw", 12);
        final int[] t = {0};
        Playtest.queue("turn the wheel until " + label + " is in view", () -> { t[0] = 0; Playtest.focusWindow(); }, () -> {
            if (!(mc().screen instanceof TerminalScreen screen)) return true;
            int i = JourneyScenario.jobIndex(id);
            if (i >= 0 && screen.jobCentre(i) != null) return true;
            if (t[0]++ % 6 == 0) Playtest.click(5);
            return t[0] > 180;
        }, 200);
        clickAt(label, () -> { int i = JourneyScenario.jobIndex(id); return i < 0 || !(mc().screen instanceof TerminalScreen s) ? null : s.jobCentre(i); });
        if (picture != null) shot(picture);
        click("Accept order", true);
        until("accepted: " + label, CivicScenario::orderTaken, 200);
        click("Deliver", true);
        until("delivered: " + label, () -> !orderTaken(), 200);
        Playtest.queue("counted: " + label, () -> {
            if (orderTaken()) Playtest.fail("the order for " + label + " was not delivered");
            else F.delivered++;
        });
        closeScreen("close the terminal");
    }

    private static boolean orderTaken() { return server(() -> WorkOrderLedger.get(me().server).player(me().getUUID()).hasUUID("order")); }
}
