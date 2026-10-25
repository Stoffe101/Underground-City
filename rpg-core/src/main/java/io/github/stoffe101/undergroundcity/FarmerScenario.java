package io.github.stoffe101.undergroundcity;

import net.minecraft.core.BlockPos;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.List;
import java.util.Set;

import static io.github.stoffe101.undergroundcity.JourneyScenario.level;
import static io.github.stoffe101.undergroundcity.JourneyScenario.mc;
import static io.github.stoffe101.undergroundcity.JourneyScenario.me;
import static io.github.stoffe101.undergroundcity.JourneyScenario.server;
import static io.github.stoffe101.undergroundcity.ThiefScenario.pass;
import static io.github.stoffe101.undergroundcity.ThiefScenario.scripted;
import static io.github.stoffe101.undergroundcity.ThiefScenario.shot;

/**
 * The Farmer's workshop through a real client (D-163, D-164; the seventh pass's Farmer stations): the Mill from the starter kit and a Grading Table crafted through the recipe book, in Valewood.
 *
 * <p><b>Track A, organic:</b> a new citizen registers as a Farmer and places the kit's Mill with a real right click on open ground near Valewood's General Store; the recipe book holds the station
 * recipes. Scripted: the clock (13:00, stopped), the trip to Valewood, the start in front of the store.
 *
 * <p><b>Track B, prepared:</b> the Farmer's XP is raised to level 4 and 120 credits and twelve wheat are added, as after a harvest of the kit's seeds and a few orders (all logged as SCRIPTED); the
 * rest is real clicks: flour ground on the Mill, Field Fundamentals and Greenhouse Management learned at the store's public terminal, a crafting table, planks, carrots and potatoes bought over the
 * store's counter, oak slabs and then a Grading Table crafted through the recipe book, the table placed, a produce crate packed on it (Greenhouse Management packs it a grade up), the crate sold to
 * the store and the flour to the Valewood bakery, and the world saved, left and opened again with the stations, the skills, the wallet and the goods unchanged.
 */
final class FarmerScenario {
    private FarmerScenario() {}

    private static final class Facts {
        volatile CityBuildings.Building store, bakery;
        volatile DoorWalk.Spot storeDoor, bakeryDoor;
        volatile BlockPos publicTerminal;
    }

    private static final Facts F = new Facts();
    private static final StationSteps.Station MILL = new StationSteps.Station("mill"), GRADING = new StationSteps.Station("grading_table");
    static final String NS = UndergroundCity.MOD_ID + ":";

    static void build() {
        ThiefScenario.tag = "FARMER";
        Playtest.keepScreens = true;
        ThiefScenario.register(0, "the Farmer card", "1 (A) a new citizen registers as a Farmer through the registration screen");
        Playtest.queue("the kit", () -> {
            int mills = JourneyScenario.count(UndergroundCity.WORKSHOP_ITEMS.get("mill").get()), seeds = JourneyScenario.count(Items.WHEAT_SEEDS);
            boolean book = server(() -> me().getRecipeBook().contains(net.minecraft.resources.ResourceLocation.fromNamespaceAndPath(UndergroundCity.MOD_ID, "grading_table")));
            Playtest.log("FARMER kit: mills " + mills + ", wheat seeds " + seeds + "; the recipe book holds the Grading Table " + book);
            if (mills != 1 || seeds < 12 || !book) Playtest.fail("the Farmer's kit does not hold a Mill and seed, or the recipe book lacks the Grading Table");
            else pass("2 (A) a new Farmer starts with a Mill and twelve seed; the Grading Table's recipe is in the recipe book (D-175)");
        });
        Playtest.queue("the clock: 13:00 with the daylight cycle stopped", () -> {
            Playtest.command("gamerule doDaylightCycle false");
            Playtest.command("time set 7000");
            scripted("the time of day was set to 13:00 and the daylight cycle stopped (the store and the bakery are open by day)");
        });
        ThiefScenario.travel("to the middle of Valewood", () -> ThiefScenario.centre("valewood"), 6);
        Playtest.queue("the General Store and the bakery near the middle of Valewood", () -> server(() -> {
            F.store = StationSteps.nearest("vw_shop", true);
            F.bakery = StationSteps.nearest("vw_bakery", true);
            if (F.store != null) F.storeDoor = ThiefScenario.streetDoor(level(), F.store);
            if (F.bakery != null) F.bakeryDoor = ThiefScenario.streetDoor(level(), F.bakery);
            Playtest.log("FARMER store " + (F.store == null ? "none" : F.store.chunkX() + "," + F.store.chunkZ()) + ", bakery " + (F.bakery == null ? "none" : F.bakery.chunkX() + "," + F.bakery.chunkZ()));
            if (F.store == null || F.storeDoor == null) Playtest.fail("no open General Store with a street door within twelve chunks of the middle of Valewood");
            return null;
        }));
        ThiefScenario.stopIfFailed("an open General Store");
        Playtest.queue("start on the street outside the store (scripted)", () -> {
            scripted("the player was put on the street outside the General Store's door at " + F.storeDoor.door().toShortString() + " in survival");
            ThiefScenario.standAt(F.storeDoor);
        }, () -> mc().player.position().distanceTo(F.storeDoor.stand()) < 0.8, 200);
        Playtest.wait("settle", 20);
        StationSteps.place(MILL, UndergroundCity.WORKSHOP_ITEMS.get("mill").get());
        ThiefScenario.stopIfFailed("placing the Mill");
        StationSteps.open(MILL, "its recipes");
        StationSteps.logRecipes(MILL);
        Playtest.queue("the Mill's recipes", () -> {
            if (!StationSteps.allowed("flour") || StationSteps.allowed("feed_flour")) Playtest.fail("the Mill does not offer flour, or offers the geared mill's feed flour at tier 1");
            else pass("3 (A) the kit's Mill is placed with a real right click near the General Store; its screen offers flour and beet sugar, and feed flour only on a geared mill");
        });
        shot("1-mill");
        JourneyScenario.closeScreen("close the Mill");
        // ------------------------------------------------------------------ Track B
        Playtest.queue("Track B: prepared (scripted)", () -> {
            scripted("TRACK B begins: the Farmer's XP was raised to level 4 (1,125 XP), 120 credits and twelve wheat were added, as after a harvest of the kit's seeds and a few orders");
            server(() -> {
                CitizenState state = CitizenState.get(mc().getSingleplayerServer());
                int xp = state.find(me().getUUID()).map(CitizenState.Citizen::careerXp).orElse(0);
                state.addCareerXp(me().getUUID(), Math.max(0, 1125 - xp));
                QShopEconomy.applyBalance(me(), QShopEconomy.balance(me()) + 120);
                me().getInventory().add(new ItemStack(Items.WHEAT, 12));
                return null;
            });
        });
        StationSteps.open(MILL, "grind flour");
        StationSteps.batch(MILL, "flour", NS + "flour", 2, "4 (B) the Mill grinds three wheat to two sacks of flour in thirty seconds of play (real clicks on the row, Start and Take)");
        StationSteps.batch(MILL, "flour", NS + "flour", 2, "5 (B) a second batch: four sacks of flour");
        JourneyScenario.closeScreen("close the Mill");
        // the store: its public terminal for the skills, then the counter
        Playtest.queue("the store's public terminal", () -> {
            F.publicTerminal = server(() -> StationSteps.terminalIn(F.store));
            Playtest.log("FARMER public terminal " + (F.publicTerminal == null ? "none" : F.publicTerminal.toShortString()));
            if (F.publicTerminal == null) Playtest.fail("the General Store has no public terminal");
        });
        ThiefScenario.stopIfFailed("a public terminal");
        StationSteps.enter("the General Store", () -> F.storeDoor);
        ThiefScenario.walkOrTeleport("walk to the public terminal", () -> StationSteps.standBeside(F.publicTerminal), 0.8, 900);
        JourneyScenario.use("open the public terminal", () -> F.publicTerminal, () -> mc().screen instanceof TerminalScreen, 400);
        StationSteps.learn("farmer.fundamentals", "Field Fundamentals");
        StationSteps.learn("farmer.greenhouse_management", "Greenhouse Management");
        Playtest.queue("both skills", () -> {
            CitizenState.Citizen c = ThiefScenario.citizen();
            if (c == null || !c.skills().containsAll(Set.of("farmer.fundamentals", "farmer.greenhouse_management"))) Playtest.fail("the skills were not learned");
            else pass("6 (B) Field Fundamentals and Greenhouse Management learned with real clicks at the General Store's public terminal (two points at level 4, one left)");
        });
        shot("2-skills");
        JourneyScenario.closeScreen("close the terminal");
        StationSteps.toCounter("the General Store", () -> F.store, () -> F.storeDoor);
        StationSteps.buy("a crafting table", NS + "shops/store_table_valewood", Items.CRAFTING_TABLE, 1);
        StationSteps.buy("oak planks", NS + "shops/store_planks_valewood", Items.OAK_PLANKS, 7);
        StationSteps.buy("carrots", NS + "shops/store_carrot_valewood", Items.CARROT, 6);
        StationSteps.buy("potatoes", NS + "shops/store_potato_valewood", Items.POTATO, 6);
        Playtest.queue("bought at the store", () -> {
            int table = JourneyScenario.count(Items.CRAFTING_TABLE), planks = JourneyScenario.count(Items.OAK_PLANKS), carrots = JourneyScenario.count(Items.CARROT), potatoes = JourneyScenario.count(Items.POTATO);
            Playtest.log("FARMER bought: table " + table + ", planks " + planks + ", carrots " + carrots + ", potatoes " + potatoes + ", wallet " + JourneyScenario.wallet());
            if (table < 1 || planks < 7 || carrots < 6 || potatoes < 6) Playtest.fail("the store did not sell everything");
            else pass("7 (B) a crafting table, planks, carrots and potatoes bought over the General Store's counter with real clicks (potatoes are sold in Valewood since the first-hour audit)");
        });
        shot("3-store-counter");
        JourneyScenario.closeScreen("close the counter");
        // out to the Mill's ground: the crafting table beside it, then the recipe book
        ThiefScenario.walkOrTeleport("back out to the Mill", () -> StationSteps.standBeside(MILL.pos), 0.8, 1500);
        final StationSteps.Station tableSpot = new StationSteps.Station("crafting_table");
        placeVanilla(tableSpot, Items.CRAFTING_TABLE, net.minecraft.world.level.block.Blocks.CRAFTING_TABLE);
        StationSteps.craftWithBook(() -> tableSpot.pos, Items.OAK_SLAB, "8 (B) oak slabs crafted through the recipe book (a click on the recipe fills the grid)");
        Item gradingItem = UndergroundCity.WORKSHOP_ITEMS.get("grading_table").get();
        StationSteps.craftWithBook(() -> tableSpot.pos, gradingItem, "9 (B) a Grading Table crafted through the recipe book: Core's station recipes are there for a new citizen without JEI (D-175)");
        StationSteps.place(GRADING, gradingItem);
        StationSteps.open(GRADING, "pack a crate");
        StationSteps.logRecipes(GRADING);
        StationSteps.batch(GRADING, "produce_crate", NS + "produce_crate", 1, "10 (B) the Grading Table packs six carrots and six potatoes into a produce crate in a minute of play");
        Playtest.queue("the crate's grade", () -> {
            String grades = StationSteps.grades(UndergroundCity.item("produce_crate"));
            Playtest.log("FARMER crate grades " + grades);
            if (!grades.contains("Fine") && !grades.contains("Reserve")) Playtest.fail("Greenhouse Management did not pack the crate a grade up: " + grades);
            else pass("11 (B) with Greenhouse Management the crate comes out " + grades + ", a grade above Standard");
        });
        shot("4-crate");
        JourneyScenario.closeScreen("close the Grading Table");
        StationSteps.toCounter("the General Store", () -> F.store, () -> F.storeDoor);
        StationSteps.sell(NS + "shops/store_produce_valewood", UndergroundCity.item("produce_crate"), 26, "12 (B) the General Store buys the produce crate (a real click on Sell)");
        JourneyScenario.closeScreen("close the counter");
        Playtest.queue("is the bakery open", () -> { if (F.bakery == null || F.bakeryDoor == null) Playtest.fail("no open bakery near the middle of Valewood"); });
        ThiefScenario.stopIfFailed("an open bakery");
        StationSteps.toCounter("the bakery", () -> F.bakery, () -> F.bakeryDoor);
        StationSteps.sell(NS + "shops/bakery_flour_valewood", UndergroundCity.item("flour"), 4 * 4, "13 (B) the Valewood bakery buys the four sacks of flour over its counter");
        shot("5-bakery");
        JourneyScenario.closeScreen("close the counter");
        StationSteps.reconnect(FarmerScenario::state, (before, after) -> {
            if (!before.equals(after)) Playtest.fail("something changed across the save and reopen");
            else pass("14 (B) saved, left to the title screen and opened again: wallet, Farmer XP, skills, the Mill and the Grading Table where they stood, nothing doubled");
        });
        Playtest.queue("summary", ThiefScenario::summary);
    }

    /** What must survive a save and reopen. */
    private static Object state() {
        CitizenState.Citizen c = ThiefScenario.citizen();
        return List.of(JourneyScenario.wallet(), c == null ? -1 : c.careerXp(), c == null ? Set.of() : Set.copyOf(c.skills()),
            server(() -> level().getBlockState(MILL.pos).getBlock() instanceof WorkshopBlock), server(() -> level().getBlockState(GRADING.pos).getBlock() instanceof WorkshopBlock),
            JourneyScenario.count(UndergroundCity.item("flour")), JourneyScenario.count(UndergroundCity.item("produce_crate")));
    }

    /** Places a vanilla block from the inventory with a real right click on open ground near the player (a crafting table). */
    /** True when the line from the player's eye to the top of {@code floor} meets nothing before it (farmer run 4: the aim at the far side of the Mill hit the Mill). Server thread. */
    static boolean clearAim(net.minecraft.server.level.ServerLevel lvl, BlockPos floor) {
        var eye = me().getEyePosition();
        // the very point the placing click aims at (JourneyScenario.use: the block's centre and 0.42 up), which must be met first and on its top (vintner run 2: a tree trunk beside the cell took the aim)
        var aim = net.minecraft.world.phys.Vec3.atCenterOf(floor).add(0, 0.42, 0);
        var hit = lvl.clip(new net.minecraft.world.level.ClipContext(eye, aim.add(aim.subtract(eye).normalize().scale(0.6)), net.minecraft.world.level.ClipContext.Block.OUTLINE,
            net.minecraft.world.level.ClipContext.Fluid.NONE, null));
        return hit.getType() == net.minecraft.world.phys.HitResult.Type.BLOCK && hit.getBlockPos().equals(floor) && hit.getDirection() == net.minecraft.core.Direction.UP && eye.distanceTo(aim) < 4.4;
    }

    static void placeVanilla(StationSteps.Station spot, Item item, net.minecraft.world.level.block.Block block) {
        Playtest.queue("open ground for the " + spot.id, () -> {
            spot.pos = server(() -> {
                var lvl = level();
                BlockPos from = me().blockPosition();
                for (int r = 2; r <= 8; r++) for (int dx = -r; dx <= r; dx++) for (int dz = -r; dz <= r; dz++) {
                    if (Math.max(Math.abs(dx), Math.abs(dz)) != r) continue;
                    for (int dy = 1; dy >= -2; dy--) {
                        BlockPos c = from.offset(dx, dy, dz);
                        BlockPos between = c.offset(-Integer.signum(dx), 0, -Integer.signum(dz));   // the aim crosses it at knee height: tall grass there takes the click
                        if (StationSteps.standable(lvl, c) && BuildingGuard.protectedAt(lvl, c).isEmpty() && BuildingGuard.protectedAt(lvl, c.below()).isEmpty()
                            && lvl.getBlockState(between).isAir() && !(lvl.getBlockState(c.below()).getBlock() instanceof WorkshopBlock) && clearAim(lvl, c.below())) return c.immutable();
                    }
                }
                return null;
            });
            Playtest.log(StationSteps.tag() + " the " + spot.id + " goes at " + (spot.pos == null ? "nowhere" : spot.pos.toShortString()));
            if (spot.pos == null) Playtest.fail("no open ground near the player for the " + spot.id);
        });
        StationSteps.hold("the " + spot.id, item);
        JourneyScenario.use("place the " + spot.id + " with a right click", () -> spot.pos == null ? null : spot.pos.below(),
            () -> spot.pos != null && server(() -> level().getBlockState(spot.pos).is(block)), 600, true);
        JourneyScenario.closeScreen("close anything the placing click opened");
    }
}
