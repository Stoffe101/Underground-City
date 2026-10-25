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
import static io.github.stoffe101.undergroundcity.JourneyScenario.until;
import static io.github.stoffe101.undergroundcity.ThiefScenario.pass;
import static io.github.stoffe101.undergroundcity.ThiefScenario.scripted;
import static io.github.stoffe101.undergroundcity.ThiefScenario.shot;

/**
 * The Vintner's cellar and still through a real client (D-163, D-164; the seventh pass's Vintner stations), in Blackwater. A prepared track throughout, every prerequisite logged as SCRIPTED: a
 * Vintner at level 10 with 200 credits, the clock at 13:00 (the club's bar is staffed by day, 08:00 to 18:00, like every shop), the trip to Blackwater, a crafting table and the materials of an Oak Cask and a Pot Still (logs and slabs,
 * copper, bricks and a furnace), and six wheat for the spirit.
 *
 * <p>Everything else is real clicks: Vine and Cellar Fundamentals, Cellar Craft, Home Distilling and the Distiller's Licence learned at the corner store's public terminal; berry wine from the
 * kit's vat; the Oak Cask and the Pot Still crafted through the recipe book and placed; aged wine in the cask (fifteen minutes of play) and licensed grain spirit in the still (ten), running
 * side by side on the active clock; both sold over the club's bar; the world saved, left and opened again.
 */
final class VintnerScenario {
    private VintnerScenario() {}

    private static final class Facts {
        volatile CityBuildings.Building store, club;
        volatile DoorWalk.Spot storeDoor, clubDoor;
        volatile BlockPos publicTerminal;
    }

    private static final Facts F = new Facts();
    private static final StationSteps.Station VAT = new StationSteps.Station("fermentation_vat"), CASK = new StationSteps.Station("oak_cask"), STILL = new StationSteps.Station("pot_still");
    static final String NS = UndergroundCity.MOD_ID + ":";

    static void build() {
        ThiefScenario.tag = "VINTNER";
        Playtest.keepScreens = true;
        ThiefScenario.register(1, "the Vintner card", "1 a new citizen registers as a Vintner through the registration screen");
        Playtest.queue("prepared (scripted)", () -> {
            Playtest.command("gamerule doDaylightCycle false");
            Playtest.command("time set 7000");
            // every shop's staff work 08:00 to 18:00, the club's bar too (HouseholdPlan.where; vintner run 6 looked for it at 20:00 and found it shut)
            scripted("PREPARED: the Vintner's XP was raised to level 10 (10,125 XP) and 200 credits added; the clock set to 13:00 and stopped (the club's bar is staffed by day)");
            server(() -> {
                CitizenState state = CitizenState.get(mc().getSingleplayerServer());
                int xp = state.find(me().getUUID()).map(CitizenState.Citizen::careerXp).orElse(0);
                state.addCareerXp(me().getUUID(), Math.max(0, ProgressionRules.xpAtLevel(10) - xp));
                QShopEconomy.applyBalance(me(), QShopEconomy.balance(me()) + 200);
                return null;
            });
        });
        ThiefScenario.travel("to the middle of Blackwater", () -> ThiefScenario.centre("blackwater"), 6);
        Playtest.queue("the corner store and the club near the middle of Blackwater", () -> server(() -> {
            F.store = StationSteps.nearest("bw_store", false);
            F.club = StationSteps.nearest("bw_club", true);
            if (F.store != null) F.storeDoor = ThiefScenario.streetDoor(level(), F.store);
            if (F.club != null) F.clubDoor = ThiefScenario.streetDoor(level(), F.club);
            Playtest.log("VINTNER store " + (F.store == null ? "none" : F.store.chunkX() + "," + F.store.chunkZ()) + ", open club " + (F.club == null ? "none" : F.club.chunkX() + "," + F.club.chunkZ()));
            if (F.store == null || F.storeDoor == null) Playtest.fail("no corner store with a street door within twelve chunks of the middle of Blackwater");
            return null;
        }));
        ThiefScenario.stopIfFailed("a corner store");
        Playtest.queue("start on the street outside the corner store (scripted)", () -> {
            scripted("the player was put on the street outside the corner store's door at " + F.storeDoor.door().toShortString() + " in survival");
            ThiefScenario.standAt(F.storeDoor);
        }, () -> mc().player.position().distanceTo(F.storeDoor.stand()) < 0.8, 200);
        Playtest.wait("settle", 20);
        // the skills, at the store's public terminal; the store may be shut in the evening, so the terminal is reached however its door allows (logged)
        Playtest.queue("the store's public terminal", () -> {
            F.publicTerminal = server(() -> StationSteps.terminalIn(F.store));
            Playtest.log("VINTNER public terminal " + (F.publicTerminal == null ? "none" : F.publicTerminal.toShortString()));
            if (F.publicTerminal == null) Playtest.fail("the corner store has no public terminal");
        });
        ThiefScenario.stopIfFailed("a public terminal");
        ThiefScenario.walkOrTeleport("walk to the public terminal", () -> StationSteps.standBeside(F.publicTerminal), 0.8, 900);
        JourneyScenario.use("open the public terminal", () -> F.publicTerminal, () -> mc().screen instanceof TerminalScreen, 400);
        StationSteps.learn("vintner.fundamentals", "Vine and Cellar Fundamentals");
        StationSteps.learn("vintner.cellar_craft", "Cellar Craft");
        StationSteps.learn("vintner.still_craft", "Home Distilling");
        StationSteps.learn("vintner.distillers_licence", "Distiller's Licence");
        Playtest.queue("four skills", () -> {
            CitizenState.Citizen c = ThiefScenario.citizen();
            if (c == null || !c.skills().containsAll(Set.of("vintner.fundamentals", "vintner.cellar_craft", "vintner.still_craft", "vintner.distillers_licence"))) Playtest.fail("the skills were not learned");
            else pass("2 four skills learned with real clicks at the corner store's public terminal: Fundamentals, Cellar Craft, Home Distilling and the Distiller's Licence (7 of level 10's 9 points)");
        });
        shot("1-skills");
        JourneyScenario.closeScreen("close the terminal");
        ThiefScenario.walkOrTeleport("back out to the street", () -> F.storeDoor.stand(), 0.8, 900);
        StationSteps.place(VAT, UndergroundCity.WORKSHOP_ITEMS.get("fermentation_vat").get());
        ThiefScenario.stopIfFailed("placing the vat");
        StationSteps.open(VAT, "berry wine");
        StationSteps.batch(VAT, "berry_wine", NS + "berry_wine", 3, "3 the kit's vat ferments berries and sugar into three bottles of berry wine (six minutes of play)");
        JourneyScenario.closeScreen("close the vat");
        Playtest.queue("a crafting table and the cask's and still's materials (scripted)", () -> {
            scripted("PREPARED: a crafting table, four oak logs, four oak slabs, three copper ingots, three bricks, a furnace and six wheat were given (the yard, the hardware desk and a farm sell them)");
            server(() -> {
                var inv = me().getInventory();
                inv.add(new ItemStack(Items.CRAFTING_TABLE));
                inv.add(new ItemStack(Items.OAK_LOG, 4));
                inv.add(new ItemStack(Items.OAK_SLAB, 4));
                inv.add(new ItemStack(Items.COPPER_INGOT, 3));
                inv.add(new ItemStack(Items.BRICKS, 3));
                inv.add(new ItemStack(Items.FURNACE));
                inv.add(new ItemStack(Items.WHEAT, 6));
                return null;
            });
        });
        final StationSteps.Station table = new StationSteps.Station("crafting_table");
        FarmerScenario.placeVanilla(table, Items.CRAFTING_TABLE, net.minecraft.world.level.block.Blocks.CRAFTING_TABLE);
        Item caskItem = UndergroundCity.WORKSHOP_ITEMS.get("oak_cask").get(), stillItem = UndergroundCity.WORKSHOP_ITEMS.get("pot_still").get();
        StationSteps.craftWithBook(() -> table.pos, caskItem, "4 an Oak Cask crafted through the recipe book (D-175)");
        StationSteps.craftWithBook(() -> table.pos, stillItem, "5 a Pot Still crafted through the recipe book");
        StationSteps.place(CASK, caskItem);
        StationSteps.place(STILL, stillItem);
        // the cask's aged wine and the still's spirit, started one after the other and left to run side by side
        start(CASK, "aged_wine", "6 the Oak Cask takes three bottles of berry wine to age (fifteen minutes of play), open to a Vintner with Cellar Craft");
        start(STILL, "grain_spirit", "7 the Pot Still takes six wheat, two sugar and two bottles for licensed grain spirit (ten minutes of play), open with the Distiller's Licence");
        until("the still is done", () -> server(() -> !level().getBlockState(STILL.pos).getValue(WorkshopBlock.WORKING)), 20 * 60 * 14);
        take(STILL, NS + "grain_spirit", 2, "8 after ten minutes of play, Take hands over two bottles of grain spirit");
        until("the cask is done", () -> server(() -> !level().getBlockState(CASK.pos).getValue(WorkshopBlock.WORKING)), 20 * 60 * 10);
        take(CASK, NS + "aged_wine", 2, "9 after fifteen minutes of play, Take hands over two bottles of aged wine");
        Playtest.queue("the grades", () -> Playtest.log("VINTNER aged wine " + StationSteps.grades(UndergroundCity.item("aged_wine")) + ", grain spirit " + StationSteps.grades(UndergroundCity.item("grain_spirit"))));
        shot("2-cask-and-still");
        Playtest.queue("is the club open", () -> { if (F.club == null || F.clubDoor == null) Playtest.fail("no open club near the middle of Blackwater"); });
        ThiefScenario.stopIfFailed("an open club");
        StationSteps.toCounter("the club's bar", () -> F.club, () -> F.clubDoor);
        StationSteps.sell(NS + "shops/bar_aged_blackwater", UndergroundCity.item("aged_wine"), 2 * 36, "10 the club's bar buys the aged wine (a real click on Sell)");
        StationSteps.sell(NS + "shops/bar_spirit_blackwater", UndergroundCity.item("grain_spirit"), 2 * 17, "11 and the grain spirit");
        shot("3-bar");
        JourneyScenario.closeScreen("close the bar");
        StationSteps.reconnect(VintnerScenario::state, (before, after) -> {
            if (!before.equals(after)) Playtest.fail("something changed across the save and reopen");
            else pass("12 saved, left to the title screen and opened again: wallet, Vintner XP, skills, the vat, cask and still where they stood, nothing doubled");
        });
        Playtest.queue("summary", ThiefScenario::summary);
    }

    /** Opens a station, starts one batch of a recipe with real clicks and closes it again (the batch runs on). */
    private static void start(StationSteps.Station station, String recipe, String passText) {
        StationSteps.open(station, "start " + recipe);
        StationSteps.logRecipes(station);
        final int[] wheel = {0};
        Playtest.queue("turn the wheel until the " + recipe + " row is in view", () -> wheel[0] = 0, () -> {
            if (!(mc().screen instanceof WorkshopScreen s) || s.rowCentre(recipe) != null) return true;
            if (wheel[0]++ % 6 == 0) Playtest.click(5);
            return wheel[0] > 90;
        }, 120);
        JourneyScenario.clickAt("the " + recipe + " row", () -> mc().screen instanceof WorkshopScreen s ? s.rowCentre(recipe) : null);
        Playtest.wait("select", 4);
        JourneyScenario.click("Start", true);
        until(recipe + " starts", () -> mc().screen instanceof WorkshopScreen s && !s.view().running().isEmpty(), 100);
        Playtest.queue(recipe + " is running", () -> {
            if (!(mc().screen instanceof WorkshopScreen s) || s.view().running().isEmpty()) { Playtest.fail(recipe + " did not start"); return; }
            Playtest.log("VINTNER started " + s.view().running() + ": " + s.view().secondsLeft() + " s left");
            pass(passText);
        });
        JourneyScenario.closeScreen("close the " + station.id);
    }

    private static void take(StationSteps.Station station, String output, int expect, String passText) {
        Item out = net.minecraftforge.registries.ForgeRegistries.ITEMS.getValue(net.minecraft.resources.ResourceLocation.parse(output));
        final int[] before = {0};
        Playtest.queue("before taking " + output, () -> before[0] = JourneyScenario.count(out));
        StationSteps.open(station, "take " + output);
        until("the screen shows the batch ready", () -> mc().screen instanceof WorkshopScreen s && s.view().readyCount() > 0, 400);
        JourneyScenario.click("Take", true);
        until(output + " is taken", () -> JourneyScenario.count(out) >= before[0] + expect, 100);
        Playtest.queue(output + " taken", () -> {
            int got = JourneyScenario.count(out) - before[0];
            if (got < expect) Playtest.fail("Take handed over " + got + " of " + expect + " " + output);
            else pass(passText);
        });
        JourneyScenario.closeScreen("close the " + station.id);
    }

    private static Object state() {
        CitizenState.Citizen c = ThiefScenario.citizen();
        return List.of(JourneyScenario.wallet(), c == null ? -1 : c.careerXp(), c == null ? Set.of() : Set.copyOf(c.skills()),
            server(() -> level().getBlockState(VAT.pos).getBlock() instanceof WorkshopBlock), server(() -> level().getBlockState(CASK.pos).getBlock() instanceof WorkshopBlock),
            server(() -> level().getBlockState(STILL.pos).getBlock() instanceof WorkshopBlock));
    }
}
