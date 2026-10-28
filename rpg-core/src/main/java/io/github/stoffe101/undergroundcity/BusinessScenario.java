package io.github.stoffe101.undergroundcity;

import net.minecraft.core.BlockPos;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;

import static io.github.stoffe101.undergroundcity.JourneyScenario.level;
import static io.github.stoffe101.undergroundcity.JourneyScenario.mc;
import static io.github.stoffe101.undergroundcity.JourneyScenario.me;
import static io.github.stoffe101.undergroundcity.JourneyScenario.server;
import static io.github.stoffe101.undergroundcity.JourneyScenario.until;
import static io.github.stoffe101.undergroundcity.ThiefScenario.pass;
import static io.github.stoffe101.undergroundcity.ThiefScenario.scripted;
import static io.github.stoffe101.undergroundcity.ThiefScenario.shot;

/**
 * Player business v1 through a real client ({@code -PucPlaytest=business}, D-208): a Smith rents the Ironhaven workshop's premises on the Home page with real clicks (as the premises run does),
 * walks in, sets a Business Counter down with a real right click, stocks it with a right click holding a dozen bottles of berry wine, opens it with a crouching right click, and waits for the
 * city's own street people to walk in and buy, as long as eight minutes of play. Then an empty-handed right click collects the takings into the wallet. Everything is read back from the server.
 *
 * <p>Scripted, each logged: the clock (13:00, stopped), the operator's prepared start (/uc_prepare), the travel to Ironhaven, the start at the workshop's door, and the counter and the wine
 * given by command (a counter is crafted, the wine made by a Vintner).
 */
final class BusinessScenario {
    private BusinessScenario() {}

    private static volatile BlockPos counter;
    private static volatile double walletBefore;
    private static final int BOTTLES = 12;

    static void build() {
        ThiefScenario.tag = "BUSINESS";
        Playtest.keepScreens = true;
        ThiefScenario.register(2, "the Smith card", "1 a new citizen registers as a Smith through the registration screen");
        PremisesScenario.rentAndEnter();
        ThiefScenario.stopIfFailed("the premises");
        Playtest.queue("a Business Counter and a dozen bottles of berry wine (scripted)", () -> {
            Playtest.command("give @s underground_city:business_counter 1");
            Playtest.command("give @s underground_city:berry_wine " + BOTTLES);
            scripted("a Business Counter and " + BOTTLES + " bottles of berry wine given by command (the counter is crafted; the wine is a Vintner's)");
        }, () -> JourneyScenario.count(UndergroundCity.BUSINESS_COUNTER_ITEM.get()) == 1 && JourneyScenario.count(UndergroundCity.item("berry_wine")) == BOTTLES, 200);
        placeCounter();
        ThiefScenario.stopIfFailed("the counter");
        Playtest.queue("the business is set up", () -> {
            String id = server(() -> PlayerBusiness.businessAt(WorkOrderLedger.get(mc().getSingleplayerServer()), counter));
            Playtest.log("BUSINESS counter at " + counter.toShortString() + " opens business " + id + " (premises " + PremisesScenario.premisesId() + ")");
            if (id == null || !id.equals(PremisesScenario.premisesId())) Playtest.fail("setting the counter down in the rented premises did not open their business");
            else pass("2 a Business Counter set down with a real right click inside the rented premises opens their business");
        });
        StationSteps.hold("the berry wine", UndergroundCity.item("berry_wine"));
        JourneyScenario.use("stock the counter with a right click", () -> counter, () -> stock() == BOTTLES, 400);
        Playtest.queue("the counter holds the wine", () -> {
            Playtest.log("BUSINESS stock " + stock() + ", wine carried " + JourneyScenario.count(UndergroundCity.item("berry_wine")));
            if (stock() != BOTTLES || JourneyScenario.count(UndergroundCity.item("berry_wine")) != 0) Playtest.fail("the wine did not move onto the counter");
            else pass("3 a right click holding " + BOTTLES + " bottles stocks the counter with them");
        });
        Playtest.queue("an empty hand and crouch", () -> {
            Playtest.focusWindow();
            var inv = mc().player.getInventory();
            for (int i = 8; i >= 0; i--) if (inv.getItem(i).isEmpty()) { inv.selected = i; break; }
            Playtest.keyDown("shift");
        });
        Playtest.wait("crouch", 6);
        JourneyScenario.use("open the business with a crouching right click", () -> counter, () -> business().getBoolean("open"), 400);
        Playtest.queue("stand up", () -> Playtest.keyUp("shift"));
        Playtest.queue("open to the city's people", () -> {
            boolean open = server(() -> PlayerBusiness.open(level(), business()));
            Playtest.log("BUSINESS open flag " + business().getBoolean("open") + ", open to customers " + open + (open ? "" : ": " + server(() -> PlayerBusiness.whyShut(level(), business()))));
            if (!open) Playtest.fail("the opened counter is not open to customers");
            else pass("4 a crouching right click opens the business, and it is open to the city's people (stocked, in good standing, at 13:00)");
            walletBefore = JourneyScenario.wallet();
        });
        shot("1-counter-open");
        // the city's people: as long as eight minutes of play
        final int[] t = {0};
        final boolean[] seen = {false};
        Playtest.queue("wait for a customer to walk in and buy", () -> t[0] = 0, () -> {
            t[0]++;
            if (t[0] % 600 == 0) Playtest.log("BUSINESS after " + t[0] / 20 + " s: stock " + stock() + ", visits " + PopulationService.businessVisits + ", purchases " + PopulationService.businessPurchases);
            if (!seen[0] && t[0] % 10 == 0) {
                boolean near = server(() -> !level().getEntitiesOfClass(TownspersonEntity.class, new net.minecraft.world.phys.AABB(counter).inflate(3)).isEmpty());
                if (near) { seen[0] = true; Playtest.log("BUSINESS a street person is at the counter after " + t[0] / 20 + " s"); Playtest.screenshotNextFrame("uc-business-2-customer"); }
            }
            return stock() < BOTTLES;
        }, 9600);
        Playtest.queue("a sale", () -> {
            long takings = business().getLong("takings");
            Playtest.log("BUSINESS after " + t[0] / 20 + " s: stock " + stock() + ", till " + takings + ", sold " + business().getInt("sold") + ", tax " + business().getLong("tax")
                + "; visits " + PopulationService.businessVisits + ", purchases " + PopulationService.businessPurchases);
            if (stock() >= BOTTLES || takings <= 0) Playtest.fail("no customer bought from the counter in eight minutes of play");
            else pass("5 a street person walked in and bought from the counter after " + t[0] / 20 + " s of play; the till holds " + takings + " after the city's sales tax of " + business().getLong("tax"));
        });
        ThiefScenario.stopIfFailed("a customer");
        final long[] till = new long[1];
        Playtest.queue("the till before collecting", () -> till[0] = business().getLong("takings"));
        JourneyScenario.use("collect the takings with an empty-handed right click", () -> counter, () -> business().getLong("takings") == 0, 400);
        Playtest.queue("the takings reached the wallet once", () -> {
            double now = JourneyScenario.wallet();
            Playtest.log("BUSINESS wallet " + walletBefore + " -> " + now + ", till " + till[0] + " -> " + business().getLong("takings") + ", paid in all " + business().getLong("paid_total"));
            if (Math.abs(now - walletBefore - till[0]) > 0.01) Playtest.fail("the wallet did not rise by the till (" + (now - walletBefore) + " against " + till[0] + ")");
            else pass("6 an empty-handed right click pays the till of " + till[0] + " into the wallet, once, and starts the statement again");
        });
        shot("3-collected");
        Playtest.queue("summary", ThiefScenario::summary);
    }

    private static net.minecraft.nbt.CompoundTag business() {
        return server(() -> WorkOrderLedger.get(mc().getSingleplayerServer()).business(PremisesScenario.premisesId()).copy());
    }

    private static int stock() {
        var stock = business().getCompound("stock");
        return stock.getAllKeys().stream().mapToInt(stock::getInt).sum();
    }

    /** The counter set down with a real right click on free floor inside the premises, two blocks in front of the player, so its front (the side that faced them) is the free cell between. */
    private static void placeCounter() {
        final Vec3[] stand = new Vec3[1];
        Playtest.queue("free floor inside the premises for the counter", () -> {
            Object[] found = server(() -> {
                var r = CrewService.need(mc().getSingleplayerServer()).residence(PremisesScenario.premisesId());
                if (r == null) return null;
                var level = level();
                Vec3 me = mc().player.position();
                Object[] best = null;
                double bestD = Double.MAX_VALUE;
                for (BlockPos c : BlockPos.betweenClosed(r.min, r.max)) {
                    if (c.getY() != r.min.getY() || !StationSteps.standable(level, c)) continue;
                    for (net.minecraft.core.Direction d : net.minecraft.core.Direction.Plane.HORIZONTAL) {
                        BlockPos between = c.relative(d), s2 = c.relative(d, 2);
                        if (!r.insideInterior(level.dimension().location().toString(), s2) || !StationSteps.standable(level, s2) || !StationSteps.standable(level, between)) continue;
                        double dist = Vec3.atBottomCenterOf(s2).distanceToSqr(me);
                        if (dist < bestD) { bestD = dist; best = new Object[] {c.immutable(), Vec3.atBottomCenterOf(s2)}; }
                    }
                }
                return best;
            });
            if (found != null) { counter = (BlockPos) found[0]; stand[0] = (Vec3) found[1]; }
            Playtest.log("BUSINESS the counter goes at " + (counter == null ? "nowhere" : counter.toShortString()));
            if (counter == null) Playtest.fail("no free floor inside the premises for the counter");
        });
        ThiefScenario.walkOrTeleport("walk across the premises", () -> stand[0] == null ? mc().player.position() : stand[0], 0.8, 1200);
        StationSteps.hold("the counter", UndergroundCity.BUSINESS_COUNTER_ITEM.get());
        JourneyScenario.use("set the counter down with a right click", () -> counter == null ? null : counter.below(),
            () -> counter != null && server(() -> level().getBlockState(counter).getBlock() instanceof PlayerBusiness.Counter), 600, true);
        JourneyScenario.closeScreen("close anything the placing click opened");
    }
}
