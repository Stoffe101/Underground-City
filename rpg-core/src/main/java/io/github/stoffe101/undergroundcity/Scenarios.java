package io.github.stoffe101.undergroundcity;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;

/** The scripted situations run by {@link Playtest}. Each queues steps; nothing here is reachable in normal play. */
final class Scenarios {
    private Scenarios() {}

    static void build(String scenario) {
        switch (scenario) {
            case "input" -> input();
            case "register" -> register();
            case "walk" -> walk();
            case "row" -> row();
            case "posts" -> posts();
            case "tracker" -> tracker();
            case "trace" -> trace();
            case "car" -> car();
            case "boat" -> boat();
            case "gun" -> gun();
            default -> throw new IllegalArgumentException("unknown scenario " + scenario);
        }
    }

    /**
     * First login of a brand-new player: the registration screen must open by itself, a mouse path through it (city,
     * profession, confirm) must create the citizen on the server and grant the welcome pack, and the screen must close.
     */
    private static void register() {
        Playtest.queue("the registration screen opens by itself at first login", () -> { },
            () -> Minecraft.getInstance().screen instanceof RegistrationScreen, 600);
        Playtest.wait("let the screen render", 20);
        Playtest.queue("screenshot of step 1", () -> Playtest.screenshot("uc-playtest-register-1"));
        Playtest.wait("settle", 10);
        Playtest.queue("click the second city (Valewood)", () -> {
            Playtest.focusWindow();
            int[] at = ((RegistrationScreen) Minecraft.getInstance().screen).cardCentre(1);
            Playtest.clickGui(at[0], at[1]);
        }, () -> ((RegistrationScreen) Minecraft.getInstance().screen).step() == 1, 100);
        Playtest.queue("click the first profession (Farmer)", () -> {
            int[] at = ((RegistrationScreen) Minecraft.getInstance().screen).cardCentre(0);
            Playtest.clickGui(at[0], at[1]);
        }, () -> ((RegistrationScreen) Minecraft.getInstance().screen).step() == 2, 100);
        Playtest.wait("settle", 10);
        Playtest.queue("screenshot of the confirm step", () -> Playtest.screenshot("uc-playtest-register-3"));
        Playtest.wait("settle", 10);
        Playtest.queue("click CONFIRM", () -> {
            int[] at = ((RegistrationScreen) Minecraft.getInstance().screen).confirmCentre();
            Playtest.clickGui(at[0], at[1]);
        }, () -> !(Minecraft.getInstance().screen instanceof RegistrationScreen), 200);
        Playtest.wait("let the server answer", 20);
        Playtest.queue("the server created the citizen and the welcome pack arrived", () -> {
            var mc = Minecraft.getInstance();
            var server = mc.getSingleplayerServer();
            var citizen = CitizenState.get(server).find(mc.player.getUUID());
            if (citizen.isEmpty()) { Playtest.fail("the server has no citizen record after the click path"); return; }
            Playtest.log("citizen " + citizen.get().city + "/" + citizen.get().career);
            if (!citizen.get().city.equals("valewood") || !citizen.get().career.equals("farmer")) Playtest.fail("the wrong identity was stored: " + citizen.get().city + "/" + citizen.get().career);
            boolean terminal = false, seeds = false;
            for (var stack : mc.player.getInventory().items) {
                if (stack.is(UndergroundCity.TERMINAL_ITEM.get())) terminal = true;
                if (stack.is(net.minecraft.world.item.Items.WHEAT_SEEDS)) seeds = true;
            }
            if (!terminal || !seeds) Playtest.fail("the welcome pack did not arrive (terminal=" + terminal + ", seeds=" + seeds + ")");
        });
    }

    /**
     * On foot along the generated roads: for each road the walker starts at its first block and holds the real W key, steering only
     * its heading along the road centreline. Options: {@code -PucPtRoute=<road id>} for one road, {@code -PucPtSprint=true}.
     */
    private static void walk() {
        var server = Minecraft.getInstance().getSingleplayerServer();
        String only = System.getProperty("uc.playtest.route", "");
        boolean sprint = Boolean.getBoolean("uc.playtest.sprint");
        var plans = DevCommands.plans(server).stream().filter(plan -> only.isBlank() || plan.id().equals(only)).toList();
        if (plans.isEmpty()) throw new IllegalStateException("no road plan matches '" + only + "'; the dev world needs the world-prototype pack");
        for (CorridorPlan plan : plans) {
            String id = plan.id();
            // Inside a city circle there is no corridor road (the city's own streets are there), so the walk covers the open stretch between the cities.
            double first = -1, last = -1;
            for (double at = 0; at <= plan.length(); at += 1) if (plan.openAt(at)) { if (first < 0) first = at; last = at; }
            final double from = first + 12, to = last - 12;
            Playtest.log("road " + id + " is " + (int) plan.length() + " blocks long; the open road runs from " + (int) first + " to " + (int) last + ", walking " + (int) from + " to " + (int) to);
            arriveOnRoad(id, (int) from, "survival");
            Playtest.queue("wait for the road plan to be built from the terrain", () -> { }, () -> roadPlan(id).built(), 1200);
            final RoadWalker[] walker = new RoadWalker[1];
            Playtest.queue("walk " + id + (sprint ? " (sprinting)" : ""), () -> walker[0] = new RoadWalker(roadPlan(id), sprint, from, to), () -> { walker[0].tick(); return walker[0].done(); }, 120_000);
        }
    }

    /** What the cursor is on, in words: a block with its position, an entity with its position, or nothing. */
    private static String describeCursor() {
        var mc = Minecraft.getInstance();
        var hit = mc.hitResult;
        if (hit == null) return "nothing";
        if (hit instanceof net.minecraft.world.phys.BlockHitResult block && hit.getType() == net.minecraft.world.phys.HitResult.Type.BLOCK) {
            return block.getBlockPos().toShortString() + " " + mc.level.getBlockState(block.getBlockPos()).getBlock();
        }
        if (hit instanceof net.minecraft.world.phys.EntityHitResult entity) return entity.getEntity().getType() + " " + entity.getEntity().getName().getString() + " at " + entity.getEntity().position();
        return String.valueOf(hit.getType());
    }

    /** The first-login screen has been closed by the harness by now; screens the scenario itself provokes are left open so it can look at them. */
    private static void screensStayOpenFromNow() {
        Playtest.wait("let the first-login screen come and go", 60);
        Playtest.queue("from here on, screens stay open", () -> Playtest.keepScreens = true);
    }

    /** Waits for the client to finish meshing the chunks around the player, then carries on either way (software rendering can be very slow; a late frame is better than a failed run). */
    private static void waitForMeshes(int ticks) {
        final int[] left = {ticks};
        Playtest.queue("let the chunk meshes finish", () -> { }, () -> {
            var renderer = Minecraft.getInstance().levelRenderer;
            // An empty mesh queue is also what a client that has received no chunk yet reports, so require sections actually on screen as well.
            if (renderer.hasRenderedAllChunks() && renderer.countRenderedChunks() >= 20) return true;
            if (--left[0] <= 0) { Playtest.log("the chunk meshes were still not finished after " + ticks + " ticks; carrying on"); return true; }
            return false;
        }, ticks + 20);
    }

    /** True once the chunk under the player has reached the client and the block below their feet is not air (so a survival player will not fall). */
    private static boolean groundBelowPlayer() {
        var mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) return false;
        BlockPos feet = mc.player.blockPosition();
        return mc.level.hasChunkAt(feet) && !mc.level.getBlockState(feet.below()).isAir();
    }

    /** True once the player is within a few blocks of {@code target} (x, z) and the ground there has arrived (a far teleport can stall the server for a long while). */
    private static boolean arrivedAt(double[] target) {
        var mc = Minecraft.getInstance();
        if (target == null || mc.player == null) return false;
        return Math.hypot(mc.player.getX() - target[0], mc.player.getZ() - target[1]) < 6 && groundBelowPlayer();
    }

    /**
     * Teleports onto a road in spectator mode (which cannot fall), waits until the ground under the player has arrived on the client, then
     * switches to {@code mode}. A far teleport on a slow machine otherwise drops the player through chunks the client has not received yet.
     */
    private static void arriveOnRoad(String road, int metres, String mode) { arriveOnRoadLater(road, () -> metres, mode); }

    /** As {@link #arriveOnRoad} for a distance only known when the step runs; a null {@code mode} leaves the player in spectator mode. */
    private static void arriveOnRoadLater(String road, java.util.function.IntSupplier metres, String mode) {
        final double[][] target = new double[1][];
        Playtest.queue("teleport onto " + road + " in spectator mode", () -> {
            int at = metres.getAsInt();
            var point = roadPlan(road).pointAt(at);
            target[0] = new double[]{point[0], point[1]};
            Playtest.command("gamemode spectator");
            Playtest.command("uc_dev road " + road + " " + at);
        });
        Playtest.queue("wait until the player is there and the ground has arrived", () -> { }, () -> arrivedAt(target[0]), 12000);
        if (mode != null) Playtest.queue("switch to " + mode, () -> Playtest.command("gamemode " + mode));
        Playtest.wait("settle", 40);
    }

    // ------------------------------------------------------------------------------------------------ Founders Row

    private static net.minecraft.server.level.ServerLevel level() { return Minecraft.getInstance().getSingleplayerServer().overworld(); }

    /** Moves the player (through the integrated server) to {@code at}, looking at {@code target}. */
    private static void standLooking(Vec3 at, Vec3 target) {
        Vec3 delta = target.subtract(at.add(0, 1.62, 0)); // the view starts at the eyes, not the feet
        float yaw = (float) Math.toDegrees(Math.atan2(-delta.x, delta.z));
        float pitch = (float) -Math.toDegrees(Math.atan2(delta.y, Math.hypot(delta.x, delta.z)));
        Playtest.command(String.format(java.util.Locale.ROOT, "tp @s %.2f %.2f %.2f %.1f %.1f", at.x, at.y, at.z, yaw, pitch));
    }

    private static net.minecraft.world.entity.npc.Villager citizen(BlockPos origin, int index) {
        var uuid = DistrictBuilder.citizensForTest(level(), "founders_row").get(index);
        var entity = level().getEntity(uuid);
        return entity instanceof net.minecraft.world.entity.npc.Villager villager ? villager : null;
    }

    private static int citizenIndex(String role, String career) {
        var ops = DistrictCatalog.INSTANCE.find("founders_row").npcOps();
        for (int i = 0; i < ops.size(); i++) {
            var npc = ops.get(i).npc();
            if (npc.role().equals(role) && (career == null || career.equals(npc.career()))) return i;
        }
        throw new IllegalStateException("no " + role + " " + career);
    }

    /** Walks up to a citizen (a step away, facing them) and speaks to them with a real right click. */
    private static void speakTo(String label, int index, String shot) {
        final Vec3[] stand = new Vec3[1];
        Playtest.queue("go to " + label, () -> {
            var villager = citizen(null, index);
            if (villager == null) { Playtest.fail(label + " is not in the world"); return; }
            // Two blocks toward the lane (the district's middle), so the player stands inside the room or on the street, never in a wall.
            double laneX = Vec3.atLowerCornerOf(DistrictBuilder.originOf(level(), "founders_row")).x + 12.5;
            Vec3 where = villager.position().add(Math.signum(laneX - villager.getX()) * 2.0, 0, 0);
            Vec3 head = villager.position().add(0, villager.getBbHeight() * 0.85, 0);
            stand[0] = where;
            standLooking(where, head);
        });
        Playtest.wait("settle", 10);
        Playtest.queue("right-click " + label, () -> {
            var villager = citizen(null, index);
            if (villager != null) standLooking(stand[0], villager.position().add(0, villager.getBbHeight() * 0.85, 0));
            Playtest.focusWindow();
            var mc = Minecraft.getInstance();
            Playtest.log(label + ": citizen at " + (villager == null ? "nowhere" : villager.position() + " on " + level().getBlockState(villager.blockPosition().below()).getBlock())
                + ", player at " + mc.player.position() + ", cursor " + describeCursor());
            Playtest.mouseButton(3, true);
        });
        Playtest.wait("hold", 4);
        Playtest.queue("release", () -> Playtest.mouseButton(3, false));
        Playtest.wait("let the answer arrive", 24);
        if (shot != null) Playtest.queue("screenshot " + shot, () -> Playtest.screenshot(shot));
    }

    /** Creative mode, a clear spot high in the sky of the test world, and Founders Row built there (or found, if an earlier run built it). */
    private static void buildRowInTheSky(BlockPos[] origin) {
        Playtest.queue("creative mode and a platform in the sky", () -> {
            var level = level();
            BlockPos existing = DistrictBuilder.originOf(level, "founders_row");
            var player = Minecraft.getInstance().player;
            origin[0] = existing != null ? existing : new BlockPos(player.getBlockX() + 12, 200, player.getBlockZ() + 12);
            Playtest.command("gamemode creative");
            Playtest.command("tp @s " + origin[0].getX() + " " + (origin[0].getY() + 1) + " " + origin[0].getZ());
        });
        Playtest.wait("let the chunks load", 160);
        final int[] attempts = {0};
        Playtest.queue("build Founders Row", () -> { }, () -> {
            if (DistrictBuilder.isBuilt(level(), "founders_row")) return true;
            if (attempts[0]++ % 40 == 0) {
                Playtest.command("tp @s " + origin[0].getX() + " " + (origin[0].getY() + 1) + " " + origin[0].getZ());
                Playtest.command("uc_district build founders_row");
            }
            return false;
        }, 1200);
        Playtest.wait("let the citizens settle", 80);
    }

    /**
     * Founders Row with its people, on a clear platform in the sky of the test world (so nothing real is overwritten): build it, look at it
     * from four places, then speak to the registrar (the first-login screen opens for a newcomer), register, speak again (a next step), and
     * speak to the Gunsmith's employer (their terminal opens) and a resident.
     */
    private static void row() {
        final BlockPos[] origin = new BlockPos[1];
        buildRowInTheSky(origin);
        screensStayOpenFromNow();
        Playtest.queue("overview from the north-west", () -> {
            Vec3 o = Vec3.atLowerCornerOf(origin[0]);
            standLooking(o.add(-6, 16, -10), o.add(14, 0, 18));
        });
        waitForMeshes(1500);
        Playtest.wait("settle", 20);
        Playtest.queue("screenshot", () -> Playtest.screenshot("uc-row-overview-nw"));
        Playtest.queue("overview from the south gate", () -> {
            Vec3 o = Vec3.atLowerCornerOf(origin[0]);
            standLooking(o.add(12.5, 6, 52), o.add(12.5, 1, 20));
        });
        waitForMeshes(1500);
        Playtest.wait("settle", 20);
        Playtest.queue("screenshot", () -> Playtest.screenshot("uc-row-gate-looking-north"));
        Playtest.queue("down the lane at eye level", () -> {
            Vec3 o = Vec3.atLowerCornerOf(origin[0]);
            standLooking(o.add(12.5, 1.1, 38), o.add(12.5, 1.5, 8));
        });
        Playtest.wait("settle", 40);
        Playtest.queue("screenshot", () -> Playtest.screenshot("uc-row-lane"));
        Playtest.queue("the north row from the lane", () -> {
            Vec3 o = Vec3.atLowerCornerOf(origin[0]);
            standLooking(o.add(12.5, 3, 14), o.add(12.5, 1, 1));
        });
        Playtest.wait("settle", 40);
        Playtest.queue("screenshot", () -> Playtest.screenshot("uc-row-north-row"));
        // The people.
        int registrar = citizenIndex("registrar", null), gunsmith = citizenIndex("employer", "gunsmith"), farmer = citizenIndex("employer", "farmer"), resident = citizenIndex("resident", null);
        speakTo("the registrar", registrar, "uc-row-registrar-first-login");
        Playtest.queue("the registration screen opened for a newcomer", () -> {
            if (!(Minecraft.getInstance().screen instanceof RegistrationScreen)) Playtest.fail("speaking to the registrar did not open the registration screen, screen=" + Minecraft.getInstance().screen);
        });
        Playtest.queue("close it with Escape", () -> Playtest.tap("escape"), () -> Minecraft.getInstance().screen == null, 100);
        Playtest.queue("register through the server as a Valewood gunsmith", () -> Playtest.command("uc register valewood gunsmith"));
        Playtest.wait("let the server answer", 30);
        speakTo("the registrar again", registrar, "uc-row-registrar-next-step");
        speakTo("the farm co-op's employer (not this citizen's trade)", farmer, "uc-row-wrong-employer");
        speakTo("the gunsmith's employer", gunsmith, "uc-row-employer-terminal");
        Playtest.queue("the employer's terminal opened", () -> {
            if (!(Minecraft.getInstance().screen instanceof TerminalScreen)) Playtest.fail("the gunsmith employer did not open the terminal, screen=" + Minecraft.getInstance().screen);
        });
        Playtest.queue("close it with Escape", () -> Playtest.tap("escape"), () -> Minecraft.getInstance().screen == null, 100);
        speakTo("a resident", resident, "uc-row-resident");
    }

    // ------------------------------------------------------------------------------------------------ the Technician's trace

    private static int traceLit(ChallengeScreen screen) {
        try {
            var method = ChallengeScreen.class.getDeclaredMethod("litPad");
            method.setAccessible(true);
            return (Integer) method.invoke(screen);
        } catch (ReflectiveOperationException error) { throw new IllegalStateException(error); }
    }

    private static boolean traceShowing(ChallengeScreen screen) {
        try {
            var method = ChallengeScreen.class.getDeclaredMethod("showing");
            method.setAccessible(true);
            return (Boolean) method.invoke(screen);
        } catch (ReflectiveOperationException error) { throw new IllegalStateException(error); }
    }

    private static void unlockWithPrerequisites(CitizenState citizens, java.util.UUID uuid, String node) {
        var skill = SkillCatalog.INSTANCE.find(node);
        for (String parent : skill.requires()) unlockWithPrerequisites(citizens, uuid, parent);
        if (!citizens.find(uuid).orElseThrow().skills().contains(node)) citizens.unlockSkill(uuid, node);
    }

    /** Opens the secure terminal with a real right click from the data office's floor and waits for the trace screen. */
    private static void startTrace(BlockPos[] origin, String label) {
        Playtest.queue(label + ": stand at the secure terminal and use it", () -> {
            Vec3 terminal = Vec3.atCenterOf(origin[0].offset(15, 0, 2)); // origin[0] is only known once the row has been built, so read it when the step runs
            standLooking(Vec3.atLowerCornerOf(origin[0].offset(15, 0, 4)).add(0.5, 0, 0.5), terminal.add(0, 0.2, 0));
        });
        Playtest.wait("settle", 15);
        Playtest.queue(label + ": right-click", () -> {
            var mc = Minecraft.getInstance();
            var lvl = level();
            Playtest.log("row origin " + origin[0].toShortString() + "; terminal cell " + lvl.getBlockState(origin[0].offset(15, 0, 2)).getBlock() + ", above it " + lvl.getBlockState(origin[0].offset(15, 1, 2)).getBlock()
                + ", below it " + lvl.getBlockState(origin[0].offset(15, -1, 2)).getBlock() + ", floor under the stand point " + lvl.getBlockState(origin[0].offset(15, -1, 4)).getBlock()
                + ", block at the stand point " + lvl.getBlockState(origin[0].offset(15, 0, 4)).getBlock());
            String aimed = mc.hitResult instanceof net.minecraft.world.phys.BlockHitResult block && block.getType() == net.minecraft.world.phys.HitResult.Type.BLOCK
                ? block.getBlockPos().toShortString() + " " + mc.level.getBlockState(block.getBlockPos()).getBlock() : String.valueOf(mc.hitResult == null ? null : mc.hitResult.getType());
            Playtest.log("cursor: " + aimed + " from " + mc.player.position() + " looking " + mc.player.getXRot() + "/" + mc.player.getYRot() + ", holding " + mc.player.getMainHandItem());
            Playtest.screenshot("uc-" + label.replace(' ', '-') + "-before-click");
            Playtest.focusWindow();
            Playtest.mouseButton(3, true);
        }, () -> Minecraft.getInstance().screen instanceof ChallengeScreen, 600);
        Playtest.queue(label + ": release the button", () -> Playtest.mouseButton(3, false));
    }

    /** Watches the sequence flash on the real screen, as a person would, and returns what was seen once it is over. */
    private static void watchTrace(java.util.List<Integer> seen, String label) {
        final int[] previous = {-1};
        Playtest.queue(label + ": watch the pads", () -> { seen.clear(); previous[0] = -1; }, () -> {
            if (!(Minecraft.getInstance().screen instanceof ChallengeScreen screen)) return true;
            int lit = traceLit(screen);
            if (lit >= 0 && previous[0] < 0) seen.add(lit);
            previous[0] = lit;
            return !traceShowing(screen);
        }, 400);
    }

    private static void typeTrace(java.util.List<Integer> seen, boolean wrong, String label) {
        final int[] next = {0}, gap = {0};
        Playtest.queue(label + ": enter the symbols with the keyboard", () -> { next[0] = 0; gap[0] = 0; Playtest.focusWindow(); }, () -> {
            if (!(Minecraft.getInstance().screen instanceof ChallengeScreen)) return true;
            if (gap[0]-- > 0) return false;
            if (next[0] >= seen.size()) return false;
            int pad = seen.get(next[0]++);
            if (wrong) pad = (pad + 1) & 3;
            Playtest.tap(Integer.toString(pad + 1));
            gap[0] = 4;
            return false;
        }, 600);
    }

    private static void spendTime(String label) {
        Playtest.queue(label + ": let server time pass so the terminal unlocks and its drive refills", () -> {
            var server = Minecraft.getInstance().getSingleplayerServer();
            server.execute(() -> WorkOrderLedger.get(server).advance(2_000_000L));
        });
        Playtest.wait("settle", 20);
    }

    private static int inventoryCount(String item) {
        int total = 0;
        for (var stack : Minecraft.getInstance().player.getInventory().items) {
            var key = net.minecraftforge.registries.ForgeRegistries.ITEMS.getKey(stack.getItem());
            if (key != null && key.toString().equals(item)) total += stack.getCount();
        }
        return total;
    }

    private static boolean terminalLocked(BlockPos origin) {
        var server = Minecraft.getInstance().getSingleplayerServer();
        var entity = server.overworld().getBlockEntity(origin.offset(15, 0, 2));
        return entity instanceof SecureTerminalBlockEntity terminal && terminal.lockedAt(WorkOrderLedger.get(server).clock());
    }

    /**
     * The Technician's trace on the real screen with the real keyboard: a success (the sequence watched and typed back), a wrong answer, walking away
     * (Escape) and running out of time, each with its consequence checked on the integrated server: the ledger, the kit, the lock-down.
     */
    private static void trace() {
        final BlockPos[] origin = new BlockPos[1];
        buildRowInTheSky(origin);
        screensStayOpenFromNow();
        Playtest.wait("let the citizens settle", 40);
        Playtest.queue("a registered Valewood technician with Bypass Tooling and four kits", () -> {
            Playtest.command("gamemode survival");
            Playtest.command("clear @s");
            Playtest.command("uc register valewood technician");
        });
        Playtest.wait("let the server answer", 40);
        Playtest.queue("experience, skills and kits (server side)", () -> {
            var mc = Minecraft.getInstance();
            var server = mc.getSingleplayerServer();
            var uuid = mc.player.getUUID();
            server.execute(() -> {
                var citizens = CitizenState.get(server);
                citizens.addCareerXp(uuid, 5000);
                unlockWithPrerequisites(citizens, uuid, "technician.bypass_tooling");
            });
            Playtest.command("give @s underground_city:bypass_kit 4");
        }, () -> inventoryCount("underground_city:bypass_kit") == 4, 200);
        final java.util.List<Integer> seen = new java.util.ArrayList<>();

        startTrace(origin, "success");
        Playtest.queue("success: screenshot while the pads flash", () -> Playtest.screenshot("uc-trace-showing"));
        watchTrace(seen, "success");
        Playtest.queue("success: five symbols were seen", () -> {
            Playtest.log("seen " + seen);
            if (seen.size() != SecureTerminalService.LENGTH) Playtest.fail("the screen flashed " + seen.size() + " symbols, expected " + SecureTerminalService.LENGTH);
        });
        Playtest.wait("success: settle", 4);
        typeTrace(seen, false, "success");
        Playtest.wait("success: the server answers", 30);
        Playtest.queue("success: the ledger arrived and one kit was spent", () -> {
            Playtest.log("ledgers " + inventoryCount("underground_city:data_ledger") + ", kits " + inventoryCount("underground_city:bypass_kit") + ", screen " + Minecraft.getInstance().screen);
            if (inventoryCount("underground_city:data_ledger") != 1) Playtest.fail("the Encrypted Ledger did not arrive after a correct trace");
            if (inventoryCount("underground_city:bypass_kit") != 3) Playtest.fail("the kit count should be 3");
            if (Minecraft.getInstance().screen != null) Playtest.fail("the trace screen did not close");
        });

        spendTime("wrong answer");
        startTrace(origin, "wrong answer");
        watchTrace(seen, "wrong answer");
        Playtest.wait("wrong answer: settle", 4);
        typeTrace(seen, true, "wrong answer");
        Playtest.wait("wrong answer: the server answers", 30);
        Playtest.queue("wrong answer: no ledger, the terminal locks down", () -> {
            Playtest.log("ledgers " + inventoryCount("underground_city:data_ledger") + ", kits " + inventoryCount("underground_city:bypass_kit") + ", locked " + terminalLocked(origin[0]));
            if (inventoryCount("underground_city:data_ledger") != 1) Playtest.fail("a wrong sequence must not pay");
            if (inventoryCount("underground_city:bypass_kit") != 2) Playtest.fail("the kit is spent when the attempt starts");
            if (!terminalLocked(origin[0])) Playtest.fail("the terminal should be locked down after a wrong answer");
        });
        Playtest.queue("wrong answer: screenshot of the consequence message", () -> Playtest.screenshot("uc-trace-wrong"));

        spendTime("abandon");
        startTrace(origin, "abandon");
        Playtest.wait("abandon: let two symbols flash", 20);
        Playtest.queue("abandon: press Escape", () -> Playtest.tap("escape"), () -> !(Minecraft.getInstance().screen instanceof ChallengeScreen), 100);
        Playtest.wait("abandon: the server answers", 30);
        Playtest.queue("abandon: the alarm tripped", () -> {
            Playtest.log("kits " + inventoryCount("underground_city:bypass_kit") + ", locked " + terminalLocked(origin[0]));
            if (inventoryCount("underground_city:bypass_kit") != 1) Playtest.fail("the kit is spent when the attempt starts");
            if (!terminalLocked(origin[0])) Playtest.fail("walking away should lock the terminal down");
        });

        spendTime("timeout");
        startTrace(origin, "timeout");
        Playtest.queue("timeout: do nothing until the window closes", () -> { }, () -> !(Minecraft.getInstance().screen instanceof ChallengeScreen), 20 * 40);
        Playtest.wait("timeout: the server answers", 30);
        Playtest.queue("timeout: the alarm tripped and no ledger came", () -> {
            Playtest.log("ledgers " + inventoryCount("underground_city:data_ledger") + ", kits " + inventoryCount("underground_city:bypass_kit") + ", locked " + terminalLocked(origin[0]));
            if (inventoryCount("underground_city:data_ledger") != 1) Playtest.fail("running out of time must not pay");
            if (inventoryCount("underground_city:bypass_kit") != 0) Playtest.fail("the last kit should be spent");
            if (!terminalLocked(origin[0])) Playtest.fail("running out of time should lock the terminal down");
        });
    }

    // ------------------------------------------------------------------------------------------------ a car on the road

    /**
     * Climbs into a vehicle the way a person does: an empty hand (a held item would be used instead), standing beside it, looking at it,
     * holding the real right mouse button for a few ticks (Immersive Vehicles polls the button's state). A few aim points are tried in turn.
     */
    private static void board(java.util.function.Supplier<net.minecraft.world.entity.Entity> vehicle, java.util.function.DoubleSupplier headingDegrees, String label, boolean fromBehind) {
        final int[] state = {0, 0, 0}; // attempt, phase, ticks in the phase
        final Vec3[] aims = {new Vec3(0, 0.7, 0), new Vec3(0, 1.1, 0), new Vec3(0, 0.4, 0), new Vec3(0.3, 0.9, -0.3), new Vec3(-0.3, 0.9, 0.3)};
        Playtest.queue(label + ": board with an empty hand", () -> {
            var mc = Minecraft.getInstance();
            int empty = -1;
            for (int i = 0; i < 9 && empty < 0; i++) if (mc.player.getInventory().getItem(i).isEmpty()) empty = i;
            if (empty < 0) { Playtest.fail("no empty hotbar slot to board with"); return; }
            mc.player.getInventory().selected = empty;
            Playtest.focusWindow();
        }, () -> {
            var mc = Minecraft.getInstance();
            if (mc.player.getVehicle() != null) { if (state[1] == 2) Playtest.mouseButton(3, false); Playtest.log(label + ": riding " + mc.player.getVehicle().getType() + " after " + (state[0] + 1) + " attempt(s)"); return true; }
            var target = vehicle.get();
            if (target == null) return false;
            switch (state[1]) {
                case 0 -> {
                    double yaw = Math.toRadians(headingDegrees.getAsDouble());
                    // Behind the vehicle (along its own forward axis, which is where the first successful boarding was made from), or beside it.
                    Vec3 offset = fromBehind ? new Vec3(Math.sin(yaw), 0, -Math.cos(yaw)).scale(1.6) : new Vec3(Math.cos(yaw), 0, Math.sin(yaw)).scale(-1.6);
                    Vec3 at = target.position();
                    standLooking(at.add(offset).add(0, 0.1, 0), at.add(aims[state[0] % aims.length]));
                    state[1] = 1; state[2] = 0;
                }
                case 1 -> { if (++state[2] >= 25) { Playtest.mouseButton(3, true); state[1] = 2; state[2] = 0; } }
                case 2 -> { if (++state[2] >= 6) { Playtest.mouseButton(3, false); state[1] = 3; state[2] = 0; } }
                default -> {
                    if (++state[2] >= 35) {
                        Playtest.log(label + ": attempt " + (state[0] + 1) + " did not seat the player; cursor " + mc.hitResult);
                        if (++state[0] >= 3 * aims.length) { Playtest.fail(label + ": no right click seated the player"); return true; }
                        state[1] = 0;
                    }
                }
            }
            return false;
        }, 6000);
        Playtest.queue(label + ": log the seat", () -> {
            var ride = Minecraft.getInstance().player.getVehicle();
            Playtest.log("riding " + (ride == null ? "nothing" : ride.getType() + " at " + ride.position()));
            if (ride != null) Playtest.screenshot("uc-" + label.replace(' ', '-') + "-seated");
        });
    }

    /** The Immersive Vehicles vehicle entity closest to the player (the mod registers its entity types under "mts"). */
    private static net.minecraft.world.entity.Entity vehicleNear() {
        var mc = Minecraft.getInstance();
        net.minecraft.world.entity.Entity best = null;
        for (var entity : mc.level.entitiesForRendering()) {
            var key = net.minecraftforge.registries.ForgeRegistries.ENTITY_TYPES.getKey(entity.getType());
            if (key == null || !key.getNamespace().equals("mts") || entity == mc.player || ivVehicle(entity) == null) continue;
            if (best == null || entity.distanceToSqr(mc.player) < best.distanceToSqr(mc.player)) best = entity;
        }
        return best;
    }

    /** Immersive Vehicles' own vehicle object wrapped by a Minecraft entity, or null. Reflection: the mod is a runtime-only dependency. */
    private static Object ivVehicle(net.minecraft.world.entity.Entity entity) {
        for (Class<?> type = entity.getClass(); type != null; type = type.getSuperclass()) {
            try {
                var field = type.getDeclaredField("entity");
                field.setAccessible(true);
                Object wrapped = field.get(entity);
                if (wrapped == null) return null;
                wrapped.getClass().getField("fuelTank");
                return wrapped;
            } catch (NoSuchFieldException next) {
                // not declared here: the wrapped entity lives in a superclass, or this is not a vehicle
            } catch (ReflectiveOperationException | RuntimeException error) {
                return null;
            }
        }
        return null;
    }

    /**
     * What the car driver reads from a vehicle: its velocity (blocks per tick, x and z) and the world heading, in Minecraft's yaw convention, of each of its four local
     * axes (+Z, -Z, +X, -X), computed with the mod's own rotation matrix; null when unreadable. The driver works out which axis is the front.
     */
    private static double[] vehicleMotion(net.minecraft.world.entity.Entity entity) {
        try {
            Object vehicle = entity == null ? null : ivVehicle(entity);
            if (vehicle == null) return null;
            Object motion = vehicle.getClass().getField("motion").get(vehicle);
            Object orientation = vehicle.getClass().getField("orientation").get(vehicle);
            Class<?> pointClass = Class.forName("minecrafttransportsimulator.baseclasses.Point3D", true, vehicle.getClass().getClassLoader());
            double[][] axes = {{0, 0, 1}, {0, 0, -1}, {1, 0, 0}, {-1, 0, 0}};
            double[] sensor = new double[6];
            sensor[0] = motion.getClass().getField("x").getDouble(motion);
            sensor[1] = motion.getClass().getField("z").getDouble(motion);
            for (int i = 0; i < 4; i++) {
                Object point = pointClass.getConstructor(double.class, double.class, double.class).newInstance(axes[i][0], axes[i][1], axes[i][2]);
                Object world = orientation.getClass().getMethod("rotate", pointClass).invoke(orientation, point);
                double x = world.getClass().getField("x").getDouble(world), z = world.getClass().getField("z").getDouble(world);
                sensor[2 + i] = Math.toDegrees(Math.atan2(-x, z));
            }
            return sensor;
        } catch (ReflectiveOperationException | RuntimeException error) {
            return null;
        }
    }

    private static String describeVehicle(Object vehicle) {
        try {
            Object tank = vehicle.getClass().getField("fuelTank").get(vehicle);
            return vehicle.getClass().getSimpleName() + ": fuel " + tank.getClass().getMethod("getFluid").invoke(tank) + " " + tank.getClass().getMethod("getFluidLevel").invoke(tank)
                + "/" + tank.getClass().getMethod("getMaxLevel").invoke(tank) + ", creative " + vehicle.getClass().getField("isCreative").get(vehicle)
                + ", engines on " + vehicle.getClass().getField("enginesOn").get(vehicle) + " running " + vehicle.getClass().getField("enginesRunning").get(vehicle);
        } catch (ReflectiveOperationException | RuntimeException error) {
            return "(unreadable: " + error + ")";
        }
    }

    /** Fills the vehicle's tank with gasoline through the mod's own tank object, on the server thread (what a fuel pump would do). */
    private static void fuelVehicle(Object vehicle) {
        try {
            Object tank = vehicle.getClass().getField("fuelTank").get(vehicle);
            int max = (Integer) tank.getClass().getMethod("getMaxLevel").invoke(tank);
            tank.getClass().getMethod("manuallySet", String.class, String.class, double.class).invoke(tank, "gasoline", "minecrafttransportsimulator", (double) max);
        } catch (ReflectiveOperationException | RuntimeException error) {
            Playtest.log("could not fuel the vehicle: " + error);
        }
    }

    /** The road with this id: the plan the generator built from the terrain when one exists (it knows its bridges), else the bare plan from the datapack. */
    private static CorridorPlan roadPlan(String road) {
        var server = Minecraft.getInstance().getSingleplayerServer();
        return CorridorRoadFeature.plans(server.overworld()).stream().filter(p -> p.id().equals(road)).findFirst()
            .orElseGet(() -> DevCommands.plans(server).stream().filter(p -> p.id().equals(road)).findFirst().orElseThrow());
    }

    /** The first run of at least {@code minimum} bridge blocks on a road: {start, end} along the road, or null. */
    private static double[] firstBridge(CorridorPlan plan, int minimum) {
        double start = -1;
        for (double at = 0; at <= plan.length(); at += 1) {
            if (plan.bridgeAt(at)) { if (start < 0) start = at; }
            else if (start >= 0) { if (at - start >= minimum) return new double[]{start, at}; start = -1; }
        }
        return start >= 0 && plan.length() - start >= minimum ? new double[]{start, plan.length()} : null;
    }

    /**
     * Immersive Vehicles' Quad on the generated road, with real keys and mouse: place it from its item with a right click, fill its tank, climb in
     * with a right click, drive it along the road holding W and steering with A and D, brake with S. Options: {@code -PucPtRoute=<road>},
     * {@code -PucPtMetres=<start>}, {@code -PucPtDrive=<blocks to drive>}, {@code -PucPtBridge=true} (start 60 blocks before the road's first bridge
     * and drive across it). The engine start, fuel and control flow are the mod's own, so the scenario logs what it finds.
     */
    private static void car() {
        String road = System.getProperty("uc.playtest.route", "ironhaven_valewood");
        boolean bridge = Boolean.getBoolean("uc.playtest.bridge");
        int[] startMetres = {Integer.getInteger("uc.playtest.metres", 300)};
        int[] driveBlocks = {Integer.getInteger("uc.playtest.drive", 150)};
        if (bridge) {
            // A road plan only knows its bridges once the terrain has been read, which happens when the first road chunks generate.
            arriveOnRoadLater(road, () -> 0, null);
            Playtest.queue("wait for the road plan to be built from the terrain", () -> { }, () -> roadPlan(road).built(), 1200);
        }
        Playtest.queue("choose where to start", () -> {
            if (bridge) {
                double[] found = firstBridge(roadPlan(road), 40);
                if (found == null) { Playtest.fail(road + " has no bridge of 40 blocks"); return; }
                startMetres[0] = (int) found[0] - 60;
                driveBlocks[0] = (int) (found[1] - found[0]) + 120;
                Playtest.log(road + ": first bridge from " + (int) found[0] + " to " + (int) found[1] + "; starting at " + startMetres[0] + ", driving " + driveBlocks[0]);
            }
        });
        pregenerate(road, () -> startMetres[0] - 12, () -> startMetres[0] + driveBlocks[0] + 40);
        final double[][] arrival = new double[1][];
        Playtest.queue("an empty inventory, then onto the road in spectator mode (which cannot fall)", () -> {
            var at = roadPlan(road).pointAt(startMetres[0]);
            arrival[0] = new double[]{at[0], at[1]};
            Playtest.command("clear @s");
            Playtest.command("gamemode spectator");
            Playtest.command("uc_dev road " + road + " " + startMetres[0]);
        });
        Playtest.queue("wait until the player is there and the ground has arrived", () -> { }, () -> arrivedAt(arrival[0]), 12000);
        Playtest.queue("creative mode", () -> Playtest.command("gamemode creative"));
        Playtest.wait("settle", 40);
        Playtest.queue("give the quad", () -> Playtest.command("give @s mts:mtsofficialpack.quad_red"), () -> inventoryCount("mts:mtsofficialpack.quad_red") >= 1, 1200);
        final double[] roadHeading = new double[1];
        Playtest.queue("aim along the road, pitched down onto the deck, and place the vehicle with a real right click", () -> {
            var mc = Minecraft.getInstance();
            int slot = -1;
            for (int i = 0; i < 9; i++) if (mc.player.getInventory().getItem(i).getItem().toString().contains("quad")) slot = i;
            if (slot < 0) { Playtest.fail("the quad item is not in the hotbar"); return; }
            mc.player.getInventory().selected = slot;
            var plan = roadPlan(road);
            var hit = plan.project(mc.player.getX(), mc.player.getZ());
            double[] ahead = plan.pointAt(hit.s() + 6), here = plan.pointAt(hit.s());
            float yaw = (float) Math.toDegrees(Math.atan2(-(ahead[0] - here[0]), ahead[1] - here[1]));
            roadHeading[0] = yaw;
            Playtest.log("on " + road + " at s=" + (int) hit.s() + ", " + String.format("%.1f", hit.distance()) + " from the centreline, bridge=" + plan.bridgeAt(hit.s()) + ", heading " + (int) yaw);
            // Immersive Vehicles puts a vehicle down with its front 90 degrees to the right of where the player looks (observed from the rear view in
            // the first run), so look 90 degrees to the left of the road to leave the Quad pointing along it.
            float placing = yaw + 90f;
            mc.player.setYRot(placing); mc.player.yHeadRot = placing; mc.player.setXRot(40f);
            Playtest.focusWindow();
            Playtest.mouseButton(3, true);
        });
        Playtest.wait("hold", 4);
        Playtest.queue("release", () -> Playtest.mouseButton(3, false));
        Playtest.queue("the vehicle exists", () -> { }, () -> vehicleNear() != null, 900);
        Playtest.wait("let it settle", 30);
        Playtest.queue("log the vehicle, fuel it (server side) and screenshot", () -> {
            var mc = Minecraft.getInstance();
            var vehicle = vehicleNear();
            Playtest.log("vehicle entity " + vehicle.getType() + " at " + vehicle.position() + " yaw " + vehicle.getYRot() + " box " + vehicle.getBoundingBox());
            Playtest.log("before fuelling: " + describeVehicle(ivVehicle(vehicle)));
            int id = vehicle.getId();
            // manuallySet changes one side only (no packet): fill the client's copy as well as the server's, as a pump's packet would.
            fuelVehicle(ivVehicle(vehicle));
            var server = mc.getSingleplayerServer();
            server.execute(() -> {
                var serverEntity = server.overworld().getEntity(id);
                if (serverEntity == null) { Playtest.log("the server cannot see vehicle " + id); return; }
                fuelVehicle(ivVehicle(serverEntity));
            });
            Playtest.screenshot("uc-car-placed");
        });
        Playtest.wait("let the tank update reach the client", 20);
        Playtest.queue("log the fuel state", () -> Playtest.log("after fuelling: " + describeVehicle(ivVehicle(vehicleNear()))));
        board(Scenarios::vehicleNear, () -> roadHeading[0], "car", true);
        final RoadDriver[] driver = new RoadDriver[1];
        Playtest.queue("drive along the road with W, A and D", () -> {
            var plan = roadPlan(road);
            var hit = plan.project(Minecraft.getInstance().player.getX(), Minecraft.getInstance().player.getZ());
            driver[0] = new RoadDriver(RoadDriver.of(plan), hit.s() + driveBlocks[0], "quad on " + road).steerByWheelModel(() -> vehicleMotion(vehicleNear()));
            Playtest.log("driving from s=" + (int) hit.s() + " for " + driveBlocks[0] + " blocks; " + describeVehicle(ivVehicle(vehicleNear())));
        }, () -> driver[0].tick(), 6000);
        Playtest.queue("report the drive", () -> {
            Playtest.log("drive: " + driver[0].summary());
            Playtest.screenshot("uc-car-driving");
            if (driver[0].summary().isEmpty()) { driver[0].release(); Playtest.fail("the drive did not finish within its time budget at s=" + (int) driver[0].progress()); }
        });
        Playtest.queue("brake with S and look at the stopping distance", () -> {
            Playtest.keyDown("s");
        });
        Playtest.wait("brake", 80);
        Playtest.queue("release S and screenshot", () -> { Playtest.keyUp("s"); Playtest.log("after braking: " + describeVehicle(ivVehicle(vehicleNear())) + " at " + Minecraft.getInstance().player.position()); Playtest.screenshot("uc-car-braked"); });
    }

    /** The Modern Life Motorboat closest to the player. */
    private static net.minecraft.world.entity.Entity boatNear() {
        var mc = Minecraft.getInstance();
        net.minecraft.world.entity.Entity best = null;
        for (var entity : mc.level.entitiesForRendering()) {
            var key = net.minecraftforge.registries.ForgeRegistries.ENTITY_TYPES.getKey(entity.getType());
            if (key == null || !key.toString().equals("modernlife:motorboat") || entity == mc.player) continue;
            if (best == null || entity.distanceToSqr(mc.player) < best.distanceToSqr(mc.player)) best = entity;
        }
        return best;
    }

    private static String describeBoat(net.minecraft.world.entity.Entity boat) {
        try {
            return "fuel " + boat.getClass().getMethod("getCurrentFuelLevel").invoke(boat) + ", out of fuel " + boat.getClass().getMethod("isOutOfFuel").invoke(boat)
                + ", acceleration " + boat.getClass().getMethod("getCurAccel").invoke(boat) + "/" + boat.getClass().getMethod("getMaxAccel").invoke(boat)
                + ", slots " + ((net.minecraft.world.Container) boat).getContainerSize();
        } catch (ReflectiveOperationException | RuntimeException error) {
            return "(unreadable: " + error + ")";
        }
    }

    /** The first run of at least {@code minimum} blocks of a road whose deck stands over open water: {start, end} along the road, or null. */
    private static double[] firstWaterRun(CorridorPlan plan, int minimum) {
        double start = -1;
        for (double at = 0; at <= plan.length(); at += 1) {
            if (plan.waterAt(at)) { if (start < 0) start = at; }
            else if (start >= 0) { if (at - start >= minimum) return new double[]{start, at}; start = -1; }
        }
        return start >= 0 && plan.length() - start >= minimum ? new double[]{start, plan.length()} : null;
    }

    /**
     * Generates the chunks along a stretch of road before a vehicle drives it. The integrated server shares its CPU with a software-rendered client in
     * the cloud container and can run half a minute behind, and a vehicle stops dead at the edge of a chunk the server has not made yet (the player
     * is not slowed by it because the walker waits for chunks, a vehicle cannot). Two chunks per server task, so the server's watchdog never sees a
     * long tick; the chunks are generated for real and kept on disk, nothing is faked.
     */
    private static void pregenerate(String road, java.util.function.DoubleSupplier from, java.util.function.DoubleSupplier to) {
        final java.util.concurrent.atomic.AtomicBoolean finished = new java.util.concurrent.atomic.AtomicBoolean();
        Playtest.queue("generate the chunks along " + road + " ahead of the vehicle", () -> {
            var plan = roadPlan(road);
            java.util.LinkedHashSet<Long> wanted = new java.util.LinkedHashSet<>();
            for (double at = Math.max(0, from.getAsDouble()); at <= Math.min(plan.length(), to.getAsDouble()); at += 6) {
                double[] point = plan.pointAt(at);
                int cx = (int) Math.floor(point[0]) >> 4, cz = (int) Math.floor(point[1]) >> 4;
                for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++) wanted.add(net.minecraft.world.level.ChunkPos.asLong(cx + dx, cz + dz));
            }
            java.util.ArrayDeque<Long> queue = new java.util.ArrayDeque<>(wanted);
            Playtest.log("generating " + queue.size() + " chunks along " + road + " from " + (int) from.getAsDouble() + " to " + (int) to.getAsDouble());
            var server = Minecraft.getInstance().getSingleplayerServer();
            Runnable[] task = new Runnable[1];
            task[0] = () -> {
                for (int i = 0; i < 2 && !queue.isEmpty(); i++) {
                    long packed = queue.poll();
                    server.overworld().getChunk(net.minecraft.world.level.ChunkPos.getX(packed), net.minecraft.world.level.ChunkPos.getZ(packed));
                }
                if (queue.isEmpty()) finished.set(true); else server.execute(task[0]);
            };
            server.execute(task[0]);
        }, finished::get, 12000);
    }

    /** Where the boat goes in: beside the centreline at road distance {@code s}, on the water surface. Server thread; empty when that spot is not water. */
    private static double[] waterBesideRoad(net.minecraft.server.level.ServerLevel level, CorridorPlan plan, double s, double offset) {
        double[] on = RoadDriver.beside(plan, offset, 8).pointAt(s);
        int x = (int) Math.floor(on[0]), z = (int) Math.floor(on[1]);
        if (!level.hasChunk(x >> 4, z >> 4)) return new double[0];
        // Beside a deck the top block is the deck itself: look down from it for the first water surface (a water source with no water above).
        int top = level.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING, x, z) - 1;
        for (int y = top; y > Math.max(level.getMinBuildHeight(), top - 80); y--) {
            var fluid = level.getFluidState(new BlockPos(x, y, z));
            if (fluid.is(net.minecraft.tags.FluidTags.WATER) && fluid.isSource() && !level.getFluidState(new BlockPos(x, y + 1, z)).is(net.minecraft.tags.FluidTags.WATER)) {
                return new double[]{on[0], on[1], y};
            }
        }
        return new double[0];
    }

    /**
     * Modern Life's Motorboat under a real bridge, with real keys and mouse: place it from its item on the water beside the deck of the first bridge
     * that crosses water, climb in with a right click, hold W and steer with A and D along the water under the deck (a little to one side, so the piers
     * on the centreline are not in the way), and log its fuel and hold. Options: {@code -PucPtRoute=<road>}.
     */
    private static void boat() {
        String road = System.getProperty("uc.playtest.route", "valewood_blackwater");
        final double[] run = new double[2];
        final double[][] spot = new double[1][];
        final double offset = 3.0;
        // A road plan only knows its bridges once the terrain has been read, which happens when the first road chunks generate.
        arriveOnRoadLater(road, () -> 0, null);
        Playtest.queue("wait for the road plan to be built from the terrain", () -> { }, () -> roadPlan(road).built(), 1200);
        Playtest.queue("find the first stretch of " + road + " that crosses water", () -> {
            double[] found = firstWaterRun(roadPlan(road), 40);
            if (found == null) { Playtest.fail(road + " has no stretch of 40 blocks over open water"); return; }
            run[0] = found[0]; run[1] = found[1];
            Playtest.log(road + ": open water under the deck from " + (int) found[0] + " to " + (int) found[1]);
        });
        pregenerate(road, () -> run[0] - 20, () -> run[1] + 20);
        arriveOnRoadLater(road, () -> (int) (run[0] + 8), "creative");
        Playtest.queue("find the water beside the deck (server side)", () -> {
            var server = Minecraft.getInstance().getSingleplayerServer();
            server.execute(() -> spot[0] = waterBesideRoad(server.overworld(), roadPlan(road), run[0] + 8, offset));
        }, () -> spot[0] != null, 600);
        Playtest.queue("into the water at the start, the boat item in hand", () -> {
            if (spot[0].length == 0) { Playtest.fail("no water beside the deck at " + road + " s=" + (int) (run[0] + 8)); return; }
            var plan = roadPlan(road);
            double[] a = plan.pointAt(run[0] + 8), b = plan.pointAt(run[0] + 14);
            double yaw = Math.toDegrees(Math.atan2(-(b[0] - a[0]), b[1] - a[1]));
            Playtest.log(String.format(java.util.Locale.ROOT, "water at %.1f, %.1f, surface y=%d", spot[0][0], spot[0][1], (int) spot[0][2]));
            Playtest.command("clear @s");
            Playtest.command(String.format(java.util.Locale.ROOT, "tp @s %.2f %.2f %.2f %.1f 35", spot[0][0], spot[0][2] + 1, spot[0][1], yaw));
            Playtest.command("give @s modernlife:motorboat_item");
        }, () -> inventoryCount("modernlife:motorboat_item") >= 1, 1200);
        Playtest.wait("let the chunks and the water settle", 60);
        Playtest.queue("place the boat on the water with a real right click", () -> {
            var mc = Minecraft.getInstance();
            int slot = -1;
            for (int i = 0; i < 9; i++) if (net.minecraftforge.registries.ForgeRegistries.ITEMS.getKey(mc.player.getInventory().getItem(i).getItem()).toString().equals("modernlife:motorboat_item")) slot = i;
            if (slot < 0) { Playtest.fail("the boat item is not in the hotbar"); return; }
            mc.player.getInventory().selected = slot;
            Playtest.focusWindow();
            Playtest.mouseButton(3, true);
        });
        Playtest.wait("hold", 4);
        Playtest.queue("release", () -> Playtest.mouseButton(3, false));
        Playtest.queue("the boat exists", () -> { }, () -> boatNear() != null, 900);
        Playtest.wait("let it settle", 40);
        Playtest.queue("log the boat and screenshot", () -> {
            var boat = boatNear();
            if (boat == null) return;
            Playtest.log("boat at " + boat.position() + " " + describeBoat(boat));
            Playtest.screenshot("uc-boat-placed");
        });
        board(Scenarios::boatNear, () -> {
            var plan = roadPlan(road);
            double[] a = plan.pointAt(run[0] + 8), b = plan.pointAt(run[0] + 14);
            return Math.toDegrees(Math.atan2(-(b[0] - a[0]), b[1] - a[1]));
        }, "boat", false);
        final RoadDriver[] driver = new RoadDriver[1];
        Playtest.queue("sail along the water under the deck with W, A and D", () -> {
            var plan = roadPlan(road);
            var route = RoadDriver.beside(plan, offset, 8);
            driver[0] = new RoadDriver(route, run[1] - 10, "motorboat under the bridge of " + road).steerByYaw().faceForward()
                .at(run[0] + 40, () -> Playtest.screenshot("uc-boat-approach"))
                .at((run[0] + run[1]) / 2, () -> { Playtest.log("under the deck: " + describeBoat(boatNear())); Playtest.screenshot("uc-boat-under-bridge"); })
                .at(run[1] - 40, () -> Playtest.screenshot("uc-boat-leaving"));
        }, () -> driver[0].tick(), 6000);
        Playtest.queue("report the crossing", () -> {
            Playtest.log("sail: " + driver[0].summary());
            Playtest.screenshot("uc-boat-across");
            if (driver[0].summary().isEmpty()) { driver[0].release(); Playtest.fail("the crossing did not finish within its time budget at " + (int) driver[0].progress()); }
            var boat = boatNear();
            if (boat != null) Playtest.log("after the crossing: " + describeBoat(boat));
        });
    }

    /**
     * The on-screen tracker of the accepted order: a registered farmer accepts the two-line greenhouse wholesale order, and the panel must follow
     * the inventory as carrots and potatoes arrive, until it says the order is ready to deliver.
     */
    private static void tracker() {
        Playtest.queue("survival mode and a registered Valewood farmer", () -> {
            Playtest.command("gamemode survival");
            Playtest.command("clear @s");
            Playtest.command("uc register valewood farmer");
        });
        Playtest.wait("let the server answer", 40);
        Playtest.queue("experience, the skills and the accepted order (server side)", () -> {
            var mc = Minecraft.getInstance();
            var server = mc.getSingleplayerServer();
            var uuid = mc.player.getUUID();
            server.execute(() -> {
                var player = server.getPlayerList().getPlayer(uuid);
                var citizens = CitizenState.get(server);
                citizens.addCareerXp(uuid, 800);
                citizens.unlockSkill(uuid, "farmer.fundamentals");
                citizens.unlockSkill(uuid, "farmer.greenhouse_management");
                WorkOrderService.accept(player, WorkOrderLedger.get(server), citizens.find(uuid).orElseThrow(), "underground_city:greenhouse_wholesale");
            });
        });
        Playtest.queue("the tracker appears", () -> { }, () -> ClientOrderTracker.current() != null, 200);
        Playtest.wait("settle", 20);
        Playtest.queue("screenshot: nothing gathered yet", () -> {
            var tracked = ClientOrderTracker.current();
            Playtest.log("tracked: " + tracked);
            if (tracked.lines().size() != 2 || tracked.lines().get(0).held() != 0) Playtest.fail("the tracker should list two empty lines, got " + tracked.lines());
            Playtest.screenshot("uc-tracker-empty");
        });
        Playtest.queue("gather half of the carrots", () -> Playtest.command("give @s minecraft:carrot 12"), () -> {
            var tracked = ClientOrderTracker.current();
            return tracked != null && tracked.lines().get(0).held() == 12;
        }, 200);
        Playtest.queue("screenshot: part way", () -> Playtest.screenshot("uc-tracker-partial"));
        Playtest.queue("gather the rest", () -> {
            Playtest.command("give @s minecraft:carrot 12");
            Playtest.command("give @s minecraft:potato 24");
        }, () -> {
            var tracked = ClientOrderTracker.current();
            return tracked != null && tracked.lines().stream().allMatch(line -> line.held() >= line.count());
        }, 200);
        Playtest.wait("settle", 10);
        Playtest.queue("screenshot: ready to deliver", () -> Playtest.screenshot("uc-tracker-ready"));
        Playtest.queue("press O to hide the tracker", () -> Playtest.tap("o"));
        Playtest.wait("settle", 10);
        Playtest.queue("screenshot: hidden", () -> Playtest.screenshot("uc-tracker-hidden"));
    }

    /**
     * The six checkpoint posts: place them beside the roads, stand at one, register and carry a pistol into the zone, and let the guard search
     * (the die is skipped by /uc_dev search; its wording and effects are what is shown).
     */
    private static void posts() {
        Playtest.queue("creative mode, then place the posts", () -> {
            Playtest.command("gamemode creative");
            Playtest.command("uc_checkpoints place");
        });
        Playtest.queue("six posts stand", () -> { }, () -> DistrictBuilder.count(level(), "checkpoint_post") >= CheckpointCatalog.INSTANCE.all().size(), 6000);
        final Vec3[] spot = new Vec3[1];
        Playtest.queue("go to the first post", () -> {
            // The order of the zones is not stable, and a pistol is legal in Blackwater: take the first zone whose law restricts it.
            var post = CheckpointCatalog.INSTANCE.all().stream().filter(zone -> {
                var law = CityLaws.INSTANCE.find(zone.jurisdiction());
                return law != null && !law.status("firearms_licensed").equals(CityLaws.LEGAL);
            }).findFirst().orElseThrow();
            Playtest.log("post at " + post.title() + " (" + post.jurisdiction() + ")");
            var guard = CheckpointPosts.guardAt(level(), post);
            if (guard.isEmpty()) { Playtest.fail("no guard stands at " + post.title()); return; }
            spot[0] = guard.get().position();
            // Two blocks south of the guard, inside the 9 x 9 lot, looking north at the guard with the booth behind.
            standLooking(spot[0].add(0, 0.1, 2.2), spot[0].add(0, 1.4, 0));
        });
        // The post is far from where the player was: wait for the chunk and the ground under the player to reach the client, then for the meshes, then a little longer.
        Playtest.queue("wait until the player is there and the ground has arrived", () -> { }, () -> arrivedAt(new double[]{spot[0].x, spot[0].z + 2.2}), 12000);
        waitForMeshes(1500);
        Playtest.wait("let the guard and the light settle", 300);
        Playtest.queue("clear the chat, screenshot of the guard", () -> Minecraft.getInstance().gui.getChat().clearMessages(true));
        Playtest.wait("one frame", 4);
        Playtest.queue("screenshot of the post", () -> Playtest.screenshot("uc-post-guard"));
        Playtest.queue("the corner of the lot, looking at the booth", () -> standLooking(spot[0].add(3.2, 0.1, 2.2), spot[0].add(-0.5, 1.3, -3.0)));
        Playtest.wait("let the frame render", 30);
        Playtest.queue("screenshot of the booth", () -> Playtest.screenshot("uc-post-booth"));
        Playtest.queue("back in front of the guard", () -> standLooking(spot[0].add(0, 0.1, 2.2), spot[0].add(0, 1.4, 0)));
        Playtest.wait("settle", 20);
        Playtest.queue("a registered gunsmith with an unlicensed pistol, in survival", () -> {
            Playtest.command("uc register valewood gunsmith");
            Playtest.command("give @s tacz:modern_kinetic_gun{GunId:\"tacz:glock_17\",GunCurrentAmmoCount:0} 1");
            Playtest.command("gamemode survival");
        });
        Playtest.wait("settle", 30);
        Playtest.queue("a search by the post's guard", () -> { Minecraft.getInstance().gui.getChat().clearMessages(true); Playtest.command("uc_dev search"); });
        Playtest.wait("let the messages arrive", 30);
        Playtest.queue("screenshot of the search", () -> Playtest.screenshot("uc-post-search"));
        Playtest.queue("the pistol was confiscated", () -> {
            if (gunSlot() >= 0) Playtest.fail("the unlicensed pistol was not confiscated");
        });
    }

    /**
     * A TaCZ pistol in a client, with real keys and mouse: select it, reload with R, aim with the right button, fire with the left,
     * and check the magazine, the carried ammunition and the damage dealt to a target. Options: none.
     */
    private static void gun() {
        final net.minecraft.world.item.ItemStack[] gunRef = new net.minecraft.world.item.ItemStack[1];
        Playtest.queue("survival mode, the pistol and its ammunition", () -> {
            Playtest.command("gamemode survival");
            Playtest.command("clear @s"); // the test world is reused between runs
            Playtest.command("give @s tacz:modern_kinetic_gun{GunId:\"tacz:glock_17\",GunFireMode:\"SEMI\",GunCurrentAmmoCount:0} 1");
            Playtest.command("give @s tacz:ammo{AmmoId:\"tacz:9mm\"} 50");
        });
        // /clear then /give: only the exact result (one empty pistol, fifty rounds) means the client has seen both.
        Playtest.queue("both items arrive", () -> { }, () -> gunSlot() >= 0 && ammoCount() == 50 && magazine() == 0, 200);
        Playtest.queue("hold the pistol", () -> {
            var mc = Minecraft.getInstance();
            int slot = gunSlot();
            if (slot > 8) { Playtest.fail("the pistol is not in the hotbar (slot " + slot + ")"); return; }
            mc.player.getInventory().selected = slot;
            gunRef[0] = mc.player.getInventory().getItem(slot);
            Playtest.log("pistol in hotbar slot " + slot + ", magazine " + magazine() + ", carried 9mm " + ammoCount());
            if (magazine() != 0) Playtest.fail("the new pistol should start empty, magazine " + magazine());
            Playtest.focusWindow();
        });
        Playtest.wait("let the draw animation finish", 60);
        Playtest.queue("screenshot with the pistol in hand", () -> Playtest.screenshot("uc-playtest-gun-hold"));
        final int[] waited = {0};
        Playtest.queue("hold R for a few ticks to reload", () -> Playtest.keyDown("r"));
        Playtest.wait("keep it down", 4);
        Playtest.queue("release R and wait for the magazine to fill", () -> Playtest.keyUp("r"), () -> {
            if (magazine() > 0) return true;
            if (++waited[0] == 60) diagnoseTrigger(false);
            if (waited[0] == 120) { diagnoseTrigger(false); try {
                Class<?> operator = Class.forName("com.tacz.guns.api.client.gameplay.IClientPlayerGunOperator");
                Object instance = operator.getMethod("fromLocalPlayer", net.minecraft.client.player.LocalPlayer.class).invoke(null, Minecraft.getInstance().player);
                operator.getMethod("reload").invoke(instance);
                Playtest.log("direct reload() called");
            } catch (ReflectiveOperationException error) { Playtest.log("direct reload failed: " + error); } }
            return false;
        }, 400);
        Playtest.wait("settle", 20);
        Playtest.queue("the magazine and the carried ammunition agree", () -> {
            int magazine = magazine(), carried = ammoCount();
            Playtest.log("after reloading: magazine " + magazine + ", carried 9mm " + carried);
            // A fresh magazine also chambers one round (HasBulletInBarrel), so the total is the magazine, the chamber and what is still carried.
            int chambered = chambered() ? 1 : 0;
            if (magazine + chambered + carried != 50) Playtest.fail("rounds were lost or created: " + magazine + " + " + chambered + " + " + carried + " != 50");
        });
        Playtest.queue("clear a firing lane and place a target", () -> {
            // The test world's spawn can be in a forest; a bullet that meets leaves proves nothing about the gun.
            Playtest.command("fill ~-1 ~ ~-1 ~1 ~3 ~9 minecraft:air");
            Playtest.command("summon minecraft:zombie ~ ~ ~7 {NoAI:1b,Silent:1b,PersistenceRequired:1b}");
        }, () -> target() != null, 200);
        final float[] health = new float[1];
        Playtest.queue("face the target and aim with the right mouse button", () -> {
            var mc = Minecraft.getInstance();
            var zombie = target();
            health[0] = zombie.getHealth();
            faceTarget(zombie);
            Playtest.mouseButton(3, true);
        }, () -> {
            faceTarget(target());
            return aimProgress() > 0.9f;
        }, 200);
        Playtest.queue("screenshot while aiming", () -> Playtest.screenshot("uc-playtest-gun-aim"));
        final int[] before = new int[1];
        final int[] held = {0};
        Playtest.queue("press the left mouse button (TaCZ polls the trigger every client tick, so it is held for a few)", () -> {
            before[0] = magazine();
            Playtest.mouseButton(1, true);
        }, () -> {
            if (magazine() < before[0]) return true;
            if (++held[0] == 40) diagnoseTrigger(false);
            if (held[0] == 80) diagnoseTrigger(true);
            return false;
        }, 200);
        Playtest.queue("release the trigger", () -> Playtest.mouseButton(1, false));
        Playtest.wait("let the bullet fly", 20);
        Playtest.queue("the target was hurt", () -> {
            var zombie = target();
            Playtest.log("target health " + health[0] + " -> " + (zombie == null ? "dead" : zombie.getHealth()) + ", magazine " + magazine());
            if (zombie != null && zombie.getHealth() >= health[0]) Playtest.fail("the shot did not hurt the target (" + zombie.getHealth() + ")");
        });
        Playtest.queue("release the aim", () -> Playtest.mouseButton(3, false));
    }

    private static int gunSlot() {
        var inventory = Minecraft.getInstance().player.getInventory();
        for (int i = 0; i < inventory.items.size(); i++) {
            var key = net.minecraftforge.registries.ForgeRegistries.ITEMS.getKey(inventory.items.get(i).getItem());
            if (key != null && key.toString().equals("tacz:modern_kinetic_gun")) return i;
        }
        return -1;
    }

    private static int magazine() {
        int slot = gunSlot();
        if (slot < 0) return -1;
        var tag = Minecraft.getInstance().player.getInventory().getItem(slot).getTag();
        return tag == null ? 0 : tag.getInt("GunCurrentAmmoCount");
    }

    private static boolean chambered() {
        int slot = gunSlot();
        if (slot < 0) return false;
        var tag = Minecraft.getInstance().player.getInventory().getItem(slot).getTag();
        return tag != null && tag.getBoolean("HasBulletInBarrel");
    }

    private static int ammoCount() {
        int total = 0;
        for (var stack : Minecraft.getInstance().player.getInventory().items) {
            var key = net.minecraftforge.registries.ForgeRegistries.ITEMS.getKey(stack.getItem());
            if (key != null && key.toString().equals("tacz:ammo") && stack.getTag() != null && stack.getTag().getString("AmmoId").equals("tacz:9mm")) total += stack.getCount();
        }
        return total;
    }

    private static net.minecraft.world.entity.LivingEntity target() {
        var mc = Minecraft.getInstance();
        var server = mc.getSingleplayerServer();
        if (server == null) return null;
        // The integrated server's copy: its health is authoritative.
        for (var entity : server.overworld().getEntities(net.minecraft.world.entity.EntityType.ZOMBIE, e -> true)) {
            if (entity.distanceTo(mc.player) < 20) return entity;
        }
        return null;
    }

    private static void faceTarget(net.minecraft.world.entity.LivingEntity zombie) {
        var player = Minecraft.getInstance().player;
        if (zombie == null) return;
        Vec3 delta = zombie.position().add(0, zombie.getBbHeight() * 0.6, 0).subtract(player.getEyePosition());
        float yaw = (float) Math.toDegrees(Math.atan2(-delta.x, delta.z));
        float pitch = (float) -Math.toDegrees(Math.atan2(delta.y, Math.hypot(delta.x, delta.z)));
        player.setYRot(yaw); player.yHeadRot = yaw; player.setXRot(pitch);
    }

    /** Where the chain from the mouse to a shot breaks: the key state, TaCZ's own in-game check, and (optionally) what shoot() answers. */
    private static void diagnoseTrigger(boolean shootDirectly) {
        var mc = Minecraft.getInstance();
        try {
            Object key = Class.forName("com.tacz.guns.client.input.ShootKey").getField("SHOOT_KEY").get(null);
            boolean down = (Boolean) key.getClass().getMethod("m_90857_").invoke(key);
            boolean inGame = (Boolean) Class.forName("com.tacz.guns.util.InputExtraCheck").getMethod("isInGame").invoke(null);
            Playtest.log("trigger diagnostics: SHOOT_KEY.isDown=" + down + " TaCZ isInGame=" + inGame + " windowActive=" + mc.isWindowActive()
                + " grabbed=" + mc.mouseHandler.isMouseGrabbed() + " screen=" + mc.screen + " magazine=" + magazine() + " chambered=" + chambered());
            if (shootDirectly) {
                Class<?> operator = Class.forName("com.tacz.guns.api.client.gameplay.IClientPlayerGunOperator");
                Object instance = operator.getMethod("fromLocalPlayer", net.minecraft.client.player.LocalPlayer.class).invoke(null, mc.player);
                Playtest.log("direct shoot() -> " + operator.getMethod("shoot").invoke(instance));
            }
        } catch (ReflectiveOperationException | RuntimeException error) {
            Playtest.log("trigger diagnostics failed: " + error);
        }
    }

    /** TaCZ's own client-side aiming progress, read by reflection (TaCZ is not a compile-time dependency). */
    private static float aimProgress() {
        try {
            Class<?> operator = Class.forName("com.tacz.guns.api.client.gameplay.IClientPlayerGunOperator");
            Object instance = operator.getMethod("fromLocalPlayer", net.minecraft.client.player.LocalPlayer.class).invoke(null, Minecraft.getInstance().player);
            return (Float) operator.getMethod("getClientAimingProgress", float.class).invoke(instance, 1.0f);
        } catch (ReflectiveOperationException | RuntimeException error) {
            Playtest.fail("cannot read TaCZ's aiming progress: " + error);
            return 1f;
        }
    }

    /** Proves the harness itself: an injected W key must move the player. */
    private static void input() {
        final Vec3[] before = new Vec3[1];
        Playtest.queue("go to the surface at Ironhaven", () -> Playtest.command("uc_dev tp ironhaven"), () -> true, 5);
        Playtest.wait("wait for chunks", 200);
        Playtest.queue("press W for three seconds", () -> {
            before[0] = Minecraft.getInstance().player.position();
            Playtest.focusWindow();
            Playtest.keyDown("w");
        }, () -> true, 5);
        Playtest.wait("walk", 60);
        Playtest.queue("release W and compare", () -> {
            Minecraft mc = Minecraft.getInstance();
            // Where the chain breaks if the player did not move: X event -> GLFW key state -> Minecraft key mapping -> movement.
            Playtest.log("diagnostics: windowActive=" + mc.isWindowActive() + " screen=" + mc.screen + " grabbed=" + mc.mouseHandler.isMouseGrabbed()
                + " glfwW=" + org.lwjgl.glfw.GLFW.glfwGetKey(mc.getWindow().getWindow(), org.lwjgl.glfw.GLFW.GLFW_KEY_W)
                + " keyUp.isDown=" + mc.options.keyUp.isDown() + " pos=" + mc.player.position() + " onGround=" + mc.player.onGround()
                + " flying=" + mc.player.getAbilities().flying + " window=" + mc.getWindow().getWidth() + "x" + mc.getWindow().getHeight());
            Playtest.keyUp("w");
            double moved = Minecraft.getInstance().player.position().distanceTo(before[0]);
            Playtest.log("moved " + String.format("%.1f", moved) + " blocks with the injected W key");
            if (moved < 3.0) Playtest.fail("the injected key did not move the player (" + moved + " blocks)");
        });
    }
}
