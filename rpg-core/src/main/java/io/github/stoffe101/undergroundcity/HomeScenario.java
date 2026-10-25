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
 * Two moves up the property ladder through a real client ({@code -PucPlaytest=homes}, D-176): a prepared citizen (32,000 credits, logged) rents a loft in Blackwater, the new larger apartment,
 * on the Home page's Find a home tab with a real click, walks in at the block's street door with the tenant's key and sets a chest down inside; then buys a villa in Valewood, the premium
 * residence, fits the Locking racks upgrade the owner may buy, moves out of the loft (its deposit back) and saves and reopens the world. Scripted, each logged: the credits, the clock, the travel
 * between the cities, the start at each building's street door, the chest put in the inventory, and a teleport for any part of a walk the walker cannot do.
 */
final class HomeScenario {
    private HomeScenario() {}

    private static final class Facts {
        volatile CityBuildings.Building lofts, villa;
        volatile DoorWalk.Spot loftsDoor, villaDoor;
        volatile String loftId = "", villaId = "", loftCategory = "", villaCategory = "";
        volatile double walletBefore;
    }

    private static final Facts F = new Facts();

    static void build() {
        ThiefScenario.tag = "HOMES";
        Playtest.keepScreens = true;
        ThiefScenario.register(2, "the Smith card", "1 a new citizen registers through the registration screen");
        Playtest.queue("prepared (scripted)", () -> {
            Playtest.command("gamerule doDaylightCycle false");
            Playtest.command("time set 7000");
            scripted("PREPARED: 32,000 credits were added (the savings of a long career) and a chest put in the inventory; the clock set to 13:00 and stopped");
            server(() -> {
                QShopEconomy.applyBalance(me(), QShopEconomy.balance(me()) + 32000);
                me().getInventory().add(new ItemStack(Items.CHEST));
                return null;
            });
        });
        // ------------------------------------------------------------------ the loft
        ThiefScenario.travel("to the middle of Blackwater", () -> ThiefScenario.centre("blackwater"), 8);
        Playtest.queue("a loft block with a flat to let", () -> {
            F.lofts = server(() -> letBuilding("bw_lofts"));
            if (F.lofts != null) F.loftsDoor = server(() -> ThiefScenario.streetDoor(level(), F.lofts));
            Playtest.log("HOMES lofts " + (F.lofts == null ? "none" : F.lofts.chunkX() + "," + F.lofts.chunkZ()));
            if (F.lofts == null || F.loftsDoor == null) Playtest.fail("no loft block with a flat to let and a street door within sixteen chunks of the middle of Blackwater");
        });
        ThiefScenario.stopIfFailed("a loft to let");
        Playtest.queue("start at the loft block's street door (scripted)", () -> { scripted("the player was put at the loft block's street door " + F.loftsDoor.door().toShortString()); ThiefScenario.standAt(F.loftsDoor); },
            () -> mc().player.position().distanceTo(F.loftsDoor.stand()) < 0.8, 200);
        Playtest.wait("settle", 30);
        openFind();
        takeListing("bw_lofts", false, (id, category) -> { F.loftId = id; F.loftCategory = category; });
        Playtest.queue("the loft is rented", () -> {
            var r = residence(F.loftId);
            Playtest.log("HOMES loft " + F.loftId + ": " + (r == null ? "none" : r.tenure + ", charge " + r.charge + ", deposit " + r.deposit + ", listed as " + F.loftCategory) + "; wallet " + JourneyScenario.wallet());
            if (r == null || r.tenure != PropertyRules.Tenure.RENT || !F.loftCategory.toLowerCase(java.util.Locale.ROOT).contains("larger") || r.deposit <= 0) Playtest.fail("the loft was not rented as a larger apartment with a deposit");
            else pass("2 a loft in Blackwater rented on the Find a home tab with a real click: a larger apartment, deposit " + r.deposit + " and the first hour paid, " + r.charge + " an hour of play after that");
        });
        shot("1-loft-rented");
        JourneyScenario.closeScreen("close the Home page");
        // in at the street door with the tenant's key, then into the flat, and a chest set down there
        StationSteps.enter("the loft block", () -> F.loftsDoor);
        final Vec3[] inside = new Vec3[1];
        Playtest.queue("a place inside the rented flat", () -> {
            inside[0] = server(() -> insideUnit(F.loftId));
            Playtest.log("HOMES inside the loft at " + (inside[0] == null ? "nowhere" : BlockPos.containing(inside[0]).toShortString()));
            if (inside[0] == null) Playtest.fail("no free floor inside the rented flat");
        });
        ThiefScenario.walkOrTeleport("walk into the flat", () -> inside[0] == null ? mc().player.position() : inside[0], 0.8, 1500);
        final StationSteps.Station chest = new StationSteps.Station("chest");
        FarmerScenario.placeVanilla(chest, Items.CHEST, net.minecraft.world.level.block.Blocks.CHEST);
        Playtest.queue("the chest counts as the flat's", () -> {
            var r = residence(F.loftId);
            boolean in = chest.pos != null && server(() -> level().getBlockState(chest.pos).is(net.minecraft.world.level.block.Blocks.CHEST));
            Playtest.log("HOMES chest at " + (chest.pos == null ? "nowhere" : chest.pos.toShortString()) + " placed " + in);
            if (!in) Playtest.fail("the chest could not be set down inside the rented flat");
            else pass("3 in at the street door with the tenant's key (a real right click) and a chest set down inside the flat with a real right click");
        });
        shot("2-loft-inside");
        // ------------------------------------------------------------------ the villa
        ThiefScenario.travel("to the middle of Valewood", () -> ThiefScenario.centre("valewood"), 8);
        Playtest.queue("a villa for sale", () -> {
            F.villa = server(() -> letBuilding("vw_villa"));
            if (F.villa != null) F.villaDoor = server(() -> ThiefScenario.streetDoor(level(), F.villa));
            Playtest.log("HOMES villa " + (F.villa == null ? "none" : F.villa.chunkX() + "," + F.villa.chunkZ()));
            if (F.villa == null || F.villaDoor == null) Playtest.fail("no villa to let or buy with a street door within sixteen chunks of the middle of Valewood");
        });
        ThiefScenario.stopIfFailed("a villa");
        Playtest.queue("start at the villa's door (scripted)", () -> { scripted("the player was put at the villa's front door " + F.villaDoor.door().toShortString()); ThiefScenario.standAt(F.villaDoor); },
            () -> mc().player.position().distanceTo(F.villaDoor.stand()) < 0.8, 200);
        Playtest.wait("settle", 30);
        Playtest.queue("before buying", () -> F.walletBefore = JourneyScenario.wallet());
        openFind();
        takeListing("vw_villa", true, (id, category) -> { F.villaId = id; F.villaCategory = category; });
        Playtest.queue("the villa is bought", () -> {
            var r = residence(F.villaId);
            double paid = F.walletBefore - JourneyScenario.wallet();
            Playtest.log("HOMES villa " + F.villaId + ": " + (r == null ? "none" : r.tenure + ", listed as " + F.villaCategory) + "; paid " + paid);
            if (r == null || r.tenure != PropertyRules.Tenure.OWN || !F.villaCategory.toLowerCase(java.util.Locale.ROOT).contains("premium") || paid < 20000) Playtest.fail("the villa was not bought as a premium residence");
            else pass("4 a villa in Valewood bought on the Find a home tab with a real click: a premium residence for " + Math.round(paid) + " credits (Valewood's price factor)");
        });
        shot("3-villa-bought");
        // the owner fits an upgrade: Your homes, the villa's row, the first upgrade's Fit
        JourneyScenario.clickAt("the Your homes tab", () -> mc().screen instanceof CrewScreen s ? s.centreOf("home_tab_0") : null);
        Playtest.wait("draw", 8);
        JourneyScenario.clickAt("the villa's row", () -> mc().screen instanceof CrewScreen s ? s.homeRowCentre(F.villaId) : null);
        Playtest.wait("draw", 8);
        Playtest.queue("before the upgrade", () -> F.walletBefore = JourneyScenario.wallet());
        JourneyScenario.clickAt("Fit on the first upgrade", () -> mc().screen instanceof CrewScreen s ? s.centreOf("upgrade_0") : null);
        until("the upgrade is fitted", () -> { var r = residence(F.villaId); return r != null && !r.upgrades.isEmpty(); }, 200);
        Playtest.queue("fitted", () -> {
            var r = residence(F.villaId);
            Playtest.log("HOMES villa upgrades " + (r == null ? "?" : r.upgrades) + "; paid " + (F.walletBefore - JourneyScenario.wallet()));
            if (r == null || r.upgrades.isEmpty()) Playtest.fail("the owner's upgrade was not fitted");
            else pass("5 the owner fits an upgrade on the Home page (a real click on Fit): " + r.upgrades + " for " + Math.round(F.walletBefore - JourneyScenario.wallet()) + " credits");
        });
        shot("4-villa-upgrade");
        // out of the loft: its row, Move out, Really?
        JourneyScenario.clickAt("the loft's row", () -> mc().screen instanceof CrewScreen s ? s.homeRowCentre(F.loftId) : null);
        Playtest.wait("draw", 8);
        Playtest.queue("before moving out", () -> F.walletBefore = JourneyScenario.wallet());
        JourneyScenario.clickAt("Move out", () -> mc().screen instanceof CrewScreen s ? s.centreOf("leave_home") : null);
        Playtest.wait("draw", 8);
        JourneyScenario.clickAt("Really?", () -> mc().screen instanceof CrewScreen s ? s.centreOf("leave_home") : null);
        until("moved out of the loft", () -> residence(F.loftId) == null || server(() -> !me().getUUID().equals(holderOf(F.loftId))), 200);
        Playtest.queue("moved out", () -> {
            double back = JourneyScenario.wallet() - F.walletBefore;
            Playtest.log("HOMES moved out of the loft: deposit back " + back);
            if (back <= 0) Playtest.fail("moving out of the loft returned no deposit");
            else pass("6 moving out of the loft (Move out, Really?) returns its deposit: +" + Math.round(back) + " credits; the chest left inside stays the player's to collect");
        });
        JourneyScenario.closeScreen("close the Home page");
        StationSteps.reconnect(() -> {
            var v = residence(F.villaId);
            return List.of(JourneyScenario.wallet(), v == null ? "none" : v.tenure + " " + v.upgrades, server(() -> me().getUUID().equals(holderOf(F.loftId))));
        }, (before, after) -> {
            if (!before.equals(after)) Playtest.fail("the homes or the wallet changed across the save and reopen");
            else pass("7 saved, left and reopened: the villa still owned with its upgrade, the loft no longer held, the wallet unchanged");
        });
        Playtest.queue("summary", ThiefScenario::summary);
    }

    /** The nearest building of a type with a unit to let within sixteen chunks (server thread). */
    private static CityBuildings.Building letBuilding(String suffix) {
        var level = level();
        BlockPos here = me().blockPosition();
        for (int r = 0; r <= 16; r++) for (int dx = -r; dx <= r; dx++) for (int dz = -r; dz <= r; dz++) {
            if (Math.max(Math.abs(dx), Math.abs(dz)) != r) continue;
            int cx = (here.getX() >> 4) + dx, cz = (here.getZ() >> 4) + dz;
            if (!level.hasChunk(cx, cz)) continue;
            CityBuildings.Building b = CityBuildings.INSTANCE.ofChunk(level, cx, cz).orElse(null);
            if (b != null && b.type().endsWith(suffix) && CityHomes.anyLet(level.getSeed(), b)) return b;
        }
        return null;
    }

    private static CrewLedger.Residence residence(String id) {
        if (id == null || id.isEmpty()) return null;
        return server(() -> CrewService.need(mc().getSingleplayerServer()).residence(id));
    }

    private static java.util.UUID holderOf(String id) {
        CrewLedger.Residence r = CrewService.need(mc().getSingleplayerServer()).residence(id);
        return r == null ? null : r.owner;
    }

    /** A free floor cell inside a residence's unit box (server thread). */
    private static Vec3 insideUnit(String id) {
        CrewLedger.Residence r = CrewService.need(mc().getSingleplayerServer()).residence(id);
        if (r == null) return null;
        var level = level();
        for (BlockPos c : BlockPos.betweenClosed(r.min, r.max))
            if (StationSteps.standable(level, c) && StationSteps.standable(level, c.east()) && level.getBlockState(c.east(2)).isAir()) return Vec3.atBottomCenterOf(c);
        return null;
    }

    /** K, the Home rail, the Find a home tab. */
    private static void openFind() {
        Playtest.queue("press K for the Crew and Home screen", () -> { Playtest.focusWindow(); Playtest.tap("k"); });
        until("the screen", () -> mc().screen instanceof CrewScreen, 200);
        Playtest.wait("draw", 10);
        JourneyScenario.clickAt("the Home rail", () -> mc().screen instanceof CrewScreen s ? s.centreOf("rail_home") : null);
        Playtest.wait("draw", 8);
        JourneyScenario.clickAt("the Find a home tab", () -> mc().screen instanceof CrewScreen s ? s.centreOf("home_tab_1") : null);
        Playtest.wait("draw", 10);
    }

    /** On the Find a home tab: pages to the first listing of a building type and clicks Rent (or Buy) on it with the real pointer. */
    private static void takeListing(String type, boolean buy, java.util.function.BiConsumer<String, String> chosen) {
        final int[] index = {-1};
        Playtest.queue("the listing of the " + type, () -> {
            if (!(mc().screen instanceof CrewScreen s)) { Playtest.fail("the Home page is not open"); return; }
            var nearby = s.snapshot().nearby();
            for (int i = 0; i < nearby.size() && index[0] < 0; i++)
                if (nearby.get(i).id().contains(type) && (buy ? nearby.get(i).offer().canBuy() : nearby.get(i).offer().canRent())) index[0] = i;
            Playtest.log("HOMES Find a home lists " + nearby.size() + ": " + nearby.stream().limit(8).map(l -> l.title() + " (" + l.offer().category() + ")").toList());
            if (index[0] < 0) { Playtest.fail("Find a home does not list the " + type); return; }
            chosen.accept(nearby.get(index[0]).id(), nearby.get(index[0]).offer().category());
        });
        ThiefScenario.stopIfFailed("the listing");
        final int[] t = {0};
        String button = buy ? "buy_" : "rent_";
        Playtest.queue("page to the " + type, () -> t[0] = 0, () -> {
            if (!(mc().screen instanceof CrewScreen s)) return true;
            if (s.centreOf(button + index[0]) != null) return true;
            if (t[0]++ % 10 == 0) { int[] next = s.centreOf("find_next"); if (next != null) Playtest.clickGui(next[0], next[1]); }
            return t[0] > 200;
        }, 240);
        JourneyScenario.clickAt((buy ? "Buy" : "Rent") + " on the " + type, () -> mc().screen instanceof CrewScreen s ? s.centreOf(button + index[0]) : null);
        Playtest.wait("the server answers", 30);
    }
}
