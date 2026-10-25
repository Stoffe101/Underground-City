package io.github.stoffe101.undergroundcity;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.registries.ForgeRegistries;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static io.github.stoffe101.undergroundcity.JourneyScenario.click;
import static io.github.stoffe101.undergroundcity.JourneyScenario.clickAt;
import static io.github.stoffe101.undergroundcity.JourneyScenario.closeScreen;
import static io.github.stoffe101.undergroundcity.JourneyScenario.count;
import static io.github.stoffe101.undergroundcity.JourneyScenario.hold;
import static io.github.stoffe101.undergroundcity.JourneyScenario.level;
import static io.github.stoffe101.undergroundcity.JourneyScenario.look;
import static io.github.stoffe101.undergroundcity.JourneyScenario.mc;
import static io.github.stoffe101.undergroundcity.JourneyScenario.me;
import static io.github.stoffe101.undergroundcity.JourneyScenario.server;
import static io.github.stoffe101.undergroundcity.JourneyScenario.until;
import static io.github.stoffe101.undergroundcity.JourneyScenario.use;
import static io.github.stoffe101.undergroundcity.JourneyScenario.wallet;
import static io.github.stoffe101.undergroundcity.ThiefScenario.pass;
import static io.github.stoffe101.undergroundcity.ThiefScenario.scripted;
import static io.github.stoffe101.undergroundcity.ThiefScenario.shot;
import static io.github.stoffe101.undergroundcity.ThiefScenario.stopIfFailed;

/**
 * The Gunsmith's trade to a real TaCZ firearm through a real client (D-163, D-168; {@code -PucPlaytest=gunsmith} with the {@code firearms} set): a new citizen registers as a Gunsmith, places the
 * Gunsmith's Bench and TaCZ's Gun Smith Table from the starter kit with real right clicks on open ground in Ironhaven, starts a Firearm Parts Kit at the bench, builds a Glock 17 from the kit
 * the starter kit carries on TaCZ's own table screen (the tab, the kit recipe and Craft, real clicks), takes the second kit off the bench when its four minutes of play are up, builds a second
 * pistol from it, and delivers both registered pistols to the city armoury's order at a terminal. The serials, the maker, the XP and the wallet are read back from the server.
 *
 * <p>Scripted, each logged as SCRIPTED: the clock, the travel to Ironhaven, the walk down to the open ground when it is above the street, the Smith's parts for the bench's kit (a Smith makes
 * them at a Machinist's Bench), four iron ingots for the second gun (the exchanges sell iron), and the career XP of a level-4 Gunsmith with Licensed Repairs for the armoury's order (four
 * levels of play). The scenario reads TaCZ's screen (which tab and which recipe are selected) to know where to click; every selection is a real click.
 */
final class GunsmithScenario {
    private GunsmithScenario() {}

    static final ResourceLocation KIT_GLOCK = ResourceLocation.fromNamespaceAndPath(UndergroundCity.MOD_ID, "tacz/glock_17");
    static final String ARMOURY = "underground_city:registered_sidearms";
    private static final String TABLE_SCREEN = "com.tacz.guns.client.gui.GunSmithTableScreen";

    private static final class Facts {
        volatile BlockPos stand, bench, table, terminal;
        volatile int xpBefore;
        volatile double walletBefore;
        final List<String> serials = new ArrayList<>();
    }
    private static final Facts F = new Facts();

    static void build() {
        ThiefScenario.tag = "GUNSMITH";
        Playtest.keepScreens = true;
        ThiefScenario.register(3, "the Gunsmith card", "1 a new citizen registers as a Gunsmith through the registration screen");
        Playtest.queue("the kit", () -> {
            Item table = ForgeRegistries.ITEMS.getValue(ResourceLocation.fromNamespaceAndPath("tacz", "gun_smith_table"));
            int benches = count(UndergroundCity.WORKSHOP_ITEMS.get("gunsmith_bench").get()), tables = table == null ? 0 : count(table);
            int kits = count(UndergroundCity.item("firearm_parts_kit")), iron = count(Items.IRON_INGOT);
            Playtest.log("GUNSMITH kit: bench " + benches + ", Gun Smith Table " + tables + ", parts kits " + kits + ", iron " + iron + ", terminal " + count(UndergroundCity.TERMINAL_ITEM.get()));
            if (benches != 1 || tables != 1 || kits != 1 || iron < 4) Playtest.fail("the Gunsmith's kit does not hold a bench, TaCZ's table, a parts kit and four iron (is the firearms set loaded?)");
            else pass("2 a new Gunsmith starts with a Gunsmith's Bench, TaCZ's Gun Smith Table, a Firearm Parts Kit, four iron and a Glock 17 blueprint");
        });
        stopIfFailed("the kit");
        Playtest.queue("13:00 with the daylight cycle stopped", () -> {
            Playtest.command("gamerule doDaylightCycle false");
            Playtest.command("time set 7000");
            scripted("the time of day was set to 13:00 and the daylight cycle stopped");
        });
        ThiefScenario.travel("to the middle of Ironhaven", () -> ThiefScenario.centre("ironhaven"), 5);
        Playtest.queue("survival, and open ground nearby with room for a bench and a table", () -> {
            Playtest.command("gamemode survival");
            BlockPos[] found = server(() -> workshopGround(me().blockPosition(), 48));
            if (found == null) { Playtest.fail("no open ground near the middle of Ironhaven with room for a bench and a two-block table"); return; }
            F.stand = found[0]; F.bench = found[1]; F.table = found[2];
            Playtest.log("GUNSMITH stand at " + F.stand.toShortString() + ", the bench at " + F.bench.toShortString() + ", the table at " + F.table.toShortString());
        });
        stopIfFailed("open ground");
        Playtest.queue("down to the open ground (scripted)", () -> {
            scripted("the player was put on open ground in Ironhaven at " + F.stand.toShortString());
            server(() -> { me().teleportTo(F.stand.getX() + 0.5, F.stand.getY(), F.stand.getZ() + 0.5); me().setDeltaMovement(Vec3.ZERO); return null; });
        }, () -> mc().player.blockPosition().distManhattan(F.stand) <= 1, 400);
        until("the ground has arrived", Scenarios::groundBelowPlayer, 2400);
        Playtest.wait("settle", 20);
        Playtest.queue("career XP before", () -> F.xpBefore = xp());

        // the bench
        Item bench = UndergroundCity.WORKSHOP_ITEMS.get("gunsmith_bench").get();
        hold("the Gunsmith's Bench", bench);
        use("place the bench with a right click on the ground", () -> F.bench.below(), () -> server(() -> level().getBlockState(F.bench).getBlock() instanceof WorkshopBlock), 600, true);
        Playtest.queue("the bench stands", () -> {
            boolean placed = server(() -> level().getBlockState(F.bench).getBlock() instanceof WorkshopBlock wb && wb.station.equals("gunsmith_bench"));
            if (!placed) Playtest.fail("the Gunsmith's Bench was not placed");
            else pass("3 the Gunsmith's Bench from the kit is placed with a real right click on open ground no building owns");
        });
        stopIfFailed("the bench");
        // the placing click can open the new bench at once (gunsmith run 3): close it, so the screen opened below shows the parts given next
        closeScreen("close anything the placing click opened");
        Playtest.queue("a Smith's parts for a kit (scripted)", () -> {
            server(() -> {
                var inv = me().getInventory();
                inv.add(new ItemStack(UndergroundCity.item("springs"), 2));
                inv.add(new ItemStack(UndergroundCity.item("steel_bracket"), 1));
                inv.add(new ItemStack(UndergroundCity.item("fasteners"), 4));
                inv.add(new ItemStack(UndergroundCity.item("machined_parts"), 1));
                return null;
            });
            scripted("two springs, a steel bracket, four fasteners and machined parts were given (a Smith makes them at a Machinist's Bench)");
        });
        use("open the bench", () -> F.bench, () -> mc().screen instanceof WorkshopScreen, 600);
        Playtest.wait("draw", 12);
        Playtest.queue("the bench's recipes", () -> {
            if (!(mc().screen instanceof WorkshopScreen s)) { Playtest.fail("the bench's screen did not open"); return; }
            var v = s.view();
            Playtest.log("GUNSMITH bench: " + v.title() + " tier " + v.tier() + "/" + v.maxTier() + "; " + v.rows().stream().map(r -> r.id() + (r.allowed() ? "" : " (" + r.reason() + ")")).toList());
        });
        clickAt("the Firearm parts kit row", () -> mc().screen instanceof WorkshopScreen s ? s.rowCentre("firearm_parts_kit") : null);
        Playtest.queue("the kit is selected", () -> {
            if (!(mc().screen instanceof WorkshopScreen s) || !s.selectedId().equals("firearm_parts_kit")) Playtest.fail("a click on the row did not select the Firearm parts kit");
        });
        shot("1-bench");
        click("Start", true);
        until("the batch starts", () -> mc().screen instanceof WorkshopScreen s && !s.view().running().isEmpty(), 100);
        final long[] started = {0};
        Playtest.queue("the kit is at work", () -> {
            if (!(mc().screen instanceof WorkshopScreen s) || s.view().running().isEmpty()) { Playtest.fail("Start did not set the kit to work"); return; }
            started[0] = server(() -> LockService.clock(mc().getSingleplayerServer()));
            Playtest.log("GUNSMITH bench started: " + s.view().running() + ", " + s.view().secondsLeft() + " s left");
            pass("4 a real click on the row and on Start sets a Firearm Parts Kit to work on the bench, four minutes of play at tier 1");
        });
        closeScreen("close the bench");
        stopIfFailed("the bench's batch");

        // the table, while the bench works
        Item table = ForgeRegistries.ITEMS.getValue(ResourceLocation.fromNamespaceAndPath("tacz", "gun_smith_table"));
        hold("the Gun Smith Table", table);
        use("place the table with a right click on the ground", () -> F.table.below(), GunsmithScenario::tableStands, 600, true);
        Playtest.queue("the table stands", () -> {
            if (!tableStands()) Playtest.fail("TaCZ's Gun Smith Table was not placed");
            else pass("5 TaCZ's Gun Smith Table from the kit is placed with a real right click beside the bench");
        });
        stopIfFailed("the table");
        closeScreen("close anything the placing click opened");
        learnBlueprint();
        stopIfFailed("the blueprint");
        build("the first pistol, from the kit the starter kit carries", "7");
        Playtest.queue("the first pistol", () -> {
            int gained = xp() - F.xpBefore;
            Playtest.log("GUNSMITH career XP after the first build: +" + gained + " (the bench's batch pays when it finishes)");
            if (gained < GunsmithBuilds.XP_REGISTERED) Playtest.fail("the kit build paid " + gained + " career XP, less than the " + GunsmithBuilds.XP_REGISTERED + " a registered build pays");
        });
        stopIfFailed("the first build");

        // the bench's kit, after its four minutes of play
        until("the bench's kit is done (four minutes of play)", () -> server(() -> !level().getBlockState(F.bench).getValue(WorkshopBlock.WORKING)), 20 * 60 * 6);
        Playtest.queue("the time it took", () -> {
            long now = server(() -> LockService.clock(mc().getSingleplayerServer()));
            Playtest.log(String.format(Locale.ROOT, "GUNSMITH the bench's kit finished after %.1f minutes of active play", (now - started[0]) / 1200.0));
        });
        use("open the bench again", () -> F.bench, () -> mc().screen instanceof WorkshopScreen, 600);
        Playtest.wait("draw", 12);
        click("Take", true);
        until("the kit is in the inventory", () -> count(UndergroundCity.item("firearm_parts_kit")) >= 1, 100);
        Playtest.queue("the second kit", () -> {
            int kits = count(UndergroundCity.item("firearm_parts_kit"));
            Playtest.log("GUNSMITH took from the bench: parts kits now " + kits);
            if (kits < 1) Playtest.fail("Take did not hand over the bench's parts kit");
            else pass("8 after four minutes of play, Take (a real click) hands over the Firearm Parts Kit the bench made");
        });
        closeScreen("close the bench");
        stopIfFailed("the bench's kit");
        Playtest.queue("four iron ingots for the second gun (scripted)", () -> {
            server(() -> { me().getInventory().add(new ItemStack(Items.IRON_INGOT, 4)); return null; });
            scripted("four iron ingots were given (the regional exchanges sell iron; the starter kit's four went into the first gun)");
        });
        build("the second pistol, from the bench's kit", "9");
        Playtest.queue("two registered pistols", () -> {
            List<String> marks = server(GunsmithScenario::marks);
            Playtest.log("GUNSMITH registered pistols in the inventory: " + marks);
            if (marks.size() < 2 || marks.stream().distinct().count() < marks.size()) Playtest.fail("two kit builds did not leave two pistols with different serials");
        });
        stopIfFailed("the second build");

        // the armoury
        Playtest.queue("a level-4 Gunsmith with Licensed Repairs (scripted)", () -> {
            server(() -> {
                CitizenState state = CitizenState.get(me().server);
                int need = ProgressionRules.xpAtLevel(4) - state.find(me().getUUID()).orElseThrow().careerXp();
                if (need > 0) state.addCareerXp(me().getUUID(), need);
                state.unlockSkill(me().getUUID(), "gunsmith.licensed_repairs");
                return null;
            });
            scripted("career XP up to level 4 and the Licensed Repairs skill were given (the armoury's order asks for both; four levels of play)");
            F.walletBefore = wallet();
        });
        hold("the terminal", UndergroundCity.TERMINAL_ITEM.get());
        // on the cell the ground search found free beside the player (gunsmith run 5: the bench and the table left the generic spot search nowhere within its reach)
        Playtest.queue("where the terminal goes", () -> F.terminal = F.stand.south().east());
        use("place the terminal with a right click on the ground", () -> F.terminal.below(), () -> server(() -> level().getBlockState(F.terminal).is(UndergroundCity.TERMINAL.get())), 600, true);
        Playtest.queue("the terminal stands", () -> { if (!server(() -> level().getBlockState(F.terminal).is(UndergroundCity.TERMINAL.get()))) Playtest.fail("the terminal could not be placed"); });
        closeScreen("close anything the placing click opened");
        stopIfFailed("the terminal");
        use("open the terminal", () -> F.terminal, () -> mc().screen instanceof TerminalScreen, 600);
        click("Work", true);
        Playtest.wait("draw", 12);
        final int[] t = {0};
        Playtest.queue("turn the wheel until the armoury's order is in view", () -> { t[0] = 0; Playtest.focusWindow(); }, () -> {
            if (!(mc().screen instanceof TerminalScreen screen)) return true;
            int i = JourneyScenario.jobIndex(ARMOURY);
            if (i >= 0 && screen.jobCentre(i) != null) return true;
            if (t[0]++ % 6 == 0) Playtest.click(5);
            return t[0] > 180;
        }, 200);
        clickAt("Registered sidearms for the city armoury", () -> { int i = JourneyScenario.jobIndex(ARMOURY); return i < 0 || !(mc().screen instanceof TerminalScreen s) ? null : s.jobCentre(i); });
        shot("4-armoury-order");
        click("Accept order", true);
        until("accepted", GunsmithScenario::orderTaken, 200);
        click("Deliver", true);
        until("delivered", () -> !orderTaken(), 200);
        Playtest.queue("paid", () -> {
            double paid = wallet() - F.walletBefore;
            List<String> left = server(GunsmithScenario::marks);
            Playtest.log(String.format(Locale.ROOT, "GUNSMITH the armoury's order: wallet %.0f -> %.0f (+%.0f), registered pistols left %d, career XP %d", F.walletBefore, wallet(), paid, left.size(), xp()));
            if (orderTaken() || paid < 1 || !left.isEmpty()) Playtest.fail("the armoury's order was not delivered and paid with the two registered pistols");
            else pass("10 Accept and Deliver (real clicks) hand both registered pistols to the city armoury for " + Math.round(paid) + " credits");
        });
        shot("5-delivered");
        closeScreen("close the terminal");
        Playtest.queue("summary", ThiefScenario::summary);
    }

    // ------------------------------------------------------------------------------------------------ the unmarked route (seventh pass)

    static final ResourceLocation KIT_GLOCK_UNMARKED = ResourceLocation.fromNamespaceAndPath(UndergroundCity.MOD_ID, "tacz/glock_17_unmarked");
    static final String DRAY_PARTS = "underground_city:dray_parts_run", DRAY_PISTOLS = "underground_city:dray_clean_pistols";

    private static final class Unmarked {
        volatile CityBuildings.Building yard, store;
        volatile DoorWalk.Spot yardDoor, storeDoor;
        volatile BlockPos storeTerminal;
        volatile double walletBefore;
    }
    private static final Unmarked U = new Unmarked();

    /**
     * The Gunsmith's unmarked route through a real client ({@code -PucPlaytest=unmarked}, the {@code firearms} set): a prepared Gunsmith (level 8, 450 credits: logged) learns Fundamentals, Licensed
     * Repairs and Unregistered Builds on the kit's own terminal, buys refined alloy, machined parts and iron on Ironhaven's exchange and springs, brackets and fasteners over the Builders' Yard's
     * counter (a solo Gunsmith needs no Smith), crafts unstamped plate through the recipe book, fits the bench's second tier, makes a parts kit and two unmarked parts kits, builds two unmarked
     * Glock 17s on TaCZ's own table (the list shows "Registered" and "Unmarked" and no plain iron recipe), checks they carry no serial and count as restricted at a checkpoint, saves and reopens
     * with them, and in Blackwater does a parts run for Dray (his trust) and hands him the two pistols. Scripted, each logged: the level and credits, the clock, the travel between the cities, the
     * start on open ground, a crafting table, and the blueprint read.
     */
    static void buildUnmarked() {
        ThiefScenario.tag = "UNMARKED";
        Playtest.keepScreens = true;
        ThiefScenario.register(3, "the Gunsmith card", "1 a new citizen registers as a Gunsmith through the registration screen");
        Playtest.queue("prepared (scripted)", () -> {
            Playtest.command("gamerule doDaylightCycle false");
            Playtest.command("time set 7000");
            scripted("PREPARED: the Gunsmith's XP was raised to level 8 (6,125 XP) and 450 credits added; the clock set to 13:00 and stopped; a crafting table given");
            server(() -> {
                CitizenState state = CitizenState.get(me().server);
                int need = ProgressionRules.xpAtLevel(8) - state.find(me().getUUID()).orElseThrow().careerXp();
                if (need > 0) state.addCareerXp(me().getUUID(), need);
                QShopEconomy.applyBalance(me(), QShopEconomy.balance(me()) + 450);
                me().getInventory().add(new ItemStack(Items.CRAFTING_TABLE));
                return null;
            });
        });
        ThiefScenario.travel("to the middle of Ironhaven", () -> ThiefScenario.centre("ironhaven"), 6);
        Playtest.queue("survival, and open ground nearby with room for a bench and a table", () -> {
            Playtest.command("gamemode survival");
            BlockPos[] found = server(() -> workshopGround(me().blockPosition(), 48));
            if (found == null) { Playtest.fail("no open ground near the middle of Ironhaven with room for a bench and a two-block table"); return; }
            F.stand = found[0]; F.bench = found[1]; F.table = found[2];
            U.yard = server(() -> StationSteps.nearest("ih_warehouse", true));
            if (U.yard != null) U.yardDoor = server(() -> ThiefScenario.streetDoor(level(), U.yard));
            Playtest.log("UNMARKED stand at " + F.stand.toShortString() + ", yard " + (U.yard == null ? "none" : U.yard.chunkX() + "," + U.yard.chunkZ()));
        });
        stopIfFailed("open ground");
        Playtest.queue("down to the open ground (scripted)", () -> {
            scripted("the player was put on open ground in Ironhaven at " + F.stand.toShortString());
            server(() -> { me().teleportTo(F.stand.getX() + 0.5, F.stand.getY(), F.stand.getZ() + 0.5); me().setDeltaMovement(Vec3.ZERO); return null; });
        }, () -> mc().player.blockPosition().distManhattan(F.stand) <= 1, 400);
        until("the ground has arrived", Scenarios::groundBelowPlayer, 2400);
        Playtest.wait("settle", 20);
        // the kit's terminal, on the cell beside the stand: the skills and the exchange
        hold("the terminal", UndergroundCity.TERMINAL_ITEM.get());
        Playtest.queue("where the terminal goes", () -> F.terminal = F.stand.south().east());
        use("place the terminal with a right click on the ground", () -> F.terminal.below(), () -> server(() -> level().getBlockState(F.terminal).is(UndergroundCity.TERMINAL.get())), 600, true);
        closeScreen("close anything the placing click opened");
        use("open the terminal", () -> F.terminal, () -> mc().screen instanceof TerminalScreen, 600);
        StationSteps.learn("gunsmith.fundamentals", "Gunsmith Fundamentals");
        StationSteps.learn("gunsmith.licensed_repairs", "Licensed Repairs");
        StationSteps.learn("gunsmith.unregistered_builds", "Unregistered Builds");
        Playtest.queue("three skills", () -> {
            CitizenState.Citizen c = ThiefScenario.citizen();
            if (c == null || !c.skills().containsAll(java.util.Set.of("gunsmith.fundamentals", "gunsmith.licensed_repairs", "gunsmith.unregistered_builds"))) Playtest.fail("the skills were not learned");
            else pass("2 Fundamentals, Licensed Repairs and Unregistered Builds learned with real clicks on the kit's terminal (5 of level 8's 7 points)");
        });
        click("TRADE", true);
        Playtest.wait("draw", 10);
        StationSteps.buy("refined alloy", "underground_city:refined_alloy", UndergroundCity.item("refined_alloy"), 2);
        StationSteps.buy("machined parts", "underground_city:machined_parts", UndergroundCity.item("machined_parts"), 6);
        StationSteps.buy("iron", "underground_city:materials/iron_ingot_ironhaven", Items.IRON_INGOT, 6);
        Playtest.queue("bought on the exchange", () -> {
            int alloy = count(UndergroundCity.item("refined_alloy")), parts = count(UndergroundCity.item("machined_parts")), iron = count(Items.IRON_INGOT);
            Playtest.log("UNMARKED exchange: alloy " + alloy + ", machined parts " + parts + ", iron " + iron + ", wallet " + wallet());
            if (alloy < 2 || parts < 6 || iron < 10) Playtest.fail("the exchange did not sell the alloy, the machined parts and the iron");
            else pass("3 refined alloy, machined parts and iron bought on Ironhaven's exchange with real clicks");
        });
        closeScreen("close the terminal");
        Playtest.queue("is the yard open", () -> { if (U.yard == null || U.yardDoor == null) Playtest.fail("no open Builders' Yard near the middle of Ironhaven"); });
        stopIfFailed("an open yard");
        StationSteps.toCounter("the Builders' Yard", () -> U.yard, () -> U.yardDoor);
        StationSteps.buy("brackets", "underground_city:shops/yard_brackets_ironhaven", UndergroundCity.item("steel_bracket"), 5);
        StationSteps.buy("springs", "underground_city:shops/yard_springs_ironhaven", UndergroundCity.item("springs"), 4);
        StationSteps.buy("fasteners", "underground_city:shops/yard_fasteners_ironhaven", UndergroundCity.item("fasteners"), 4);
        Playtest.queue("bought at the yard", () -> {
            int brackets = count(UndergroundCity.item("steel_bracket")), springs = count(UndergroundCity.item("springs")), fasteners = count(UndergroundCity.item("fasteners"));
            Playtest.log("UNMARKED yard: brackets " + brackets + ", springs " + springs + ", fasteners " + fasteners + ", wallet " + wallet());
            if (brackets < 5 || springs < 4 || fasteners < 4) Playtest.fail("the yard did not sell the parts");
            else pass("4 a solo Gunsmith buys springs, brackets and fasteners over the Builders' Yard's counter with real clicks: no Smith needed (where a Smith's sales land, as in the Smith run)");
        });
        shot("u1-yard");
        closeScreen("close the counter");
        ThiefScenario.walkOrTeleport("back to the open ground", () -> Vec3.atBottomCenterOf(F.stand), 0.8, 1500);
        final StationSteps.Station craft = new StationSteps.Station("crafting_table");
        FarmerScenario.placeVanilla(craft, Items.CRAFTING_TABLE, net.minecraft.world.level.block.Blocks.CRAFTING_TABLE);
        StationSteps.craftWithBook(() -> craft.pos, UndergroundCity.item("unstamped_plate"), "5 unstamped plate crafted through the recipe book (two alloy, machined parts and an ingot make two)");
        // the bench, its second tier, a parts kit and two unmarked kits
        Item benchItem = UndergroundCity.WORKSHOP_ITEMS.get("gunsmith_bench").get();
        hold("the Gunsmith's Bench", benchItem);
        use("place the bench with a right click on the ground", () -> F.bench.below(), () -> server(() -> level().getBlockState(F.bench).getBlock() instanceof WorkshopBlock), 600, true);
        closeScreen("close anything the placing click opened");
        final StationSteps.Station bench = new StationSteps.Station("gunsmith_bench");
        Playtest.queue("the bench", () -> bench.pos = F.bench);
        StationSteps.open(bench, "fit the second tier");
        StationSteps.logRecipes(bench);
        StationSteps.fitTier(bench, 2, "6 Fit tier 2 (a real click) uses four brackets and two springs: the Vice and Jigs open the unmarked kit");
        StationSteps.batch(bench, "firearm_parts_kit", "underground_city:firearm_parts_kit", 1, "7 a Firearm Parts Kit from the yard's springs, bracket and fasteners and the exchange's machined part");
        StationSteps.batch(bench, "unmarked_parts_kit", "underground_city:unmarked_parts_kit", 1, "8 an Unmarked Parts Kit: a parts kit rebuilt around unstamped plate");
        StationSteps.batch(bench, "unmarked_parts_kit", "underground_city:unmarked_parts_kit", 1, "9 a second Unmarked Parts Kit from the starter kit's parts kit");
        closeScreen("close the bench");
        Item tableItem = ForgeRegistries.ITEMS.getValue(ResourceLocation.fromNamespaceAndPath("tacz", "gun_smith_table"));
        hold("the Gun Smith Table", tableItem);
        use("place the table with a right click on the ground", () -> F.table.below(), GunsmithScenario::tableStands, 600, true);
        closeScreen("close anything the placing click opened");
        learnBlueprint();
        stopIfFailed("the blueprint");
        buildUnmarkedGun("the first unmarked pistol", "10");
        buildUnmarkedGun("the second unmarked pistol", "11");
        Playtest.queue("no serial, restricted", () -> {
            List<String> marks = server(GunsmithScenario::unmarkedGuns);
            String category = server(() -> {
                for (ItemStack st : me().getInventory().items) if (GunsmithBuilds.UNMARKED.equals(GunsmithBuilds.provenance(st))) return ContrabandCatalog.INSTANCE.classify(st);
                return null;
            });
            var verdict = server(() -> CheckpointService.violations(CitizenState.get(me().server).find(me().getUUID()).orElse(null), CityLaws.INSTANCE.find("blackwater"),
                me().getInventory().items.stream().filter(st -> GunsmithBuilds.UNMARKED.equals(GunsmithBuilds.provenance(st))).toList(), me()));
            Playtest.log("UNMARKED pistols " + marks + "; category " + category + "; a Blackwater search would find " + verdict);
            if (marks.size() < 2 || !"firearms_restricted".equals(category) || verdict.isEmpty()) Playtest.fail("the unmarked pistols carry a serial, or a checkpoint would not take them");
            else pass("12 two unmarked Glock 17s with no serial: restricted weapons at a checkpoint (a Blackwater search would find " + verdict + ")");
        });
        GunsmithScenario.reopenWithGuns();
        // Blackwater: the corner store's public terminal, a parts run for Dray's trust, then the pistols
        ThiefScenario.travel("to the middle of Blackwater", () -> ThiefScenario.centre("blackwater"), 6);
        Playtest.queue("the corner store", () -> {
            U.store = server(() -> StationSteps.nearest("bw_store", false));
            if (U.store != null) { U.storeDoor = server(() -> ThiefScenario.streetDoor(level(), U.store)); U.storeTerminal = server(() -> StationSteps.terminalIn(U.store)); }
            Playtest.log("UNMARKED corner store " + (U.store == null ? "none" : U.store.chunkX() + "," + U.store.chunkZ()) + ", terminal " + (U.storeTerminal == null ? "none" : U.storeTerminal.toShortString()));
            if (U.storeTerminal == null) Playtest.fail("no corner store with a public terminal near the middle of Blackwater");
        });
        stopIfFailed("a public terminal in Blackwater");
        Playtest.queue("start outside the corner store (scripted)", () -> { scripted("the player was put outside the corner store's door"); ThiefScenario.standAt(U.storeDoor); }, () -> mc().player.position().distanceTo(U.storeDoor.stand()) < 0.8, 200);
        StationSteps.enter("the corner store", () -> U.storeDoor);
        ThiefScenario.walkOrTeleport("walk to the public terminal", () -> StationSteps.standBeside(U.storeTerminal), 0.8, 900);
        use("open the public terminal", () -> U.storeTerminal, () -> mc().screen instanceof TerminalScreen, 600);
        click("TRADE", true);
        Playtest.wait("draw", 10);
        StationSteps.buy("optical components", "underground_city:optical_components", UndergroundCity.item("optical_components"), 2);
        deliver("Dray's parts run", DRAY_PARTS, "13 Dray's parts run (four machined parts, two optical components) accepted and delivered at the corner store's public terminal: his trust 1");
        Playtest.queue("Dray's trust", () -> {
            int trust = server(() -> WorkOrderLedger.get(me().server).trust(me().getUUID(), "dray"));
            Playtest.log("UNMARKED Dray's trust " + trust);
            if (trust < 1) Playtest.fail("the parts run did not raise Dray's trust");
        });
        Playtest.queue("before the pistols", () -> U.walletBefore = wallet());
        deliver("pistols with no numbers for Dray", DRAY_PISTOLS, "14 Dray's order for two pistols with no numbers accepted and delivered with real clicks");
        Playtest.queue("paid for the pistols", () -> {
            double paid = wallet() - U.walletBefore;
            int left = server(GunsmithScenario::unmarkedGuns).size();
            Playtest.log(String.format(Locale.ROOT, "UNMARKED Dray paid %.0f; unmarked pistols left %d; Dray's trust %d", paid, left, server(() -> WorkOrderLedger.get(me().server).trust(me().getUUID(), "dray"))));
            if (paid < 1 || left != 0) Playtest.fail("Dray did not take and pay for the two pistols");
        });
        shot("u5-dray");
        closeScreen("close the terminal");
        Playtest.queue("summary", ThiefScenario::summary);
    }

    /** Accept and Deliver an order at the open terminal, with real clicks; passes with {@code passText} when the order is gone and something was paid. */
    private static void deliver(String label, String order, String passText) {
        click("Work", true);
        Playtest.wait("draw", 12);
        // Dray's orders are underground ones: the Work page lists one view at a time
        click("Underground", true);
        Playtest.wait("draw", 8);
        final int[] t = {0};
        Playtest.queue("turn the wheel until " + label + " is in view", () -> { t[0] = 0; Playtest.focusWindow(); }, () -> {
            if (!(mc().screen instanceof TerminalScreen screen)) return true;
            int i = JourneyScenario.jobIndex(order);
            if (i >= 0 && screen.jobCentre(i) != null) return true;
            if (t[0]++ % 6 == 0) Playtest.click(5);
            return t[0] > 180;
        }, 200);
        clickAt(label, () -> { int i = JourneyScenario.jobIndex(order); return i < 0 || !(mc().screen instanceof TerminalScreen s) ? null : s.jobCentre(i); });
        final double[] before = {0};
        Playtest.queue("before " + label, () -> before[0] = wallet());
        click("Accept order", true);
        until("accepted " + label, GunsmithScenario::orderTaken, 200);
        click("Deliver", true);
        until("delivered " + label, () -> !orderTaken(), 200);
        Playtest.queue("paid for " + label, () -> {
            double paid = wallet() - before[0];
            Playtest.log("UNMARKED " + label + ": +" + Math.round(paid) + " credits");
            if (orderTaken() || paid < 1) Playtest.fail(label + " was not delivered and paid");
            else pass(passText + " (+" + Math.round(paid) + " credits)");
        });
    }

    /** The table's list for a kit gun: the kit recipes ("Registered", "Unmarked") and not TaCZ's own iron recipe (D-177); then one unmarked build with real clicks. */
    private static void buildUnmarkedGun(String label, String step) {
        use("open the table (" + label + ")", () -> F.table, GunsmithScenario::tableOpen, 600);
        Playtest.wait("draw", 20);
        final ResourceLocation[] type = new ResourceLocation[1];
        Playtest.queue("the table's list for the Glock 17", () -> {
            if (!tableOpen()) { Playtest.fail("TaCZ's table screen did not open"); return; }
            @SuppressWarnings("unchecked") Map<ResourceLocation, List<ResourceLocation>> recipes = (Map<ResourceLocation, List<ResourceLocation>>) field(mc().screen, "recipes");
            type[0] = null;
            List<ResourceLocation> glocks = new ArrayList<>();
            if (recipes != null) for (var e : recipes.entrySet()) {
                if (e.getValue().contains(KIT_GLOCK_UNMARKED)) type[0] = e.getKey();
                for (ResourceLocation id : e.getValue()) if (id.getPath().contains("glock_17")) glocks.add(id);
            }
            Playtest.log("UNMARKED the table lists for the Glock 17: " + glocks + " (under " + type[0] + ")");
            if (type[0] == null) { Playtest.fail("the table does not list the unmarked kit recipe"); return; }
            if (glocks.contains(ResourceLocation.fromNamespaceAndPath("tacz", "gun/glock_17"))) Playtest.fail("TaCZ's own iron recipe for the Glock 17 is still on the list beside the kit recipes");
            else if (step.equals("10")) pass("10a TaCZ's table lists the Glock 17 twice, as \"Registered\" and \"Unmarked\" kit recipes, and no longer TaCZ's iron recipe (three entries called \"Glock 17\" in gunsmith run 5)");
        });
        stopIfFailed("the unmarked recipe on the table");
        pick("the tab", "TypeButton", () -> type[0] != null && type[0].equals(field(mc().screen, "selectedType")));
        pick("the unmarked kit recipe", "ResultButton", () -> KIT_GLOCK_UNMARKED.equals(selectedRecipe()));
        shot("u-" + step + "-table");
        final int[] before = {0};
        Playtest.queue("guns before", () -> before[0] = server(GunsmithScenario::unmarkedGuns).size());
        clickAt("Craft", () -> {
            if (!(mc().screen instanceof net.minecraft.client.gui.screens.inventory.AbstractContainerScreen<?> s)) return null;
            for (var child : s.children())
                if (child instanceof net.minecraft.client.gui.components.ImageButton b && b.getWidth() == 48 && b.getHeight() == 18) return new int[]{b.getX() + 24, b.getY() + 9};
            return null;
        });
        until("the pistol is in the inventory", () -> server(GunsmithScenario::unmarkedGuns).size() > before[0], 200);
        Playtest.queue("built: " + label, () -> {
            List<String> guns = server(GunsmithScenario::unmarkedGuns);
            if (guns.size() <= before[0]) { Playtest.fail("Craft on the unmarked kit recipe left no unmarked pistol"); return; }
            Playtest.log("UNMARKED built " + guns.get(guns.size() - 1) + "; unmarked kits now " + count(UndergroundCity.item("unmarked_parts_kit")) + ", iron " + count(Items.IRON_INGOT));
            pass(step + " a real click on the tab, the unmarked kit recipe and Craft builds a Glock 17 with no serial: " + guns.get(guns.size() - 1));
        });
        closeScreen("close the table");
    }

    /** The unmarked guns carried, as "gun (provenance, serial or none, display name)" (server thread). */
    private static List<String> unmarkedGuns() {
        List<String> out = new ArrayList<>();
        for (ItemStack stack : me().getInventory().items) {
            CompoundTag tag = stack.getTag();
            if (tag != null && GunsmithBuilds.UNMARKED.equals(tag.getString(GunsmithBuilds.PROVENANCE)))
                out.add(tag.getString("GunId") + " (serial " + (tag.getString(GunsmithBuilds.SERIAL).isEmpty() ? "none" : tag.getString(GunsmithBuilds.SERIAL)) + ", named " + stack.getHoverName().getString() + ")");
        }
        return out;
    }

    /** Save, leave and reopen with the unmarked guns in the inventory: they come back with their provenance and still no serial. */
    private static void reopenWithGuns() {
        StationSteps.reconnect(() -> List.of(server(GunsmithScenario::unmarkedGuns), wallet()), (before, after) -> {
            if (!before.equals(after)) Playtest.fail("the unmarked pistols or the wallet changed across the save and reopen");
            else pass("12b saved, left and reopened: both pistols still unmarked, no serial gained, the wallet unchanged");
        });
    }

    /**
     * The kit's Glock 17 blueprint is read with a real right click (TaCZ Blueprints: the table shows only the guns a player has learned). Before it, Core must have said so: at
     * registration (gunsmith run 2 opened the table to an empty pistol tab, and nothing in the game had said why).
     */
    private static void learnBlueprint() {
        Playtest.queue("Core said to read the blueprint", () -> {
            String said = Playtest.heardSince(0, "right-click it to learn the gun");
            Playtest.log("GUNSMITH the registration hint: " + (said == null ? "not heard" : "\"" + said + "\""));
            if (said == null) Playtest.fail("nothing told the new Gunsmith to read the kit's blueprint before the table");
        });
        Item blueprint = ForgeRegistries.ITEMS.getValue(ResourceLocation.fromNamespaceAndPath("taczblueprints", "blueprint_pistol"));
        hold("the Glock 17 blueprint", blueprint);
        final int[] t = {0};
        Playtest.queue("right-click the blueprint (looking at the sky)", () -> { t[0] = 0; Playtest.focusWindow(); }, () -> {
            if (learned()) { if (t[0] % 16 == 10 || t[0] % 16 == 11) Playtest.mouseButton(3, false); return true; }
            look(mc().player.getEyePosition().add(0.3, 10, 0.3));
            t[0]++;
            boolean sky = mc().hitResult == null || mc().hitResult.getType() == net.minecraft.world.phys.HitResult.Type.MISS;
            if (t[0] % 16 == 10 && sky) Playtest.mouseButton(3, true);
            if (t[0] % 16 == 12) Playtest.mouseButton(3, false);
            return false;
        }, 400);
        Playtest.wait("settle", 6);
        Playtest.queue("the Glock 17 is learned", () -> {
            Playtest.log("GUNSMITH learned guns: " + server(() -> OptionalModProbes.unlockedGuns(me())) + "; blueprints left " + count(blueprint));
            if (!learned()) Playtest.fail("a right click on the blueprint did not teach the Glock 17");
            else pass("6 Core tells the new Gunsmith to read the kit's blueprint, and a real right click on it teaches the Glock 17");
        });
    }

    private static boolean learned() {
        return server(() -> OptionalModProbes.unlockedGuns(me()).stream().anyMatch(g -> g.equals("tacz:glock_17") || g.equals("tacz:gun/glock_17")));
    }

    /** One kit build on TaCZ's table with real clicks: open it, the tab that holds the kit recipe, the kit recipe, Craft; the new gun must carry a serial nobody has yet. */
    private static void build(String label, String step) {
        use("open the table (" + label + ")", () -> F.table, GunsmithScenario::tableOpen, 600);
        Playtest.wait("draw", 20);
        final ResourceLocation[] type = new ResourceLocation[1];
        Playtest.queue("which tab holds the kit recipe", () -> {
            if (!tableOpen()) { Playtest.fail("TaCZ's table screen did not open"); return; }
            @SuppressWarnings("unchecked") Map<ResourceLocation, List<ResourceLocation>> recipes = (Map<ResourceLocation, List<ResourceLocation>>) field(mc().screen, "recipes");
            type[0] = null;
            if (recipes != null) for (var e : recipes.entrySet()) if (e.getValue().contains(KIT_GLOCK)) type[0] = e.getKey();
            Playtest.log("GUNSMITH table tabs: " + (recipes == null ? "unreadable" : recipes.keySet()) + "; the kit recipe is under " + type[0]
                + (recipes == null || type[0] == null ? "" : "; that tab lists " + recipes.get(type[0])));
            if (type[0] == null) {
                // where it went missing: the client's recipe book, the server's, the result's tab, and what the pistol tab shows instead
                var client = mc().level.getRecipeManager();
                long ours = client.getRecipes().stream().filter(r -> r.getId().getNamespace().equals(UndergroundCity.MOD_ID) && r.getId().getPath().startsWith("tacz/")).count();
                Playtest.log("GUNSMITH diagnosis: the client knows " + ours + " of Core's table recipes; the kit Glock on the client " + describe(client.byKey(KIT_GLOCK).orElse(null))
                    + "; on the server " + server(() -> describe(me().server.getRecipeManager().byKey(KIT_GLOCK).orElse(null)))
                    + "; TaCZ's own Glock on the client " + describe(client.byKey(ResourceLocation.fromNamespaceAndPath("tacz", "gun/glock_17")).orElse(null))
                    + "; the pistol tab lists " + (recipes == null ? "?" : recipes.get(ResourceLocation.fromNamespaceAndPath("tacz", "pistol"))));
                Playtest.fail("TaCZ's table screen does not list the kit recipe " + KIT_GLOCK);
            }
        });
        stopIfFailed("the kit recipe on the table's screen");
        pick("the tab", "TypeButton", () -> type[0] != null && type[0].equals(field(mc().screen, "selectedType")));
        pick("the kit recipe", "ResultButton", () -> KIT_GLOCK.equals(selectedRecipe()));
        shot(step + "-table-" + F.serials.size());
        final int[] before = {0};
        Playtest.queue("guns before", () -> before[0] = server(GunsmithScenario::marks).size());
        clickAt("Craft", () -> {
            if (!(mc().screen instanceof net.minecraft.client.gui.screens.inventory.AbstractContainerScreen<?> s)) return null;
            for (var child : s.children())
                if (child instanceof net.minecraft.client.gui.components.ImageButton b && b.getWidth() == 48 && b.getHeight() == 18) return new int[]{b.getX() + 24, b.getY() + 9};
            return null;
        });
        until("the pistol is in the inventory", () -> server(GunsmithScenario::marks).size() > before[0], 200);
        Playtest.queue("built: " + label, () -> {
            List<String> marks = server(GunsmithScenario::marks);
            if (marks.size() <= before[0]) { Playtest.fail("Craft on the kit recipe left no registered pistol (" + label + ")"); return; }
            String mark = marks.get(marks.size() - 1);
            F.serials.add(mark);
            Playtest.log("GUNSMITH built " + mark + "; parts kits now " + count(UndergroundCity.item("firearm_parts_kit")) + ", iron " + count(Items.IRON_INGOT));
            pass(step + " a real click on the tab, the kit recipe and Craft on TaCZ's own screen builds a Glock 17 registered to the maker: " + mark);
        });
        closeScreen("close the table");
    }

    /** Clicks the table's buttons of one kind in turn, with the real pointer, until {@code done}; the next page with the wheel when none of those shown is it. */
    private static void pick(String label, String kind, java.util.function.BooleanSupplier done) {
        final int[] t = {0}, next = {0};
        Playtest.queue("click " + label + " on the table's screen", () -> { t[0] = 0; next[0] = 0; Playtest.focusWindow(); }, () -> {
            if (!tableOpen()) return true;
            if (done.getAsBoolean()) return true;
            int phase = t[0]++ % 16;
            if (phase == 0) {
                List<net.minecraft.client.gui.components.AbstractWidget> shown = new ArrayList<>();
                for (var child : mc().screen.children())
                    if (child instanceof net.minecraft.client.gui.components.AbstractWidget w && w.visible && w.getClass().getSimpleName().equals(kind)) shown.add(w);
                if (next[0] >= shown.size()) {
                    if (next[0] > 0 && t[0] < 600) { next[0] = 0; Playtest.click(5); return false; }   // the next page of the list
                    Playtest.fail("none of the " + shown.size() + " " + kind + "s on the table's screen is " + label);
                    return true;
                }
                var w = shown.get(next[0]++);
                Playtest.moveGui(w.getX() + w.getWidth() / 2, w.getY() + w.getHeight() / 2);
            }
            if (phase == 5) Playtest.pressLeft();
            return false;
        }, 900);
        Playtest.queue(label + " is selected", () -> { if (!done.getAsBoolean()) Playtest.fail(label + " could not be selected on the table's screen"); });
        Playtest.wait("draw", 6);
    }

    // ------------------------------------------------------------------------------------------------ reading

    static boolean tableOpen() { return mc().screen != null && mc().screen.getClass().getName().equals(TABLE_SCREEN); }

    private static boolean tableStands() {
        return server(() -> F.table != null && "tacz:gun_smith_table".equals(String.valueOf(ForgeRegistries.BLOCKS.getKey(level().getBlockState(F.table).getBlock()))));
    }

    private static Object field(Object owner, String name) {
        if (owner == null) return null;
        try {
            Field f = owner.getClass().getDeclaredField(name);
            f.setAccessible(true);
            return f.get(owner);
        } catch (ReflectiveOperationException e) {
            return null;
        }
    }

    /** A table recipe for the log: absent, or its type and the tab its result names (TaCZ's getResult().getGroup(), by reflection). */
    private static String describe(net.minecraft.world.item.crafting.Recipe<?> recipe) {
        if (recipe == null) return "absent";
        String group;
        try {
            Object result = recipe.getClass().getMethod("getResult").invoke(recipe);
            group = String.valueOf(result.getClass().getMethod("getGroup").invoke(result));
        } catch (ReflectiveOperationException | RuntimeException e) {
            group = "unreadable (" + e + ")";
        }
        return "present (" + recipe.getClass().getSimpleName() + ", tab " + group + ")";
    }

    private static ResourceLocation selectedRecipe() {
        return field(mc().screen, "selectedRecipe") instanceof net.minecraft.world.item.crafting.Recipe<?> r ? r.getId() : null;
    }

    /** The registered guns in the player's inventory, as "serial by maker" (server thread). */
    private static List<String> marks() {
        List<String> out = new ArrayList<>();
        for (ItemStack stack : me().getInventory().items) {
            CompoundTag tag = stack.getTag();
            if (tag != null && GunsmithBuilds.REGISTERED.equals(tag.getString(GunsmithBuilds.PROVENANCE)))
                out.add(tag.getString("GunId") + " serial " + tag.getString(GunsmithBuilds.SERIAL) + " by " + tag.getString(GunsmithBuilds.MAKER));
        }
        return out;
    }

    private static int xp() { return server(() -> CitizenState.get(me().server).find(me().getUUID()).map(CitizenState.Citizen::careerXp).orElse(0)); }

    private static boolean orderTaken() { return server(() -> WorkOrderLedger.get(me().server).player(me().getUUID()).hasUUID("order")); }

    /**
     * Open ground for the trade (server thread): a cell to stand on, the bench two blocks east of it, and the table two blocks south (TaCZ's table is two blocks long and runs on away from
     * the player, so the cell beyond it must be free too), all on ground open to the sky that no building owns. Returns {stand, bench, table} or null.
     */
    private static BlockPos[] workshopGround(BlockPos around, int radius) {
        ServerLevel lvl = level();
        BlockPos[] best = null;
        for (BlockPos column : BlockPos.betweenClosed(around.offset(-radius, 0, -radius), around.offset(radius, 0, radius))) {
            BlockPos s = new BlockPos(column.getX(), lvl.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, column.getX(), column.getZ()), column.getZ());
            BlockPos b = s.relative(Direction.EAST, 2), t = s.relative(Direction.SOUTH, 2);
            boolean ok = true;
            for (BlockPos c : new BlockPos[]{s, s.east(), b, s.south(), t, t.south(), t.east(), s.south().east()})
                if (!PlayerPath.standable(lvl, c) || !lvl.getBlockState(c.below()).isFaceSturdy(lvl, c.below(), Direction.UP)
                    || BuildingGuard.protectedAt(lvl, c).isPresent() || BuildingGuard.protectedAt(lvl, c.below()).isPresent()) { ok = false; break; }
            if (!ok) continue;
            if (best == null || s.distToCenterSqr(around.getX(), s.getY(), around.getZ()) < best[0].distToCenterSqr(around.getX(), best[0].getY(), around.getZ()))
                best = new BlockPos[]{s.immutable(), b.immutable(), t.immutable()};
        }
        return best;
    }
}
