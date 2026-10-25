package io.github.stoffe101.undergroundcity;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;

import java.util.Locale;
import java.util.function.Supplier;

import static io.github.stoffe101.undergroundcity.JourneyScenario.aimedAt;
import static io.github.stoffe101.undergroundcity.JourneyScenario.level;
import static io.github.stoffe101.undergroundcity.JourneyScenario.look;
import static io.github.stoffe101.undergroundcity.JourneyScenario.mc;
import static io.github.stoffe101.undergroundcity.JourneyScenario.me;
import static io.github.stoffe101.undergroundcity.JourneyScenario.server;
import static io.github.stoffe101.undergroundcity.JourneyScenario.until;
import static io.github.stoffe101.undergroundcity.ThiefScenario.pass;
import static io.github.stoffe101.undergroundcity.ThiefScenario.scripted;

/**
 * The real-client steps the career scenarios share (seventh pass): finding an open shop of a furnished type, placing a workshop station with a real right click on open ground no building owns,
 * opening it, running one batch with real clicks on its row, Start and Take while its time passes on the active clock, fitting a tier, walking in at a shop's door and selling or buying over its
 * counter, learning a skill on a terminal's Skills page, crafting a Core station through the vanilla recipe book, and saving, leaving and reopening the world. Every shortcut a caller takes is
 * logged by the caller as SCRIPTED; nothing here grants anything.
 */
final class StationSteps {
    private StationSteps() {}

    /** One placed station: where it stands (set once placed). */
    static final class Station {
        final String id;
        volatile BlockPos pos;
        Station(String id) { this.id = id; }
    }

    static String tag() { return ThiefScenario.tag; }

    // ------------------------------------------------------------------------------------------------ places

    /** The nearest building of a furnished type (by the end of its type id) within twelve chunks of the player, open now when {@code open}. Server thread. */
    static CityBuildings.Building nearest(String suffix, boolean open) {
        ServerLevel level = level();
        BlockPos here = me().blockPosition();
        for (int r = 0; r <= 12; r++) for (int dx = -r; dx <= r; dx++) for (int dz = -r; dz <= r; dz++) {
            if (Math.max(Math.abs(dx), Math.abs(dz)) != r) continue;
            int cx = (here.getX() >> 4) + dx, cz = (here.getZ() >> 4) + dz;
            if (!level.hasChunk(cx, cz)) continue;
            CityBuildings.Building b = CityBuildings.INSTANCE.ofChunk(level, cx, cz).orElse(null);
            if (b != null && b.type().endsWith(suffix) && (!open || ShopCounters.openNow(level, b))) return b;
        }
        return null;
    }

    static boolean standable(ServerLevel level, BlockPos c) {
        return level.getBlockState(c).isAir() && level.getBlockState(c.above()).isAir() && level.getBlockState(c.below()).isSolidRender(level, c.below());
    }

    static Vec3 standBeside(BlockPos block) {
        if (block == null) return mc().player.position();
        return server(() -> {
            for (Direction d : Direction.Plane.HORIZONTAL) {
                BlockPos c = block.relative(d);
                if (standable(level(), c)) return Vec3.atBottomCenterOf(c);
            }
            return Vec3.atBottomCenterOf(block.relative(Direction.NORTH));
        });
    }

    /** In at the street door like a customer: walk to the step, a right click on the door until it stands open. */
    static void enter(String name, Supplier<DoorWalk.Spot> door) {
        ThiefScenario.walkOrTeleport("walk to the door of " + name, () -> door.get().stand(), 0.8, 1500);
        final int[] knock = {0};
        Playtest.queue("open the door of " + name, () -> { knock[0] = 0; Playtest.focusWindow(); }, () -> {
            BlockPos d = door.get().door();
            if (server(() -> level().getBlockState(d).getBlock() instanceof net.minecraft.world.level.block.DoorBlock && level().getBlockState(d).getValue(net.minecraft.world.level.block.DoorBlock.OPEN))) return true;
            look(Vec3.atCenterOf(d).add(0, knock[0] % 96 < 48 ? 0.4 : 1.2, 0));   // the lower half, then the upper (somebody in the doorway hides the lower half)
            if (++knock[0] % 24 == 12 && (aimedAt(d) || aimedAt(d.above()))) Playtest.click(3);
            if (knock[0] % 96 == 60) {
                var hit = mc().hitResult;
                String what = mc().crosshairPickEntity != null ? mc().crosshairPickEntity.getName().getString()
                    : hit instanceof net.minecraft.world.phys.BlockHitResult b ? mc().level.getBlockState(b.getBlockPos()).getBlock().getDescriptionId() + " at " + b.getBlockPos().toShortString() : "nothing";
                Playtest.log(tag() + " the door of " + name + " at " + d.toShortString() + " is still shut; the crosshair is on " + what + ", " + String.format(java.util.Locale.ROOT, "%.1f", mc().player.position().distanceTo(Vec3.atCenterOf(d))) + " blocks off");
            }
            return false;
        }, 800);
    }

    /** The shopkeeper of a building (its household worker who serves at the shop's spot). */
    static TownspersonEntity keeper(CityBuildings.Building b, DoorWalk.Spot door) {
        return server(() -> {
            if (b == null || door == null) return null;
            for (TownspersonEntity body : level().getEntitiesOfClass(TownspersonEntity.class, new net.minecraft.world.phys.AABB(door.door()).inflate(40)))
                if (body.homeKey() == b.key() && ShopCounters.shopOf(body).isPresent()) return body;
            return null;
        });
    }

    /**
     * A cell of the building's public floor in sight of the keeper, one to four blocks from them; failing that the public cell nearest them (the yard's keeper stands behind a desk with
     * no public cell in sight within four blocks: smith run 2), from where the conversation's own walker finds a clear side.
     */
    static Vec3 beforeKeeper(CityBuildings.Building b, TownspersonEntity k) {
        if (k == null) return null;
        return server(() -> {
            StaffZones.Zones z = StaffZones.of(level(), b);
            BlockPos best = null, nearest = null;
            double bestD = Double.MAX_VALUE, nearestD = Double.MAX_VALUE;
            for (long key : z.publicCells()) {
                BlockPos c = BlockPos.of(key);
                double d = c.distToCenterSqr(k.position());
                if (d >= 2.0 && d < nearestD && standable(level(), c)) { nearest = c; nearestD = d; }
                if (d < 2.0 || d > 16.0 || d >= bestD) continue;
                if (!level().clip(new net.minecraft.world.level.ClipContext(Vec3.atCenterOf(c).add(0, 1.1, 0), k.getEyePosition(), net.minecraft.world.level.ClipContext.Block.COLLIDER,
                    net.minecraft.world.level.ClipContext.Fluid.NONE, k)).getType().equals(net.minecraft.world.phys.HitResult.Type.MISS)) continue;
                best = c; bestD = d;
            }
            BlockPos chosen = best != null ? best : nearest;
            return chosen == null ? null : Vec3.atBottomCenterOf(chosen);
        });
    }

    /** Walk in at the door, across the public floor to the keeper, speak, "What do you sell?", the Trade page: the shop's counter is open on the terminal screen. */
    static void toCounter(String name, Supplier<CityBuildings.Building> building, Supplier<DoorWalk.Spot> door) {
        enter(name, door);
        final Vec3[] floor = new Vec3[1];
        Playtest.queue("a place in front of the keeper of " + name, () -> {
            TownspersonEntity k = keeper(building.get(), door.get());
            floor[0] = beforeKeeper(building.get(), k);
            Playtest.log(tag() + " keeper of " + name + " " + (k == null ? "not found" : "at " + k.blockPosition().toShortString()) + "; standing at " + (floor[0] == null ? "nowhere" : BlockPos.containing(floor[0]).toShortString()));
            if (floor[0] == null) Playtest.fail("no public floor in sight of the keeper of " + name);
        });
        ThiefScenario.walkOrTeleport("walk to the counter of " + name, () -> floor[0] == null ? mc().player.position() : floor[0], 0.6, 1200);
        JourneyScenario.speakTo("the keeper of " + name, () -> keeper(building.get(), door.get()));
        JourneyScenario.choose("What do you sell?", "terminal");
        JourneyScenario.click("TRADE", true);
        Playtest.wait("draw", 10);
        Playtest.queue("the counter of " + name, () -> {
            if (!(mc().screen instanceof TerminalScreen t)) { Playtest.fail("the counter of " + name + " did not open"); return; }
            for (var o : t.data().offers()) Playtest.log(tag() + " " + name + " offer " + o.id() + " " + o.price() + " each, " + o.stock() + "/" + o.capacity() + (o.pays() > 0 ? ", buys at " + o.pays() + ", carried " + o.carried() : ""));
        });
    }

    /** The Operations Terminal block inside a building (the public one its plan carries, D-175). Server thread. */
    static BlockPos terminalIn(CityBuildings.Building b) {
        ServerLevel level = level();
        for (int y = b.streetY(); y < b.streetY() + 12; y++) for (int x = 0; x < 16; x++) for (int z = 0; z < 16; z++) {
            BlockPos p = new BlockPos((b.chunkX() << 4) + x, y, (b.chunkZ() << 4) + z);
            if (level.getBlockState(p).getBlock() instanceof TerminalBlock) return p;
        }
        return null;
    }

    // ------------------------------------------------------------------------------------------------ a station

    /** Moves an item to a hotbar slot (logged as scripted when it had to come from the backpack rows) and selects that slot with its number key. */
    static void hold(String label, Item item) {
        Playtest.queue("hold " + label, () -> {
            Playtest.focusWindow();
            int slot = -1;
            for (int i = 0; i < 9; i++) if (mc().player.getInventory().getItem(i).is(item)) slot = i;
            if (slot < 0) {
                scripted(label + " was moved from the backpack rows to the last hotbar slot");
                server(() -> {
                    var inv = me().getInventory();
                    for (int i = 9; i < inv.items.size(); i++) if (inv.getItem(i).is(item)) { ItemStack moved = inv.getItem(i); inv.setItem(i, inv.getItem(8)); inv.setItem(8, moved); break; }
                    return null;
                });
                slot = 8;
            }
            Playtest.tap(String.valueOf(slot + 1));
        });
        Playtest.wait("select", 4);
    }

    /** Places a station from the inventory with a real right click on open ground no building owns, within 24 blocks of the player, walking there first. */
    static void place(Station station, Item item) {
        final Vec3[] stand = new Vec3[1];
        Playtest.queue("open ground for the " + station.id, () -> {
            Object[] found = server(() -> {
                ServerLevel level = level();
                BlockPos from = me().blockPosition();
                for (int r = 3; r <= 24; r++) for (int dx = -r; dx <= r; dx++) for (int dz = -r; dz <= r; dz++) {
                    if (Math.max(Math.abs(dx), Math.abs(dz)) != r) continue;
                    for (int dy = 1; dy >= -2; dy--) {
                        BlockPos c = from.offset(dx, dy, dz);
                        if (!standable(level, c) || BuildingGuard.protectedAt(level, c).isPresent() || BuildingGuard.protectedAt(level, c.below()).isPresent()) continue;
                        if (level.getBlockState(c.below()).getBlock() instanceof WorkshopBlock) continue;
                        Direction back = Math.abs(dx) >= Math.abs(dz) ? (dx > 0 ? Direction.WEST : Direction.EAST) : (dz > 0 ? Direction.NORTH : Direction.SOUTH);
                        BlockPos s2 = c.relative(back, 2), between = c.relative(back);
                        // the cell between must be clear too: the aim from two blocks back crosses it at knee height, and a lawn's tall grass there took the click (farmer run 1)
                        if (!standable(level, s2) || !level.getBlockState(between).isAir() || !level.getBlockState(between.above()).isAir()) continue;
                        return new Object[]{c.immutable(), Vec3.atBottomCenterOf(s2)};
                    }
                }
                return null;
            });
            if (found != null) { station.pos = (BlockPos) found[0]; stand[0] = (Vec3) found[1]; }
            Playtest.log(tag() + " the " + station.id + " goes at " + (station.pos == null ? "nowhere" : station.pos.toShortString()));
            if (station.pos == null) Playtest.fail("no open ground within 24 blocks that no building owns");
        });
        ThiefScenario.walkOrTeleport("walk to the open ground", () -> stand[0] == null ? mc().player.position() : stand[0], 0.8, 1200);
        hold("the " + station.id, item);
        final int[] t = {0};
        Playtest.queue("right-click the ground with the " + station.id + " in hand", () -> { t[0] = 0; Playtest.focusWindow(); }, () -> {
            if (station.pos == null) return true;
            if (server(() -> level().getBlockState(station.pos).getBlock() instanceof WorkshopBlock)) return true;
            look(Vec3.atCenterOf(station.pos.below()).add(0, 0.5, 0));
            if (++t[0] % 24 == 12 && aimedAt(station.pos.below())) Playtest.click(3);
            return false;
        }, 400);
        // the placing click can leave a screen open (gunsmith run 3): shut it before the station is opened on purpose
        JourneyScenario.closeScreen("close anything the placing click opened");
        Playtest.queue("the " + station.id + " stands", () -> {
            boolean placed = station.pos != null && server(() -> level().getBlockState(station.pos).getBlock() instanceof WorkshopBlock wb && wb.station.equals(station.id));
            if (!placed) Playtest.fail("the " + station.id + " was not placed");
        });
    }

    static void open(Station station, String label) {
        final int[] t = {0};
        Playtest.queue("right-click the " + station.id + ": " + label, () -> { t[0] = 0; Playtest.focusWindow(); }, () -> {
            if (mc().screen instanceof WorkshopScreen) return true;
            look(Vec3.atCenterOf(station.pos));
            if (++t[0] % 24 == 12 && aimedAt(station.pos)) Playtest.click(3);
            return false;
        }, 400);
        Playtest.wait("draw", 12);
    }

    /** Logs the station's recipes as its screen shows them (allowed or why not). */
    static void logRecipes(Station station) {
        Playtest.queue("the " + station.id + "'s recipes", () -> {
            if (!(mc().screen instanceof WorkshopScreen s)) { Playtest.fail("the " + station.id + "'s screen did not open"); return; }
            var v = s.view();
            Playtest.log(tag() + " " + v.title() + " tier " + v.tier() + "/" + v.maxTier() + "; " + v.rows().stream().map(r -> r.id() + (r.allowed() ? "" : " (" + r.reason() + ")")).toList());
        });
    }

    static boolean allowed(String recipe) {
        return mc().screen instanceof WorkshopScreen s && s.view().rows().stream().anyMatch(r -> r.id().equals(recipe) && r.allowed());
    }

    /**
     * One batch with the station's screen open: a real click on the row (the mouse wheel first when it is below the fold), Start, the batch's time on the active clock, Take. Passes with
     * {@code passText} when {@code expect} more of {@code output} are carried and the career XP rose. Returns nothing; the caller reads the grade or the count after.
     */
    /**
     * Clicks a recipe's row with the real pointer until it is selected, at most three times: under a loaded machine a click was taken a few ticks after the check that looked for it (smith run 5),
     * and a person whose click did not take clicks again.
     */
    static void selectRow(String recipe) {
        final int[] t = {0}, clicks = {0};
        Playtest.queue("click the " + recipe + " row until it is selected", () -> { t[0] = 0; clicks[0] = 0; }, () -> {
            if (!(mc().screen instanceof WorkshopScreen s)) return true;
            if (s.selectedId().equals(recipe)) return true;
            int[] at = s.rowCentre(recipe);
            if (at != null && t[0] % 30 == 0 && clicks[0] < 3) {
                if (clicks[0] > 0) Playtest.log(tag() + " the click on the " + recipe + " row had not taken; clicking it again");
                if (t[0] % 60 == 0) Playtest.moveGui(at[0], at[1]); else Playtest.pressLeft();
                if (t[0] % 60 != 0) clicks[0]++;
            }
            t[0]++;
            return t[0] > 200;
        }, 220);
        Playtest.queue(recipe + " is selected", () -> {
            if (!(mc().screen instanceof WorkshopScreen s) || !s.selectedId().equals(recipe)) Playtest.fail("three clicks on the row did not select " + recipe);
        });
    }

    static void batch(Station station, String recipe, String output, int expect, String passText) {
        Item out = net.minecraftforge.registries.ForgeRegistries.ITEMS.getValue(net.minecraft.resources.ResourceLocation.parse(output));
        final int[] before = new int[2];
        final long[] started = {0};
        Playtest.queue("before " + recipe, () -> {
            before[0] = JourneyScenario.count(out);
            CitizenState.Citizen c = ThiefScenario.citizen();
            before[1] = c == null ? 0 : c.careerXp();
        });
        final int[] wheel = {0};
        Playtest.queue("turn the wheel until the " + recipe + " row is in view", () -> wheel[0] = 0, () -> {
            if (!(mc().screen instanceof WorkshopScreen s) || s.rowCentre(recipe) != null) return true;
            if (wheel[0]++ % 6 == 0) Playtest.click(5);
            return wheel[0] > 90;
        }, 120);
        selectRow(recipe);
        Playtest.wait("select", 4);
        JourneyScenario.click("Start", true);
        until(recipe + " starts", () -> mc().screen instanceof WorkshopScreen s && !s.view().running().isEmpty(), 100);
        Playtest.queue(recipe + " is running", () -> {
            started[0] = server(() -> LockService.clock(mc().getSingleplayerServer()));
            if (mc().screen instanceof WorkshopScreen s) Playtest.log(tag() + " started " + s.view().running() + ": " + s.view().secondsLeft() + " s left");
        });
        until(recipe + " is done", () -> server(() -> !level().getBlockState(station.pos).getValue(WorkshopBlock.WORKING)), 20 * 60 * 20);
        // the open screen asks the station again once its countdown is up (smith run 1 found Take grey until the station was reopened)
        until("the screen shows the batch ready", () -> mc().screen instanceof WorkshopScreen s && s.view().readyCount() > 0, 400);
        JourneyScenario.click("Take", true);
        until(recipe + " is taken", () -> JourneyScenario.count(out) >= before[0] + expect, 100);
        Playtest.queue(recipe + " taken", () -> {
            long now = server(() -> LockService.clock(mc().getSingleplayerServer()));
            CitizenState.Citizen c = ThiefScenario.citizen();
            int got = JourneyScenario.count(out) - before[0], xp = c == null ? 0 : c.careerXp();
            Playtest.log(String.format(Locale.ROOT, "%s %s: %d made in %.2f minutes of active play, career XP %d -> %d; grades %s", tag(), recipe, got, (now - started[0]) / 1200.0, before[1], xp, grades(out)));
            if (got < expect || xp <= before[1]) Playtest.fail(recipe + " did not hand over " + expect + " or paid no career XP");
            else pass(passText + " (+" + (xp - before[1]) + " career XP)");
        });
    }

    /** The grades of the carried stacks of an item, for the log ("2 Fine, 1 Standard"). */
    static String grades(Item item) {
        return server(() -> {
            java.util.Map<String, Integer> by = new java.util.TreeMap<>();
            for (ItemStack st : me().getInventory().items) if (st.is(item)) by.merge(Workshops.GRADES[Math.max(0, Math.min(Workshops.GRADES.length - 1, Workshops.grade(st)))], st.getCount(), Integer::sum);
            return by.toString();
        });
    }

    /** Fits the next tier with a real click on its button; passes when the station and its screen show {@code tier}. */
    static void fitTier(Station station, int tier, String passText) {
        JourneyScenario.click("Fit tier " + tier, true);
        until("the " + station.id + " is tier " + tier, () -> server(() -> level().getBlockState(station.pos).getValue(WorkshopBlock.TIER) == tier), 100);
        until("the screen shows tier " + tier, () -> mc().screen instanceof WorkshopScreen s && s.view().tier() == tier, 100);
        Playtest.queue("tier " + tier, () -> {
            var v = mc().screen instanceof WorkshopScreen s ? s.view() : null;
            Playtest.log(tag() + " fitted: " + (v == null ? "?" : v.tierName() + ", " + v.batch() + " at a time, " + v.speedPercent() + "% speed"));
            if (v == null || v.tier() != tier) Playtest.fail("tier " + tier + " was not fitted");
            else pass(passText);
        });
    }

    // ------------------------------------------------------------------------------------------------ the terminal and counters

    static void learn(String node, String title) {
        JourneyScenario.click("SKILLS", true);
        Playtest.wait("draw", 10);
        final int[] t = {0};
        Playtest.queue("turn the wheel until " + title + " is in view", () -> { t[0] = 0; Playtest.focusWindow(); }, () -> {
            if (!(mc().screen instanceof TerminalScreen screen)) return true;
            int where = screen.skillBeyond(node);
            if (where == 0 || where == 2) return true;
            if (t[0]++ % 6 == 0) Playtest.click(where > 0 ? 5 : 4);
            return false;
        }, 200);
        JourneyScenario.clickAt("the " + title + " card", () -> mc().screen instanceof TerminalScreen screen ? screen.skillCentre(node) : null);
        Playtest.wait("select", 6);
        JourneyScenario.click("Unlock", true);
        until(title + " learned", () -> server(() -> CitizenState.get(mc().getSingleplayerServer()).find(mc().player.getUUID()).map(c -> c.skills().contains(node)).orElse(false)), 200);
    }

    static void buy(String label, String line, Item item, int n) {
        for (int i = 0; i < n; i++) {
            final int[] had = {0};
            // a purchase greys every Buy button until the terminal's next snapshot; under a server stall the next click came first and was lost (farmer run 3)
            until("the Buy button for " + label + " is lit", () -> mc().screen instanceof TerminalScreen t && t.buyReady(line), 400);
            final int[] clicks = {0}, since = {0};
            Playtest.queue("buy " + label + " (" + (i + 1) + "/" + n + ")", () -> {
                had[0] = JourneyScenario.count(item);
                clicks[0] = 1; since[0] = 0;
                if (!(mc().screen instanceof TerminalScreen t) || !t.clickBuyOffer(line)) Playtest.fail("no active BUY 1 on " + line);
            });
            // a click the server was too busy for comes back as a lit screen with nothing bought (farmer run 8); a person clicks again
            Playtest.queue("bought " + label, () -> { }, () -> {
                if (JourneyScenario.count(item) > had[0]) return true;
                if (++since[0] > 30 && clicks[0] < 3 && mc().screen instanceof TerminalScreen t && t.buyReady(line)) {
                    Playtest.log(tag() + " the click on Buy for " + label + " bought nothing; clicking again");
                    clicks[0]++; since[0] = 0;
                    t.clickBuyOffer(line);
                }
                return false;
            }, 300);
            Playtest.wait("draw", 6);
        }
    }

    /** Sells everything carried of a line's item with one real click on its Sell button; passes when the wallet rose by at least {@code minPaid} and the shelf by what was sold. */
    static void sell(String line, Item item, int minPaid, String passText) {
        final double[] wallet = new double[1];
        final int[] shelf = new int[1], carried = new int[1];
        Playtest.queue("before selling " + line, () -> {
            wallet[0] = JourneyScenario.wallet();
            carried[0] = JourneyScenario.count(item);
            shelf[0] = server(() -> WorkOrderLedger.get(mc().getSingleplayerServer()).regionalUnits(RegionalCatalog.INSTANCE.find(line)));
            if (!(mc().screen instanceof TerminalScreen t) || !t.clickSellOffer(line)) Playtest.fail("no active Sell button on " + line);
        });
        until("the sale lands", () -> JourneyScenario.count(item) < carried[0], 200);
        Playtest.wait("draw", 10);
        Playtest.queue("sold on " + line, () -> {
            double w = JourneyScenario.wallet();
            int s = server(() -> WorkOrderLedger.get(mc().getSingleplayerServer()).regionalUnits(RegionalCatalog.INSTANCE.find(line)));
            int sold = carried[0] - JourneyScenario.count(item);
            Playtest.log(tag() + " sold " + sold + " on " + line + ": wallet " + wallet[0] + " -> " + w + ", shelf " + shelf[0] + " -> " + s);
            if (w - wallet[0] < minPaid - 0.01 || s != shelf[0] + sold) Playtest.fail("the sale on " + line + " did not pay or did not shelve what was sold");
            else pass(passText + ": +" + Math.round(w - wallet[0]) + " credits, shelf " + shelf[0] + " -> " + s);
        });
    }

    // ------------------------------------------------------------------------------------------------ the recipe book (D-175)

    /**
     * Crafts a Core station through the vanilla recipe book on a crafting table the player stands at: the book's button, the station's recipe (found by its result on the book's pages), which
     * fills the grid, then a Shift-click on the result. Passes when one more of {@code result} is carried.
     */
    static void craftWithBook(Supplier<BlockPos> table, Item result, String passText) {
        JourneyScenario.use("open the crafting table", table, () -> mc().screen instanceof net.minecraft.client.gui.screens.inventory.CraftingScreen, 400);
        Playtest.wait("draw", 10);
        final int[] had = {0};
        Playtest.queue("before crafting", () -> had[0] = JourneyScenario.count(result));
        Playtest.queue("open the recipe book", () -> {
            if (!(mc().screen instanceof net.minecraft.client.gui.screens.inventory.CraftingScreen s)) { Playtest.fail("the crafting screen is not open"); return; }
            if (s.getRecipeBookComponent().isVisible()) return;
            for (var child : s.children())
                if (child instanceof net.minecraft.client.gui.components.ImageButton b) { Playtest.clickGui(b.getX() + b.getWidth() / 2, b.getY() + b.getHeight() / 2); return; }
            Playtest.fail("no recipe book button on the crafting screen");
        });
        Playtest.wait("the book opens", 10);
        final int[] t = {0};
        Playtest.queue("find and click the recipe in the book", () -> t[0] = 0, () -> {
            if (!(mc().screen instanceof net.minecraft.client.gui.screens.inventory.CraftingScreen s)) return true;
            if (s.getMenu().getSlot(0).hasItem() && s.getMenu().getSlot(0).getItem().is(result)) return true;
            int[] at = RecipeBookProbe.buttonFor(s, result);
            // one click fills one set; a second click before the grid shows the first fills two, and the Shift-click then makes both (farmer run 9 had too few planks left for its table)
            if (at != null) { if (t[0]++ % 60 == 0) Playtest.clickGui(at[0], at[1]); return false; }
            if (t[0]++ % 12 == 0 && !RecipeBookProbe.nextPage(s)) { Playtest.fail("the recipe book does not show " + result + " on any page"); return true; }
            return t[0] > 240;
        }, 400);
        Playtest.queue("the grid is filled", () -> {
            if (!(mc().screen instanceof net.minecraft.client.gui.screens.inventory.CraftingScreen s) || !s.getMenu().getSlot(0).getItem().is(result)) Playtest.fail("the recipe did not fill the grid with " + result);
        });
        ThiefScenario.shot("recipe-book-" + net.minecraftforge.registries.ForgeRegistries.ITEMS.getKey(result).getPath());
        // Shift-click the result into the inventory; under a loaded machine the Shift press can land after the click and leave the result on the pointer (farmer run 7), so a result
        // on the pointer is put down in an empty slot, and a click that took nothing is made again, as a person would
        final int[] c = {0};
        Playtest.queue("take the result (Shift-click)", () -> c[0] = 0, () -> {
            if (JourneyScenario.count(result) > had[0]) { Playtest.keyUp("left.shift"); return true; }
            if (!(mc().screen instanceof net.minecraft.client.gui.screens.inventory.CraftingScreen s)) return true;
            var menu = s.getMenu();
            if (menu.getCarried().is(result)) {
                if (c[0]++ % 20 == 0) for (int i = 10; i < menu.slots.size(); i++) {
                    var slot = menu.getSlot(i);
                    if (!slot.hasItem()) { Playtest.keyUp("left.shift"); Playtest.clickGui(s.getGuiLeft() + slot.x + 8, s.getGuiTop() + slot.y + 8); break; }
                }
                return false;
            }
            var out = menu.getSlot(0);
            switch (c[0]++ % 40) {
                case 0 -> Playtest.keyDown("left.shift");
                case 6 -> Playtest.clickGui(s.getGuiLeft() + out.x + 8, s.getGuiTop() + out.y + 8);
                case 14 -> Playtest.keyUp("left.shift");
                default -> { }
            }
            return c[0] > 240;
        }, 260);
        until("crafted", () -> JourneyScenario.count(result) > had[0], 200);
        Playtest.queue("crafted " + result, () -> {
            if (JourneyScenario.count(result) <= had[0]) Playtest.fail("nothing was crafted");
            else pass(passText);
        });
        JourneyScenario.closeScreen("close the crafting table");
    }

    // ------------------------------------------------------------------------------------------------ save and return

    /** Saves, leaves to the title screen and opens the world again; {@code check} runs after, with {@code before} what it read before leaving. */
    static void reconnect(Supplier<Object> read, java.util.function.BiConsumer<Object, Object> check) {
        final Object[] before = new Object[1];
        Playtest.queue("before leaving", () -> { before[0] = read.get(); Playtest.log(tag() + " before leaving: " + before[0]); });
        Playtest.queue("save and quit to the title screen, then open the world again", () -> {
            var mc = mc();
            String folder = System.getProperty("uc.playtest.world", "UC-Playtest");
            mc.level.disconnect();
            mc.clearLevel(new net.minecraft.client.gui.screens.GenericDirtMessageScreen(net.minecraft.network.chat.Component.translatable("menu.savingLevel")));
            mc.setScreen(new net.minecraft.client.gui.screens.TitleScreen());
            mc.createWorldOpenFlows().loadLevel(mc.screen, folder);
        }, () -> mc().player != null && mc().level != null && Scenarios.groundBelowPlayer(), 6000);
        Playtest.wait("settle", 60);
        Playtest.queue("after reopening", () -> {
            Object after = read.get();
            Playtest.log(tag() + " after reopening: " + after);
            check.accept(before[0], after);
        });
    }
}
