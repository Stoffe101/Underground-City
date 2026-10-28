package io.github.stoffe101.undergroundcity;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;

import java.util.Locale;

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
 * A Vintner's first workshop and first sale to a shop through a real client (D-163, D-164): a new citizen registers as a Vintner, places the Fermentation Vat from the starter kit with a real right
 * click on the street outside the Ironhaven pub, opens it, starts a batch of berry wine with a click, waits out its six minutes of play on the active clock (no shortcut), takes the three bottles,
 * fits the vat's second tier from Smith parts, walks into the pub, asks the barkeeper what they sell and sells the wine over the counter with the Sell button. The wallet, the shelf and the vat's
 * tier are read back from the server.
 *
 * <p>Scripted, each logged as SCRIPTED: the clock (13:00, stopped, so the pub is open), the travel to Ironhaven, the start on the street outside the pub, and the Smith parts for the second tier
 * (a Smith partner or the builders' yard would supply them).
 */
final class WorkshopScenario {
    private WorkshopScenario() {}

    private static final class Facts {
        CityBuildings.Building pub;
        DoorWalk.Spot street;
        BlockPos vat;
        double walletBefore;
        int shelfBefore;
    }

    private static final Facts F = new Facts();
    static final String WINE_LINE = UndergroundCity.MOD_ID + ":shops/pub_wine_ironhaven";

    static void build() {
        ThiefScenario.tag = "WORKSHOP";
        Playtest.keepScreens = true;
        ThiefScenario.register(1, "the Vintner card", "1 a new citizen registers as a Vintner through the registration screen");
        Playtest.queue("the kit", () -> {
            int vats = JourneyScenario.count(UndergroundCity.WORKSHOP_ITEMS.get("fermentation_vat").get());
            int berries = JourneyScenario.count(net.minecraft.world.item.Items.SWEET_BERRIES);
            Playtest.log("WORKSHOP kit: vats " + vats + ", sweet berries " + berries + ", sugar " + JourneyScenario.count(net.minecraft.world.item.Items.SUGAR) + ", bottles " + JourneyScenario.count(net.minecraft.world.item.Items.GLASS_BOTTLE));
            if (vats != 1 || berries < 8) Playtest.fail("the Vintner's kit does not hold a Fermentation Vat and berries");
            else pass("2 a new Vintner starts with a Fermentation Vat, berries, sugar and bottles");
        });
        Playtest.queue("the clock: 13:00 with the daylight cycle stopped", () -> {
            Playtest.command("gamerule doDaylightCycle false");
            Playtest.command("time set 7000");
            scripted("the time of day was set to 13:00 and the daylight cycle stopped (the pub is open by day)");
        });
        ThiefScenario.travel("to the middle of Ironhaven", () -> ThiefScenario.centre("ironhaven"), 6);
        choosePub();
        ThiefScenario.stopIfFailed("an open pub");
        Playtest.queue("start on the street outside the pub (scripted)", () -> {
            scripted("the player was put on the street outside the pub's front door at " + F.street.door().toShortString() + " in survival");
            ThiefScenario.standAt(F.street);
        }, () -> mc().player.position().distanceTo(F.street.stand()) < 0.8, 200);
        Playtest.wait("settle", 20);
        placeVat();
        ThiefScenario.stopIfFailed("placing the vat");
        brew();
        fitTier();
        sell();
        Playtest.queue("summary", ThiefScenario::summary);
    }

    // ------------------------------------------------------------------------------------------------ the pub

    private static void choosePub() {
        Playtest.queue("an open pub near the middle of Ironhaven", () -> server(() -> {
            ServerLevel level = level();
            BlockPos here = me().blockPosition();
            for (int r = 0; r <= 10 && F.pub == null; r++) for (int dx = -r; dx <= r && F.pub == null; dx++) for (int dz = -r; dz <= r && F.pub == null; dz++) {
                if (Math.max(Math.abs(dx), Math.abs(dz)) != r) continue;
                int cx = (here.getX() >> 4) + dx, cz = (here.getZ() >> 4) + dz;
                if (!level.hasChunk(cx, cz)) continue;
                CityBuildings.Building b = CityBuildings.INSTANCE.ofChunk(level, cx, cz).orElse(null);
                if (b == null || !b.type().endsWith("ih_pub")) continue;
                boolean open = ShopCounters.openNow(level, b);
                Playtest.log("WORKSHOP pub at chunk " + cx + "," + cz + ": open " + open);
                if (!open) continue;
                DoorWalk.Spot street = ThiefScenario.streetDoor(level, b);
                if (street == null) continue;
                F.pub = b;
                F.street = street;
            }
            if (F.pub == null) Playtest.fail("no open pub with a street door within ten chunks of the middle of Ironhaven");
            return null;
        }));
    }

    /** The pub's barkeeper: the household worker whose post is the pub's counter. */
    private static TownspersonEntity keeper() {
        return server(() -> {
            if (F.pub == null) return null;
            for (TownspersonEntity body : level().getEntitiesOfClass(TownspersonEntity.class, new net.minecraft.world.phys.AABB(F.street.door()).inflate(40)))
                if (body.homeKey() == F.pub.key() && ShopCounters.shopOf(body).isPresent()) return body;
            return null;
        });
    }

    // ------------------------------------------------------------------------------------------------ the vat

    private static void placeVat() {
        // a city building's street is part of its plot and nobody may build there (BuildingGuard): the vat goes on the first open ground near the pub that no building owns,
        // with standing room two blocks short of it on the side nearer the pub, walked to with the real keys
        final Vec3[] stand = new Vec3[1];
        Playtest.queue("open ground near the pub to set the vat on", () -> {
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
            if (found != null) { F.vat = (BlockPos) found[0]; stand[0] = (Vec3) found[1]; }
            Playtest.log("WORKSHOP the vat goes at " + (F.vat == null ? "nowhere" : F.vat.toShortString()) + (stand[0] == null ? "" : ", standing at " + BlockPos.containing(stand[0]).toShortString()));
            if (F.vat == null) Playtest.fail("no open ground within 24 blocks of the pub that no building owns");
        });
        ThiefScenario.walkOrTeleport("walk to the open ground", () -> stand[0] == null ? mc().player.position() : stand[0], 0.8, 1200);
        final int[] t = {0};
        Item vat = UndergroundCity.WORKSHOP_ITEMS.get("fermentation_vat").get();
        Playtest.queue("right-click the street with the vat in hand", () -> {
            t[0] = 0;
            Playtest.focusWindow();
            int slot = -1;
            for (int i = 0; i < 9; i++) if (mc().player.getInventory().getItem(i).is(vat)) slot = i;
            if (slot < 0) {
                scripted("the vat was moved from the backpack rows to the last hotbar slot");
                server(() -> {
                    var inv = me().getInventory();
                    for (int i = 9; i < inv.items.size(); i++) if (inv.getItem(i).is(vat)) { ItemStack moved = inv.getItem(i); inv.setItem(i, inv.getItem(8)); inv.setItem(8, moved); break; }
                    return null;
                });
                slot = 8;
            }
            Playtest.tap(String.valueOf(slot + 1));
        }, () -> {
            if (F.vat == null) return true;
            if (server(() -> level().getBlockState(F.vat).getBlock() instanceof WorkshopBlock)) return true;
            look(Vec3.atCenterOf(F.vat.below()).add(0, 0.5, 0));
            if (++t[0] % 24 == 12 && aimedAt(F.vat.below())) Playtest.click(3);
            return false;
        }, 400);
        Playtest.queue("the vat stands", () -> {
            boolean placed = F.vat != null && server(() -> level().getBlockState(F.vat).getBlock() instanceof WorkshopBlock wb && wb.station.equals("fermentation_vat"));
            if (!placed) Playtest.fail("the vat was not placed");
            else pass("3 the Fermentation Vat from the kit is placed with a real right click on open ground near the pub that no building owns");
        });
        Playtest.wait("draw", 10);
        shot("1-vat-placed");
    }

    /** Air for a body and a solid floor under it. */
    private static boolean standable(ServerLevel level, BlockPos c) {
        return level.getBlockState(c).isAir() && level.getBlockState(c.above()).isAir() && level.getBlockState(c.below()).isSolidRender(level, c.below());
    }

    private static void openVat(String label) {
        final int[] t = {0};
        Playtest.queue("right-click the vat: " + label, () -> { t[0] = 0; Playtest.focusWindow(); }, () -> {
            if (mc().screen instanceof WorkshopScreen) return true;
            look(Vec3.atCenterOf(F.vat));
            if (++t[0] % 24 == 12 && aimedAt(F.vat)) Playtest.click(3);
            return false;
        }, 400);
        Playtest.wait("draw", 12);
    }

    private static void brew() {
        openVat("open it");
        Playtest.queue("the vat's recipes", () -> {
            if (!(mc().screen instanceof WorkshopScreen s)) { Playtest.fail("the vat's screen did not open"); return; }
            var v = s.view();
            Playtest.log("WORKSHOP vat: " + v.title() + " tier " + v.tier() + "/" + v.maxTier() + "; " + v.rows().stream().map(r -> r.id() + (r.allowed() ? "" : " (" + r.reason() + ")")).toList());
            boolean wine = v.rows().stream().anyMatch(r -> r.id().equals("berry_wine") && r.allowed());
            boolean gated = v.rows().stream().anyMatch(r -> r.id().equals("glow_wine") && !r.allowed());
            if (!wine || !gated) Playtest.fail("the vat does not offer berry wine, or offers glow-berry wine to a new Vintner");
            else pass("4 the vat's screen lists berry wine as ready and glow-berry wine as needing a better tier and Cellar Craft");
            s.select("berry_wine");
        });
        shot("2-vat-screen");
        JourneyScenario.click("Start", true);
        until("the batch starts", () -> mc().screen instanceof WorkshopScreen s && !s.view().running().isEmpty(), 100);
        final long[] started = {0};
        Playtest.queue("the batch is running", () -> {
            var v = ((WorkshopScreen) mc().screen).view();
            started[0] = server(() -> LockService.clock(mc().getSingleplayerServer()));
            Playtest.log("WORKSHOP started: " + v.running() + ", " + v.secondsLeft() + " s left; note \"" + v.note() + "\"; berries now " + JourneyScenario.count(net.minecraft.world.item.Items.SWEET_BERRIES));
            if (v.secondsTotal() < 300) Playtest.fail("the batch is shorter than its recipe's six minutes at tier 1");
            else pass("5 Start (a real click) takes the berries, sugar and bottles and sets six minutes of play running");
        });
        shot("3-vat-working");
        JourneyScenario.closeScreen("close the vat");
        Playtest.queue("the vat shows it is at work", () -> {
            boolean working = server(() -> level().getBlockState(F.vat).getValue(WorkshopBlock.WORKING));
            if (!working) Playtest.fail("the vat does not show it is at work");
        });
        // the six minutes of play pass on the active clock, with nothing skipped
        until("the batch is done (six minutes of play)", () -> server(() -> !level().getBlockState(F.vat).getValue(WorkshopBlock.WORKING)), 20 * 60 * 8);
        Playtest.queue("the time it took", () -> {
            long now = server(() -> LockService.clock(mc().getSingleplayerServer()));
            Playtest.log(String.format(Locale.ROOT, "WORKSHOP the batch finished after %.1f minutes of active play", (now - started[0]) / 1200.0));
        });
        openVat("take the wine");
        Item wine = UndergroundCity.item("berry_wine");
        JourneyScenario.click("Take", true);
        until("the wine is in the inventory", () -> JourneyScenario.count(wine) >= 3, 100);
        Playtest.queue("three bottles", () -> {
            int n = JourneyScenario.count(wine);
            Playtest.log("WORKSHOP took: " + n + " berry wine; note \"" + ((WorkshopScreen) mc().screen).view().note() + "\"");
            if (n != 3) Playtest.fail("the batch did not hand over three bottles");
            else pass("6 after six minutes of play, Take (a real click) hands over three bottles of berry wine");
        });
        shot("4-vat-taken");
    }

    private static void fitTier() {
        Playtest.queue("Smith parts for the second tier (scripted)", () -> {
            scripted("four barrel hoops and four fasteners were given (a Smith makes them at a Machinist's Bench, and the builders' yard sells a few)");
            server(() -> {
                me().getInventory().add(new ItemStack(UndergroundCity.item("barrel_hoops"), 4));
                me().getInventory().add(new ItemStack(UndergroundCity.item("fasteners"), 4));
                return null;
            });
        });
        Playtest.wait("the screen refreshes", 10);
        JourneyScenario.closeScreen("close the vat");
        openVat("fit the tier");
        shot("5-vat-tier-ready");
        JourneyScenario.click("Fit tier 2", true);
        until("the vat is tier 2", () -> server(() -> level().getBlockState(F.vat).getValue(WorkshopBlock.TIER) == 2), 100);
        until("the screen shows tier 2", () -> mc().screen instanceof WorkshopScreen s && s.view().tier() == 2, 100);
        Playtest.queue("tier 2", () -> {
            var v = mc().screen instanceof WorkshopScreen s ? s.view() : null;
            int tier = server(() -> level().getBlockState(F.vat).getValue(WorkshopBlock.TIER));
            Playtest.log("WORKSHOP fitted: tier " + tier + (v == null ? "" : ", " + v.tierName() + ", " + v.batch() + " at a time, " + v.speedPercent() + "% speed; note \"" + v.note() + "\""));
            if (tier != 2 || JourneyScenario.count(UndergroundCity.item("barrel_hoops")) != 0 || v == null || v.batch() != 2) Playtest.fail("the second tier was not fitted from the parts, or the screen does not show two batches at a time");
            else pass("7 Fit tier 2 (a real click) uses the hoops and fasteners; the vat runs two batches at a time and shows its iron hoops");
        });
        shot("6-vat-tier2");
        JourneyScenario.closeScreen("close the vat");
        Playtest.queue("look at the vat", () -> look(Vec3.atCenterOf(F.vat)));
        Playtest.wait("draw", 10);
        shot("7-vat-tier2-block");
    }

    /** The shortage variant (eleventh pass, {@code -PucPtShortage=true}): the pub's wine runs short before the sale, so the notice, the barkeeper's ask and the short price are seen. */
    static final boolean SHORTAGE = Boolean.getBoolean("uc.playtest.shortage");

    private static void sell() {
        if (SHORTAGE) {
            Playtest.queue("the pub's customers buy its wine down to short (scripted)", () -> {
                server(() -> {
                    var srv = mc().getSingleplayerServer();
                    WorkOrderLedger ledger = WorkOrderLedger.get(srv);
                    RegionalStock line = RegionalCatalog.INSTANCE.find(WINE_LINE);
                    ledger.setRegionalUnitsForTest(line, Shortages.mark(line) + 1);
                    int before = ledger.regionalUnits(line);
                    if (ledger.drawDown(line, 1) == 1) Shortages.drawnDown(srv, line, before, before - 1);
                    return null;
                });
                scripted("the pub's wine shelf was set one above its short mark and one customer's purchase taken off it on the server, as the city's customers do in play");
            });
            until("the shortage notice reaches the client", () -> ClientNotice.lastTitle.endsWith("is short"), 200);
            Playtest.queue("the Vintner was told", () -> pass("8a when its customers buy the pub's wine short, the Vintner in the game is told by a notice: \"" + ClientNotice.lastTitle + "\""));
        }
        Playtest.queue("before the sale", () -> {
            F.walletBefore = JourneyScenario.wallet();
            F.shelfBefore = server(() -> WorkOrderLedger.get(mc().getSingleplayerServer()).regionalUnits(RegionalCatalog.INSTANCE.find(WINE_LINE)));
            Playtest.log("WORKSHOP before the sale: wallet " + F.walletBefore + ", the pub's wine shelf " + F.shelfBefore);
        });
        // in at the front door like a customer: to the step, a real right click on the door, then across the public floor to the counter (workshop run 2's walker, sent straight for a
        // cell beside the barkeeper, picked one behind the bar and circled the block)
        ThiefScenario.walkOrTeleport("walk to the pub's front door", () -> F.street.stand(), 0.8, 1200);
        final int[] knock = {0};
        Playtest.queue("open the pub's door with a right click", () -> { knock[0] = 0; Playtest.focusWindow(); }, () -> {
            if (server(() -> level().getBlockState(F.street.door()).getBlock() instanceof net.minecraft.world.level.block.DoorBlock
                && level().getBlockState(F.street.door()).getValue(net.minecraft.world.level.block.DoorBlock.OPEN))) return true;
            look(Vec3.atCenterOf(F.street.door()).add(0, 0.4, 0));
            if (++knock[0] % 24 == 12 && (aimedAt(F.street.door()) || aimedAt(F.street.door().above()))) Playtest.click(3);
            return false;
        }, 400);
        final Vec3[] floor = new Vec3[1];
        Playtest.queue("a place on the public floor in front of the barkeeper", () -> {
            TownspersonEntity k = keeper();
            floor[0] = k == null ? null : server(() -> {
                StaffZones.Zones z = StaffZones.of(level(), F.pub);
                BlockPos best = null;
                double bestD = Double.MAX_VALUE;
                for (long key : z.publicCells()) {
                    BlockPos c = BlockPos.of(key);
                    double d = c.distToCenterSqr(k.position());
                    if (d < 2.0 || d > 16.0 || d >= bestD) continue;
                    if (!level().clip(new net.minecraft.world.level.ClipContext(Vec3.atCenterOf(c).add(0, 1.1, 0), k.getEyePosition(), net.minecraft.world.level.ClipContext.Block.COLLIDER,
                        net.minecraft.world.level.ClipContext.Fluid.NONE, k)).getType().equals(net.minecraft.world.phys.HitResult.Type.MISS)) continue;
                    best = c; bestD = d;
                }
                return best == null ? null : Vec3.atBottomCenterOf(best);
            });
            Playtest.log("WORKSHOP the barkeeper " + (k == null ? "is not found" : "is at " + k.blockPosition().toShortString()) + "; standing at " + (floor[0] == null ? "nowhere on the public floor in sight" : BlockPos.containing(floor[0]).toShortString()));
            if (floor[0] == null) Playtest.fail("no public floor in sight of the barkeeper");
        });
        ThiefScenario.walkOrTeleport("walk across the public floor to the counter", () -> floor[0] == null ? mc().player.position() : floor[0], 0.6, 900);
        JourneyScenario.speakTo("the barkeeper", WorkshopScenario::keeper);
        shot("8-barkeeper");
        if (SHORTAGE) Playtest.queue("the barkeeper asks for what is short", () -> {
            String said = mc().screen instanceof DialogueScreen d ? d.text() : "";
            Playtest.log("WORKSHOP the barkeeper says: " + said);
            if (!said.contains("nearly out")) Playtest.fail("the barkeeper of a pub whose wine is short does not ask for it");
            else pass("8b the barkeeper asks for it: \"" + said + "\"");
        });
        JourneyScenario.choose("What do you sell?", "terminal");
        JourneyScenario.click("TRADE", true);
        Playtest.wait("draw", 10);
        Playtest.queue("the pub's counter", () -> {
            if (!(mc().screen instanceof TerminalScreen t)) { Playtest.fail("the pub's counter did not open"); return; }
            for (var o : t.data().offers()) Playtest.log("WORKSHOP pub offer " + o.id() + " " + o.item() + " " + o.price() + " each, " + o.stock() + "/" + o.capacity() + (o.pays() > 0 ? ", buys at " + o.pays() + ", carried " + o.carried() : ""));
            boolean wine = t.data().offers().stream().anyMatch(o -> o.id().equals(WINE_LINE) && o.pays() > 0 && o.carried() == 3);
            if (!wine) Playtest.fail("the pub's counter does not offer to buy the three bottles");
            if (SHORTAGE) {
                RegionalStock line = RegionalCatalog.INSTANCE.find(WINE_LINE);
                int shortPays = Shortages.shortPays(line, RegionalCatalog.INSTANCE.supply(WINE_LINE).pays());
                boolean premium = t.data().offers().stream().anyMatch(o -> o.id().equals(WINE_LINE) && o.pays() == shortPays);
                if (!premium) Playtest.fail("the counter does not show the short price of " + shortPays + " a bottle");
                else pass("8c the pub's counter shows it pays " + shortPays + " a bottle while its wine is short (" + RegionalCatalog.INSTANCE.supply(WINE_LINE).pays() + " otherwise)");
            }
        });
        shot("9-pub-counter");
        Playtest.queue("click Sell on the wine line", () -> {
            if (!(mc().screen instanceof TerminalScreen t) || !t.clickSellOffer(WINE_LINE)) Playtest.fail("no active Sell button on the pub's wine line");
        });
        until("the sale lands", () -> JourneyScenario.count(UndergroundCity.item("berry_wine")) == 0, 200);
        Playtest.wait("draw", 10);
        Playtest.queue("sold", () -> {
            double w = JourneyScenario.wallet();
            int shelf = server(() -> WorkOrderLedger.get(mc().getSingleplayerServer()).regionalUnits(RegionalCatalog.INSTANCE.find(WINE_LINE)));
            CitizenState.Citizen c = ThiefScenario.citizen();
            Playtest.log("WORKSHOP sold: wallet " + F.walletBefore + " -> " + w + ", shelf " + F.shelfBefore + " -> " + shelf + ", Vintner XP " + (c == null ? -1 : c.careerXp()));
            if (w - F.walletBefore < 3 * 14 - 0.01 || shelf != F.shelfBefore + 3) Playtest.fail("the pub did not pay for three bottles and shelve them");
            else pass("8 the pub buys the three bottles over its counter (a real click on Sell): +" + Math.round(w - F.walletBefore) + " credits, its wine shelf " + F.shelfBefore + " -> " + shelf);
            if (SHORTAGE) {
                int shortPays = Shortages.shortPays(RegionalCatalog.INSTANCE.find(WINE_LINE), RegionalCatalog.INSTANCE.supply(WINE_LINE).pays());
                if (Math.abs(w - F.walletBefore - 3 * shortPays) > 0.01) Playtest.fail("the short shelf did not pay " + shortPays + " a bottle (" + (w - F.walletBefore) + " for three)");
                else pass("8d selling into the shortage paid " + shortPays + " a bottle, a quarter more");
            }
        });
        shot("10-pub-sold");
        JourneyScenario.closeScreen("close the counter");
    }
}
