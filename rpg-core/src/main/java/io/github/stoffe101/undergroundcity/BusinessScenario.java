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
            else pass("3 a Business Counter set down with a real right click inside the rented premises opens their business");
        });
        StationSteps.hold("the berry wine", UndergroundCity.item("berry_wine"));
        JourneyScenario.use("stock the counter with a right click", () -> counter, () -> stock() == BOTTLES, 400);
        Playtest.queue("the counter holds the wine", () -> {
            Playtest.log("BUSINESS stock " + stock() + ", wine carried " + JourneyScenario.count(UndergroundCity.item("berry_wine")));
            if (stock() != BOTTLES || JourneyScenario.count(UndergroundCity.item("berry_wine")) != 0) Playtest.fail("the wine did not move onto the counter");
            else pass("4 a right click holding " + BOTTLES + " bottles stocks the counter with them");
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
            // read the business first: a server read inside another blocks the server thread on itself until the read times out (business runs 1 and 2)
            var b = business();
            boolean open = server(() -> PlayerBusiness.open(level(), b));
            Playtest.log("BUSINESS open flag " + b.getBoolean("open") + ", open to customers " + open + (open ? "" : ": " + server(() -> PlayerBusiness.whyShut(level(), b))));
            if (!open) Playtest.fail("the opened counter is not open to customers");
            else pass("5 a crouching right click opens the business, and it is open to the city's people (stocked, in good standing, at 13:00)");
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
            else pass("6 a street person walked in and bought from the counter after " + t[0] / 20 + " s of play; the till holds " + takings + " after the city's sales tax of " + business().getLong("tax"));
        });
        ThiefScenario.stopIfFailed("a customer");
        final long[] till = new long[1];
        Playtest.queue("the till before collecting", () -> till[0] = business().getLong("takings"));
        JourneyScenario.use("collect the takings with an empty-handed right click", () -> counter, () -> business().getLong("takings") == 0, 400);
        Playtest.queue("the takings reached the wallet once", () -> {
            double now = JourneyScenario.wallet();
            Playtest.log("BUSINESS wallet " + walletBefore + " -> " + now + ", till " + till[0] + " -> " + business().getLong("takings") + ", paid in all " + business().getLong("paid_total"));
            if (Math.abs(now - walletBefore - till[0]) > 0.01) Playtest.fail("the wallet did not rise by the till (" + (now - walletBefore) + " against " + till[0] + ")");
            else pass("7 an empty-handed right click pays the till of " + till[0] + " into the wallet, once, and starts the statement again");
        });
        shot("3-collected");
        growth();
        reopen();
        Playtest.queue("summary", ThiefScenario::summary);
    }

    /**
     * The counter's growth (twelfth pass, D-212), through the owner's own command, which is the business's interface (the statement names it): a price below the city's, an upgrade refused
     * below its career level with the parts carried, then fitted from them, more stock than tier 1 has room for, and a customer who pays the lower price.
     */
    private static void growth() {
        final int[] mark = new int[1];
        final long[] city = new long[1];
        final long[] at = new long[2];
        Playtest.queue("8: the owner types /uc business price 90", () -> { mark[0] = Playtest.heardCount(); Playtest.command("uc business price 90"); },
            () -> business().getInt("price_pct") == 90, 200);
        Playtest.queue("the price took", () -> {
            var b = business();
            String wine = wineKey(b);
            city[0] = server(() -> PlayerBusiness.price(wine));
            long now = server(() -> PlayerBusiness.price(wine, b));
            at[0] = b.getInt("sold"); at[1] = b.getLong("gross");
            String said = Playtest.heardSince(mark[0], "Prices at");
            Playtest.log("BUSINESS price " + b.getInt("price_pct") + "%: wine at " + now + " against the city's " + city[0] + ", " + PlayerBusiness.salesPerHour(b) + " sales an hour; heard '" + said + "'");
            if (b.getInt("price_pct") != 90 || now >= city[0] || said == null) Playtest.fail("/uc business price 90 did not lower the counter's price");
            else pass("8 the owner's own command /uc business price 90 puts the wine at " + now + " against the city's " + city[0] + ", for at most " + PlayerBusiness.salesPerHour(b) + " sales an hour (8 at the city's price)");
        });
        Playtest.queue("tier 2's parts (scripted)", () -> {
            Playtest.command("give @s underground_city:machined_parts 4");
            Playtest.command("give @s underground_city:refined_alloy 2");
            scripted("four machined parts and two refined alloy given by command (Ironhaven's exchange sells them; a Smith also makes machined parts)");
        }, () -> JourneyScenario.count(UndergroundCity.item("machined_parts")) == 4 && JourneyScenario.count(UndergroundCity.item("refined_alloy")) == 2, 200);
        Playtest.queue("9: /uc business upgrade at level 5", () -> { mark[0] = Playtest.heardCount(); Playtest.command("uc business upgrade"); },
            () -> Playtest.heardSince(mark[0], "Tier 2") != null || Playtest.heardSince(mark[0], "Fitted") != null, 200);
        Playtest.queue("refused below the level", () -> {
            String said = Playtest.heardSince(mark[0], "Tier 2 needs career level 14");
            int tier = PlayerBusiness.tier(business());
            Playtest.log("BUSINESS upgrade at level 5: tier " + tier + ", parts " + JourneyScenario.count(UndergroundCity.item("machined_parts")) + "+" + JourneyScenario.count(UndergroundCity.item("refined_alloy")) + "; heard '" + said + "'");
            if (said == null || tier != 1 || JourneyScenario.count(UndergroundCity.item("machined_parts")) != 4) Playtest.fail("an upgrade below career level 14 was not refused, or took the parts");
            else pass("9 /uc business upgrade at level 5 is refused (\"" + said + "\"); the parts are kept");
        });
        Playtest.queue("level 14 (scripted)", () -> {
            Playtest.command("uc_prepare level @s 14");
            scripted("PREPARED: the Smith's level raised to 14 with /uc_prepare (tier 2's level; the economy model puts it at about 20 active hours of Smith work)");
        }, () -> server(() -> CitizenState.get(mc().getSingleplayerServer()).find(me().getUUID()).map(CitizenState.Citizen::careerLevel).orElse(0)) >= 14, 200);
        Playtest.queue("10: /uc business upgrade at level 14", () -> { mark[0] = Playtest.heardCount(); Playtest.command("uc business upgrade"); },
            () -> PlayerBusiness.tier(business()) == 2, 200);
        Playtest.queue("tier 2 fitted", () -> {
            var b = business();
            String said = Playtest.heardSince(mark[0], "Fitted tier 2");
            int parts = JourneyScenario.count(UndergroundCity.item("machined_parts")), alloy = JourneyScenario.count(UndergroundCity.item("refined_alloy"));
            Playtest.log("BUSINESS upgrade at level 14: tier " + PlayerBusiness.tier(b) + ", room " + PlayerBusiness.capacity(b) + ", parts left " + parts + "+" + alloy + "; heard '" + said + "'");
            if (PlayerBusiness.tier(b) != 2 || parts != 0 || alloy != 0 || said == null) Playtest.fail("tier 2 was not fitted from the parts carried");
            else pass("10 at level 14 the same command fits tier 2 from the parts carried (\"" + said + "\"): the four machined parts and two refined alloy are used up");
        });
        final int[] before = new int[1];
        Playtest.queue("64 more bottles (scripted)", () -> {
            before[0] = stock();
            Playtest.command("give @s underground_city:berry_wine 64");
            scripted("64 more bottles of berry wine given by command (a Vintner's)");
        }, () -> JourneyScenario.count(UndergroundCity.item("berry_wine")) == 64, 200);
        StationSteps.hold("the berry wine", UndergroundCity.item("berry_wine"));
        JourneyScenario.use("stock the counter again with a right click", () -> counter, () -> JourneyScenario.count(UndergroundCity.item("berry_wine")) == 0, 400);
        Playtest.queue("more room than tier 1", () -> {
            int now = stock();
            Playtest.log("BUSINESS stock " + before[0] + " -> " + now + ", wine carried " + JourneyScenario.count(UndergroundCity.item("berry_wine")));
            if (now <= PlayerBusiness.CAPACITY || JourneyScenario.count(UndergroundCity.item("berry_wine")) != 0) Playtest.fail("the tier 2 counter did not take more than tier 1's room of " + PlayerBusiness.CAPACITY);
            else pass("11 a right click stocks 64 more bottles: " + now + " on the counter, more than tier 1's room of " + PlayerBusiness.CAPACITY);
        });
        Playtest.queue("an empty hand", () -> { var inv = mc().player.getInventory(); for (int i = 8; i >= 0; i--) if (inv.getItem(i).isEmpty()) { inv.selected = i; break; } });
        final int[] t = {0};
        Playtest.queue("wait for a customer at the lower price", () -> t[0] = 0, () -> { t[0]++; return t[0] % 20 == 0 && business().getInt("sold") > at[0]; }, 9600);
        Playtest.queue("a sale at 90%", () -> {
            var b = business();
            String wine = wineKey(b);
            long priced = server(() -> PlayerBusiness.price(wine, b));
            int sold = b.getInt("sold") - (int) at[0];
            long gross = b.getLong("gross") - at[1], each = sold == 0 ? 0 : gross / sold;
            Playtest.log("BUSINESS after " + t[0] / 20 + " s at 90%: sold " + sold + " for " + gross + ", tax " + b.getLong("tax") + ", till " + b.getLong("takings") + "; the city's price " + city[0]);
            if (sold == 0) Playtest.fail("no customer bought at the lower price in eight minutes of play");
            else if (each * sold != gross || each != priced || each >= city[0]) Playtest.fail("the customers did not pay the counter's lower price (" + gross + " for " + sold + ")");
            else pass("12 a street person bought at the lower price after " + t[0] / 20 + " s of play: " + each + " a bottle against the city's " + city[0] + ", " + b.getLong("takings") + " in the till after tax");
        });
    }

    /** The wine's line on the counter (the item and its grade). */
    private static String wineKey(net.minecraft.nbt.CompoundTag b) {
        return b.getCompound("stock").getAllKeys().stream().filter(k -> k.startsWith("underground_city:berry_wine|")).findFirst().orElse("underground_city:berry_wine|0");
    }

    private static final int[] kept = new int[3];

    private static void reopen() {
        Playtest.queue("13: before the quit", () -> {
            var b = business();
            kept[0] = PlayerBusiness.tier(b); kept[1] = b.getInt("price_pct"); kept[2] = stock();
            Playtest.log("BUSINESS before the quit: tier " + kept[0] + ", price " + kept[1] + "%, stock " + kept[2] + ", till " + b.getLong("takings") + ", wallet " + JourneyScenario.wallet());
        });
        Playtest.queue("save and quit to the title screen, then open the world again", () -> {
            var mc = mc();
            String folder = System.getProperty("uc.playtest.world", "UC-Playtest");
            mc.level.disconnect();
            mc.clearLevel(new net.minecraft.client.gui.screens.GenericDirtMessageScreen(net.minecraft.network.chat.Component.translatable("menu.savingLevel")));
            mc.setScreen(new net.minecraft.client.gui.screens.TitleScreen());
            mc.createWorldOpenFlows().loadLevel(mc.screen, folder);
        }, () -> mc().player != null && mc().level != null && Scenarios.groundBelowPlayer(), 6000);
        Playtest.wait("settle after the reopen", 60);
        Playtest.queue("13: after the reopen", () -> {
            var b = business();
            Playtest.log("BUSINESS after the reopen: tier " + PlayerBusiness.tier(b) + ", price " + b.getInt("price_pct") + "%, stock " + stock() + ", open " + b.getBoolean("open"));
            if (PlayerBusiness.tier(b) != kept[0] || b.getInt("price_pct") != kept[1]) Playtest.fail("the counter's tier or price was lost by a save and a reopen");
            else if (stock() > kept[2]) Playtest.fail("the counter's stock grew over a save and a reopen");
            else pass("13 saved, quit and reopened: the counter keeps tier " + kept[0] + ", prices at " + kept[1] + "% and its stock (" + stock() + "; nothing doubled), and it is still open");
        });
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
