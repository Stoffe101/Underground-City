package io.github.stoffe101.undergroundcity;

import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import static io.github.stoffe101.undergroundcity.JourneyScenario.aimedAt;
import static io.github.stoffe101.undergroundcity.JourneyScenario.click;
import static io.github.stoffe101.undergroundcity.JourneyScenario.clickAt;
import static io.github.stoffe101.undergroundcity.JourneyScenario.closeScreen;
import static io.github.stoffe101.undergroundcity.JourneyScenario.count;
import static io.github.stoffe101.undergroundcity.JourneyScenario.gridSlot;
import static io.github.stoffe101.undergroundcity.JourneyScenario.hold;
import static io.github.stoffe101.undergroundcity.JourneyScenario.inventorySlotWith;
import static io.github.stoffe101.undergroundcity.JourneyScenario.level;
import static io.github.stoffe101.undergroundcity.JourneyScenario.look;
import static io.github.stoffe101.undergroundcity.JourneyScenario.mc;
import static io.github.stoffe101.undergroundcity.JourneyScenario.me;
import static io.github.stoffe101.undergroundcity.JourneyScenario.mine;
import static io.github.stoffe101.undergroundcity.JourneyScenario.placeAhead;
import static io.github.stoffe101.undergroundcity.JourneyScenario.putBack;
import static io.github.stoffe101.undergroundcity.JourneyScenario.server;
import static io.github.stoffe101.undergroundcity.JourneyScenario.slotClick;
import static io.github.stoffe101.undergroundcity.JourneyScenario.until;
import static io.github.stoffe101.undergroundcity.JourneyScenario.use;
import static io.github.stoffe101.undergroundcity.JourneyScenario.wallet;
import static io.github.stoffe101.undergroundcity.ThiefScenario.pass;
import static io.github.stoffe101.undergroundcity.ThiefScenario.scripted;
import static io.github.stoffe101.undergroundcity.ThiefScenario.shot;
import static io.github.stoffe101.undergroundcity.ThiefScenario.stopIfFailed;

/**
 * Scenario D of the brief, "Cannabis progression", through a real client ({@code -PucPlaytest=cannabis}, a new furnished archipelago world): a Farmer learns the controlled crop's skills and its
 * licence on the terminal's Skills page, accepts the licensed supply order for the Blackwater dispensary, buys the catalyst and rare seed stock on the Valewood exchange, crafts Cultivation Nutrient
 * and Cannabis Seeds in the inventory's grid, hoes a plot, plants and feeds three plants, harvests them, checks that every item and the order are called Cannabis (D-142), and delivers the flower at a
 * terminal in Blackwater for the order's pay. Then (sixth pass) the processing step and the law: the harvest's spare flower is hung on a Drying Rack placed in the Blackwater street and taken
 * down dried after six minutes of play (D-160), and a plant grown in an Ironhaven street, where the crop is prohibited, waits for the officer on the beat to see it and pull it up.
 *
 * <p>Scripted rather than played, each logged as SCRIPTED: the career XP of a level-10 Farmer (the climb is the journey's), the travel (teleports), the time of day, the bone meal and glass bottles
 * (vanilla goods a farm has), the random tick speed while the plants grow (minutes of waiting, not play), and any walk the path walker could not finish; in the sixth-pass steps the Drying Rack
 * (its recipe needs a crafting table), flower and a seed only when the harvest left too few, a block of dirt to plant in on the paved street, and a raised random tick speed while waiting for the
 * officer (it decides how often the plant looks, not whether an officer comes or sees it). Every click, key and screen is the real one.
 */
final class CannabisScenario {
    private CannabisScenario() {}

    static final List<String> SKILLS = List.of("farmer.fundamentals", "farmer.greenhouse_management", "farmer.restricted_cultivation", "farmer.controlled_crop_licence");
    static final String ORDER = "underground_city:licensed_cultivar_supply";
    static final int LEVEL = 10, PLANTS = 3, CATALYSTS = 5, RARE_SEEDS = 2, GROWING_TICK_SPEED = 120;

    private static final class Facts {
        volatile BlockPos terminal, placed;
        final List<BlockPos> plot = new ArrayList<>();
        volatile boolean looked;
        volatile double walletBeforeBuy, walletBeforeDelivery;
        volatile int xpBeforeDelivery, flowers;
    }

    private static final Facts F = new Facts();
    /** The sixth-pass steps' facts: the rack, the flower hung, the dried cannabis taken down, the street plant and Ironhaven's heat round it. */
    private static final class Later {
        volatile BlockPos rack, dirt;
        volatile int hung, driedBefore, heatBefore;
    }
    private static final Later L = new Later();

    private static Item core(String id) { return UndergroundCity.item(id); }

    static void build() {
        ThiefScenario.tag = "CANNABIS";
        Playtest.keepScreens = true;
        ThiefScenario.register(0, "the Farmer card", "1 a new citizen registers as a Farmer (the crop is the Farmer's restricted good)");
        seasoned();
        learnAndAccept();
        stopIfFailed("the skills and the order");
        Playtest.queue("the clock: 10:00 with the daylight cycle stopped", () -> {
            Playtest.command("gamerule doDaylightCycle false");
            Playtest.command("time set 4000");
            scripted("the time of day was set to 10:00 and the daylight cycle stopped (the crop needs bright light)");
        });
        buyInValewood();
        stopIfFailed("buying the inputs");
        craft();
        stopIfFailed("crafting");
        plant();
        stopIfFailed("planting");
        grow();
        harvest();
        names();
        deliver();
        dry();
        publicGrowing();
        Playtest.queue("summary", ThiefScenario::summary);
    }

    // ------------------------------------------------------------------------------------------------ the Farmer

    private static void seasoned() {
        Playtest.queue("a Farmer of level " + LEVEL + " (scripted career XP)", () -> {
            int target = ProgressionRules.xpAtLevel(LEVEL);
            server(() -> CitizenState.get(mc().getSingleplayerServer()).addCareerXp(me().getUUID(), target - CitizenState.get(mc().getSingleplayerServer()).find(me().getUUID()).map(c -> c.careerXp()).orElse(0)));
            scripted("the career XP of a level-" + LEVEL + " Farmer was given by the script (" + target + " XP): the climb from level 1 is the journey's");
        });
        Playtest.wait("settle", 10);
        Playtest.queue("level and points", () -> {
            CitizenState.Citizen c = ThiefScenario.citizen();
            Playtest.log("CANNABIS Farmer level " + c.careerLevel() + ", " + c.careerXp() + " XP, " + server(() -> SkillService.pointsAvailable(c)) + " career points; wallet " + wallet());
            if (c.careerLevel() != LEVEL) Playtest.fail("the Farmer is level " + c.careerLevel() + ", not " + LEVEL);
        });
    }

    private static boolean learned(String id) {
        return server(() -> CitizenState.get(mc().getSingleplayerServer()).find(mc().player.getUUID()).map(c -> c.skills().contains(id)).orElse(false));
    }

    private static boolean orderTaken() {
        return server(() -> {
            var tag = WorkOrderLedger.get(mc().getSingleplayerServer()).player(mc().player.getUUID());
            return tag.hasUUID("order") && ORDER.equals(tag.getCompound("job").getString("id"));
        });
    }

    /** The Founders Row terminal: the four skills on the Skills page (the wheel, the card, Unlock), then the licensed supply order on the work page. */
    private static void learnAndAccept() {
        Playtest.queue("find the Founders Row terminal", () -> {
            F.terminal = server(() -> {
                BlockPos row = DistrictBuilder.originOf(level(), "founders_row");
                if (row == null) return null;
                BlockPos guess = row.offset(4, 0, 2);
                for (BlockPos p : BlockPos.betweenClosed(guess.offset(-3, -2, -3), guess.offset(3, 2, 3))) if (level().getBlockState(p).is(UndergroundCity.TERMINAL.get())) return p.immutable();
                return null;
            });
            if (F.terminal == null) Playtest.fail("no terminal on Founders Row");
        });
        stopIfFailed("finding the terminal");
        ThiefScenario.travel("to Founders Row", () -> F.terminal == null ? null : new Vec3(F.terminal.getX() + 0.5, F.terminal.getY() + 30, F.terminal.getZ() + 2.5), 2);
        Playtest.queue("stand in front of the terminal", () -> {
            var server = mc().getSingleplayerServer();
            server.execute(() -> {
                Direction facing = level().getBlockState(F.terminal).getValue(net.minecraft.world.level.block.HorizontalDirectionalBlock.FACING);
                BlockPos stand = F.terminal.relative(facing);
                me().setGameMode(net.minecraft.world.level.GameType.SURVIVAL);
                me().teleportTo(level(), stand.getX() + 0.5, stand.getY(), stand.getZ() + 0.5, facing.getOpposite().toYRot(), 20f);
                me().setDeltaMovement(Vec3.ZERO);
            });
            scripted("the player was put in front of the Founders Row terminal");
        }, () -> mc().player.position().distanceTo(Vec3.atBottomCenterOf(F.terminal)) < 2.5, 600);
        Playtest.wait("settle", 25);
        use("right-click the terminal", () -> F.terminal, () -> mc().screen instanceof TerminalScreen, 400);
        until("the terminal opens", () -> mc().screen instanceof TerminalScreen, 100);
        click("Skills", true);
        Playtest.wait("draw", 12);
        for (String id : SKILLS) {
            final int[] t = {0};
            Playtest.queue("turn the wheel until " + id + " is in view", () -> { t[0] = 0; Playtest.focusWindow(); }, () -> {
                if (!(mc().screen instanceof TerminalScreen screen)) return true;
                int where = screen.skillBeyond(id);
                if (where == 0 || where == 2) return true;
                if (t[0]++ % 6 == 0) Playtest.click(where > 0 ? 5 : 4);
                return false;
            }, 200);
            clickAt("the " + id + " card", () -> mc().screen instanceof TerminalScreen screen ? screen.skillCentre(id) : null);
            Playtest.wait("select", 8);
            click("Unlock", true);
            until("learned " + id, () -> learned(id), 200);
        }
        Playtest.wait("draw", 10);
        shot("1-skills");
        Playtest.queue("the skills", () -> {
            long have = SKILLS.stream().filter(CannabisScenario::learned).count();
            Playtest.log("CANNABIS learned " + have + " of " + SKILLS.size() + " skills");
            if (have != SKILLS.size()) Playtest.fail("only " + have + " of the crop's skills were learned");
            else pass("2 learn Field Fundamentals, Greenhouse Management, Restricted Cultivation and the Controlled-Crop Licence on the terminal's Skills page");
        });
        click("Work", true);
        Playtest.wait("draw", 12);
        final int[] t = {0};
        Playtest.queue("turn the wheel until the licensed supply order is in view", () -> { t[0] = 0; Playtest.focusWindow(); }, () -> {
            if (!(mc().screen instanceof TerminalScreen screen)) return true;
            int i = JourneyScenario.jobIndex(ORDER);
            if (i >= 0 && screen.jobCentre(i) != null) return true;
            if (t[0]++ % 6 == 0) Playtest.click(5);
            return t[0] > 180;
        }, 200);
        Playtest.queue("the order on the page", () -> {
            if (!(mc().screen instanceof TerminalScreen screen)) return;
            var jobs = screen.visibleJobs();
            StringBuilder b = new StringBuilder();
            for (var j : jobs) b.append(" [").append(j.id()).append(": ").append(j.title()).append("]");
            Playtest.log("CANNABIS orders on the page:" + b);
        });
        clickAt("the licensed cannabis supply order", () -> { int i = JourneyScenario.jobIndex(ORDER); return i < 0 ? null : ((TerminalScreen) mc().screen).jobCentre(i); });
        shot("2-order");
        click("Accept order", true);
        until("the order is accepted on the server", CannabisScenario::orderTaken, 200);
        Playtest.queue("accepted", () -> {
            if (!orderTaken()) { Playtest.fail("the licensed supply order was not accepted"); return; }
            String title = server(() -> { var o = WorkOrderCatalog.INSTANCE.find(ORDER); return o == null ? "?" : o.title(); });
            if (!title.toLowerCase(Locale.ROOT).contains("cannabis")) Playtest.fail("the order is called \"" + title + "\", not by the crop's name");
            else pass("3 accept \"" + title + "\" (six flower for the Blackwater dispensary, 225 credits) on the terminal's work page");
        });
        closeScreen("close the terminal");
    }

    // ------------------------------------------------------------------------------------------------ the inputs

    private static void buyInValewood() {
        ThiefScenario.travel("to the middle of Valewood", () -> ThiefScenario.centre("valewood"), 4);
        final BlockPos[] street = new BlockPos[1];
        Playtest.queue("survival, and open street ground nearby that nobody owns", () -> {
            Playtest.command("gamemode survival");
            street[0] = server(() -> openGround(me().blockPosition(), 40, false));
            Playtest.log("CANNABIS open ground for the terminal: " + (street[0] == null ? "none" : street[0].toShortString()));
            if (street[0] == null) Playtest.fail("no open street ground near the middle of Valewood");
        });
        stopIfFailed("open ground in Valewood");
        Playtest.queue("down to the street (scripted)", () -> {
            scripted("the player was put on open street ground in Valewood at " + street[0].toShortString());
            server(() -> { me().teleportTo(street[0].getX() + 0.5, street[0].getY(), street[0].getZ() + 0.5); me().setDeltaMovement(Vec3.ZERO); return null; });
        }, () -> mc().player.blockPosition().distManhattan(street[0]) <= 1, 400);
        until("the ground has arrived", Scenarios::groundBelowPlayer, 2400);
        Playtest.wait("settle", 20);
        hold("the welcome-pack terminal", UndergroundCity.TERMINAL_ITEM.get());
        final BlockPos[] spot = new BlockPos[1];
        placeAhead("the terminal", UndergroundCity.TERMINAL.get(), spot, pos -> server(() -> BuildingGuard.protectedAt(level(), pos).isEmpty() && BuildingGuard.protectedAt(level(), pos.below()).isEmpty()));
        Playtest.queue("placed", () -> { F.placed = spot[0]; if (spot[0] == null || !mc().level.getBlockState(spot[0]).is(UndergroundCity.TERMINAL.get())) Playtest.fail("the terminal could not be placed in the Valewood street"); });
        stopIfFailed("placing the terminal");
        // the wallet before the terminal opens: the page shows what the wallet held when it opened, and greys out what it cannot pay for (run 3 raised it with the page open, and no Buy took)
        Playtest.queue("the wallet", () -> {
            F.walletBeforeBuy = wallet();
            double need = 30.0 * CATALYSTS + 14.0 * RARE_SEEDS + 20;
            if (F.walletBeforeBuy < need) {
                double target = need;
                server(() -> { QShopEconomy.applyBalance(me(), target); return null; });
                scripted(String.format(Locale.ROOT, "the wallet was raised from %.0f to %.0f credits for the inputs (a level-10 Farmer has earned more than a new citizen)", F.walletBeforeBuy, target));
                F.walletBeforeBuy = target;
            }
        });
        Playtest.wait("the wallet settles", 10);
        use("open the terminal", () -> F.placed, () -> mc().screen instanceof TerminalScreen, 200);
        click("TRADE", true);
        Playtest.wait("draw", 10);
        final String[] catalyst = {null}, seeds = {null};
        Playtest.queue("the Valewood shelves", () -> {
            if (!(mc().screen instanceof TerminalScreen t)) return;
            for (var o : t.data().offers()) {
                if (o.item().endsWith(":agro_catalyst")) catalyst[0] = o.id();
                if (o.item().endsWith(":rare_seeds")) seeds[0] = o.id();
                if (o.item().endsWith(":agro_catalyst") || o.item().endsWith(":rare_seeds")) Playtest.log("CANNABIS Valewood offer " + o.id() + " " + o.item() + " " + o.price() + " credits, " + o.stock() + " left");
            }
            if (catalyst[0] == null || seeds[0] == null) Playtest.fail("the Valewood exchange shows no catalyst or no rare seed stock");
        });
        stopIfFailed("the shelves");
        buy("Agricultural Catalyst", catalyst, core("agro_catalyst"), CATALYSTS);
        buy("Rare Seed Stock", seeds, core("rare_seeds"), RARE_SEEDS);
        shot("3-exchange");
        Playtest.queue("bought", () -> {
            double w = wallet();
            int c = count(core("agro_catalyst")), s = count(core("rare_seeds"));
            Playtest.log(String.format(Locale.ROOT, "CANNABIS bought %d catalyst and %d rare seed stock; wallet %.0f -> %.0f", c, s, F.walletBeforeBuy, w));
            if (c < CATALYSTS || s < RARE_SEEDS) Playtest.fail("the exchange sold " + c + " catalyst and " + s + " rare seed stock");
            else pass(String.format(Locale.ROOT, "4 buy %d Agricultural Catalyst and %d Rare Seed Stock on the Valewood exchange with Buy clicks (%.0f credits)", c, s, F.walletBeforeBuy - w));
        });
        closeScreen("close the terminal");
        // the terminal goes with the Farmer to Blackwater, where the order is delivered
        mine("mine the terminal back", () -> F.placed, 600);
        ThiefScenario.walkOrTeleport("pick up the terminal", () -> Vec3.atBottomCenterOf(F.placed), 0.6, 200);
        until("the terminal is carried again", () -> count(UndergroundCity.TERMINAL_ITEM.get()) >= 1, 200);
        Playtest.queue("carried", () -> { if (count(UndergroundCity.TERMINAL_ITEM.get()) < 1) Playtest.fail("the terminal was not picked up again"); });
    }

    /** Clicks Buy 1 on an exchange line until the player carries {@code wanted} of the item (one click a second, so each purchase is answered first). */
    private static void buy(String label, String[] offer, Item item, int wanted) {
        final int[] t = {0};
        Playtest.queue("buy " + wanted + " " + label + " one click at a time", () -> t[0] = 0, () -> {
            if (count(item) >= wanted || !(mc().screen instanceof TerminalScreen screen)) return true;
            int phase = t[0]++ % 20;
            int[] at = offer[0] == null ? null : screen.offerBuyCentre(offer[0]);
            if (at == null) return t[0] > 100;
            if (phase == 0) Playtest.moveGui(at[0], at[1]);
            if (phase == 4) Playtest.pressLeft();
            return t[0] > 20 * (wanted + 6);
        }, 20 * (wanted + 8));
    }

    /**
     * Open ground near {@code around}: a standable cell nobody's building or home owns, with room round it, the nearest first. With {@code soil}, a row of three grass or dirt blocks under the open
     * sky instead, for a plot: the first cell of the row is returned.
     */
    static BlockPos openGround(BlockPos around, int radius, boolean soil) {
        ServerLevel lvl = level();
        BlockPos best = null;
        // the ground open to the sky in each column (a roof is a city building's, and refused below): the player may stand high above the street after a teleport
        for (BlockPos column : BlockPos.betweenClosed(around.offset(-radius, 0, -radius), around.offset(radius, 0, radius))) {
            BlockPos q = new BlockPos(column.getX(), lvl.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, column.getX(), column.getZ()), column.getZ());
            if (!PlayerPath.standable(lvl, q) || BuildingGuard.protectedAt(lvl, q).isPresent() || BuildingGuard.protectedAt(lvl, q.below()).isPresent()) continue;
            boolean roomAround = true;
            for (Direction d : Direction.Plane.HORIZONTAL) if (BuildingGuard.protectedAt(lvl, q.relative(d, 2)).isPresent()) { roomAround = false; break; }
            if (!roomAround) continue;
            if (soil) {
                boolean row = true;
                for (int k = 0; k < PLANTS + 1 && row; k++) {
                    BlockPos cell = q.offset(k, 0, 0);
                    BlockState below = lvl.getBlockState(cell.below());
                    // the two rows in front clear too: the farmer stands two blocks south of the row and works it from there (run 4 found a bush between the hand and the third cell)
                    row = (below.is(Blocks.GRASS_BLOCK) || below.is(Blocks.DIRT)) && lvl.getBlockState(cell).isAir() && lvl.getBlockState(cell.above()).isAir() && lvl.canSeeSky(cell)
                        && BuildingGuard.protectedAt(lvl, cell.below()).isEmpty()
                        && lvl.getBlockState(cell.south()).isAir() && lvl.getBlockState(cell.south().above()).isAir()
                        && lvl.getBlockState(cell.south(2)).isAir() && lvl.getBlockState(cell.south(2).above()).isAir();
                }
                if (!row || !PlayerPath.standable(lvl, q.offset(1, 0, 2))) continue;
            }
            if (best == null || q.distToCenterSqr(around.getX(), q.getY(), around.getZ()) < best.distToCenterSqr(around.getX(), best.getY(), around.getZ())) best = q;
        }
        return best;
    }

    // ------------------------------------------------------------------------------------------------ making

    private static void craft() {
        Playtest.queue("bone meal and glass bottles (scripted)", () -> {
            server(() -> { me().getInventory().add(new ItemStack(Items.BONE_MEAL, 3)); me().getInventory().add(new ItemStack(Items.GLASS_BOTTLE, 3)); return null; });
            scripted("three bone meal and three glass bottles were put in the inventory (vanilla goods any farm has)");
        });
        Playtest.wait("settle", 6);
        Playtest.queue("open the inventory with E", () -> { Playtest.focusWindow(); Playtest.tap("e"); });
        until("the inventory screen", () -> mc().screen instanceof InventoryScreen, 100);
        // three batches of nutrient at once: three of each input in three squares of the 2x2 grid, then Shift-click the result
        fill("the catalyst", core("agro_catalyst"), 1, 3);
        fill("the bone meal", Items.BONE_MEAL, 2, 3);
        fill("the glass bottles", Items.GLASS_BOTTLE, 3, 3);
        shot("4-nutrient-recipe");
        slotClick("the Cultivation Nutrient (Shift-click)", () -> gridSlot(0), 1, true);
        Playtest.wait("craft", 10);
        // two batches of seeds: rare seed stock, catalyst and wheat seeds (the welcome pack's)
        fill("the rare seed stock", core("rare_seeds"), 1, 2);
        fill("the catalyst", core("agro_catalyst"), 2, 2);
        fill("the wheat seeds", Items.WHEAT_SEEDS, 3, 2);
        slotClick("the Cannabis Seeds (Shift-click)", () -> gridSlot(0), 1, true);
        Playtest.wait("craft", 10);
        shot("5-crafted");
        Playtest.queue("crafted", () -> {
            int nutrient = count(core("cultivation_nutrient")), seeds = count(core("cannabis_seeds"));
            Playtest.log("CANNABIS crafted: " + nutrient + " Cultivation Nutrient, " + seeds + " Cannabis Seeds");
            if (nutrient < 3 * PLANTS || seeds < PLANTS) Playtest.fail("the grid made " + nutrient + " nutrient and " + seeds + " seeds");
            else pass("5 craft " + nutrient + " Cultivation Nutrient and " + seeds + " Cannabis Seeds in the inventory's grid");
        });
        closeScreen("close the inventory");
    }

    /** Picks up the stack of {@code item}, drops {@code n} into grid square {@code square} with right clicks, and puts the rest back. */
    private static void fill(String label, Item item, int square, int n) {
        slotClick(label, () -> inventorySlotWith((AbstractContainerScreen<?>) mc().screen, st -> st.is(item)), 1, false);
        for (int k = 0; k < n; k++) slotClick("grid square " + square + " (one)", () -> gridSlot(square), 3, false);
        putBack("the rest of " + label);
    }

    private static void plant() {
        Playtest.queue("a plot: three grass or dirt blocks under the open sky nearby that nobody owns", () -> {
            BlockPos first = server(() -> openGround(me().blockPosition(), 48, true));
            if (first == null) { Playtest.fail("no row of open soil near the street"); return; }
            for (int k = 0; k < PLANTS; k++) F.plot.add(first.offset(k, -1, 0));
            Playtest.log("CANNABIS plot: " + F.plot);
        });
        stopIfFailed("finding soil");
        Playtest.queue("stand beside the plot (scripted)", () -> {
            BlockPos at = F.plot.get(1).offset(0, 1, 2);
            scripted("the player was put beside the plot at " + at.toShortString());
            server(() -> { me().teleportTo(at.getX() + 0.5, at.getY(), at.getZ() + 0.5); me().setDeltaMovement(Vec3.ZERO); return null; });
        }, () -> mc().player.blockPosition().distManhattan(F.plot.get(1).offset(0, 1, 2)) <= 1, 400);
        Playtest.wait("settle", 15);
        hold("the wooden hoe", Items.WOODEN_HOE);
        for (int k = 0; k < PLANTS; k++) {
            final int i = k;
            use("hoe the soil " + (k + 1), () -> F.plot.get(i), () -> mc().level.getBlockState(F.plot.get(i)).is(Blocks.FARMLAND), 300);
        }
        hold("the Cannabis Seeds", core("cannabis_seeds"));
        for (int k = 0; k < PLANTS; k++) {
            final int i = k;
            use("plant a seed in the farmland " + (k + 1), () -> F.plot.get(i), () -> mc().level.getBlockState(F.plot.get(i).above()).getBlock() instanceof RestrictedCropBlock, 300);
        }
        shot("6-planted");
        Playtest.queue("planted", () -> {
            int planted = 0;
            for (BlockPos p : F.plot) if (server(() -> level().getBlockState(p.above()).getBlock() instanceof RestrictedCropBlock)) planted++;
            Playtest.log("CANNABIS planted " + planted + " of " + PLANTS);
            if (planted != PLANTS) Playtest.fail("only " + planted + " plants stand in the plot");
            else pass("6 hoe a plot with the welcome pack's hoe and plant " + PLANTS + " Cannabis Seeds with right clicks (Restricted Cultivation lets a Farmer plant them)");
        });
    }

    /** Feeds each plant whenever it is hungry (one dose per stage) and waits for the next stage, until all three are mature. */
    private static void grow() {
        hold("the Cultivation Nutrient", core("cultivation_nutrient"));
        Playtest.queue("the random tick speed while the plants grow (scripted)", () -> {
            Playtest.command("gamerule randomTickSpeed " + GROWING_TICK_SPEED);
            scripted("the random tick speed was raised to " + GROWING_TICK_SPEED + " while the plants grew (minutes of waiting, not play); put back to 3 after");
        });
        final int[] t = {0}, fed = {0};
        Playtest.queue("feed the plants and wait until all are mature", () -> t[0] = 0, () -> {
            t[0]++;
            int mature = 0;
            BlockPos hungry = null;
            for (BlockPos p : F.plot) {
                BlockState s = mc().level.getBlockState(p.above());
                if (!(s.getBlock() instanceof RestrictedCropBlock)) continue;
                if (s.getValue(RestrictedCropBlock.AGE) >= 3) { mature++; continue; }
                if (!s.getValue(RestrictedCropBlock.NOURISHED) && hungry == null) hungry = p.above();
            }
            if (mature == PLANTS) return true;
            if (hungry != null) {
                look(Vec3.atCenterOf(hungry).add(0, -0.25, 0));
                if (t[0] % 10 == 5 && aimedAt(hungry)) { Playtest.click(3); fed[0]++; }
            }
            if (t[0] % 400 == 1) Playtest.log("CANNABIS growing: " + mature + " mature, " + count(core("cultivation_nutrient")) + " nutrient left, " + fed[0] + " feeding clicks");
            return false;
        }, 12000);
        Playtest.queue("the random tick speed back to 3", () -> Playtest.command("gamerule randomTickSpeed 3"));
        shot("7-mature");
        Playtest.queue("grown", () -> {
            int mature = 0;
            for (BlockPos p : F.plot) { BlockState s = mc().level.getBlockState(p.above()); if (s.getBlock() instanceof RestrictedCropBlock && s.getValue(RestrictedCropBlock.AGE) >= 3) mature++; }
            int left = count(core("cultivation_nutrient"));
            Playtest.log("CANNABIS mature " + mature + " of " + PLANTS + ", nutrient left " + left);
            if (mature != PLANTS) Playtest.fail("only " + mature + " plants matured");
            else pass("7 feed each plant a dose of Cultivation Nutrient per stage with right clicks until all " + PLANTS + " are mature (" + (3 * PLANTS - left) + " doses)");
        });
    }

    private static void harvest() {
        Playtest.queue("an empty hand", () -> { Playtest.focusWindow(); Playtest.tap("9"); server(() -> { var inv = me().getInventory(); ItemStack held = inv.getItem(8); if (!held.isEmpty()) { inv.setItem(8, ItemStack.EMPTY); inv.add(held); } return null; }); });
        for (int k = 0; k < PLANTS; k++) {
            final int i = k;
            mine("harvest the plant " + (k + 1), () -> F.plot.get(i).above(), 200);
        }
        // the drops: walk over each one that did not come to hand
        final int[] t = {0};
        Playtest.queue("pick up what fell", () -> t[0] = 0, () -> {
            t[0]++;
            Vec3 drop = server(() -> {
                List<ItemEntity> items = level().getEntitiesOfClass(ItemEntity.class, new AABB(F.plot.get(1)).inflate(6), e -> e.isAlive());
                return items.isEmpty() ? null : items.get(0).position();
            });
            if (drop == null) { Playtest.keyUp("w"); return true; }
            look(drop);
            mc().player.setXRot(10);
            if (t[0] % 20 == 1) Playtest.keyDown("w");
            if (t[0] % 20 == 8) Playtest.keyUp("w");
            return t[0] > 400;
        }, 440);
        Playtest.queue("let go", () -> Playtest.keyUp("w"));
        Playtest.queue("the harvest", () -> {
            F.flowers = count(core("cannabis_flower"));
            Playtest.log("CANNABIS harvested " + F.flowers + " Cannabis Flower, " + count(core("cannabis_seeds")) + " Cannabis Seeds carried");
            if (F.flowers < 6) Playtest.fail("the harvest gave " + F.flowers + " flower, fewer than the order's six");
            else pass("8 harvest the three mature plants with the real left click: " + F.flowers + " Cannabis Flower");
        });
    }

    /** Every crop item the player carries is called Cannabis by the client (D-142), and none by the former fictional name; the inventory is photographed with the flower's tooltip. */
    private static void names() {
        Playtest.queue("open the inventory with E", () -> { Playtest.focusWindow(); Playtest.tap("e"); });
        until("the inventory screen", () -> mc().screen instanceof InventoryScreen, 100);
        Playtest.queue("point at the flower", () -> {
            if (!(mc().screen instanceof AbstractContainerScreen<?> screen)) return;
            var slot = inventorySlotWith(screen, st -> st.is(core("cannabis_flower")));
            if (slot != null) { int[] c = JourneyScenario.slotCentre(screen, slot); Playtest.moveGui(c[0], c[1]); }
        });
        Playtest.wait("the tooltip", 10);
        shot("8-cannabis-flower");
        Playtest.queue("what the client calls them", () -> {
            List<String> names = new ArrayList<>();
            boolean old = false;
            // the former name, from the alias table that keeps older saves loading (the one place it is still written, D-142)
            java.util.Set<String> former = new java.util.HashSet<>();
            for (String renamed : RenamedIds.ITEMS.keySet()) former.add(renamed.substring(0, renamed.indexOf('_')));
            for (ItemStack st : mc().player.getInventory().items) {
                if (st.isEmpty()) continue;
                String id = net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(st.getItem()).toString(), name = st.getHoverName().getString();
                if (id.contains("cannabis") || id.contains("nutrient")) names.add(id + " = \"" + name + "\"");
                for (String word : former) if (name.toLowerCase(Locale.ROOT).contains(word) || id.contains(word)) old = true;
            }
            Playtest.log("CANNABIS names on the client: " + names);
            boolean right = names.stream().anyMatch(n -> n.endsWith("\"Cannabis Flower\"")) && names.stream().filter(n -> n.contains("cannabis_seeds")).allMatch(n -> n.endsWith("\"Cannabis Seeds\""));
            if (old) Playtest.fail("an item still carries the former name");
            else if (!right) Playtest.fail("the crop's items are not called Cannabis Flower and Cannabis Seeds: " + names);
            else pass("9 the client calls the crop's items Cannabis Flower and Cannabis Seeds, and nothing carries the former name");
        });
        closeScreen("close the inventory");
    }

    // ------------------------------------------------------------------------------------------------ the buyer

    private static void deliver() {
        ThiefScenario.travel("to the middle of Blackwater", () -> ThiefScenario.centre("blackwater"), 4);
        final BlockPos[] street = new BlockPos[1];
        Playtest.queue("survival, and open street ground nearby that nobody owns", () -> {
            Playtest.command("gamemode survival");
            street[0] = server(() -> openGround(me().blockPosition(), 40, false));
            if (street[0] == null) Playtest.fail("no open street ground near the middle of Blackwater");
        });
        stopIfFailed("open ground in Blackwater");
        Playtest.queue("down to the street (scripted)", () -> {
            scripted("the player was put on open street ground in Blackwater at " + street[0].toShortString());
            server(() -> { me().teleportTo(street[0].getX() + 0.5, street[0].getY(), street[0].getZ() + 0.5); me().setDeltaMovement(Vec3.ZERO); return null; });
        }, () -> mc().player.blockPosition().distManhattan(street[0]) <= 1, 400);
        until("the ground has arrived", Scenarios::groundBelowPlayer, 2400);
        Playtest.wait("settle", 20);
        hold("the terminal", UndergroundCity.TERMINAL_ITEM.get());
        final BlockPos[] spot = new BlockPos[1];
        placeAhead("the terminal", UndergroundCity.TERMINAL.get(), spot, pos -> server(() -> BuildingGuard.protectedAt(level(), pos).isEmpty() && BuildingGuard.protectedAt(level(), pos.below()).isEmpty()));
        Playtest.queue("placed in Blackwater", () -> { F.placed = spot[0]; if (spot[0] == null || !mc().level.getBlockState(spot[0]).is(UndergroundCity.TERMINAL.get())) Playtest.fail("the terminal could not be placed in the Blackwater street"); });
        stopIfFailed("placing the terminal in Blackwater");
        use("open the terminal", () -> F.placed, () -> mc().screen instanceof TerminalScreen, 200);
        click("Work", true);
        Playtest.wait("draw", 12);
        Playtest.queue("before the delivery", () -> { F.walletBeforeDelivery = wallet(); F.xpBeforeDelivery = ThiefScenario.citizen().careerXp(); });
        final int[] t = {0};
        Playtest.queue("turn the wheel until the order is in view", () -> { t[0] = 0; Playtest.focusWindow(); }, () -> {
            if (!(mc().screen instanceof TerminalScreen screen)) return true;
            int i = JourneyScenario.jobIndex(ORDER);
            if (i >= 0 && screen.jobCentre(i) != null) return true;
            if (t[0]++ % 6 == 0) Playtest.click(5);
            return t[0] > 180;
        }, 200);
        clickAt("the licensed cannabis supply order", () -> { int i = JourneyScenario.jobIndex(ORDER); return i < 0 ? null : ((TerminalScreen) mc().screen).jobCentre(i); });
        shot("9-ready");
        click("Deliver", true);
        until("paid", () -> !orderTaken(), 200);
        Playtest.wait("settle", 10);
        shot("10-delivered");
        Playtest.queue("paid", () -> {
            double w = wallet();
            int xp = ThiefScenario.citizen().careerXp(), flowers = count(core("cannabis_flower"));
            Playtest.log(String.format(Locale.ROOT, "CANNABIS delivery in Blackwater: wallet %.0f -> %.0f, career XP %d -> %d, flower %d -> %d, order still taken %s", F.walletBeforeDelivery, w, F.xpBeforeDelivery, xp,
                F.flowers, flowers, orderTaken()));
            if (orderTaken()) Playtest.fail("the order was not delivered in Blackwater");
            else if (w - F.walletBeforeDelivery < 225) Playtest.fail("the delivery paid " + (w - F.walletBeforeDelivery) + " credits, not the order's 225 or more");
            else pass(String.format(Locale.ROOT, "10 deliver six Cannabis Flower at a terminal in Blackwater: %.0f credits and %d career XP", w - F.walletBeforeDelivery, xp - F.xpBeforeDelivery));
        });
        closeScreen("close the terminal");
    }

    // ------------------------------------------------------------------------------------------------ processing (D-160)

    /** The spare flower is hung on a Drying Rack in the Blackwater street and taken down dried after six minutes of active play. */
    private static void dry() {
        Playtest.queue("a Drying Rack, and flower to hang (scripted only where the harvest left too little)", () -> {
            int spare = count(core("cannabis_flower"));
            server(() -> { me().getInventory().add(new ItemStack(UndergroundCity.DRYING_RACK_ITEM.get())); return null; });
            String extra = "";
            if (spare < 2) { int give = 2 - spare; server(() -> { me().getInventory().add(new ItemStack(core("cannabis_flower"), give)); return null; }); extra = " and " + give + " Cannabis Flower (the harvest left " + spare + " after the order)"; }
            scripted("a Drying Rack was put in the inventory (seven sticks and a string at a crafting table)" + extra);
            L.driedBefore = count(core("dried_cannabis"));
        });
        hold("the Drying Rack", UndergroundCity.DRYING_RACK_ITEM.get());
        final BlockPos[] spot = new BlockPos[1];
        placeAhead("the Drying Rack", UndergroundCity.DRYING_RACK.get(), spot, pos -> server(() -> BuildingGuard.protectedAt(level(), pos).isEmpty() && BuildingGuard.protectedAt(level(), pos.below()).isEmpty()));
        Playtest.queue("the rack stands", () -> { L.rack = spot[0]; if (spot[0] == null || !mc().level.getBlockState(spot[0]).is(UndergroundCity.DRYING_RACK.get())) Playtest.fail("the Drying Rack could not be placed in the street"); });
        stopIfFailed("placing the Drying Rack");
        hold("the Cannabis Flower", core("cannabis_flower"));
        Playtest.queue("how much flower", () -> L.hung = count(core("cannabis_flower")));
        use("hang the flower on the rack with a right click", () -> L.rack,
            () -> mc().level.getBlockState(L.rack).getValue(DryingRack.LOAD) == DryingRack.Load.DRYING, 300);
        shot("11-drying");
        Playtest.queue("hung", () -> {
            int left = count(core("cannabis_flower"));
            L.hung -= left;
            Playtest.log("CANNABIS hung " + L.hung + " Cannabis Flower on the rack at " + L.rack.toShortString());
            if (L.hung < 2) Playtest.fail("the rack took " + L.hung + " flower");
        });
        stopIfFailed("hanging the flower");
        Playtest.wait("six minutes of play while it dries (the player stands in the street)", (int) DryingRack.DRY_TICKS + 200);
        Playtest.queue("an empty hand", () -> { Playtest.focusWindow(); Playtest.tap("9"); server(() -> { var inv = me().getInventory(); ItemStack held = inv.getItem(8); if (!held.isEmpty()) { inv.setItem(8, ItemStack.EMPTY); inv.add(held); } return null; }); });
        use("take the dried cannabis down with a right click", () -> L.rack,
            () -> count(core("dried_cannabis")) > L.driedBefore, 300);
        shot("12-dried");
        Playtest.queue("dried", () -> {
            int dried = count(core("dried_cannabis")) - L.driedBefore;
            Playtest.log("CANNABIS took down " + dried + " Dried Cannabis from " + L.hung + " flower");
            if (dried != DryingRack.driedFrom(L.hung)) Playtest.fail("the rack gave " + dried + " Dried Cannabis for " + L.hung + " flower, not " + DryingRack.driedFrom(L.hung));
            else pass("11 hang " + L.hung + " Cannabis Flower on a Drying Rack in the street and, after six minutes of play, take down " + dried + " Dried Cannabis");
        });
    }

    // ------------------------------------------------------------------------------------------------ growing in public view (D-160)

    /** A plant grown in an Ironhaven street, where the crop is prohibited: the officer on the beat who sees it pulls it up, and the grower gains heat. */
    private static void publicGrowing() {
        ThiefScenario.travel("to the middle of Ironhaven", () -> ThiefScenario.centre("ironhaven"), 4);
        final BlockPos[] street = new BlockPos[1];
        Playtest.queue("survival, and open street ground nearby that nobody owns", () -> {
            Playtest.command("gamemode survival");
            street[0] = server(() -> openGround(me().blockPosition(), 40, false));
            if (street[0] == null) Playtest.fail("no open street ground near the middle of Ironhaven");
        });
        stopIfFailed("open ground in Ironhaven");
        Playtest.queue("down to the street (scripted)", () -> {
            scripted("the player was put on open street ground in Ironhaven at " + street[0].toShortString());
            server(() -> { me().teleportTo(street[0].getX() + 0.5, street[0].getY(), street[0].getZ() + 0.5); me().setDeltaMovement(Vec3.ZERO); return null; });
        }, () -> mc().player.blockPosition().distManhattan(street[0]) <= 1, 400);
        until("the ground has arrived", Scenarios::groundBelowPlayer, 2400);
        Playtest.wait("settle", 20);
        Playtest.queue("a block of dirt to plant in, and a seed if none is left (scripted)", () -> {
            boolean seed = count(core("cannabis_seeds")) < 1;
            server(() -> { me().getInventory().add(new ItemStack(Items.DIRT)); if (seed) me().getInventory().add(new ItemStack(core("cannabis_seeds"))); return null; });
            scripted("a block of dirt was put in the inventory (the street is paved)" + (seed ? " and one Cannabis Seed (the harvest left none)" : ""));
            L.heatBefore = server(() -> WorkOrderLedger.get(me().server).heat(me().getUUID(), "ironhaven"));
        });
        hold("the dirt", Items.DIRT);
        final BlockPos[] spot = new BlockPos[1];
        placeAhead("the dirt", Blocks.DIRT, spot, pos -> server(() -> BuildingGuard.protectedAt(level(), pos).isEmpty() && BuildingGuard.protectedAt(level(), pos.below()).isEmpty() && level().canSeeSky(pos.above())));
        Playtest.queue("the dirt is down", () -> { L.dirt = spot[0]; if (spot[0] == null || !mc().level.getBlockState(spot[0]).is(Blocks.DIRT)) Playtest.fail("the dirt could not be put down in the street"); });
        stopIfFailed("putting the dirt down");
        hold("the wooden hoe", Items.WOODEN_HOE);
        use("hoe the dirt", () -> L.dirt, () -> mc().level.getBlockState(L.dirt).is(Blocks.FARMLAND), 300);
        hold("the Cannabis Seeds", core("cannabis_seeds"));
        use("plant a seed in the street", () -> L.dirt, () -> mc().level.getBlockState(L.dirt.above()).getBlock() instanceof RestrictedCropBlock, 300);
        shot("13-street-plant");
        Playtest.queue("planted in public view", () -> {
            boolean planted = server(() -> level().getBlockState(L.dirt.above()).getBlock() instanceof RestrictedCropBlock);
            var view = server(() -> CannabisLaw.inPublicView(level(), L.dirt.above()));
            Playtest.log("CANNABIS street plant at " + L.dirt.above().toShortString() + ": planted " + planted + ", in public view " + view.orElse("no"));
            if (!planted || view.isEmpty()) Playtest.fail("the plant is not standing in public view in Ironhaven");
        });
        stopIfFailed("planting in the street");
        Playtest.queue("the random tick speed while waiting for the beat (scripted)", () -> {
            Playtest.command("gamerule randomTickSpeed 30");
            scripted("the random tick speed was raised to 30 while waiting for an officer: a plant looks round on its random ticks; the officer's coming and seeing are the beat's own");
        });
        Playtest.queue("wait in the street for an officer on the beat to see the plant (up to fifteen minutes)", () -> { }, () -> !(mc().level.getBlockState(L.dirt.above()).getBlock() instanceof RestrictedCropBlock), 18000);
        Playtest.queue("the random tick speed back to 3", () -> Playtest.command("gamerule randomTickSpeed 3"));
        shot("14-pulled-up");
        Playtest.queue("pulled up", () -> {
            boolean gone = server(() -> !(level().getBlockState(L.dirt.above()).getBlock() instanceof RestrictedCropBlock));
            int heat = server(() -> WorkOrderLedger.get(me().server).heat(me().getUUID(), "ironhaven"));
            long seized = server(() -> CannabisLaw.plantings(me().server).seized);
            Playtest.log("CANNABIS street plant gone " + gone + "; seized by the law " + seized + "; Ironhaven heat " + L.heatBefore + " -> " + heat);
            if (!gone) Playtest.fail("no officer pulled the plant up in fifteen minutes");
            else if (seized < 1 || heat <= L.heatBefore) Playtest.fail("the plant went, but not by the law (seized " + seized + ", heat " + L.heatBefore + " -> " + heat + ")");
            else pass("12 a plant grown in an Ironhaven street is seen by the officer on the beat and pulled up; Ironhaven's heat rises by " + (heat - L.heatBefore));
        });
    }
}
