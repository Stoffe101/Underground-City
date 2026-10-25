package io.github.stoffe101.undergroundcity;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
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
import static io.github.stoffe101.undergroundcity.ThiefScenario.shot;

/**
 * A Smith's trade through a real client (D-163, D-164; the seventh pass's first career journey). Two tracks, never mixed up:
 *
 * <p><b>Track A, organic:</b> a new citizen registers as a Smith, places the Machinist's Bench from the starter kit with a real right click on open ground near Ironhaven's Builders' Yard, makes
 * fasteners and brackets from the kit's iron with real clicks on Start and Take (each batch's seconds pass on the active clock, nothing skipped), walks in at the yard's door and sells the fasteners
 * over the counter with the Sell button. Scripted, each logged as SCRIPTED: the clock (13:00, stopped, so the yard is open), the trip to Ironhaven and the start in front of the yard.
 *
 * <p><b>Track B, prepared:</b> the Smith's XP is raised to level 4 and 60 credits are added, as after an hour of orders (both logged as SCRIPTED); the rest is real clicks: Forge Fundamentals and
 * Structural Works learned on the terminal's Skills page, copper and redstone bought at the Hardware and Metals desk, refined alloy bought on the exchange at the workshop's public terminal,
 * springs and a gear set made at the bench, the Drill Press (tier 2) fitted from them, machined parts made on it (the part a Gunsmith's parts kit needs), and the world saved, left and opened
 * again with the bench's tier, the skills, the wallet and the goods unchanged.
 */
final class SmithScenario {
    private SmithScenario() {}

    private static final class Facts {
        CityBuildings.Building yard, hardware;
        DoorWalk.Spot yardDoor, hardwareDoor;
        BlockPos bench, publicTerminal;
        double walletBefore;
        int shelfBefore;
    }

    private static final Facts F = new Facts();
    static final String FASTENER_LINE = UndergroundCity.MOD_ID + ":shops/yard_fasteners_ironhaven";
    static final String COPPER_LINE = UndergroundCity.MOD_ID + ":materials/copper_ingot_ironhaven";
    static final String REDSTONE_LINE = UndergroundCity.MOD_ID + ":materials/redstone_ironhaven";
    static final String ALLOY_LINE = UndergroundCity.MOD_ID + ":refined_alloy";

    static void build() {
        ThiefScenario.tag = "SMITH";
        Playtest.keepScreens = true;
        // ------------------------------------------------------------------ Track A
        ThiefScenario.register(2, "the Smith card", "1 (A) a new citizen registers as a Smith through the registration screen");
        Playtest.queue("the kit", () -> {
            int benches = JourneyScenario.count(UndergroundCity.WORKSHOP_ITEMS.get("machinist_bench").get());
            int iron = JourneyScenario.count(Items.IRON_INGOT), nuggets = JourneyScenario.count(Items.IRON_NUGGET);
            Playtest.log("SMITH kit: benches " + benches + ", iron " + iron + ", nuggets " + nuggets + "; recipe book holds the bench recipe " + server(() -> me().getRecipeBook().contains(net.minecraft.resources.ResourceLocation.fromNamespaceAndPath(UndergroundCity.MOD_ID, "machinist_bench"))));
            if (benches != 1 || iron < 6 || nuggets < 12) Playtest.fail("the Smith's kit does not hold a Machinist's Bench, six iron and twelve nuggets");
            else pass("2 (A) a new Smith starts with a Machinist's Bench, six iron ingots and twelve nuggets; the station recipes are in the recipe book");
        });
        Playtest.queue("the clock: 13:00 with the daylight cycle stopped", () -> {
            Playtest.command("gamerule doDaylightCycle false");
            Playtest.command("time set 7000");
            scripted("the time of day was set to 13:00 and the daylight cycle stopped (the yard and the hardware desk are open by day)");
        });
        ThiefScenario.travel("to the middle of Ironhaven", () -> ThiefScenario.centre("ironhaven"), 6);
        Playtest.queue("the Builders' Yard and Hardware and Metals near the middle of Ironhaven", () -> server(() -> {
            F.yard = nearestOpen("ih_warehouse");
            F.hardware = nearestOpen("ih_workshop");
            if (F.yard != null) F.yardDoor = ThiefScenario.streetDoor(level(), F.yard);
            if (F.hardware != null) F.hardwareDoor = ThiefScenario.streetDoor(level(), F.hardware);
            Playtest.log("SMITH yard " + (F.yard == null ? "none" : F.yard.chunkX() + "," + F.yard.chunkZ()) + ", hardware " + (F.hardware == null ? "none" : F.hardware.chunkX() + "," + F.hardware.chunkZ()));
            if (F.yard == null || F.yardDoor == null) Playtest.fail("no open Builders' Yard with a street door within twelve chunks of the middle of Ironhaven");
            return null;
        }));
        ThiefScenario.stopIfFailed("an open Builders' Yard");
        Playtest.queue("start on the street outside the yard (scripted)", () -> {
            scripted("the player was put on the street outside the Builders' Yard's door at " + F.yardDoor.door().toShortString() + " in survival");
            ThiefScenario.standAt(F.yardDoor);
        }, () -> mc().player.position().distanceTo(F.yardDoor.stand()) < 0.8, 200);
        Playtest.wait("settle", 20);
        placeBench();
        ThiefScenario.stopIfFailed("placing the bench");
        openBench("its recipes");
        Playtest.queue("the bench's recipes", () -> {
            if (!(mc().screen instanceof WorkshopScreen s)) { Playtest.fail("the bench's screen did not open"); return; }
            var v = s.view();
            Playtest.log("SMITH bench: " + v.title() + " tier " + v.tier() + "/" + v.maxTier() + "; " + v.rows().stream().map(r -> r.id() + (r.allowed() ? "" : " (" + r.reason() + ")")).toList());
            boolean open = v.rows().stream().anyMatch(r -> r.id().equals("fasteners") && r.allowed()) && v.rows().stream().anyMatch(r -> r.id().equals("steel_bracket") && r.allowed());
            boolean gated = v.rows().stream().anyMatch(r -> r.id().equals("gear_set") && !r.allowed()) && v.rows().stream().anyMatch(r -> r.id().equals("machined_parts") && !r.allowed());
            if (!open || !gated) Playtest.fail("the bench does not offer fasteners and brackets, or offers the gear set and machined parts to a new Smith");
            else pass("3 (A) the bench lists fasteners and brackets as ready, the gear set as needing Structural Works and machined parts as needing the Drill Press");
        });
        shot("1-bench-screen");
        batch("fasteners", "underground_city:fasteners", 8, "4 (A) Start and Take (real clicks) turn six nuggets into eight fasteners after twenty seconds of play");
        batch("steel_bracket", "underground_city:steel_bracket", 3, "5 (A) Start and Take turn two iron into three steel brackets after forty seconds of play");
        shot("2-bench-made");
        JourneyScenario.closeScreen("close the bench");
        sellFasteners();
        // ------------------------------------------------------------------ Track B
        Playtest.queue("Track B: prepared (scripted)", () -> {
            scripted("TRACK B begins: the Smith's XP was raised to level 4 (1,125 XP) and 60 credits were added, as after an hour of orders");
            server(() -> {
                CitizenState state = CitizenState.get(mc().getSingleplayerServer());
                int xp = state.find(me().getUUID()).map(CitizenState.Citizen::careerXp).orElse(0);
                state.addCareerXp(me().getUUID(), Math.max(0, 1125 - xp));
                QShopEconomy.applyBalance(me(), QShopEconomy.balance(me()) + 60);
                return null;
            });
        });
        Playtest.queue("the workshop's public terminal", () -> {
            F.publicTerminal = F.hardware == null ? null : server(() -> terminalIn(F.hardware));
            Playtest.log("SMITH public terminal " + (F.publicTerminal == null ? "none" : F.publicTerminal.toShortString()));
            if (F.publicTerminal == null) Playtest.fail("Hardware and Metals has no public terminal");
        });
        ThiefScenario.stopIfFailed("a public terminal");
        enterBuilding("Hardware and Metals", () -> F.hardwareDoor);
        ThiefScenario.walkOrTeleport("walk to the public terminal", () -> standBeside(F.publicTerminal), 0.8, 900);
        JourneyScenario.use("open the public terminal", () -> F.publicTerminal, () -> mc().screen instanceof TerminalScreen, 400);
        learn("smith.fundamentals", "Forge Fundamentals");
        learn("smith.structural_works", "Structural Works");
        Playtest.queue("both skills", () -> {
            CitizenState.Citizen c = ThiefScenario.citizen();
            if (c == null || !c.skills().contains("smith.fundamentals") || !c.skills().contains("smith.structural_works")) Playtest.fail("the skills were not learned");
            else pass("6 (B) Forge Fundamentals and Structural Works learned with real clicks on the Skills page of the workshop's public terminal (Structural Works now costs one point at level 3)");
        });
        JourneyScenario.click("TRADE", true);
        Playtest.wait("draw", 10);
        buy("refined alloy on the Ironhaven exchange", ALLOY_LINE, UndergroundCity.item("refined_alloy"), 1);
        Playtest.queue("bought on the exchange", () -> {
            if (JourneyScenario.count(UndergroundCity.item("refined_alloy")) < 1) Playtest.fail("no refined alloy was bought");
            else pass("7 (B) the public terminal in Hardware and Metals opens Ironhaven's exchange: a real click buys refined alloy");
        });
        shot("4-exchange");
        JourneyScenario.closeScreen("close the terminal");
        final Vec3[] desk = new Vec3[1];
        Playtest.queue("a place in front of the hardware foreman", () -> {
            TownspersonEntity k = foreman();
            desk[0] = beforeKeeper(F.hardware, k);
            Playtest.log("SMITH foreman " + (k == null ? "not found" : "at " + k.blockPosition().toShortString()) + "; standing at " + (desk[0] == null ? "nowhere" : BlockPos.containing(desk[0]).toShortString()));
            if (desk[0] == null) Playtest.fail("no public floor in sight of the hardware foreman");
        });
        ThiefScenario.walkOrTeleport("walk to the hardware desk", () -> desk[0] == null ? mc().player.position() : desk[0], 0.6, 1200);
        JourneyScenario.speakTo("the hardware foreman", SmithScenario::foreman);
        JourneyScenario.choose("What do you sell?", "terminal");
        JourneyScenario.click("TRADE", true);
        Playtest.wait("draw", 10);
        buy("copper at the hardware desk", COPPER_LINE, Items.COPPER_INGOT, 2);
        buy("redstone at the hardware desk", REDSTONE_LINE, Items.REDSTONE, 1);
        Playtest.queue("bought at the desk", () -> {
            int copper = JourneyScenario.count(Items.COPPER_INGOT), redstone = JourneyScenario.count(Items.REDSTONE);
            Playtest.log("SMITH bought: copper " + copper + ", redstone " + redstone + ", wallet " + JourneyScenario.wallet());
            if (copper < 2 || redstone < 1) Playtest.fail("the copper and redstone were not bought at the hardware desk");
            else pass("8 (B) copper and redstone bought over the Hardware and Metals desk with real clicks (redstone is sold in Ironhaven since the first-hour audit)");
        });
        shot("5-hardware-desk");
        JourneyScenario.closeScreen("close the desk");
        ThiefScenario.walkOrTeleport("back out to the bench", () -> standBeside(F.bench), 0.8, 1500);
        openBench("springs and a gear set");
        batch("springs", "underground_city:springs", 4, "9 (B) springs from an ingot and redstone (sixty seconds of play)");
        batch("gear_set", "underground_city:gear_set", 1, "10 (B) with Structural Works the gear set is open: two iron and two copper make one (ninety seconds of play)");
        Playtest.wait("the screen refreshes", 10);
        JourneyScenario.closeScreen("close the bench");
        openBench("fit the Drill Press");
        shot("6-tier-ready");
        JourneyScenario.click("Fit tier 2", true);
        until("the bench is tier 2", () -> server(() -> level().getBlockState(F.bench).getValue(WorkshopBlock.TIER) == 2), 100);
        until("the screen shows tier 2", () -> mc().screen instanceof WorkshopScreen s && s.view().tier() == 2, 100);
        Playtest.queue("tier 2", () -> {
            var v = mc().screen instanceof WorkshopScreen s ? s.view() : null;
            Playtest.log("SMITH fitted: " + (v == null ? "?" : v.tierName() + ", " + v.batch() + " at a time, " + v.speedPercent() + "% speed") + "; gear sets " + JourneyScenario.count(UndergroundCity.item("gear_set")));
            if (v == null || v.tier() != 2 || JourneyScenario.count(UndergroundCity.item("gear_set")) != 0) Playtest.fail("the Drill Press was not fitted from the gear set, brackets and springs");
            else pass("11 (B) Fit tier 2 (a real click) uses the gear set, two brackets and two springs: the Drill Press runs two batches at a time, a third faster");
        });
        batch("machined_parts", "underground_city:machined_parts", 2, "12 (B) on the Drill Press, refined alloy and an ingot make two machined parts, the part every Firearm Parts Kit needs");
        shot("7-machined-parts");
        JourneyScenario.closeScreen("close the bench");
        reconnect();
        Playtest.queue("summary", ThiefScenario::summary);
    }

    // ------------------------------------------------------------------------------------------------ places

    /** The nearest open building of a furnished type within twelve chunks of the player. */
    private static CityBuildings.Building nearestOpen(String suffix) {
        ServerLevel level = level();
        BlockPos here = me().blockPosition();
        for (int r = 0; r <= 12; r++) for (int dx = -r; dx <= r; dx++) for (int dz = -r; dz <= r; dz++) {
            if (Math.max(Math.abs(dx), Math.abs(dz)) != r) continue;
            int cx = (here.getX() >> 4) + dx, cz = (here.getZ() >> 4) + dz;
            if (!level.hasChunk(cx, cz)) continue;
            CityBuildings.Building b = CityBuildings.INSTANCE.ofChunk(level, cx, cz).orElse(null);
            if (b != null && b.type().endsWith(suffix) && ShopCounters.openNow(level, b)) return b;
        }
        return null;
    }

    /** The Operations Terminal block inside a building (the public one its plan carries, D-175). */
    private static BlockPos terminalIn(CityBuildings.Building b) {
        ServerLevel level = level();
        for (int y = b.streetY(); y < b.streetY() + 12; y++) for (int x = 0; x < 16; x++) for (int z = 0; z < 16; z++) {
            BlockPos p = new BlockPos((b.chunkX() << 4) + x, y, (b.chunkZ() << 4) + z);
            if (level.getBlockState(p).getBlock() instanceof TerminalBlock) return p;
        }
        return null;
    }

    private static Vec3 standBeside(BlockPos block) {
        if (block == null) return mc().player.position();
        return server(() -> {
            for (Direction d : Direction.Plane.HORIZONTAL) {
                BlockPos c = block.relative(d);
                if (standable(level(), c)) return Vec3.atBottomCenterOf(c);
            }
            return Vec3.atBottomCenterOf(block.relative(Direction.NORTH));
        });
    }

    /** In at the street door like a customer: to the step, a right click on the door. */
    private static void enterBuilding(String name, Supplier<DoorWalk.Spot> door) {
        ThiefScenario.walkOrTeleport("walk to the door of " + name, () -> door.get().stand(), 0.8, 1500);
        final int[] knock = {0};
        Playtest.queue("open the door of " + name, () -> { knock[0] = 0; Playtest.focusWindow(); }, () -> {
            BlockPos d = door.get().door();
            if (server(() -> level().getBlockState(d).getBlock() instanceof net.minecraft.world.level.block.DoorBlock && level().getBlockState(d).getValue(net.minecraft.world.level.block.DoorBlock.OPEN))) return true;
            look(Vec3.atCenterOf(d).add(0, 0.4, 0));
            if (++knock[0] % 24 == 12 && (aimedAt(d) || aimedAt(d.above()))) Playtest.click(3);
            return false;
        }, 400);
    }

    private static TownspersonEntity keeperOf(CityBuildings.Building b, DoorWalk.Spot door) {
        return server(() -> {
            if (b == null || door == null) return null;
            for (TownspersonEntity body : level().getEntitiesOfClass(TownspersonEntity.class, new net.minecraft.world.phys.AABB(door.door()).inflate(40)))
                if (body.homeKey() == b.key() && ShopCounters.shopOf(body).isPresent()) return body;
            return null;
        });
    }

    private static TownspersonEntity yardKeeper() { return keeperOf(F.yard, F.yardDoor); }
    private static TownspersonEntity foreman() { return keeperOf(F.hardware, F.hardwareDoor); }

    /** A cell of the public floor in sight of the keeper, two to four blocks from them. */
    private static Vec3 beforeKeeper(CityBuildings.Building b, TownspersonEntity k) { return StationSteps.beforeKeeper(b, k); }

    // ------------------------------------------------------------------------------------------------ the bench

    private static void placeBench() {
        final Vec3[] stand = new Vec3[1];
        Playtest.queue("open ground near the yard to set the bench on", () -> {
            Object[] found = server(() -> {
                ServerLevel level = level();
                BlockPos from = me().blockPosition();
                for (int r = 3; r <= 24; r++) for (int dx = -r; dx <= r; dx++) for (int dz = -r; dz <= r; dz++) {
                    if (Math.max(Math.abs(dx), Math.abs(dz)) != r) continue;
                    for (int dy = 1; dy >= -2; dy--) {
                        BlockPos c = from.offset(dx, dy, dz);
                        if (!standable(level, c) || BuildingGuard.protectedAt(level, c).isPresent()) continue;
                        Direction back = Math.abs(dx) >= Math.abs(dz) ? (dx > 0 ? Direction.WEST : Direction.EAST) : (dz > 0 ? Direction.NORTH : Direction.SOUTH);
                        BlockPos s2 = c.relative(back, 2);
                        if (!standable(level, s2)) continue;
                        return new Object[]{c.immutable(), Vec3.atBottomCenterOf(s2)};
                    }
                }
                return null;
            });
            if (found != null) { F.bench = (BlockPos) found[0]; stand[0] = (Vec3) found[1]; }
            Playtest.log("SMITH the bench goes at " + (F.bench == null ? "nowhere" : F.bench.toShortString()));
            if (F.bench == null) Playtest.fail("no open ground within 24 blocks of the yard that no building owns");
        });
        ThiefScenario.walkOrTeleport("walk to the open ground", () -> stand[0] == null ? mc().player.position() : stand[0], 0.8, 1200);
        final int[] t = {0};
        Item bench = UndergroundCity.WORKSHOP_ITEMS.get("machinist_bench").get();
        Playtest.queue("right-click the ground with the bench in hand", () -> {
            t[0] = 0;
            Playtest.focusWindow();
            int slot = -1;
            for (int i = 0; i < 9; i++) if (mc().player.getInventory().getItem(i).is(bench)) slot = i;
            if (slot < 0) {
                scripted("the bench was moved from the backpack rows to the last hotbar slot");
                server(() -> {
                    var inv = me().getInventory();
                    for (int i = 9; i < inv.items.size(); i++) if (inv.getItem(i).is(bench)) { ItemStack moved = inv.getItem(i); inv.setItem(i, inv.getItem(8)); inv.setItem(8, moved); break; }
                    return null;
                });
                slot = 8;
            }
            Playtest.tap(String.valueOf(slot + 1));
        }, () -> {
            if (F.bench == null) return true;
            if (server(() -> level().getBlockState(F.bench).getBlock() instanceof WorkshopBlock)) return true;
            look(Vec3.atCenterOf(F.bench.below()).add(0, 0.5, 0));
            if (++t[0] % 24 == 12 && aimedAt(F.bench.below())) Playtest.click(3);
            return false;
        }, 400);
        // the placing click can leave a screen open (gunsmith run 3): shut it before the bench is opened on purpose
        JourneyScenario.closeScreen("close anything the placing click opened");
        Playtest.queue("the bench stands", () -> {
            boolean placed = F.bench != null && server(() -> level().getBlockState(F.bench).getBlock() instanceof WorkshopBlock wb && wb.station.equals("machinist_bench"));
            if (!placed) Playtest.fail("the bench was not placed");
        });
    }

    private static boolean standable(ServerLevel level, BlockPos c) {
        return level.getBlockState(c).isAir() && level.getBlockState(c.above()).isAir() && level.getBlockState(c.below()).isSolidRender(level, c.below());
    }

    private static void openBench(String label) {
        final int[] t = {0};
        Playtest.queue("right-click the bench: " + label, () -> { t[0] = 0; Playtest.focusWindow(); }, () -> {
            if (mc().screen instanceof WorkshopScreen) return true;
            look(Vec3.atCenterOf(F.bench));
            if (++t[0] % 24 == 12 && aimedAt(F.bench)) Playtest.click(3);
            return false;
        }, 400);
        Playtest.wait("draw", 12);
    }

    /** One batch: select the recipe, Start (a real click), wait out its time on the active clock with the screen open, Take (a real click); the career XP is read before and after. */
    private static void batch(String recipe, String output, int expect, String passText) {
        Item out = net.minecraftforge.registries.ForgeRegistries.ITEMS.getValue(net.minecraft.resources.ResourceLocation.parse(output));
        final int[] before = new int[2];
        final long[] started = {0};
        Playtest.queue("select " + recipe, () -> {
            if (!(mc().screen instanceof WorkshopScreen s)) { Playtest.fail("the bench's screen is not open"); return; }
            before[0] = JourneyScenario.count(out);
            CitizenState.Citizen c = ThiefScenario.citizen();
            before[1] = c == null ? 0 : c.careerXp();
        });
        // the row is chosen with the real pointer (a click on it), as a person would; a row below the fold is brought up with the mouse wheel first
        final int[] wheel = {0};
        Playtest.queue("turn the wheel until the " + recipe + " row is in view", () -> wheel[0] = 0, () -> {
            if (!(mc().screen instanceof WorkshopScreen s) || s.rowCentre(recipe) != null) return true;
            if (wheel[0]++ % 6 == 0) Playtest.click(5);
            return wheel[0] > 90;
        }, 120);
        StationSteps.selectRow(recipe);
        Playtest.wait("select", 4);
        JourneyScenario.click("Start", true);
        until(recipe + " starts", () -> mc().screen instanceof WorkshopScreen s && !s.view().running().isEmpty(), 100);
        Playtest.queue(recipe + " is running", () -> {
            started[0] = server(() -> LockService.clock(mc().getSingleplayerServer()));
            var v = ((WorkshopScreen) mc().screen).view();
            Playtest.log("SMITH started " + v.running() + ": " + v.secondsLeft() + " s left");
        });
        until(recipe + " is done", () -> server(() -> !level().getBlockState(F.bench).getValue(WorkshopBlock.WORKING)), 20 * 60 * 4);
        // the open screen asks the station again once its countdown is up (smith run 1 found Take grey until the station was reopened)
        until("the screen shows the batch ready", () -> mc().screen instanceof WorkshopScreen s && s.view().readyCount() > 0, 400);
        JourneyScenario.click("Take", true);
        until(recipe + " is taken", () -> JourneyScenario.count(out) >= before[0] + expect, 100);
        Playtest.queue(recipe + " taken", () -> {
            long now = server(() -> LockService.clock(mc().getSingleplayerServer()));
            CitizenState.Citizen c = ThiefScenario.citizen();
            int got = JourneyScenario.count(out) - before[0], xp = c == null ? 0 : c.careerXp();
            Playtest.log(String.format(Locale.ROOT, "SMITH %s: %d made in %.2f minutes of active play, Smith XP %d -> %d", recipe, got, (now - started[0]) / 1200.0, before[1], xp));
            if (got != expect || xp <= before[1]) Playtest.fail(recipe + " did not hand over " + expect + " or paid no Smith XP");
            else pass(passText + " (+" + (xp - before[1]) + " Smith XP)");
        });
    }

    // ------------------------------------------------------------------------------------------------ the yard

    private static void sellFasteners() {
        Playtest.queue("before the sale", () -> {
            F.walletBefore = JourneyScenario.wallet();
            F.shelfBefore = server(() -> WorkOrderLedger.get(mc().getSingleplayerServer()).regionalUnits(RegionalCatalog.INSTANCE.find(FASTENER_LINE)));
            Playtest.log("SMITH before the sale: wallet " + F.walletBefore + ", the yard's fastener shelf " + F.shelfBefore);
        });
        enterBuilding("the Builders' Yard", () -> F.yardDoor);
        final Vec3[] floor = new Vec3[1];
        Playtest.queue("a place on the yard's public floor in front of the keeper", () -> {
            TownspersonEntity k = yardKeeper();
            floor[0] = beforeKeeper(F.yard, k);
            Playtest.log("SMITH yard keeper " + (k == null ? "not found" : "at " + k.blockPosition().toShortString()) + "; standing at " + (floor[0] == null ? "nowhere" : BlockPos.containing(floor[0]).toShortString()));
            if (floor[0] == null) Playtest.fail("no public floor in sight of the yard's keeper");
        });
        ThiefScenario.walkOrTeleport("walk across the yard to the desk", () -> floor[0] == null ? mc().player.position() : floor[0], 0.6, 900);
        JourneyScenario.speakTo("the yard keeper", SmithScenario::yardKeeper);
        JourneyScenario.choose("What do you sell?", "terminal");
        JourneyScenario.click("TRADE", true);
        Playtest.wait("draw", 10);
        Playtest.queue("the yard's counter", () -> {
            if (!(mc().screen instanceof TerminalScreen t)) { Playtest.fail("the yard's counter did not open"); return; }
            for (var o : t.data().offers()) Playtest.log("SMITH yard offer " + o.id() + " " + o.price() + " each, " + o.stock() + "/" + o.capacity() + (o.pays() > 0 ? ", buys at " + o.pays() + ", carried " + o.carried() : ""));
            if (t.data().offers().stream().noneMatch(o -> o.id().equals(FASTENER_LINE) && o.pays() > 0 && o.carried() == 8)) Playtest.fail("the yard does not offer to buy the eight fasteners");
        });
        shot("3-yard-counter");
        Playtest.queue("click Sell on the fastener line", () -> {
            if (!(mc().screen instanceof TerminalScreen t) || !t.clickSellOffer(FASTENER_LINE)) Playtest.fail("no active Sell button on the yard's fastener line");
        });
        until("the sale lands", () -> JourneyScenario.count(UndergroundCity.item("fasteners")) == 0, 200);
        Playtest.wait("draw", 10);
        Playtest.queue("sold", () -> {
            double w = JourneyScenario.wallet();
            int shelf = server(() -> WorkOrderLedger.get(mc().getSingleplayerServer()).regionalUnits(RegionalCatalog.INSTANCE.find(FASTENER_LINE)));
            Playtest.log("SMITH sold: wallet " + F.walletBefore + " -> " + w + ", shelf " + F.shelfBefore + " -> " + shelf);
            if (w - F.walletBefore < 8 * 2 - 0.01 || shelf != F.shelfBefore + 8) Playtest.fail("the yard did not pay for eight fasteners and shelve them");
            else pass("5b (A) the Builders' Yard buys the eight fasteners over its counter (a real click on Sell): +" + Math.round(w - F.walletBefore) + " credits; its shelf, which a Gunsmith buys from, " + F.shelfBefore + " -> " + shelf);
        });
        JourneyScenario.closeScreen("close the counter");
    }

    private static void learn(String node, String title) {
        StationSteps.learn(node, title);
        shot("skill-" + node.substring(node.indexOf('.') + 1));
    }

    private static void buy(String label, String line, Item item, int n) { StationSteps.buy(label, line, item, n); }

    // ------------------------------------------------------------------------------------------------ save and return

    private static void reconnect() {
        final Object[] before = new Object[6];
        Playtest.queue("before leaving", () -> {
            CitizenState.Citizen c = ThiefScenario.citizen();
            before[0] = JourneyScenario.wallet();
            before[1] = c == null ? 0 : c.careerXp();
            before[2] = c == null ? java.util.Set.of() : java.util.Set.copyOf(c.skills());
            before[3] = JourneyScenario.count(UndergroundCity.item("machined_parts"));
            before[4] = JourneyScenario.count(UndergroundCity.item("springs"));
            before[5] = server(() -> level().getBlockState(F.bench).getValue(WorkshopBlock.TIER));
            Playtest.log("SMITH before leaving: wallet " + before[0] + ", XP " + before[1] + ", skills " + before[2] + ", machined parts " + before[3] + ", springs " + before[4] + ", bench tier " + before[5]);
        });
        Playtest.queue("save and quit to the title screen, then open the world again", () -> {
            var mc = mc();
            String folder = System.getProperty("uc.playtest.world", "UC-Playtest");
            mc.level.disconnect();
            mc.clearLevel(new net.minecraft.client.gui.screens.GenericDirtMessageScreen(net.minecraft.network.chat.Component.translatable("menu.savingLevel")));
            mc.setScreen(new net.minecraft.client.gui.screens.TitleScreen());
            mc.createWorldOpenFlows().loadLevel(mc.screen, folder);
        }, () -> mc().player != null && mc().level != null && Scenarios.groundBelowPlayer(), 6000);
        Playtest.wait("settle", 60);
        Playtest.queue("everything is still there", () -> {
            CitizenState.Citizen c = ThiefScenario.citizen();
            double w = JourneyScenario.wallet();
            int xp = c == null ? -1 : c.careerXp();
            Object skills = c == null ? java.util.Set.of() : java.util.Set.copyOf(c.skills());
            int parts = JourneyScenario.count(UndergroundCity.item("machined_parts")), springs = JourneyScenario.count(UndergroundCity.item("springs"));
            int tier = server(() -> level().getBlockState(F.bench).getBlock() instanceof WorkshopBlock ? level().getBlockState(F.bench).getValue(WorkshopBlock.TIER) : -1);
            Playtest.log("SMITH after reopening: wallet " + w + ", XP " + xp + ", skills " + skills + ", machined parts " + parts + ", springs " + springs + ", bench tier " + tier);
            if (w != (double) before[0] || xp != (int) before[1] || !skills.equals(before[2]) || parts != (int) before[3] || springs != (int) before[4] || tier != (int) before[5])
                Playtest.fail("something changed across the save and reopen");
            else pass("13 (B) saved, left to the title screen and opened again: wallet, Smith XP, both skills, the machined parts and springs and the bench's Drill Press unchanged, nothing doubled");
        });
    }
}
