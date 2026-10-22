package io.github.stoffe101.undergroundcity;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.screens.GenericDirtMessageScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.gui.screens.inventory.CraftingScreen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

/**
 * The first-player journey on the real client, end to end ({@code -PucPlaytest=journey}): a brand-new character arrives at the landing in front of Founders Row in a new archipelago world and plays the
 * first hour of a Smith with the real keyboard and mouse. Every step that a person does with their hands is done here with injected X11 input on the real screens (the pointer clicks buttons, cards,
 * inventory slots and recipe grids; W, Space, Shift and the mouse buttons move, jump, mine and use), and every outcome is read back from the server (the citizen record, the wallet, the work-order journal,
 * the Crew ledger, the containers). Nothing is marked complete by a command.
 *
 * <p>The steps, in order: arrive; register (Ironhaven, Smith) on the first-login screen; follow the guidance arrow to the employer with the keys; speak to her and open the orders; try a level-2 order
 * (refused); accept the first order; speak to an employer of another trade (no orders offered); chop a tree and mine stone with a pickaxe; craft planks, a crafting table, sticks and stone pickaxes on the
 * real crafting screens; deliver at the employer's terminal (paid in credits and experience); accept the second order; buy rations at the civilian counter, then try something the wallet cannot cover
 * (refused, nothing moves); rent a motel room on the Home page (the city's free welcome hours) and put a loaf in its barrel; learn a skill on the SKILLS page; walk at a locked city door without picks
 * (it stays shut); save, quit to the title screen and open the world again (everything is still there); walk to the road and across a bridge to Valewood; place the welcome-pack terminal there and deliver
 * the second order, buy on the Valewood exchange and try to buy from a shelf another buyer has just emptied (refused); walk back across the bridge.
 *
 * <p>What is scripted rather than played, each named in the log as it happens: the route a person would choose is the game's own pathfinding (the keys still do the walking); where a tree or stone is is
 * looked up in the world; the walk from the Row to the road's first open block may be cut short by a teleport (logged with the distance) when no route is found; a shelf is emptied on the server to
 * stand in for another buyer. It is evidence that the game can be played this way by a script in a software-rendered client, not that it is fun.</p>
 */
final class JourneyScenario {
    private JourneyScenario() {}

    /** What the journey learned as it went (written from either thread; read on the client). */
    private static final class Facts {
        volatile BlockPos employerDesk, otherEmployer, tree, stone, motelDoor, motelBarrel, placedTerminal;
        volatile Vec3 employerAt;
        volatile double walletAfterDelivery, walletAfterRations;
        volatile int xpAfterDelivery, motelRoom = 1;
        volatile String road = "";
        volatile double roadFrom, roadTo;
        volatile long started = System.nanoTime();
        final List<String> passed = new ArrayList<>();
        final List<String> scripted = new ArrayList<>();
    }
    private static final Facts F = new Facts();
    static final String CAREER = "smith";

    // ------------------------------------------------------------------------------------------------ build

    static void build() {
        Playtest.keepScreens = true;
        String skip = System.getProperty("uc.playtest.skip", "");
        arrive();
        register();
        followArrowToEmployer();
        openOrdersAndAccept();
        wrongTrade();
        gatherWood();
        gatherStone();
        craftAll();
        deliverFirst();
        civilianCounter();
        rentMotel();
        learnSkill();
        if (!skip.contains("reconnect")) reconnect();
        if (!skip.contains("travel")) { travelOut(); valewood(); travelBack(); }
        Playtest.queue("journey summary", JourneyScenario::summary);
    }

    // ------------------------------------------------------------------------------------------------ small tools

    private static Minecraft mc() { return Minecraft.getInstance(); }
    private static ServerLevel level() { return mc().getSingleplayerServer().overworld(); }
    private static ServerPlayer me() { return mc().getSingleplayerServer().getPlayerList().getPlayer(mc().player.getUUID()); }

    /** Runs on the integrated server's thread and waits for the answer (reads of shared server state are made where the server makes them). */
    private static <T> T server(Supplier<T> read) {
        try { return mc().getSingleplayerServer().submit(read).get(20, TimeUnit.SECONDS); }
        catch (Exception error) { throw new IllegalStateException("server read failed: " + error, error); }
    }

    private static void pass(String step) { F.passed.add(step); Playtest.log("JOURNEY PASS " + step); }
    private static void scripted(String what) { F.scripted.add(what); Playtest.log("JOURNEY SCRIPTED " + what); }

    private static CitizenState.Citizen citizen() { return server(() -> CitizenState.get(mc().getSingleplayerServer()).find(mc().player.getUUID()).orElse(null)); }
    private static double wallet() { return server(() -> QShopEconomy.balance(me())); }
    private static int count(Item item) { return server(() -> me().getInventory().countItem(item)); }

    private static void shot(String name) {
        Playtest.queue("screenshot " + name, () -> Playtest.screenshotNextFrame("uc-journey-" + name));
        Playtest.wait("frame", 8);
    }

    /** A label as a person reads it: case does not matter, nor a number-key prefix ("1  OPEN MY ORDERS" reads "open my orders"). */
    static String plain(String text) { return text == null ? "" : text.replaceFirst("^\\s*\\d+\\s+", "").trim().toLowerCase(Locale.ROOT); }
    static boolean says(String shown, String label) { return plain(shown).startsWith(plain(label)); }

    /** The first visible widget of the open screen whose label starts with {@code label}. */
    private static AbstractWidget widget(String label) {
        Screen screen = mc().screen;
        if (screen == null) return null;
        for (var child : screen.children()) {
            if (child instanceof AbstractWidget w && w.visible && says(w.getMessage().getString(), label)) return w;
        }
        return null;
    }

    /** Moves the real pointer onto a button found by its label, waits a frame, and clicks it. A missing button is a failure; an inactive one is clicked anyway when {@code expectActive} is false. */
    private static void click(String label, boolean expectActive) {
        final AbstractWidget[] found = new AbstractWidget[1];
        Playtest.queue("point at '" + label + "'", () -> {
            found[0] = widget(label);
            if (found[0] == null) { Playtest.fail("no '" + label + "' on " + (mc().screen == null ? "no screen" : mc().screen.getClass().getSimpleName())); return; }
            if (expectActive && !found[0].active) Playtest.fail("'" + label + "' is greyed out: " + found[0].getMessage().getString());
            Playtest.moveGui(found[0].getX() + found[0].getWidth() / 2, found[0].getY() + found[0].getHeight() / 2);
        });
        Playtest.wait("the pointer settles", 4);
        Playtest.queue("click '" + label + "'", () -> { if (found[0] != null) Playtest.pressLeft(); });
        Playtest.wait("the click lands", 6);
    }

    private static void clickAt(String label, Supplier<int[]> where) {
        final int[][] at = new int[1][];
        Playtest.queue("point at " + label, () -> {
            at[0] = where.get();
            if (at[0] == null) { Playtest.fail(label + " is not on the screen"); return; }
            Playtest.moveGui(at[0][0], at[0][1]);
        });
        Playtest.wait("the pointer settles", 4);
        Playtest.queue("click " + label, () -> { if (at[0] != null) Playtest.pressLeft(); });
        Playtest.wait("the click lands", 6);
    }

    private static void until(String label, BooleanSupplier done, int ticks) { Playtest.queue(label, () -> { }, done, ticks); }

    /** Ends the run when something has failed that every later step depends on (a cascade of follow-on failures tells nothing new). */
    private static void stopIfFailed(String what) {
        Playtest.queue("stop if " + what + " failed", () -> { if (Playtest.failures.length() > 0) { Playtest.log("JOURNEY stopping: " + what + " failed, and the rest depends on it"); summary(); Playtest.finish(); } });
    }

    private static void walk(String label, Supplier<Vec3> target, double within, int budget) {
        final PathWalker[] walker = new PathWalker[1];
        Playtest.queue(label, () -> walker[0] = new PathWalker(label, target, within), () -> {
            walker[0].tick();
            if (walker[0].done() && walker[0].failed()) Playtest.fail(walker[0].summary());
            return walker[0].done();
        }, budget);
        Playtest.queue("stop walking", () -> { if (walker[0] != null) walker[0].stop(); });
        Playtest.wait("settle", 10);
    }

    /** Turns the view to a point (what moving the mouse in the world does). */
    private static void look(Vec3 at) {
        var p = mc().player;
        Vec3 eye = p.getEyePosition();
        Vec3 d = at.subtract(eye);
        p.setYRot((float) Math.toDegrees(Math.atan2(-d.x, d.z)));
        p.yHeadRot = p.getYRot();
        p.setXRot((float) -Math.toDegrees(Math.atan2(d.y, Math.hypot(d.x, d.z))));
    }

    private static boolean aimedAt(BlockPos pos) {
        HitResult hit = mc().hitResult;
        return hit instanceof BlockHitResult b && hit.getType() == HitResult.Type.BLOCK && b.getBlockPos().equals(pos);
    }

    // ------------------------------------------------------------------------------------------------ 1. arrival and registration

    private static void arrive() {
        until("the client has the ground under the player", Scenarios::groundBelowPlayer, 2400);
        Playtest.queue("where the player arrived", () -> {
            BlockPos feet = mc().player.blockPosition();
            BlockPos row = DistrictBuilder.originOf(level(), "founders_row");
            Playtest.log("JOURNEY arrived at " + feet.toShortString() + ", Founders Row at " + (row == null ? "none" : row.toShortString()) + ", game mode " + mc().gameMode.getPlayerMode());
            if (row == null) Playtest.fail("Founders Row was not built at the landing");
            else if (Math.hypot(feet.getX() - row.getX(), feet.getZ() - row.getZ()) > 100) Playtest.fail("the arrival is far from Founders Row");
            else pass("1 arrive at the landing in front of Founders Row");
        });
    }

    private static void register() {
        until("the first-login registration screen opens by itself", () -> mc().screen instanceof RegistrationScreen, 1200);
        Playtest.wait("let the screen render", 20);
        shot("01-register");
        clickAt("the Ironhaven card", () -> { Playtest.focusWindow(); return ((RegistrationScreen) mc().screen).cardCentre(0); });
        until("the profession step", () -> mc().screen instanceof RegistrationScreen s && s.step() == 1, 100);
        Playtest.wait("settle", 6);
        clickAt("the Smith card", () -> ((RegistrationScreen) mc().screen).cardCentre(2));
        until("the confirm step", () -> mc().screen instanceof RegistrationScreen s && s.step() == 2, 100);
        shot("02-register-confirm");
        clickAt("CONFIRM", () -> ((RegistrationScreen) mc().screen).confirmCentre());
        until("the screen closes", () -> !(mc().screen instanceof RegistrationScreen), 300);
        Playtest.wait("let the server answer", 20);
        Playtest.queue("the citizen record and the welcome pack", () -> {
            var c = citizen();
            if (c == null) { Playtest.fail("no citizen after the click path"); return; }
            Playtest.log("JOURNEY citizen " + c.city + "/" + c.career + ", wallet " + wallet() + ", pickaxe " + count(Items.STONE_PICKAXE) + ", terminal " + count(UndergroundCity.TERMINAL_ITEM.get()));
            if (!c.city.equals("ironhaven") || !c.career.equals(CAREER)) Playtest.fail("registered as " + c.city + "/" + c.career);
            else if (count(UndergroundCity.TERMINAL_ITEM.get()) < 1) Playtest.fail("the welcome pack has no terminal");
            else pass("2 register citizenship and choose a profession (Ironhaven, Smith) on the real screen");
        });
        Playtest.queue("survival mode", () -> { if (mc().gameMode.getPlayerMode() != net.minecraft.world.level.GameType.SURVIVAL) { scripted("switched the new world's player to survival with /gamemode"); Playtest.command("gamemode survival"); } });
        Playtest.wait("settle", 20);
    }

    // ------------------------------------------------------------------------------------------------ 2. the arrow, the employer and the first order

    private static void followArrowToEmployer() {
        until("the guidance card names the employer and points at her", () -> {
            var objective = ClientOrderTracker.objective();
            return objective != null && objective.target() != null && objective.title().startsWith("Meet your employer");
        }, 600);
        Playtest.queue("what the card says", () -> {
            var o = ClientOrderTracker.objective();
            Playtest.log("JOURNEY guidance: '" + o.title() + "' -> " + o.targetName() + " at " + o.target().toShortString() + ", " + String.format(Locale.ROOT, "%.0f", Math.sqrt(o.target().distSqr(mc().player.blockPosition()))) + " blocks");
        });
        shot("03-guidance-arrow");
        walk("follow the arrow to the employer", () -> {
            var o = ClientOrderTracker.objective();
            if (o != null && o.target() != null) return Vec3.atBottomCenterOf(o.target());
            var e = employer(CAREER);
            return e == null ? null : e.position();
        }, 2.6, 3000);
        Playtest.queue("arrived beside the employer", () -> {
            var e = employer(CAREER);
            double d = e == null ? -1 : e.distanceTo(mc().player);
            Playtest.log("JOURNEY employer " + (e == null ? "missing" : e.getName().getString()) + String.format(Locale.ROOT, " %.1f blocks away", d));
            if (e == null || d > 4.5) Playtest.fail("the walk did not end beside the employer");
            else pass("3 follow the employer waypoint on foot (pathfinding route, real W key)");
        });
        shot("04-at-employer");
        stopIfFailed("the walk to the employer");
    }

    private static net.minecraft.world.entity.npc.Villager employer(String career) {
        return server(() -> Journey.find(level(), "employer", career));
    }

    /** Right-clicks a citizen with the real mouse until their conversation opens: aim at the head from where the player stands (they may have to step closer). */
    private static void speakTo(String label, Supplier<net.minecraft.world.entity.npc.Villager> who) {
        final int[] tick = {0}, tries = {0};
        Playtest.queue("speak to " + label, Playtest::focusWindow, () -> {
            if (mc().screen instanceof DialogueScreen) return true;
            var v = who.get();
            if (v == null) { Playtest.fail(label + " is not in the world"); return true; }
            var client = mc().level.getEntity(v.getId());
            Vec3 head = (client != null ? client.position() : v.position()).add(0, v.getBbHeight() * 0.85, 0);
            look(head);
            tick[0]++;
            if (tick[0] % 12 == 3) Playtest.mouseButton(3, true);
            if (tick[0] % 12 == 5) Playtest.mouseButton(3, false);
            if (tick[0] % 12 == 0 && ++tries[0] > 10) { Playtest.fail(label + ": no conversation after ten right clicks"); return true; }
            return false;
        }, 400);
        Playtest.wait("the conversation draws", 12);
    }

    private static void choose(String label, String expect) {
        clickAt("'" + label + "'", () -> {
            if (!(mc().screen instanceof DialogueScreen s)) return null;
            for (int i = 0; i < 8; i++) {
                String text = s.choiceLabel(i);
                if (text != null && says(text, label)) return s.choiceCentre(i);
            }
            return null;
        });
        if (expect != null) until("what '" + label + "' opens", () -> switch (expect) {
            case "terminal" -> mc().screen instanceof TerminalScreen;
            case "dialogue" -> mc().screen instanceof DialogueScreen;
            default -> mc().screen == null;
        }, 400);
        Playtest.wait("draw", 10);
    }

    private static int jobIndex(String id) {
        if (!(mc().screen instanceof TerminalScreen t)) return -1;
        var jobs = t.visibleJobs();
        for (int i = 0; i < jobs.size(); i++) if (jobs.get(i).id().equals(id)) return i;
        return -1;
    }

    private static void openOrdersAndAccept() {
        speakTo("the Smith's employer", () -> employer(CAREER));
        shot("05-employer-dialogue");
        choose("Open my orders", "terminal");
        Playtest.queue("employer met", () -> { var c = citizen(); if (c != null && c.journey("employer") > 0) pass("4 find and speak to the real employer; her orders open on her terminal"); else Playtest.fail("the employer step is not marked"); });
        shot("06-orders");
        // a level 2 order cannot be taken by a level 1 smith
        clickAt("the level 2 order (iron tools)", () -> { int i = jobIndex("underground_city:iron_tools"); return i < 0 ? null : ((TerminalScreen) mc().screen).jobCentre(i); });
        Playtest.queue("the level 2 order's accept button", () -> {
            var w = widget("Accept order");
            Playtest.log("JOURNEY iron tools accept button: " + (w == null ? "absent" : (w.active ? "active" : "greyed out") + " '" + w.getMessage().getString() + "'"));
        });
        click("Accept order", false);
        Playtest.wait("the server answers", 20);
        Playtest.queue("refused: the level 2 order is not taken", () -> {
            boolean has = server(() -> WorkOrderLedger.get(mc().getSingleplayerServer()).player(mc().player.getUUID()).hasUUID("order"));
            if (has) Playtest.fail("a level 1 smith accepted a level 2 order"); else pass("5a failure case: an order above the player's level is refused");
        });
        Playtest.queue("reopen the terminal if the refusal closed it", () -> { });
        Playtest.wait("settle", 6);
        Playtest.queue("is the terminal still open", () -> {
            if (!(mc().screen instanceof TerminalScreen)) Playtest.log("JOURNEY the terminal closed after the refusal; speaking to the employer again");
        });
        reopenOrdersIfClosed();
        clickAt("the first order (stone tools)", () -> { int i = jobIndex("underground_city:stone_tools"); return i < 0 ? null : ((TerminalScreen) mc().screen).jobCentre(i); });
        shot("07-stone-tools");
        click("Accept order", true);
        until("the order is accepted on the server", () -> server(() -> WorkOrderLedger.get(mc().getSingleplayerServer()).player(mc().player.getUUID()).hasUUID("order")), 200);
        Playtest.queue("accepted", () -> pass("5 accept the introductory work order (3 stone pickaxes, 24 credits) with a click"));
        stopIfFailed("accepting the first order");
        shot("08-accepted");
        Playtest.queue("close the terminal", () -> Playtest.tap("escape"));
        until("no screen", () -> mc().screen == null, 100);
    }

    private static void reopenOrdersIfClosed() {
        final boolean[] needed = {false};
        Playtest.queue("check", () -> needed[0] = !(mc().screen instanceof TerminalScreen));
        Playtest.queue("speak again when needed", () -> { }, () -> {
            if (!needed[0] || mc().screen instanceof TerminalScreen) return true;
            if (mc().screen instanceof DialogueScreen s) {
                for (int i = 0; i < 8; i++) { String t = s.choiceLabel(i); if (t != null && says(t, "Open my orders")) { int[] c = s.choiceCentre(i); Playtest.clickGui(c[0], c[1]); return false; } }
                return false;
            }
            var v = employer(CAREER);
            if (v != null) { look(v.position().add(0, v.getBbHeight() * 0.85, 0)); Playtest.mouseButton(3, true); Playtest.mouseButton(3, false); }
            return false;
        }, 400);
        Playtest.wait("draw", 10);
    }

    /** Incorrect profession: an employer of another trade offers no orders to a Smith. */
    private static void wrongTrade() {
        Playtest.queue("find the farmer's employer", () -> {
            var v = employer("farmer");
            F.otherEmployer = v == null ? null : v.blockPosition();
            if (v == null) Playtest.log("JOURNEY no farmer employer in the Row");
        });
        walk("walk to the farmers' employer", () -> { var v = employer("farmer"); return v == null ? mc().player.position() : v.position(); }, 2.6, 1600);
        speakTo("the farmers' employer", () -> employer("farmer"));
        shot("09-other-trade");
        Playtest.queue("no orders for another trade", () -> {
            if (!(mc().screen instanceof DialogueScreen s)) { Playtest.fail("the other employer's conversation did not open"); return; }
            boolean orders = false;
            for (int i = 0; i < 8; i++) { String t = s.choiceLabel(i); if (t != null && says(t, "Open my orders")) orders = true; }
            if (orders) Playtest.fail("another trade's employer offers a Smith her orders");
            else pass("5b failure case: an employer of another profession offers no orders");
        });
        Playtest.queue("close", () -> Playtest.tap("escape"));
        until("no screen", () -> mc().screen == null, 100);
    }

    // ------------------------------------------------------------------------------------------------ 3. materials: a tree and the quarry

    /** The nearest natural log (a log with leaves beside it, so never a building's beam) within {@code radius} blocks, on the server. */
    private static BlockPos nearestTree(int radius) {
        return server(() -> {
            ServerLevel level = level();
            BlockPos here = me().blockPosition();
            BlockPos best = null;
            double bestD = Double.MAX_VALUE;
            for (BlockPos p : BlockPos.betweenClosed(here.offset(-radius, -6, -radius), here.offset(radius, 14, radius))) {
                if (!level.isLoaded(p)) continue;
                BlockState s = level.getBlockState(p);
                if (!s.is(BlockTags.LOGS_THAT_BURN)) continue;
                if (!level.getBlockState(p.below()).is(BlockTags.DIRT)) continue;   // the bottom of a trunk standing on soil
                boolean leaves = false;
                for (BlockPos q : BlockPos.betweenClosed(p.offset(-2, 1, -2), p.offset(2, 6, 2))) if (level.getBlockState(q).is(BlockTags.LEAVES)) { leaves = true; break; }
                if (!leaves || CityBuildings.INSTANCE.at(level, p).isPresent()) continue;
                double d = p.distSqr(here);
                if (d < bestD) { bestD = d; best = p.immutable(); }
            }
            return best;
        });
    }

    /** Holds the left mouse button on a block until it is gone (as a player mines), aiming at it every tick. */
    private static void mine(String label, Supplier<BlockPos> block, int budget) {
        final BlockPos[] target = new BlockPos[1];
        final boolean[] down = {false};
        Playtest.queue(label, () -> target[0] = block.get(), () -> {
            if (target[0] == null) { Playtest.fail(label + ": nothing to mine"); return true; }
            if (mc().level.getBlockState(target[0]).isAir()) { if (down[0]) { Playtest.mouseButton(1, false); down[0] = false; } return true; }
            look(Vec3.atCenterOf(target[0]));
            if (!down[0] && aimedAt(target[0])) { Playtest.mouseButton(1, true); down[0] = true; }
            return false;
        }, budget);
        Playtest.queue("let go of the mouse", () -> { if (down[0]) Playtest.mouseButton(1, false); });
        Playtest.wait("the drop lands", 8);
    }

    private static void gatherWood() {
        Playtest.queue("look for a tree near the Row", () -> {
            F.tree = nearestTree(56);
            Playtest.log("JOURNEY nearest natural tree: " + (F.tree == null ? "none within 56 blocks" : F.tree.toShortString() + String.format(Locale.ROOT, ", %.0f blocks", Math.sqrt(F.tree.distSqr(mc().player.blockPosition())))));
            if (F.tree == null) Playtest.fail("no natural tree within 56 blocks of Founders Row: a new Smith with no credits cannot make sticks or a crafting table");
            else scripted("the tree's position was looked up in the world (a person would look around)");
        });
        walk("walk to the tree", () -> F.tree == null ? mc().player.position() : Vec3.atBottomCenterOf(F.tree), 2.2, 2400);
        for (int i = 0; i < 4; i++) {
            final int n = i;
            mine("chop log " + (i + 1) + " by hand", () -> {
                if (F.tree == null) return null;
                // the lowest log of the trunk that is still there, up to four high (reachable from the ground)
                for (int y = 0; y < 4; y++) { BlockPos p = F.tree.above(y); if (mc().level.getBlockState(p).is(BlockTags.LOGS)) return p; }
                return null;
            }, 400);
        }
        Playtest.queue("pick up the logs", () -> { });
        walk("step onto the drops", () -> F.tree == null ? mc().player.position() : Vec3.atBottomCenterOf(F.tree), 0.6, 200);
        Playtest.wait("collect", 30);
        Playtest.queue("logs in the inventory", () -> {
            int logs = server(() -> { int n = 0; for (ItemStack s : me().getInventory().items) if (s.is(net.minecraft.tags.ItemTags.LOGS)) n += s.getCount(); return n; });
            Playtest.log("JOURNEY logs carried: " + logs);
            if (logs < 3) Playtest.fail("only " + logs + " logs after chopping a tree"); else pass("6a chop a tree by hand for logs (real left button)");
        });
        stopIfFailed("chopping wood");
    }

    /** A stone block in the old quarry's walls (or, without a quarry, any exposed natural stone nearby) that is not one of the quarry's ore seams. */
    private static BlockPos quarryStone() {
        return server(() -> {
            ServerLevel level = level();
            BlockPos origin = DistrictBuilder.originOf(level, "old_quarry");
            BlockPos here = me().blockPosition();
            BlockPos centre = origin != null ? origin.offset(9, -3, 9) : here;
            BlockPos best = null;
            double bestD = Double.MAX_VALUE;
            for (BlockPos p : BlockPos.betweenClosed(centre.offset(-12, -8, -12), centre.offset(12, 6, 12))) {
                BlockState s = level.getBlockState(p);
                if (!(s.is(Blocks.STONE) || s.is(Blocks.COBBLESTONE) || s.is(Blocks.ANDESITE))) continue;
                if (!level.getBlockState(p.above()).isAir() && !level.getBlockState(p.north()).isAir() && !level.getBlockState(p.south()).isAir()
                    && !level.getBlockState(p.east()).isAir() && !level.getBlockState(p.west()).isAir()) continue;   // must show a face
                if (!level.getBlockState(p.below()).isSolidRender(level, p.below())) continue;
                double d = p.distSqr(here);
                if (d < bestD) { bestD = d; best = p.immutable(); }
            }
            return best;
        });
    }

    private static void gatherStone() {
        Playtest.queue("where the old quarry is", () -> {
            BlockPos q = server(() -> DistrictBuilder.originOf(level(), "old_quarry"));
            Playtest.log("JOURNEY old quarry: " + (q == null ? "not built in this world" : q.toShortString() + String.format(Locale.ROOT, ", %.0f blocks from the player", Math.sqrt(q.distSqr(mc().player.blockPosition())))));
        });
        hold("the stone pickaxe", Items.STONE_PICKAXE);
        Playtest.queue("choose a stone face in the quarry", () -> { F.stone = quarryStone(); Playtest.log("JOURNEY first stone: " + (F.stone == null ? "none" : F.stone.toShortString())); });
        walk("walk to the old quarry", () -> F.stone == null ? mc().player.position() : Vec3.atBottomCenterOf(F.stone), 3.0, 3600);
        for (int i = 0; i < 20; i++) {
            mine("mine stone " + (i + 1), JourneyScenario::reachableStone, 200);
        }
        Playtest.queue("pick up the stone", () -> { });
        Playtest.wait("collect", 30);
        Playtest.queue("cobblestone in the inventory", () -> {
            int c = count(Items.COBBLESTONE);
            Playtest.log("JOURNEY cobblestone carried: " + c);
            if (c < 18) Playtest.fail("only " + c + " cobblestone after mining"); else pass("6b mine stone in the old quarry with the welcome pack's pickaxe (real left button)");
        });
        stopIfFailed("mining stone");
    }

    /** The nearest stone block within reach that has a face showing, or null. */
    private static BlockPos reachableStone() {
        var p = mc().player;
        BlockPos eye = BlockPos.containing(p.getEyePosition());
        BlockPos best = null;
        double bestD = 20;
        for (BlockPos q : BlockPos.betweenClosed(eye.offset(-4, -4, -4), eye.offset(4, 3, 4))) {
            BlockState s = mc().level.getBlockState(q);
            if (!(s.is(Blocks.STONE) || s.is(Blocks.ANDESITE) || s.is(Blocks.DIORITE) || s.is(Blocks.GRANITE) || s.is(Blocks.COBBLESTONE))) continue;
            if (q.getY() < p.getBlockY() - 1) continue;   // never dig below the feet: no holes to fall into
            double d = Vec3.atCenterOf(q).distanceToSqr(p.getEyePosition());
            if (d > 16) continue;
            var hit = mc().level.clip(new net.minecraft.world.level.ClipContext(p.getEyePosition(), Vec3.atCenterOf(q), net.minecraft.world.level.ClipContext.Block.OUTLINE, net.minecraft.world.level.ClipContext.Fluid.NONE, p));
            if (hit.getType() != HitResult.Type.BLOCK || !hit.getBlockPos().equals(q)) continue;
            if (d < bestD) { bestD = d; best = q.immutable(); }
        }
        if (best == null) {
            // step toward the nearest stone face the server knows of
            BlockPos next = quarryStone();
            if (next != null && Vec3.atCenterOf(next).distanceTo(p.position()) > 3) Playtest.log("JOURNEY no stone in reach; nearest face " + next.toShortString());
        }
        return best;
    }

    // ------------------------------------------------------------------------------------------------ 4. the real crafting screens

    private static int[] slotCentre(AbstractContainerScreen<?> screen, Slot slot) {
        return new int[]{screen.getGuiLeft() + slot.x + 8, screen.getGuiTop() + slot.y + 8};
    }

    private static Slot inventorySlotWith(AbstractContainerScreen<?> screen, java.util.function.Predicate<ItemStack> what) {
        for (Slot s : screen.getMenu().slots) if (s.container instanceof net.minecraft.world.entity.player.Inventory && what.test(s.getItem())) return s;
        return null;
    }

    private static Slot emptyInventorySlot(AbstractContainerScreen<?> screen) { return inventorySlotWith(screen, ItemStack::isEmpty); }

    /** One real click on a slot (left or right button), with Shift held when asked. */
    private static void slotClick(String label, Supplier<Slot> slot, int button, boolean shift) {
        final Slot[] s = new Slot[1];
        Playtest.queue("point at " + label, () -> {
            s[0] = mc().screen instanceof AbstractContainerScreen<?> ? slot.get() : null;
            if (s[0] == null) { Playtest.fail("no slot for " + label + " on " + mc().screen); return; }
            int[] c = slotCentre((AbstractContainerScreen<?>) mc().screen, s[0]);
            Playtest.moveGui(c[0], c[1]);
        });
        Playtest.wait("settle", 3);
        Playtest.queue("click " + label, () -> {
            if (s[0] == null) return;
            if (shift) Playtest.keyDown("shift");
            Playtest.mouseButton(button, true); Playtest.mouseButton(button, false);
            if (shift) Playtest.keyUp("shift");
        });
        Playtest.wait("the server answers", 5);
    }

    private static Slot gridSlot(int index) {
        // the crafting grids are the menu's first slots: 0 is the result, then 1..4 (2x2) or 1..9 (3x3)
        return ((AbstractContainerScreen<?>) mc().screen).getMenu().slots.get(index);
    }

    private static boolean carried() { return mc().player != null && !mc().player.containerMenu.getCarried().isEmpty(); }

    /** Puts whatever is on the pointer back in an empty inventory slot. */
    private static void putBack(String label) {
        Playtest.queue("put back " + label, () -> { }, () -> !carried(), 1);
        slotClick("an empty inventory slot (" + label + ")", () -> carried() ? emptyInventorySlot((AbstractContainerScreen<?>) mc().screen) : null, 1, false);
    }

    private static void craftAll() {
        Playtest.queue("open the inventory with E", () -> { Playtest.focusWindow(); Playtest.tap("e"); });
        until("the inventory screen", () -> mc().screen instanceof InventoryScreen, 100);
        shot("10-inventory");
        // logs into planks: the whole stack into the 2x2 grid, then Shift-click the result
        slotClick("the logs", () -> inventorySlotWith((AbstractContainerScreen<?>) mc().screen, st -> st.is(net.minecraft.tags.ItemTags.LOGS)), 1, false);
        slotClick("grid square 1", () -> gridSlot(1), 1, false);
        slotClick("the planks (Shift-click)", () -> gridSlot(0), 1, true);
        Playtest.wait("craft", 10);
        Playtest.queue("planks crafted", () -> Playtest.log("JOURNEY planks: " + count(Items.OAK_PLANKS) + " oak, any " + server(() -> { int n = 0; for (ItemStack st : me().getInventory().items) if (st.is(net.minecraft.tags.ItemTags.PLANKS)) n += st.getCount(); return n; })));
        // a crafting table: one plank in each of the four squares (right clicks drop one each)
        slotClick("the planks", () -> inventorySlotWith((AbstractContainerScreen<?>) mc().screen, st -> st.is(net.minecraft.tags.ItemTags.PLANKS)), 1, false);
        for (int i = 1; i <= 4; i++) { final int g = i; slotClick("grid square " + g + " (one plank)", () -> gridSlot(g), 3, false); }
        putBack("the other planks");
        slotClick("the crafting table", () -> gridSlot(0), 1, true);
        Playtest.wait("craft", 10);
        // sticks: three planks in each of the two left squares, then Shift-click (12 sticks)
        slotClick("the planks", () -> inventorySlotWith((AbstractContainerScreen<?>) mc().screen, st -> st.is(net.minecraft.tags.ItemTags.PLANKS)), 1, false);
        for (int i = 0; i < 3; i++) { slotClick("grid square 1 (one plank)", () -> gridSlot(1), 3, false); slotClick("grid square 3 (one plank)", () -> gridSlot(3), 3, false); }
        putBack("the other planks");
        slotClick("the sticks (Shift-click)", () -> gridSlot(0), 1, true);
        Playtest.wait("craft", 10);
        shot("11-sticks-and-table");
        Playtest.queue("table and sticks", () -> {
            int table = count(Items.CRAFTING_TABLE), sticks = count(Items.STICK);
            Playtest.log("JOURNEY crafting table " + table + ", sticks " + sticks);
            if (table < 1 || sticks < 12) Playtest.fail("crafting in the inventory made " + table + " tables and " + sticks + " sticks");
        });
        Playtest.queue("close the inventory", () -> Playtest.tap("escape"));
        until("no screen", () -> mc().screen == null, 100);
        // place the table on the ground in front and open it
        hold("the crafting table", Items.CRAFTING_TABLE);
        final BlockPos[] spot = new BlockPos[1];
        Playtest.queue("look at the ground two blocks ahead", () -> {
            var p = mc().player;
            Direction facing = p.getDirection();
            BlockPos ground = p.blockPosition().relative(facing, 2).below();
            for (int dy = 2; dy >= -2; dy--) {
                BlockPos g = p.blockPosition().relative(facing, 2).offset(0, dy - 1, 0);
                if (!mc().level.getBlockState(g).isAir() && mc().level.getBlockState(g.above()).isAir() && mc().level.getBlockState(g.above(2)).isAir()) { ground = g; break; }
            }
            spot[0] = ground.above();
            look(Vec3.atCenterOf(ground).add(0, 0.5, 0));
        });
        Playtest.wait("aim", 4);
        Playtest.queue("place it with a right click", () -> { Playtest.mouseButton(3, true); Playtest.mouseButton(3, false); });
        until("the table stands there", () -> spot[0] != null && mc().level.getBlockState(spot[0]).is(Blocks.CRAFTING_TABLE), 60);
        Playtest.queue("open it", () -> { look(Vec3.atCenterOf(spot[0])); });
        Playtest.wait("aim", 4);
        Playtest.queue("right click the table", () -> { Playtest.mouseButton(3, true); Playtest.mouseButton(3, false); });
        until("the crafting screen", () -> mc().screen instanceof CraftingScreen, 100);
        // six stone pickaxes: cobblestone in the top row and sticks down the middle, six of each per square, then Shift-click the result
        slotClick("the cobblestone", () -> inventorySlotWith((AbstractContainerScreen<?>) mc().screen, st -> st.is(Items.COBBLESTONE)), 1, false);
        for (int g : new int[]{1, 2, 3}) for (int k = 0; k < 6; k++) slotClick("grid square " + g + " (one stone)", () -> gridSlot(g), 3, false);
        putBack("the other cobblestone");
        slotClick("the sticks", () -> inventorySlotWith((AbstractContainerScreen<?>) mc().screen, st -> st.is(Items.STICK)), 1, false);
        for (int g : new int[]{5, 8}) for (int k = 0; k < 6; k++) slotClick("grid square " + g + " (one stick)", () -> gridSlot(g), 3, false);
        putBack("the other sticks");
        shot("12-pickaxe-recipe");
        slotClick("the stone pickaxes (Shift-click)", () -> gridSlot(0), 1, true);
        Playtest.wait("craft", 12);
        Playtest.queue("pickaxes made", () -> {
            int made = server(() -> { int n = 0; for (ItemStack st : me().getInventory().items) if (st.is(Items.STONE_PICKAXE) && st.getDamageValue() == 0) n++; return n; });
            Playtest.log("JOURNEY undamaged stone pickaxes: " + made);
            if (made < 6) Playtest.fail("the crafting screen made " + made + " of 6 stone pickaxes"); else pass("7 produce the goods on the real crafting screens (planks, a table, sticks, 6 stone pickaxes)");
        });
        stopIfFailed("crafting");
        Playtest.queue("close", () -> Playtest.tap("escape"));
        until("no screen", () -> mc().screen == null, 100);
    }

    // ------------------------------------------------------------------------------------------------ 5. delivery and the counter

    private static void deliverFirst() {
        walk("walk back to the employer", () -> { var e = employer(CAREER); return e == null ? null : e.position(); }, 2.6, 4000);
        speakTo("the employer", () -> employer(CAREER));
        choose("Open my orders", "terminal");
        Playtest.queue("before the delivery", () -> Playtest.log("JOURNEY before delivery: wallet " + wallet() + ", XP " + citizen().careerXp()));
        clickAt("the accepted order", () -> { int i = jobIndex("underground_city:stone_tools"); return i < 0 ? null : ((TerminalScreen) mc().screen).jobCentre(i); });
        shot("13-ready-to-deliver");
        Playtest.queue("the deliver button", () -> { var w = widget("Deliver"); Playtest.log("JOURNEY deliver button: " + (w == null ? "absent" : w.getMessage().getString() + (w.active ? "" : " (greyed)"))); });
        click("Deliver", true);
        until("paid", () -> server(() -> CitizenState.get(mc().getSingleplayerServer()).find(mc().player.getUUID()).map(c -> c.journey("deliveries") > 0).orElse(false)), 200);
        Playtest.queue("paid in credits and experience", () -> {
            F.walletAfterDelivery = wallet();
            F.xpAfterDelivery = citizen().careerXp();
            Playtest.log("JOURNEY after delivery: wallet " + F.walletAfterDelivery + ", XP " + F.xpAfterDelivery + ", level " + citizen().careerLevel() + ", points " + SkillService.pointsAvailable(citizen()));
            if (F.walletAfterDelivery < 24 || F.xpAfterDelivery < 125) Playtest.fail("the delivery paid " + F.walletAfterDelivery + " credits and " + F.xpAfterDelivery + " XP");
            else pass("8 deliver the goods at the employer's terminal and receive payment (credits and career XP)");
        });
        shot("14-delivered");
        // the second order, for the trip
        Playtest.wait("refresh", 10);
        clickAt("the stone tools order again", () -> { int i = jobIndex("underground_city:stone_tools"); return i < 0 ? null : ((TerminalScreen) mc().screen).jobCentre(i); });
        click("Accept order", true);
        until("the second order is accepted", () -> server(() -> WorkOrderLedger.get(mc().getSingleplayerServer()).player(mc().player.getUUID()).hasUUID("order")), 200);
    }

    private static void civilianCounter() {
        click("TRADE", true);
        Playtest.wait("draw", 8);
        shot("15-trade-page");
        click("Open civilian shop", true);
        until("the civilian counter opens", () -> mc().screen instanceof CivilianShopScreen, 300);
        Playtest.wait("draw", 10);
        shot("16-civilian-counter");
        clickAt("Buy 1 on Rations", () -> mc().screen instanceof CivilianShopScreen s ? s.buyCentre("Rations") : null);
        until("the rations arrive", () -> count(Items.BREAD) >= 8, 200);
        Playtest.queue("bought", () -> {
            F.walletAfterRations = wallet();
            Playtest.log("JOURNEY rations bought: bread " + count(Items.BREAD) + ", wallet " + F.walletAfterRations);
            if (F.walletAfterRations != F.walletAfterDelivery - 20) Playtest.fail("the rations cost " + (F.walletAfterDelivery - F.walletAfterRations) + " credits, not 20");
            else pass("9 buy something useful at a functioning shop (rations at the civilian counter, real click)");
        });
        Playtest.wait("the counter refreshes", 20);
        shot("17-counter-poor");
        clickAt("Buy 1 on Workbench (more than the wallet holds)", () -> mc().screen instanceof CivilianShopScreen s ? s.buyCentre("Workbench") : null);
        Playtest.wait("the server answers", 30);
        Playtest.queue("refused, nothing moved", () -> {
            double w = wallet();
            int tables = count(Items.CRAFTING_TABLE);
            Playtest.log("JOURNEY after the unaffordable click: wallet " + w + ", crafting tables " + tables);
            if (w != F.walletAfterRations) Playtest.fail("the wallet changed on an unaffordable purchase"); else pass("9a failure case: a purchase the wallet cannot cover changes nothing");
        });
        Playtest.queue("close", () -> Playtest.tap("escape"));
        until("no screen", () -> mc().screen == null, 100);
    }

    // ------------------------------------------------------------------------------------------------ 6. a home

    private static void rentMotel() {
        Playtest.queue("where the motel's first room is", () -> {
            BlockPos motel = server(() -> DistrictBuilder.originOf(level(), "row_motel"));
            if (motel == null) { Playtest.fail("the Row Motel was not built"); return; }
            F.motelDoor = motel.offset(3, 0, 6);
            F.motelBarrel = motel.offset(5, 0, 1);
            Playtest.log("JOURNEY motel at " + motel.toShortString() + ", room 1 door " + F.motelDoor.toShortString());
        });
        walk("walk to the Row Motel", () -> F.motelDoor == null ? mc().player.position() : Vec3.atBottomCenterOf(F.motelDoor.south(2)), 1.5, 4000);
        shot("18-motel");
        Playtest.queue("press K for the Crew and Home screen", () -> { Playtest.focusWindow(); Playtest.tap("k"); });
        until("the Crew screen", () -> mc().screen instanceof CrewScreen, 200);
        Playtest.wait("draw", 10);
        crewButton("rail_home");
        crewButton("home_tab_1");
        shot("19-find-a-home");
        Playtest.queue("which offer is Room 1", () -> {
            if (!(mc().screen instanceof CrewScreen s)) return;
            var v = s.snapshot();
            for (int i = 0; i < v.nearby().size(); i++) Playtest.log("JOURNEY home offer " + i + ": " + v.nearby().get(i).id() + " '" + v.nearby().get(i).title() + "'");
        });
        clickAt("Rent on the first free motel room", () -> {
            if (!(mc().screen instanceof CrewScreen s)) return null;
            var v = s.snapshot();
            for (int i = 0; i < v.nearby().size(); i++) if (v.nearby().get(i).id().contains("motel_")) {
                UcButton b = s.button("rent_" + i);
                if (b == null || !b.visible || !b.active) continue;
                Playtest.log("JOURNEY renting " + v.nearby().get(i).id());
                return new int[]{b.getX() + b.getWidth() / 2, b.getY() + b.getHeight() / 2};
            }
            return null;
        });
        until("the room is ours", () -> server(() -> {
            CrewLedger ledger = CrewService.ledger(mc().getSingleplayerServer());
            return ledger != null && ledger.residencesOf(CrewLedger.OwnerType.PLAYER, mc().player.getUUID()).stream().anyMatch(r -> r.id.contains("motel_"));
        }), 300);
        Playtest.queue("rented", () -> {
            // the room this player rented decides which door and barrel are theirs
            String id = server(JourneyScenario::residenceId);
            int room = id.endsWith("motel_2") ? 2 : id.endsWith("motel_3") ? 3 : id.endsWith("motel_4") ? 4 : 1;
            BlockPos motel = server(() -> DistrictBuilder.originOf(level(), "row_motel"));
            F.motelDoor = motel.offset(3 + 5 * (room - 1), 0, 6);
            F.motelBarrel = motel.offset(5 + 5 * (room - 1), 0, 1);
            F.motelRoom = room;
            Playtest.log("JOURNEY room " + room + ": door " + F.motelDoor.toShortString() + ", barrel " + F.motelBarrel.toShortString());
            double w = wallet();
            Playtest.log("JOURNEY rented the motel room; wallet " + w + " (the welcome hours are free)");
            if (w != F.walletAfterRations) Playtest.fail("renting the welcome room cost " + (F.walletAfterRations - w)); else pass("10 rent a starter property on the Home page (motel room, free welcome hours)");
        });
        shot("20-rented");
        Playtest.queue("close", () -> Playtest.tap("escape"));
        until("no screen", () -> mc().screen == null, 100);
        // into the room: the door, then the barrel
        walk("walk in through the door", () -> F.motelDoor == null ? null : Vec3.atBottomCenterOf(F.motelDoor.north(2)), 1.0, 800);
        Playtest.queue("inside", () -> {
            boolean inside = server(() -> { var r = CrewService.ledger(mc().getSingleplayerServer()).residence(residenceId()); return r != null && r.insideInterior(level().dimension().location().toString(), me().blockPosition()); });
            if (inside) pass("11a enter the rented room through its door"); else Playtest.fail("the walk did not end inside the room");
        });
        Playtest.queue("look at the barrel", () -> look(Vec3.atCenterOf(F.motelBarrel)));
        Playtest.wait("aim", 4);
        Playtest.queue("open the barrel", () -> { Playtest.mouseButton(3, true); Playtest.mouseButton(3, false); });
        until("the barrel's screen", () -> mc().screen instanceof AbstractContainerScreen<?> s && !(s instanceof InventoryScreen), 100);
        shot("21-barrel");
        slotClick("the bread (Shift-click into the barrel)", () -> inventorySlotWith((AbstractContainerScreen<?>) mc().screen, st -> st.is(Items.BREAD)), 1, true);
        Playtest.queue("close", () -> Playtest.tap("escape"));
        until("no screen", () -> mc().screen == null, 100);
        Playtest.queue("the bread is in the barrel", () -> {
            int stored = server(() -> level().getBlockEntity(F.motelBarrel) instanceof net.minecraft.world.Container c ? c.countItem(Items.BREAD) : -1);
            Playtest.log("JOURNEY bread in the barrel: " + stored);
            if (stored > 0) pass("11 use the property's storage (bread into the room's barrel)"); else Playtest.fail("nothing was stored in the barrel");
        });
    }

    private static String residenceId() {
        CrewLedger ledger = CrewService.ledger(mc().getSingleplayerServer());
        for (var r : ledger.residencesOf(CrewLedger.OwnerType.PLAYER, mc().player.getUUID())) return r.id;
        return "";
    }

    private static void crewButton(String id) {
        clickAt("the Crew screen's " + id, () -> {
            if (!(mc().screen instanceof CrewScreen s)) return null;
            UcButton b = s.button(id);
            return b == null || !b.visible ? null : new int[]{b.getX() + b.getWidth() / 2, b.getY() + b.getHeight() / 2};
        });
        Playtest.wait("rebuild", 8);
    }

    // ------------------------------------------------------------------------------------------------ 7. a skill, a locked door, a reconnect

    private static void learnSkill() {
        Playtest.queue("place the welcome-pack terminal? no: use the room's terminal", () -> { });
        Playtest.queue("the room's terminal", () -> {
            BlockPos motel = server(() -> DistrictBuilder.originOf(level(), "row_motel"));
            BlockPos terminal = motel.offset(4 + 5 * (F.motelRoom - 1), 0, 1);
            look(Vec3.atCenterOf(terminal));
        });
        Playtest.wait("aim", 4);
        Playtest.queue("right click the room's terminal", () -> { Playtest.mouseButton(3, true); Playtest.mouseButton(3, false); });
        until("the terminal opens", () -> mc().screen instanceof TerminalScreen, 100);
        click("SKILLS", true);
        Playtest.wait("draw", 10);
        shot("22-skills");
        clickAt("the Forge Fundamentals card", () -> mc().screen instanceof TerminalScreen t ? t.skillCentre("smith.fundamentals") : null);
        Playtest.wait("select", 6);
        shot("23-skill-selected");
        click("Unlock", true);
        until("learned", () -> server(() -> CitizenState.get(mc().getSingleplayerServer()).find(mc().player.getUUID()).map(c -> c.skills().contains("smith.fundamentals")).orElse(false)), 200);
        Playtest.queue("skill", () -> pass("12 gain profession experience and unlock a skill on the SKILLS page (Forge Fundamentals)"));
        shot("24-skill-learned");
        Playtest.queue("close", () -> Playtest.tap("escape"));
        until("no screen", () -> mc().screen == null, 100);
    }

    private static void reconnect() {
        Playtest.queue("before the reconnect", () -> Playtest.log("JOURNEY before reconnect: wallet " + wallet() + ", XP " + citizen().careerXp() + ", order " + server(() -> WorkOrderLedger.get(mc().getSingleplayerServer()).player(mc().player.getUUID()).hasUUID("order")) + ", home " + residenceId()));
        final double[] before = new double[2];
        Playtest.queue("remember", () -> { before[0] = wallet(); before[1] = citizen().careerXp(); });
        Playtest.queue("save and quit to the title screen, then open the world again", () -> {
            var mc = mc();
            String folder = System.getProperty("uc.playtest.world", "UC-Playtest");   // Playtest makes the world in a folder of this name
            Playtest.log("JOURNEY leaving world " + folder);
            mc.level.disconnect();
            mc.clearLevel(new GenericDirtMessageScreen(Component.translatable("menu.savingLevel")));
            mc.setScreen(new TitleScreen());
            mc.createWorldOpenFlows().loadLevel(mc.screen, folder);
        }, () -> mc().player != null && mc().level != null && Scenarios.groundBelowPlayer(), 6000);
        Playtest.wait("settle", 60);
        Playtest.queue("everything is still there", () -> {
            double w = wallet();
            int xp = citizen().careerXp();
            boolean order = server(() -> WorkOrderLedger.get(mc().getSingleplayerServer()).player(mc().player.getUUID()).hasUUID("order"));
            String home = server(JourneyScenario::residenceId);
            Playtest.log("JOURNEY after reconnect: wallet " + w + ", XP " + xp + ", order " + order + ", home " + home + ", registration screen " + (mc().screen instanceof RegistrationScreen));
            if (w != before[0] || xp != before[1] || !order || home.isEmpty() || mc().screen instanceof RegistrationScreen) Playtest.fail("state changed across the reconnect");
            else pass("13 reconnect during progression (save, quit to title, reopen): wallet, XP, accepted order and home unchanged, no second registration");
        });
    }

    // ------------------------------------------------------------------------------------------------ 8. the bridge and Valewood

    private static CorridorPlan roadTo(String city) {
        for (CorridorPlan plan : DevCommands.plans(mc().getSingleplayerServer())) {
            String id = plan.id();
            if (id.contains("ironhaven") && id.contains(city)) return plan;
        }
        return null;
    }

    private static void travelOut() {
        Playtest.queue("the road to Valewood", () -> {
            CorridorPlan plan = roadTo("valewood");
            if (plan == null) { Playtest.fail("no road plan between Ironhaven and Valewood"); return; }
            double first = -1, last = -1;
            for (double at = 0; at <= plan.length(); at += 1) if (plan.openAt(at)) { if (first < 0) first = at; last = at; }
            boolean fromIronhaven = plan.id().startsWith("ironhaven");
            F.road = plan.id();
            F.roadFrom = fromIronhaven ? first + 4 : last - 4;
            F.roadTo = fromIronhaven ? last - 4 : first + 4;
            double[] start = plan.pointAt(F.roadFrom);
            Playtest.log("JOURNEY road " + plan.id() + " is " + (int) plan.length() + " blocks; open " + (int) first + " to " + (int) last + "; its Ironhaven end is " + String.format(Locale.ROOT, "%.0f", Math.hypot(start[0] - mc().player.getX(), start[1] - mc().player.getZ())) + " blocks from the player");
        });
        walk("walk from the Row to the road's Ironhaven end", () -> {
            CorridorPlan plan = roadTo("valewood");
            if (plan == null) return null;
            double[] p = plan.pointAt(F.roadFrom);
            return new Vec3(p[0], mc().player.getY(), p[1]);
        }, 6.0, 12000);
        shot("25-road-start");
        final RoadWalker[] walker = new RoadWalker[1];
        Playtest.queue("walk the road and its bridge to Valewood with the W key", () -> {
            CorridorPlan plan = roadTo("valewood");
            walker[0] = new RoadWalker(plan, false, F.roadFrom, F.roadTo);
        }, () -> { walker[0].tick(); return walker[0].done(); }, 24000);
        Playtest.queue("arrived in Valewood", () -> {
            String city = server(() -> CityRegions.INSTANCE.cityAt(level(), me().blockPosition()).orElse(""));
            Playtest.log("JOURNEY road walk: " + walker[0].summary() + "; now in '" + city + "'");
            if (!walker[0].summary().contains("reached")) Playtest.fail("the road walk did not reach the far end: " + walker[0].summary());
            else pass("14a travel across a real bridge to another city on foot");
        });
        shot("26-valewood-arrival");
    }

    /** Puts an item on the hotbar the way a person does when it is in the main inventory (E, click it, click the last hotbar square, Escape), then selects it with its number key. */
    private static void hold(String label, Item item) {
        final boolean[] needed = {false};
        Playtest.queue("is " + label + " on the hotbar", () -> {
            int slot = server(() -> { var inv = me().getInventory(); for (int i = 0; i < 9; i++) if (inv.items.get(i).is(item)) return i; return -1; });
            needed[0] = slot < 0;
            if (!needed[0]) Playtest.tap(String.valueOf(slot + 1));
        });
        Playtest.queue("open the inventory if " + label + " must be moved", () -> { if (needed[0]) { Playtest.focusWindow(); Playtest.tap("e"); } });
        until("the inventory, when needed", () -> !needed[0] || mc().screen instanceof InventoryScreen, 100);
        Playtest.queue("pick up " + label, () -> { }, () -> true, 1);
        slotClickIf(needed, "pick up " + label, () -> inventorySlotWith((AbstractContainerScreen<?>) mc().screen, st -> st.is(item)), 1);
        slotClickIf(needed, "the ninth hotbar square", () -> ((AbstractContainerScreen<?>) mc().screen).getMenu().slots.get(44), 1);
        slotClickIf(needed, "an empty square for what was there", () -> carried() ? emptyInventorySlot((AbstractContainerScreen<?>) mc().screen) : null, 1);
        Playtest.queue("close the inventory", () -> { if (needed[0]) Playtest.tap("escape"); });
        until("no screen", () -> mc().screen == null, 100);
        Playtest.queue("select " + label, () -> {
            int slot = server(() -> { var inv = me().getInventory(); for (int i = 0; i < 9; i++) if (inv.items.get(i).is(item)) return i; return -1; });
            if (slot < 0) Playtest.fail(label + " is still not on the hotbar"); else Playtest.tap(String.valueOf(slot + 1));
        });
        Playtest.wait("select", 4);
    }

    private static void slotClickIf(boolean[] needed, String label, Supplier<Slot> slot, int button) {
        final Slot[] s = new Slot[1];
        Playtest.queue("point at " + label, () -> {
            if (!needed[0]) return;
            s[0] = mc().screen instanceof AbstractContainerScreen<?> ? slot.get() : null;
            if (s[0] == null) return;
            int[] c = slotCentre((AbstractContainerScreen<?>) mc().screen, s[0]);
            Playtest.moveGui(c[0], c[1]);
        });
        Playtest.wait("settle", 3);
        Playtest.queue("click " + label, () -> { if (needed[0] && s[0] != null) { Playtest.mouseButton(button, true); Playtest.mouseButton(button, false); } });
        Playtest.wait("the server answers", 5);
    }

    /** Right-clicks a block with the real mouse until {@code done}. */
    private static void use(String label, Supplier<BlockPos> block, BooleanSupplier done, int budget) {
        final int[] t = {0};
        Playtest.queue(label, Playtest::focusWindow, () -> {
            if (done.getAsBoolean()) return true;
            BlockPos b = block.get();
            if (b == null) { Playtest.fail(label + ": no block"); return true; }
            look(Vec3.atCenterOf(b));
            t[0]++;
            if (t[0] % 10 == 4) Playtest.mouseButton(3, true);
            if (t[0] % 10 == 6) Playtest.mouseButton(3, false);
            return false;
        }, budget);
        Playtest.wait("settle", 6);
    }

    private static void valewood() {
        walk("walk into the city", () -> {
            var city = CityRegions.INSTANCE.all().stream().filter(c -> c.id().equals("valewood")).findFirst().orElse(null);
            return city == null ? null : new Vec3(city.centerX(), mc().player.getY(), city.centerZ());
        }, 60, 6000);
        Playtest.queue("in Valewood", () -> Playtest.log("JOURNEY in '" + server(() -> CityRegions.INSTANCE.cityAt(level(), me().blockPosition()).orElse("")) + "' at " + mc().player.blockPosition().toShortString()));
        shot("27-valewood-street");
        valewoodDoor();
        // the welcome-pack terminal, placed in the street
        hold("the welcome-pack terminal", UndergroundCity.TERMINAL_ITEM.get());
        final BlockPos[] spot = new BlockPos[1];
        Playtest.queue("look at open street ground two blocks ahead", () -> {
            var p = mc().player;
            for (Direction d : new Direction[]{p.getDirection(), p.getDirection().getClockWise(), p.getDirection().getCounterClockWise(), p.getDirection().getOpposite()}) {
                BlockPos g = p.blockPosition().relative(d, 2).below();
                if (!mc().level.getBlockState(g).isAir() && mc().level.getBlockState(g.above()).isAir() && mc().level.getBlockState(g.above(2)).isAir()) { spot[0] = g.above(); break; }
            }
            if (spot[0] == null) spot[0] = p.blockPosition().relative(p.getDirection(), 2);
            look(Vec3.atCenterOf(spot[0].below()).add(0, 0.5, 0));
        });
        Playtest.wait("aim", 4);
        use("place the terminal", () -> spot[0].below(), () -> spot[0] != null && mc().level.getBlockState(spot[0]).is(UndergroundCity.TERMINAL.get()), 200);
        Playtest.queue("placed", () -> { F.placedTerminal = spot[0]; if (!mc().level.getBlockState(spot[0]).is(UndergroundCity.TERMINAL.get())) Playtest.fail("the terminal could not be placed in the street"); });
        use("open the terminal", () -> F.placedTerminal, () -> mc().screen instanceof TerminalScreen, 200);
        shot("28-valewood-terminal");
        Playtest.queue("a visit to a rival city", () -> {
            int visits = server(() -> WorkOrderLedger.get(mc().getSingleplayerServer()).foreignVisits(mc().player.getUUID(), "ironhaven"));
            if (visits >= 1) pass("14b a rival city visit is recorded at a terminal inside Valewood"); else Playtest.fail("no visit recorded in Valewood");
        });
        clickAt("the second order", () -> { int i = jobIndex("underground_city:stone_tools"); return i < 0 ? null : ((TerminalScreen) mc().screen).jobCentre(i); });
        click("Deliver", true);
        until("the second delivery", () -> server(() -> CitizenState.get(mc().getSingleplayerServer()).find(mc().player.getUUID()).map(c -> c.journey("deliveries") >= 2).orElse(false)), 200);
        Playtest.queue("second order", () -> { Playtest.log("JOURNEY second delivery in Valewood: wallet " + wallet() + ", XP " + citizen().careerXp()); pass("15 complete a follow-up activity in another city (the second order, delivered in Valewood)"); });
        Playtest.wait("refresh", 10);
        click("TRADE", true);
        Playtest.wait("draw", 10);
        shot("29-valewood-exchange");
        final String[] charcoal = {null}, iron = {null};
        Playtest.queue("the Valewood shelves", () -> {
            if (!(mc().screen instanceof TerminalScreen t)) return;
            for (var o : t.data().offers()) {
                Playtest.log("JOURNEY Valewood offer " + o.id() + " " + o.item() + " " + o.price() + " credits, " + o.stock() + " left");
                if (o.item().equals("minecraft:charcoal")) charcoal[0] = o.id();
                if (o.item().equals("minecraft:iron_ingot")) iron[0] = o.id();
            }
        });
        // buy one iron ingot for the Smith's next order (12 credits in Valewood)
        final double[] w0 = new double[1];
        Playtest.queue("before buying", () -> w0[0] = wallet());
        clickAt("Buy 1 on the iron ingot line", () -> mc().screen instanceof TerminalScreen t && iron[0] != null ? t.offerBuyCentre(iron[0]) : null);
        until("the iron arrives", () -> count(Items.IRON_INGOT) >= 1, 200);
        Playtest.queue("bought on the exchange", () -> {
            double w = wallet();
            Playtest.log("JOURNEY bought iron in Valewood: wallet " + w0[0] + " -> " + w);
            if (w >= w0[0]) Playtest.fail("the wallet did not pay for the iron"); else pass("16a buy raw material on the Valewood exchange (finite shared shelf)");
        });
        // another buyer has emptied the charcoal shelf: a click on it changes nothing
        Playtest.queue("another buyer empties the charcoal shelf", () -> {
            scripted("the Valewood charcoal shelf was emptied on the server to stand in for another buyer");
            server(() -> {
                RegionalStock stock = charcoal[0] == null ? null : RegionalCatalog.INSTANCE.find(charcoal[0]);
                if (stock != null) WorkOrderLedger.get(mc().getSingleplayerServer()).setRegionalUnitsForTest(stock, 0);
                return stock != null;
            });
            Playtest.tap("escape");
        });
        until("closed", () -> mc().screen == null, 100);
        use("open the terminal again", () -> F.placedTerminal, () -> mc().screen instanceof TerminalScreen, 200);
        click("TRADE", true);
        Playtest.wait("draw", 10);
        shot("30-sold-out");
        final double[] w1 = new double[1];
        final int[] c1 = new int[1];
        Playtest.queue("before the sold-out click", () -> { w1[0] = wallet(); c1[0] = count(Items.CHARCOAL); });
        clickAt("Buy 1 on the empty charcoal line", () -> mc().screen instanceof TerminalScreen t && charcoal[0] != null ? t.offerBuyCentre(charcoal[0]) : null);
        Playtest.wait("the server answers", 30);
        Playtest.queue("refused, nothing moved", () -> {
            if (wallet() != w1[0] || count(Items.CHARCOAL) != c1[0]) Playtest.fail("a sold-out shelf sold something");
            else pass("16b failure case: a shelf another buyer emptied sells nothing and takes nothing");
        });
        Playtest.queue("close", () -> Playtest.tap("escape"));
        until("no screen", () -> mc().screen == null, 100);
    }

    /** Inaccessible building: walking at a locked city door and right-clicking it with no lockpicks leaves the door shut and the player outside. */
    private static void valewoodDoor() {
        final DoorWalk.Spot[] spot = new DoorWalk.Spot[1];
        final boolean[] searched = {false};
        Playtest.queue("find a locked street door in Valewood", () -> {
            var server = mc().getSingleplayerServer();
            server.execute(() -> { try { spot[0] = DoorWalk.find(server.overworld(), "valewood", true, "", b -> true).orElse(null); } finally { searched[0] = true; } });
        }, () -> searched[0], 6000);
        Playtest.queue("the door", () -> { if (spot[0] == null) Playtest.fail("no locked street door near Valewood's centre"); else Playtest.log("JOURNEY locked door " + spot[0].door().toShortString() + " of a " + spot[0].type() + " (" + spot[0].securityClass() + ")"); });
        walk("walk to the door", () -> spot[0] == null ? null : spot[0].stand(), 1.2, 3000);
        Playtest.queue("no lockpicks carried", () -> { if (count(UndergroundCity.item("lockpick")) > 0) Playtest.fail("the new Smith carries lockpicks"); });
        final int[] t = {0};
        Playtest.queue("hold W into the door and right-click it", () -> { if (spot[0] != null) { Playtest.focusWindow(); Playtest.keyDown("w"); } }, () -> {
            if (spot[0] == null) return true;
            var p = mc().player;
            p.setYRot(spot[0].yaw() + 180f);
            p.setXRot(0);
            t[0]++;
            if (t[0] % 6 == 2) Playtest.mouseButton(3, true);
            if (t[0] % 6 == 4) Playtest.mouseButton(3, false);
            return t[0] > 80;
        }, 120);
        Playtest.queue("let go", () -> { Playtest.keyUp("w"); Playtest.mouseButton(3, false); });
        Playtest.wait("settle", 10);
        Playtest.queue("the door is shut and the player outside", () -> {
            if (spot[0] == null) return;
            boolean open = server(() -> DoorWalk.doorOpen(level(), spot[0]));
            double past = spot[0].past(mc().player.position());
            Playtest.log("JOURNEY locked door after the walk: " + (open ? "OPEN" : "shut") + String.format(Locale.ROOT, ", player %.2f blocks past the door", past) + ", screen " + mc().screen);
            if (open || past > 0.2 || mc().screen instanceof LockpickScreen) Playtest.fail("a locked door let a player with no picks in");
            else pass("16c failure case: a locked city building stays shut to a player without lockpicks");
        });
        Playtest.queue("close anything that opened", () -> { if (mc().screen != null) Playtest.tap("escape"); });
    }

    private static void travelBack() {
        final RoadWalker[] walker = new RoadWalker[1];
        walk("walk back to the road's Valewood end", () -> {
            CorridorPlan plan = roadTo("valewood");
            if (plan == null) return null;
            double[] p = plan.pointAt(F.roadTo);
            return new Vec3(p[0], mc().player.getY(), p[1]);
        }, 6.0, 8000);
        Playtest.queue("walk back across the bridge to Ironhaven", () -> walker[0] = new RoadWalker(roadTo("valewood"), false, F.roadTo, F.roadFrom),
            () -> { walker[0].tick(); return walker[0].done(); }, 24000);
        Playtest.queue("back", () -> {
            Playtest.log("JOURNEY return walk: " + walker[0].summary());
            if (walker[0].summary().contains("reached")) pass("17 return across the bridge to Ironhaven"); else Playtest.fail("the return walk did not reach Ironhaven's end");
        });
        shot("31-home-again");
    }

    static void summary() {
        Playtest.log("JOURNEY steps passed with real input: " + F.passed.size());
        for (String s : F.passed) Playtest.log("JOURNEY   " + s);
        Playtest.log("JOURNEY scripted shortcuts: " + F.scripted.size());
        for (String s : F.scripted) Playtest.log("JOURNEY   " + s);
        Playtest.log(String.format(Locale.ROOT, "JOURNEY took %.0f s of real time", (System.nanoTime() - F.started) / 1e9));
    }
}
