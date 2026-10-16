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
            Playtest.log("road " + plan.id() + " is " + (int) plan.length() + " blocks long");
            Playtest.queue("teleport to the start of " + plan.id(), () -> Playtest.command("uc_dev road " + plan.id() + " 0"));
            Playtest.wait("let the ground load", 120);
            RoadWalker walker = new RoadWalker(plan, sprint);
            Playtest.queue("walk " + plan.id() + (sprint ? " (sprinting)" : ""), () -> { }, () -> { walker.tick(); return walker.done(); }, 120_000);
        }
    }

    // ------------------------------------------------------------------------------------------------ Founders Row

    private static net.minecraft.server.level.ServerLevel level() { return Minecraft.getInstance().getSingleplayerServer().overworld(); }

    /** Moves the player (through the integrated server) to {@code at}, looking at {@code target}. */
    private static void standLooking(Vec3 at, Vec3 target) {
        Vec3 delta = target.subtract(at);
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
            // Two blocks to their east, at eye height of a standing player, looking at their head.
            Vec3 where = villager.position().add(2.0, 0, 0);
            Vec3 head = villager.position().add(0, villager.getBbHeight() * 0.85, 0);
            stand[0] = where;
            standLooking(where, head);
        });
        Playtest.wait("settle", 10);
        Playtest.queue("right-click " + label, () -> {
            var villager = citizen(null, index);
            if (villager != null) standLooking(stand[0], villager.position().add(0, villager.getBbHeight() * 0.85, 0));
            Playtest.focusWindow();
            Playtest.mouseButton(3, true);
        });
        Playtest.wait("hold", 4);
        Playtest.queue("release", () -> Playtest.mouseButton(3, false));
        Playtest.wait("let the answer arrive", 24);
        if (shot != null) Playtest.queue("screenshot " + shot, () -> Playtest.screenshot(shot));
    }

    /**
     * Founders Row with its people, on a clear platform in the sky of the test world (so nothing real is overwritten): build it, look at it
     * from four places, then speak to the registrar (the first-login screen opens for a newcomer), register, speak again (a next step), and
     * speak to the Gunsmith's employer (their terminal opens) and a resident.
     */
    private static void row() {
        final BlockPos[] origin = new BlockPos[1];
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
        Playtest.queue("overview from the north-west", () -> {
            Vec3 o = Vec3.atLowerCornerOf(origin[0]);
            standLooking(o.add(-6, 16, -10), o.add(14, 0, 18));
        });
        Playtest.wait("settle", 40);
        Playtest.queue("screenshot", () -> Playtest.screenshot("uc-row-overview-nw"));
        Playtest.queue("overview from the south gate", () -> {
            Vec3 o = Vec3.atLowerCornerOf(origin[0]);
            standLooking(o.add(12.5, 6, 52), o.add(12.5, 1, 20));
        });
        Playtest.wait("settle", 40);
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
            var post = CheckpointCatalog.INSTANCE.all().get(0);
            var guard = CheckpointPosts.guardAt(level(), post);
            if (guard.isEmpty()) { Playtest.fail("no guard stands at " + post.title()); return; }
            spot[0] = guard.get().position();
            standLooking(spot[0].add(3, 0.2, 5), spot[0].add(0, 1.2, 0));
        });
        Playtest.wait("let the chunks and the guard settle", 100);
        Playtest.queue("screenshot of the post", () -> Playtest.screenshot("uc-post-guard"));
        Playtest.queue("a registered gunsmith with an unlicensed pistol, in survival", () -> {
            Playtest.command("uc register valewood gunsmith");
            Playtest.command("give @s tacz:modern_kinetic_gun{GunId:\"tacz:glock_17\",GunCurrentAmmoCount:0} 1");
            Playtest.command("gamemode survival");
        });
        Playtest.wait("settle", 30);
        Playtest.queue("a search by the post's guard", () -> Playtest.command("uc_dev search"));
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
        Playtest.queue("press R to reload", () -> Playtest.tap("r"), () -> magazine() > 0, 400);
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
