package io.github.stoffe101.undergroundcity;

import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.phys.Vec3;

import java.util.Locale;

import static io.github.stoffe101.undergroundcity.JourneyScenario.level;
import static io.github.stoffe101.undergroundcity.JourneyScenario.mc;
import static io.github.stoffe101.undergroundcity.JourneyScenario.me;
import static io.github.stoffe101.undergroundcity.JourneyScenario.server;
import static io.github.stoffe101.undergroundcity.JourneyScenario.until;
import static io.github.stoffe101.undergroundcity.ThiefScenario.pass;
import static io.github.stoffe101.undergroundcity.ThiefScenario.scripted;
import static io.github.stoffe101.undergroundcity.ThiefScenario.shot;
import static io.github.stoffe101.undergroundcity.ThiefScenario.stopIfFailed;

/**
 * Freight from the Northgate depot (D-156) through a real client ({@code -PucPlaytest=freight}, a new furnished archipelago world): a new citizen goes to the depot on the Ironhaven-Blackwater road,
 * which is built as they arrive, walks to the dispatcher, asks for freight in the real conversation, is handed a manifest for another city, is told to deliver that one first when they ask again,
 * and takes it to that city, where opening a terminal pays its fee once.
 *
 * <p>Scripted rather than played, each logged as SCRIPTED: the clock (13:00), the travel (teleports to the road at the depot and to the destination city; the roads were walked in the journey runs)
 * and standing on open ground in the city. The depot, the dispatcher, the conversation and the payment are the game's.
 */
final class FreightScenario {
    private FreightScenario() {}

    private static final class Facts {
        volatile String city = "";
        volatile int fee;
        volatile double walletBefore;
        volatile BlockPos placed;
    }

    private static final Facts F = new Facts();

    static void build() {
        ThiefScenario.tag = "FREIGHT";
        Playtest.keepScreens = true;
        ThiefScenario.register(0, "the Farmer card", "1 a new citizen (a Farmer) registers");
        Playtest.queue("the clock: 13:00 with the daylight cycle stopped", () -> {
            Playtest.command("gamerule doDaylightCycle false");
            Playtest.command("time set 7000");
            scripted("the time of day was set to 13:00 and the daylight cycle stopped");
        });
        until("the roads are planned (in the background as the world loaded)", () -> server(() -> !CorridorRoadFeature.ready(level()).isEmpty()), 2400);
        ThiefScenario.travel("to the road at the Northgate depot", FreightScenario::roadAtDepot, 4);
        Playtest.queue("survival", () -> Playtest.command("gamemode survival"));
        until("the client has the ground under the player", Scenarios::groundBelowPlayer, 1200);
        until("the Northgate depot has been built as the traveller came near", () -> server(() -> PlacedSites.get(level(), RoadsidePlaces.KEY).done.contains("northgate_depot")), 3600);
        Playtest.queue("the depot", () -> {
            Long where = server(() -> PlacedSites.get(level(), RoadsidePlaces.KEY).where.get("northgate_depot"));
            Playtest.log("FREIGHT the Northgate depot " + (where == null ? "was NOT built" : "stands at " + BlockPos.of(where).toShortString()));
            if (where == null) Playtest.fail("the Northgate depot was not built beside the road");
        });
        stopIfFailed("the depot");
        ask();
        stopIfFailed("the manifest");
        deliver();
        Playtest.queue("summary", ThiefScenario::summary);
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

    /** The depot's dispatcher, once the depot stands. */
    private static Villager dispatcher() { return server(FreightScenario::dispatcherHere); }

    /** The same, on the server thread (a server read from inside one waits on itself: run 4). */
    private static Villager dispatcherHere() {
        Long where = PlacedSites.get(level(), RoadsidePlaces.KEY).where.get("northgate_depot");
        if (where == null) return null;
        for (var v : level().getEntitiesOfClass(Villager.class, new net.minecraft.world.phys.AABB(BlockPos.of(where)).inflate(48)))
            if (CityNpcs.definition(v).map(n -> n.shop().equals(Freight.DEPOT_SHOP)).orElse(false)) return v;
        return null;
    }

    private static String carried() { return server(() -> Freight.carrying(me())); }

    /** A cell within three blocks of the dispatcher, on the floor, with nothing between the eye and her face: the nearest one to the office door's side. */
    private static Vec3 frontOfDesk() {
        return server(() -> {
            Villager v = dispatcherHere();
            if (v == null) return null;
            var level = level();
            BlockPos at = v.blockPosition();
            Vec3 face = v.getEyePosition();
            BlockPos best = null;
            double bestDistance = Double.MAX_VALUE;
            for (BlockPos cell : BlockPos.betweenClosed(at.offset(-3, -1, -3), at.offset(3, 1, 3))) {
                if (cell.equals(at) || !PlayerPath.standable(level, cell)) continue;
                Vec3 eye = Vec3.atBottomCenterOf(cell).add(0, 1.62, 0);
                double distance = eye.distanceTo(face);
                if (distance < 1.5 || distance > 3.5) continue;
                var hit = level.clip(new net.minecraft.world.level.ClipContext(eye, face, net.minecraft.world.level.ClipContext.Block.COLLIDER, net.minecraft.world.level.ClipContext.Fluid.NONE, null));
                if (hit.getType() == net.minecraft.world.phys.HitResult.Type.BLOCK) continue;
                if (distance < bestDistance) { bestDistance = distance; best = cell.immutable(); }
            }
            return best == null ? null : Vec3.atBottomCenterOf(best);
        });
    }

    /** Walks to the dispatcher, asks for freight in the conversation, and asks again. */
    private static void ask() {
        // her desk is in the depot's office, through its door: the path walker stops outside the glass (run 2), so the last steps may be a teleport, logged
        final Vec3[] desk = new Vec3[1];
        Playtest.queue("a place in front of the dispatcher's desk", () -> {   // worked out once: the walker asks for its target every tick, and a stalled server timed the asking out (run 3)
            desk[0] = frontOfDesk();
            Playtest.log("FREIGHT in front of the desk: " + desk[0]);
            if (desk[0] == null) Playtest.fail("no place with a clear view of the dispatcher");
        });
        stopIfFailed("a place by the desk");
        ThiefScenario.walkOrTeleport("walk to the dispatcher's desk", () -> desk[0], 0.8, 1200);
        JourneyScenario.speakTo("the depot's dispatcher", FreightScenario::dispatcher);
        shot("1-dispatcher");
        JourneyScenario.choose("Any freight going out?", "dialogue");
        shot("2-manifest");
        Playtest.queue("a manifest", () -> {
            String said = mc().screen instanceof DialogueScreen d ? d.text() : "";
            F.city = carried() == null ? "" : carried();
            F.fee = server(() -> {
                for (int i = 0; i < me().getInventory().getContainerSize(); i++) {
                    var tag = me().getInventory().getItem(i).getTagElement("uc_letter");
                    if (tag != null && Freight.KIND.equals(tag.getString("kind"))) return tag.getInt("fee");
                }
                return 0;
            });
            Playtest.log("FREIGHT the dispatcher said: " + said + "; carrying a manifest for '" + F.city + "' worth " + F.fee);
            if (F.city.isEmpty()) Playtest.fail("the dispatcher handed over no manifest: " + said);
            else if (!said.contains(TerminalScreenText.capitalize(F.city)) || !said.contains(F.fee + " credits")) Playtest.fail("the dispatcher's words do not name the city and the fee: " + said);
            else pass("2 ask the depot's dispatcher for freight in the conversation: a manifest for " + TerminalScreenText.capitalize(F.city) + ", " + F.fee + " credits on delivery");
        });
        JourneyScenario.choose("Any freight going out?", "dialogue");
        Playtest.queue("one at a time", () -> {
            String said = mc().screen instanceof DialogueScreen d ? d.text() : "";
            int manifests = server(() -> {
                int n = 0;
                for (int i = 0; i < me().getInventory().getContainerSize(); i++) {
                    var tag = me().getInventory().getItem(i).getTagElement("uc_letter");
                    if (tag != null && Freight.KIND.equals(tag.getString("kind"))) n++;
                }
                return n;
            });
            Playtest.log("FREIGHT asked again: " + said + "; manifests carried " + manifests);
            if (manifests != 1 || !said.contains("Deliver that one first")) Playtest.fail("a second manifest, or no word why not: " + said + " (" + manifests + " carried)");
            else pass("3 asking again while carrying it: \"" + said + "\"");
        });
        JourneyScenario.choose("Goodbye", null);
    }

    /** Takes the manifest to its city: a terminal opened there pays its fee once. */
    private static void deliver() {
        ThiefScenario.travel("to the middle of the manifest's city", () -> ThiefScenario.centre(F.city), 4);
        final BlockPos[] street = new BlockPos[1];
        Playtest.queue("survival, and open street ground nearby that nobody owns", () -> {
            Playtest.command("gamemode survival");
            street[0] = server(() -> CannabisScenario.openGround(me().blockPosition(), 40, false));
            if (street[0] == null) Playtest.fail("no open street ground near the middle of " + F.city);
        });
        stopIfFailed("open ground in the city");
        Playtest.queue("down to the street (scripted)", () -> {
            scripted("the player was put on open street ground in " + TerminalScreenText.capitalize(F.city) + " at " + street[0].toShortString());
            server(() -> { me().teleportTo(street[0].getX() + 0.5, street[0].getY(), street[0].getZ() + 0.5); me().setDeltaMovement(Vec3.ZERO); return null; });
        }, () -> mc().player.blockPosition().distManhattan(street[0]) <= 1, 400);
        until("the ground has arrived", Scenarios::groundBelowPlayer, 2400);
        Playtest.wait("settle", 20);
        Playtest.queue("the wallet before", () -> F.walletBefore = JourneyScenario.wallet());
        JourneyScenario.hold("the welcome-pack terminal", UndergroundCity.TERMINAL_ITEM.get());
        final BlockPos[] spot = new BlockPos[1];
        JourneyScenario.placeAhead("the terminal", UndergroundCity.TERMINAL.get(), spot, pos -> server(() -> BuildingGuard.protectedAt(level(), pos).isEmpty() && BuildingGuard.protectedAt(level(), pos.below()).isEmpty()));
        Playtest.queue("placed", () -> { F.placed = spot[0]; if (spot[0] == null || !mc().level.getBlockState(spot[0]).is(UndergroundCity.TERMINAL.get())) Playtest.fail("the terminal could not be placed in the street"); });
        stopIfFailed("placing the terminal");
        JourneyScenario.use("open the terminal", () -> F.placed, () -> mc().screen instanceof TerminalScreen, 200);
        Playtest.wait("the server answers", 20);
        shot("3-delivered");
        Playtest.queue("delivered", () -> {
            double after = JourneyScenario.wallet();
            String left = carried();
            Playtest.log(String.format(Locale.ROOT, "FREIGHT delivery in %s: wallet %.0f -> %.0f (fee %d); manifest still carried: %s", F.city, F.walletBefore, after, F.fee, left));
            if (left != null) Playtest.fail("the manifest was not taken at a terminal in " + F.city);
            else if (Math.round(after - F.walletBefore) != F.fee) Playtest.fail("the delivery paid " + Math.round(after - F.walletBefore) + " credits, not " + F.fee);
            else pass("4 open a terminal in " + TerminalScreenText.capitalize(F.city) + ": the manifest is taken and pays " + F.fee + " credits");
        });
        JourneyScenario.closeScreen("close the terminal");
    }
}
