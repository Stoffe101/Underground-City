package io.github.stoffe101.undergroundcity;

import net.minecraft.client.gui.screens.GenericDirtMessageScreen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
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
 * An exploration find (D-186) through a real client ({@code -PucPlaytest=finds}, a new furnished archipelago world): a new citizen goes to the Northgate depot beside the Ironhaven to
 * Blackwater road, which is built as they come near, and the battered crate Core sets on its lot; a real right click on it hands over what was rolled for them, once, unmarked; a second
 * click finds nothing more; after a save, a quit and a reopen the crate still knows they have been through it.
 *
 * <p>Scripted, each logged: the clock (13:00, stopped), the travel to the road at the depot (a teleport; the roads are walked in the journey runs) and any walk the walker could not finish.
 */
final class FindScenario {
    private FindScenario() {}

    private static final String SITE = "place:northgate_depot";
    private static final class Facts {
        volatile BlockPos crate;
        volatile List<ItemStack> rolled = List.of();
        volatile int carriedBefore, carriedAfter;
    }
    private static final Facts F = new Facts();

    static void build() {
        ThiefScenario.tag = "FINDS";
        Playtest.keepScreens = true;
        ThiefScenario.register(0, "the Farmer card", "1 a new citizen (a Farmer) registers");
        Playtest.queue("the clock: 13:00 with the daylight cycle stopped", () -> {
            Playtest.command("gamerule doDaylightCycle false");
            Playtest.command("time set 7000");
            scripted("the time of day was set to 13:00 and the daylight cycle stopped");
        });
        until("the roads are planned (in the background as the world loaded)", () -> server(() -> !CorridorRoadFeature.ready(level()).isEmpty()), 2400);
        ThiefScenario.travel("to the road at the Northgate depot", FindScenario::roadAtDepot, 4);
        Playtest.queue("survival", () -> Playtest.command("gamemode survival"));
        until("the client has the ground under the player", Scenarios::groundBelowPlayer, 1200);
        until("the Northgate depot has been built as the traveller came near", () -> server(() -> PlacedSites.get(level(), RoadsidePlaces.KEY).done.contains("northgate_depot")), 3600);
        until("the depot's crate has been set on its lot", () -> server(() -> ExplorationFinds.record(level().getServer()).finds.containsKey(SITE)), 1200);
        Playtest.queue("the crate", () -> {
            ExplorationFinds.Find find = server(() -> ExplorationFinds.record(level().getServer()).finds.get(SITE));
            if (find == null) { Playtest.fail("no crate was set at the Northgate depot"); return; }
            F.crate = find.pos();
            F.rolled = server(() -> ExplorationFinds.roll(level().getSeed(), find, me().getUUID()));
            Playtest.log("FINDS the depot's crate (" + find.kind() + ") at " + F.crate.toShortString() + "; rolled for this player: " + describe(F.rolled));
            pass("2 the Northgate depot is built as the traveller comes near, and a battered crate stands on its lot");
        });
        ThiefScenario.stopIfFailed("the crate");
        ThiefScenario.walkOrTeleport("walk to the crate", FindScenario::besideCrate, 1.2, 1600);
        shot("1-crate");
        Playtest.queue("what is carried before", () -> F.carriedBefore = carried());
        JourneyScenario.use("go through the crate (a real right click)", () -> F.crate, () -> carried() > F.carriedBefore, 400);
        Playtest.wait("the notice", 20);
        shot("2-find-notice");
        Playtest.queue("the find", () -> {
            F.carriedAfter = carried();
            int expected = 0;
            for (ItemStack s : F.rolled) expected += s.getCount();
            boolean stolen = server(() -> { for (ItemStack s : me().getInventory().items) if (!s.isEmpty() && StolenGoods.is(s)) return true; return false; });
            boolean recorded = server(() -> ExplorationFinds.record(level().getServer()).found.getOrDefault(me().getUUID(), java.util.Set.of()).contains(SITE));
            int got = F.carriedAfter - F.carriedBefore;
            if (got != expected || stolen || !recorded) Playtest.fail("the crate handed over " + got + " items (" + expected + " rolled), marked stolen " + stolen + ", recorded " + recorded);
            else pass("3 a real right click hands over what was rolled for this player (" + describe(F.rolled) + "), unmarked, and the record says they have been through it");
        });
        clickAgain("4 a second right click finds nothing more (the crate says they have been through it)");
        reopen();
        clickAgain("6 after the reopen a right click still finds nothing more");
        Playtest.queue("summary", ThiefScenario::summary);
    }

    /** A right click on the crate that must hand over nothing: the inventory is the same after it. */
    private static void clickAgain(String step) {
        final int[] before = {0};
        final long[] opened = {0};
        final int[] revisits = {0};
        Playtest.queue("what is carried before clicking again", () -> {
            before[0] = carried();
            opened[0] = server(() -> (long) ExplorationFinds.record(level().getServer()).opened);
            revisits[0] = ExplorationFinds.revisits;
        });
        // done when the server has answered the click as a visit to a crate already gone through
        JourneyScenario.use("the crate again (a real right click)", () -> F.crate, () -> ExplorationFinds.revisits > revisits[0], 300);
        Playtest.wait("settle", 20);
        Playtest.queue("nothing more", () -> {
            long now = server(() -> (long) ExplorationFinds.record(level().getServer()).opened);
            if (carried() != before[0] || now != opened[0]) Playtest.fail("a second visit found more: carried " + before[0] + " -> " + carried() + ", crates opened " + opened[0] + " -> " + now);
            else pass(step);
        });
    }

    private static void reopen() {
        Playtest.queue("save and quit to the title screen, then open the world again", () -> {
            var mc = mc();
            String folder = System.getProperty("uc.playtest.world", "UC-Playtest");
            Playtest.log("FINDS leaving world " + folder + " carrying " + carried() + " items");
            mc.level.disconnect();
            mc.clearLevel(new GenericDirtMessageScreen(Component.translatable("menu.savingLevel")));
            mc.setScreen(new TitleScreen());
            mc.createWorldOpenFlows().loadLevel(mc.screen, folder);
        }, () -> mc().player != null && mc().level != null && Scenarios.groundBelowPlayer(), 6000);
        Playtest.wait("settle", 60);
        Playtest.queue("the record after the reopen", () -> {
            boolean recorded = server(() -> ExplorationFinds.record(level().getServer()).found.getOrDefault(me().getUUID(), java.util.Set.of()).contains(SITE)
                && ExplorationFinds.at(level().getServer(), F.crate).isPresent());
            if (!recorded || carried() != F.carriedAfter) Playtest.fail("after the reopen the crate's record is " + recorded + " and the player carries " + carried() + " (" + F.carriedAfter + " before)");
            else pass("5 saved, left and reopened: the find is still the player's and the crate still knows they have been through it");
        });
    }

    /** Everything the player carries, counted. */
    private static int carried() { return server(() -> { int n = 0; for (ItemStack s : me().getInventory().items) n += s.getCount(); return n; }); }

    private static String describe(List<ItemStack> stacks) {
        List<String> out = new ArrayList<>();
        for (ItemStack s : stacks) out.add(s.getCount() + " " + net.minecraftforge.registries.ForgeRegistries.ITEMS.getKey(s.getItem()));
        return out.isEmpty() ? "nothing" : String.join(", ", out);
    }

    /** A cell beside the crate a person can stand in, the one nearest the player. */
    private static Vec3 besideCrate() {
        return server(() -> {
            if (F.crate == null) return null;
            Vec3 best = null;
            for (Direction d : Direction.Plane.HORIZONTAL) {
                BlockPos c = F.crate.relative(d);
                if (!StaffZones.walkable(level(), c)) continue;
                Vec3 v = Vec3.atBottomCenterOf(c);
                if (best == null || v.distanceToSqr(me().position()) < best.distanceToSqr(me().position())) best = v;
            }
            return best == null ? Vec3.atBottomCenterOf(F.crate.above()) : best;
        });
    }

    /** The road's deck at the depot's share of the Ironhaven-Blackwater road. */
    private static Vec3 roadAtDepot() {
        return server(() -> {
            RoadsidePlaces.Place depot = RoadsidePlaces.CATALOG.find("northgate_depot");
            for (CorridorPlan plan : CorridorRoadFeature.ready(level())) {
                if (depot == null || !plan.id().equals(depot.road()) || !plan.built()) continue;
                double s = depot.at() * plan.length();
                double[] p = plan.pointAt(s);
                return new Vec3(p[0], CorridorPlan.deckBlockY(plan.deckHalfAt(s)) + 1, p[1]);
            }
            return null;
        });
    }
}
