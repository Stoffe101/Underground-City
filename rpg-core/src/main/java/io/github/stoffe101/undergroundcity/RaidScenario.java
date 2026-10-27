package io.github.stoffe101.undergroundcity;

import net.minecraft.core.BlockPos;
import net.minecraft.world.item.ItemStack;

import java.util.List;
import java.util.Locale;
import java.util.Optional;

import static io.github.stoffe101.undergroundcity.JourneyScenario.level;
import static io.github.stoffe101.undergroundcity.JourneyScenario.mc;
import static io.github.stoffe101.undergroundcity.JourneyScenario.me;
import static io.github.stoffe101.undergroundcity.JourneyScenario.server;
import static io.github.stoffe101.undergroundcity.ThiefScenario.pass;
import static io.github.stoffe101.undergroundcity.ThiefScenario.scripted;
import static io.github.stoffe101.undergroundcity.ThiefScenario.shot;

/**
 * The bonded store job (D-173) through a real client ({@code -PucPlaytest=raid}, a new furnished archipelago world): a thief of level 8 with Professional Tools takes Kessler's job, tracks it, cases the Ironhaven
 * bonded store before dawn, picks its high-security lock with a Pro Pick Set on the real lock screen, searches inside with the real right click until the seized shipment turns up (marked stolen with the thief's name, Ironhaven's
 * heat raised by the watchman's report), and carries it to Blackwater, where it is delivered for the job's pay.
 *
 * <p>Scripted, each logged: the Thief XP and four Thief skills of a seasoned thief, the clock (04:00, stopped), taking and tracking the job through the server's own functions (the terminal's buttons are exercised
 * by the journey runs), the travel between cities (teleports), any walk the walker could not finish, the lockpicks in the hotbar, the Pro Pick Set, the walks between rooms, and the delivery through the
 * server's own function at a point inside Blackwater.
 */
final class RaidScenario {
    private RaidScenario() {}

    private static final String JOB = "underground_city:raid_bonded_store";
    private static final java.util.List<String> SKILLS = java.util.List.of("thief.casing", "thief.steady_hands", "thief.feel_for_pins", "thief.pro_tools");
    private static final class Facts {
        volatile Raids.Target store;
        volatile CityBuildings.Building building;
        volatile DoorWalk.Spot door;
        volatile int heatBefore;
        volatile double walletBefore;
    }
    private static final Facts F = new Facts();

    static void build() {
        ThiefScenario.tag = "RAID";
        Playtest.keepScreens = true;
        ThiefScenario.register(0, "the Farmer card", "1 a new citizen (a Farmer) registers through the registration screen");
        Playtest.queue("a thief of level " + Raids.MIN_THIEF_LEVEL + " (scripted)", () -> {
            int target = ProgressionRules.thiefXpAtLevel(Raids.MIN_THIEF_LEVEL), have = ThiefScenario.thiefXp();
            server(() -> Thievery.award(me(), target - have, "A thief with a few jobs behind them.", false));
            scripted("the Thief XP of a level-" + Raids.MIN_THIEF_LEVEL + " thief was given (" + target + " XP): the climb is the thief scenario's");
        });
        Playtest.queue("the Thief skills of a thief who opens high-security locks (scripted)", () -> {
            server(() -> { for (String id : SKILLS) CitizenState.get(me().server).unlockSkill(me().getUUID(), id); return null; });
            scripted("Casing, Steady Hands, Feel for Pins and Professional Tools were learned through the server (the terminal's Thief page is played in the heist runs)");
        });
        Playtest.queue("04:00 with the daylight cycle stopped", () -> {
            Playtest.command("gamerule doDaylightCycle false");
            Playtest.command("time set 22000");
            scripted("the time was set to 04:00 and the daylight cycle stopped: the store is shut, its yard's night trade (until 03:00) gone home");
        });
        ThiefScenario.travel("to the middle of Ironhaven", () -> ThiefScenario.centre("ironhaven"), 6);
        Playtest.queue("take Kessler's job (scripted: the server's own accept) and track it", () -> {
            String said = server(() -> {
                try {
                    WorkOrderLedger orders = WorkOrderLedger.get(me().server);
                    WorkOrderService.accept(me(), orders, CitizenState.get(me().server).find(me().getUUID()).orElseThrow(), JOB);
                    Navigation.request(me(), "order", JOB);
                    return null;
                } catch (RuntimeException error) { return error.getMessage(); }
            });
            scripted("the job was taken and tracked through the server's own functions (the terminal's Accept and Track buttons)");
            if (said != null) { Playtest.fail("the job was refused: " + said); return; }
            Optional<Raids.Target> store = server(() -> Raids.store(level()));
            if (store.isEmpty()) { Playtest.fail("this world has no bonded store (no warehouse near Ironhaven's centre)"); return; }
            F.store = store.get();
            F.building = server(() -> {
                for (int dx = -10; dx <= 10; dx++) for (int dz = -10; dz <= 10; dz++) {
                    var region = CityRegions.INSTANCE.all().stream().filter(r -> r.id().equals("ironhaven")).findFirst().orElseThrow();
                    var b = CityBuildings.INSTANCE.ofChunk(level(), region.centerChunkX() + dx, region.centerChunkZ() + dz);
                    if (b.isPresent() && b.get().key() == F.store.key()) return b.get();
                }
                return null;
            });
            Playtest.log(String.format(Locale.ROOT, "RAID the bonded store: %s at %s (tier %d)", F.building == null ? "?" : F.building.type(), F.store.door().toShortString(), F.store.tier()));
        });
        ThiefScenario.stopIfFailed("taking the job");
        Playtest.wait("the destination arrives", 20);
        Playtest.queue("the status display points at the store", () -> {
            Navigation.Destination d = ClientNavigation.current();
            Playtest.log("RAID tracked on the client: " + (d == null ? "nothing" : d.title() + " at " + d.pos().toShortString()));
            if (d == null || !d.title().contains("bonded store")) Playtest.fail("the client does not track the store");
            else pass("2 the job is taken and tracked: the status display points at the bonded store");
        });
        shot("1-tracked");
        Playtest.queue("the store's street door", () -> {
            F.door = server(() -> ThiefScenario.streetDoor(level(), F.building));
            if (F.door == null) { Playtest.fail("the store has no street door the scenario can use"); return; }
            List<BlockPos> furniture = server(() -> ThiefScenario.furniture(level(), F.building, F.door.door()));
            ThiefScenario.useBuilding(F.door, F.building, furniture);
            Playtest.log("RAID the store's door " + F.door.door().toShortString() + "; " + furniture.size() + " searchable pieces inside");
        });
        ThiefScenario.stopIfFailed("the store's door");
        Playtest.queue("on the street in front of the store (scripted)", () -> {
            scripted("the player was put on the street in front of the store's door at " + F.door.door().toShortString() + " in survival (the travel was a teleport)");
            ThiefScenario.standAt(F.door);
        }, () -> mc().player.position().distanceTo(F.door.stand()) < 0.8, 200);
        Playtest.wait("settle", 20);
        ThiefScenario.prepareAtDoor();
        Playtest.queue("a Pro Pick Set in place of the lockpicks (scripted)", () -> {
            server(() -> { me().getInventory().setItem(8, new ItemStack(UndergroundCity.item(LockService.PRO_SET))); return null; });
            scripted("a Pro Pick Set was put in hotbar slot 9 in place of the lockpicks (crafted, or bought now and then at the cannery den): the store's door is a high-security lock");
        });
        ThiefScenario.caseIt("2-casing-store", true);
        ThiefScenario.waitForClearStreet("ironhaven");
        Playtest.queue("heat before", () -> {
            F.heatBefore = server(() -> WorkOrderLedger.get(me().server).heat(me().getUUID(), "ironhaven"));
            F.walletBefore = server(() -> QShopEconomy.balance(me()));
        });
        ThiefScenario.pickTheDoor();
        ThiefScenario.stopIfFailed("the store's door");
        ThiefScenario.search();
        Playtest.queue("the shipment", () -> {
            int shipments = JourneyScenario.count(UndergroundCity.item(Raids.SHIPMENT));
            boolean marked = server(() -> me().getInventory().items.stream().anyMatch(s -> s.is(UndergroundCity.item(Raids.SHIPMENT)) && StolenGoods.is(s) && me().getUUID().equals(StolenGoods.thief(s))));
            int heat = server(() -> WorkOrderLedger.get(me().server).heat(me().getUUID(), "ironhaven"));
            Playtest.log("RAID shipment " + shipments + ", marked stolen with the thief " + marked + "; Ironhaven heat " + F.heatBefore + " -> " + heat);
            if (shipments != 1 || !marked) Playtest.fail("the search did not turn up the shipment, marked stolen with the thief");
            else if (heat < F.heatBefore + Raids.REPORT_HEAT && heat < WorkOrderLedger.MAX_HEAT) Playtest.fail("the watchman's report did not heat Ironhaven");
            else pass("5 the search turns up the seized shipment, marked stolen with the thief's name; the watchman's report heats Ironhaven by " + (heat - F.heatBefore));
        });
        Playtest.wait("the destination moves", 20);
        Playtest.queue("the order now points at Blackwater", () -> {
            Navigation.Destination d = ClientNavigation.current();
            Playtest.log("RAID tracked after the find: " + (d == null ? "nothing" : d.title() + " in " + d.city()));
            if (d == null || !d.city().equals("blackwater")) Playtest.fail("the tracked destination did not move to Blackwater");
            else pass("6 with the shipment in hand the order is tracked to Kessler in Blackwater");
        });
        shot("6-shipment");
        ThiefScenario.travel("to the middle of Blackwater", () -> ThiefScenario.centre("blackwater"), 4);
        Playtest.queue("deliver to Kessler (scripted: the server's own delivery at a point inside Blackwater)", () -> {
            String said = server(() -> {
                try {
                    WorkOrderLedger orders = WorkOrderLedger.get(me().server);
                    WorkOrderService.complete(me(), orders, orders.player(me().getUUID()).getUUID("order"), me().blockPosition());
                    return null;
                } catch (RuntimeException error) { return error.getMessage(); }
            });
            scripted("the delivery went through the server's own function at the player's position inside Blackwater (the terminal's Deliver button)");
            double wallet = server(() -> QShopEconomy.balance(me()));
            int left = JourneyScenario.count(UndergroundCity.item(Raids.SHIPMENT));
            Playtest.log("RAID delivered: " + (said == null ? "yes" : said) + "; wallet " + F.walletBefore + " -> " + wallet + "; shipments left " + left);
            if (said != null || left != 0 || wallet < F.walletBefore + 700) Playtest.fail("the delivery to Kessler failed: " + said);
            else pass("7 Kessler takes the shipment in Blackwater and pays " + (long) (wallet - F.walletBefore) + " credits");
        });
        Playtest.queue("summary", ThiefScenario::summary);
    }
}
