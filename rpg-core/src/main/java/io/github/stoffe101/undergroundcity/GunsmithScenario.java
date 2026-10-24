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
        build("the first pistol, from the kit the starter kit carries", "6");
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
            else pass("7 after four minutes of play, Take (a real click) hands over the Firearm Parts Kit the bench made");
        });
        closeScreen("close the bench");
        stopIfFailed("the bench's kit");
        Playtest.queue("four iron ingots for the second gun (scripted)", () -> {
            server(() -> { me().getInventory().add(new ItemStack(Items.IRON_INGOT, 4)); return null; });
            scripted("four iron ingots were given (the regional exchanges sell iron; the starter kit's four went into the first gun)");
        });
        build("the second pistol, from the bench's kit", "8");
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
        final BlockPos[] spot = new BlockPos[1];
        JourneyScenario.placeAhead("the terminal", UndergroundCity.TERMINAL.get(), spot, pos -> server(() -> BuildingGuard.protectedAt(level(), pos).isEmpty() && BuildingGuard.protectedAt(level(), pos.below()).isEmpty()));
        Playtest.queue("the terminal stands", () -> { F.terminal = spot[0]; if (spot[0] == null || !mc().level.getBlockState(spot[0]).is(UndergroundCity.TERMINAL.get())) Playtest.fail("the terminal could not be placed"); });
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
            else pass("9 Accept and Deliver (real clicks) hand both registered pistols to the city armoury for " + Math.round(paid) + " credits");
        });
        shot("5-delivered");
        closeScreen("close the terminal");
        Playtest.queue("summary", ThiefScenario::summary);
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
            if (type[0] == null) Playtest.fail("TaCZ's table screen does not list the kit recipe " + KIT_GLOCK);
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
