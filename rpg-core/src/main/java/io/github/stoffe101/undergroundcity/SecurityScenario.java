package io.github.stoffe101.undergroundcity;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractButton;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.phys.Vec3;

/**
 * The SecurityCraft trial worked through a real client (D-140): {@code -PucPlaytest=security} with the {@code securitycraft} set. In a new furnished archipelago world Core fits
 * SecurityCraft's devices to the street door of a real generated Ironhaven tenement (the install is a command, as an operator would run it). Then, with real key presses and
 * clicks: a stranger right-clicks the keypad door, SecurityCraft's own code screen opens, the code is typed, the door opens and Core counts it as a break-in by code; walking
 * on across the hall breaks SecurityCraft's laser, and Core rings the building's alarm (its police dispatch) and SecurityCraft's siren. The door opens from the inside without a
 * code. Made a keyholder, the same person comes in by the code and crosses the laser with nothing raised. A sentry and a keypad door from a creative give cannot be used or
 * placed, and no SecurityCraft recipe reaches the client.
 *
 * <p>What is scripted rather than played: the registration (a command), the install and the keyholder (operator commands), the choice of building, the player's place in front
 * of the door (a teleport), the step into the doorway and the gives. The code screen, the typing, the door, the laser, the alarm and the refusals are the real game.
 */
final class SecurityScenario {
    private SecurityScenario() {}

    private static final class Facts {
        volatile DoorWalk.Spot spot;
        volatile SecurityCraftTrial.Install install;
        volatile String installError;
        volatile boolean searched;
        volatile int dispatchesBefore, tripsBefore, heatBefore, entriesBefore;
        volatile String screen = "";
    }

    static void build() {
        Playtest.keepScreens = true;
        Facts f = new Facts();
        Playtest.queue("the client has the ground under the player", () -> { }, Scenarios::groundBelowPlayer, 1200);
        Playtest.queue("the first-login registration screen opens by itself", () -> { }, () -> Minecraft.getInstance().screen instanceof RegistrationScreen, 900);
        Playtest.queue("a registered Ironhaven farmer (by command)", () -> {
            Minecraft.getInstance().setScreen(null);
            Playtest.command("gamemode survival");
            Playtest.command("uc register ironhaven farmer");
        });
        Playtest.wait("let the server answer", 40);
        Playtest.queue("SecurityCraft is loaded and its recipes did not reach the client", () -> {
            var mc = Minecraft.getInstance();
            if (!SecurityCraftTrial.loaded()) { Playtest.fail("SecurityCraft is not loaded: run with the securitycraft set"); return; }
            long recipes = mc.level.getRecipeManager().getRecipes().stream().filter(r -> r.getId().getNamespace().equals(SecurityCraftTrial.MOD)).count();
            Playtest.log("SECURITY client recipes from SecurityCraft: " + recipes);
            if (recipes != 0) Playtest.fail(recipes + " SecurityCraft recipes reached the client");
        });

        Playtest.queue("find a tenement in Ironhaven and fit the devices to its street door (server thread)", () -> {
            var server = Minecraft.getInstance().getSingleplayerServer();
            server.execute(() -> {
                try {
                    var level = server.overworld();
                    f.spot = DoorWalk.find(level, true, "flat_block", b -> b.security() != null && b.security().response() != null).orElse(null);
                    if (f.spot == null) return;
                    try { f.install = SecurityCraftTrial.install(level, f.spot.door()); }
                    catch (IllegalStateException refused) { f.installError = refused.getMessage(); }
                } finally { f.searched = true; }
            });
        }, () -> f.searched, 24000);
        Playtest.queue("what was fitted", () -> {
            if (f.spot == null) { Playtest.fail("no tenement with a street door within sixteen chunks of Ironhaven's centre"); return; }
            if (f.install == null) { Playtest.fail("the install was refused: " + f.installError); return; }
            Playtest.log("SECURITY fitted at " + f.install.door.toShortString() + " (" + f.spot.type() + ", class " + f.spot.securityClass() + "): code " + f.install.code + ", lasers " + f.install.lasers
                + ", sirens " + f.install.alarms + ", cameras " + f.install.cameras + ", lights " + f.install.lights);
            if (f.install.lasers.size() != 2) Playtest.fail("no laser pair fitted across the hall (the hall was too narrow or blocked)");
        });

        enterByCode("stranger", f, false);
        Playtest.queue("stranger: the entry counted as a break-in by code", () -> {
            if (f.install == null) return;
            var server = Minecraft.getInstance().getSingleplayerServer();
            int heat = WorkOrderLedger.get(server).heat(Minecraft.getInstance().player.getUUID(), f.install.city);
            Playtest.log("SECURITY stranger code entries " + f.install.codeEntries + " (before " + f.entriesBefore + "), heat " + f.heatBefore + " -> " + heat + "; police " + PoliceResponse.status());
            if (f.install.codeEntries != f.entriesBefore + 1) Playtest.fail("the stranger's entry by code was not counted as a break-in");
            if (heat <= f.heatBefore) Playtest.fail("a break-in by code raised no heat");
        });
        crossLaser("stranger", f, true);
        leaveFromInside("stranger", f);

        Playtest.queue("the player becomes a keyholder (operator command)", () -> Playtest.command("uc_security keyholder " + Minecraft.getInstance().player.getGameProfile().getName()));
        Playtest.wait("the laser's cooldown and the siren run out", 20 * 32);
        enterByCode("keyholder", f, true);
        Playtest.queue("keyholder: an ordinary entry", () -> {
            if (f.install == null) return;
            Playtest.log("SECURITY keyholder entries " + f.install.keyholderEntries + ", code entries " + f.install.codeEntries);
            if (f.install.keyholderEntries != 1) Playtest.fail("the keyholder's entry was not counted as theirs");
            if (f.install.codeEntries != f.entriesBefore) Playtest.fail("the keyholder's entry was counted as a break-in");
            if (!f.install.cameras.isEmpty()) {
                var server = Minecraft.getInstance().getSingleplayerServer();
                server.execute(() -> {
                    try {
                        var entity = server.overworld().getBlockEntity(f.install.cameras.get(0));
                        Object allowed = entity.getClass().getMethod("isAllowed", String.class).invoke(entity, Minecraft.getInstance().player.getGameProfile().getName());
                        Playtest.log("SECURITY the camera's allowlist names the keyholder: " + allowed);
                        if (!Boolean.TRUE.equals(allowed)) Playtest.fail("the camera's allowlist does not name the keyholder");
                    } catch (ReflectiveOperationException error) { Playtest.fail("the camera's allowlist could not be read: " + error); }
                });
            }
        });
        crossLaser("keyholder", f, false);
        leaveFromInside("keyholder", f);

        Playtest.queue("a sentry and a keypad door in a survival player's hands", () -> {
            var mc = Minecraft.getInstance();
            var server = mc.getSingleplayerServer();
            server.execute(() -> {
                var player = server.getPlayerList().getPlayer(mc.player.getUUID());
                player.getInventory().clearContent();
                player.getInventory().setItem(0, new ItemStack(BuiltInRegistries.ITEM.get(new ResourceLocation(SecurityCraftTrial.MOD, "sentry"))));
                player.getInventory().setItem(1, new ItemStack(BuiltInRegistries.ITEM.get(new ResourceLocation(SecurityCraftTrial.MOD, "keypad_door_item"))));
                player.getInventory().selected = 0;
                player.teleportTo(f.spot.stand().x, f.spot.stand().y, f.spot.stand().z);
                player.setXRot(60);
            });
        });
        Playtest.wait("settle", 20);
        final long[] sentriesBefore = {0};
        Playtest.queue("right-click the ground with the sentry", () -> {
            sentriesBefore[0] = sentries();
            Playtest.focusWindow();
            Playtest.mouseButton(3, true);
        });
        Playtest.wait("hold", 6);
        Playtest.queue("release, then the keypad door", () -> { Playtest.mouseButton(3, false); Playtest.tap("2"); });
        Playtest.wait("settle", 6);
        Playtest.queue("right-click the ground with the keypad door", () -> Playtest.mouseButton(3, true));
        Playtest.wait("hold", 6);
        Playtest.queue("no sentry came into the world and no keypad door was placed", () -> {
            Playtest.mouseButton(3, false);
            var mc = Minecraft.getInstance();
            var server = mc.getSingleplayerServer();
            long now = sentries();
            boolean placed = false;
            BlockPos feet = mc.player.blockPosition();
            for (BlockPos at : BlockPos.betweenClosed(feet.offset(-4, -2, -4), feet.offset(4, 3, 4))) if (SecurityCraftTrial.isKeypadDoor(server.overworld().getBlockState(at)) && !at.equals(f.install.door) && !at.equals(f.install.door.above())) placed = true;
            Playtest.log("SECURITY sentries " + sentriesBefore[0] + " -> " + now + ", a keypad door placed by hand: " + placed);
            if (now > sentriesBefore[0]) Playtest.fail("a sentry entered the world");
            if (placed) Playtest.fail("a player placed a keypad door");
            if (!f.install.door.equals(BlockPos.ZERO)) Playtest.log("SECURITY " + SecurityCraftTrial.status(server));
        });
        Playtest.queue("SECURITY summary", () -> {
            if (Playtest.failures.length() == 0) Playtest.log("SECURITY PASS: Core fitted SecurityCraft's devices and judged every entry and crossing; the forbidden devices stayed out");
        });
    }

    private static long sentries() {
        var server = Minecraft.getInstance().getSingleplayerServer();
        long[] n = {0};
        for (var entity : server.overworld().getAllEntities()) if (BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()).getNamespace().equals(SecurityCraftTrial.MOD) && !BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()).getPath().contains("camera")) n[0]++;
        return n[0];
    }

    /** From the street: right-click the keypad door until SecurityCraft's code screen opens, type the code with real key presses, press Enter (or its button), and see the door open. */
    private static void enterByCode(String label, Facts f, boolean keyholder) {
        Playtest.queue(label + ": stand in front of the door", () -> {
            if (f.spot == null || f.install == null) return;
            var mc = Minecraft.getInstance();
            var server = mc.getSingleplayerServer();
            server.execute(() -> {
                var player = server.getPlayerList().getPlayer(mc.player.getUUID());
                DoorWalk.prepare(player, f.spot, false);
                f.heatBefore = WorkOrderLedger.get(server).heat(player.getUUID(), f.install.city);
                f.entriesBefore = f.install.codeEntries;
                f.dispatchesBefore = PoliceResponse.dispatches();
            });
        }, () -> f.spot == null || Minecraft.getInstance().player.position().distanceTo(f.spot.stand()) < 1.5, 600);
        Playtest.wait("settle", 25);
        final int[] ticks = {0};
        Playtest.queue(label + ": hold W and right-click the door until a screen opens", () -> { ticks[0] = 0; if (f.spot != null) { Playtest.focusWindow(); Playtest.keyDown("w"); } }, () -> {
            if (f.spot == null || f.install == null) return true;
            var mc = Minecraft.getInstance();
            ticks[0]++;
            mc.player.setYRot(f.spot.yaw());
            mc.player.setXRot(0);
            if (mc.screen != null) return true;
            if (f.spot.past(mc.player.position()) > -1.0) { if (ticks[0] % 4 == 1) Playtest.mouseButton(3, true); else if (ticks[0] % 4 == 2) Playtest.mouseButton(3, false); }
            return false;
        }, 400);
        Playtest.queue(label + ": let go", () -> {
            Playtest.keyUp("w");
            Playtest.mouseButton(3, false);
            var screen = Minecraft.getInstance().screen;
            f.screen = screen == null ? "none" : screen.getClass().getName();
            Playtest.log("SECURITY " + label + " screen: " + f.screen);
            if (screen == null || !f.screen.contains("securitycraft")) Playtest.fail(label + ": the door did not open SecurityCraft's code screen (" + f.screen + ")");
        });
        Playtest.wait("the screen draws", 10);
        Playtest.queue(label + ": screenshot of the code screen", () -> Playtest.screenshotNextFrame("uc-security-" + label + "-1-code"));
        Playtest.wait("frame", 8);
        Playtest.queue(label + ": type the code", () -> {
            if (f.install == null) return;
            var screen = Minecraft.getInstance().screen;
            if (screen != null) for (var child : screen.children()) if (child instanceof EditBox box) { screen.setFocused(box); box.setFocused(true); }
            Playtest.focusWindow();
            for (char digit : f.install.code.toCharArray()) Playtest.tap(String.valueOf(digit));
        });
        Playtest.wait("the keys arrive", 20);
        Playtest.queue(label + ": what the field holds", () -> {
            var screen = Minecraft.getInstance().screen;
            String typed = "";
            if (screen != null) for (var child : screen.children()) if (child instanceof EditBox box) typed = box.getValue();
            Playtest.log("SECURITY " + label + " typed " + typed.length() + " characters");
            if (f.install != null && typed.length() != f.install.code.length()) Playtest.fail(label + ": the code field holds " + typed.length() + " characters after typing " + f.install.code.length());
        });
        Playtest.queue(label + ": press Enter", () -> Playtest.tap("Return"));
        Playtest.wait("the server answers", 30);
        Playtest.queue(label + ": if the screen is still open, press its button", () -> {
            var screen = Minecraft.getInstance().screen;
            if (screen == null) return;
            for (var child : screen.children()) if (child instanceof AbstractButton button) {
                String text = button.getMessage().getString().toLowerCase(java.util.Locale.ROOT);
                Playtest.log("SECURITY " + label + " button: '" + text + "'");
            }
            for (var child : screen.children()) if (child instanceof AbstractButton button && button.isActive()) {
                String text = button.getMessage().getString().toLowerCase(java.util.Locale.ROOT);
                if (text.contains("enter") || text.contains("open") || text.contains("submit") || text.contains("ok") || text.contains("check") || text.contains("✔") || text.contains("√")) {
                    Playtest.log("SECURITY " + label + " pressing '" + text + "'");
                    button.onPress();
                    break;
                }
            }
        });
        Playtest.wait("the server answers", 30);
        Playtest.queue(label + ": the door is open", () -> {
            if (f.spot == null) return;
            var mc = Minecraft.getInstance();
            boolean open = DoorWalk.doorOpen(mc.getSingleplayerServer().overworld(), f.spot);
            Playtest.log("SECURITY " + label + " door " + (open ? "OPEN" : "closed") + ", screen " + (mc.screen == null ? "closed" : mc.screen.getClass().getSimpleName()));
            if (!open) Playtest.fail(label + ": the right code typed on SecurityCraft's screen did not open the door");
            if (mc.screen != null) mc.setScreen(null);
        });
        Playtest.queue(label + ": screenshot of the open door", () -> Playtest.screenshotNextFrame("uc-security-" + label + "-2-open"));
        Playtest.wait("frame", 8);
    }

    /** Walks through the doorway and across the laser two steps in, then reads what Core did. */
    private static void crossLaser(String label, Facts f, boolean expectAlarm) {
        Playtest.queue(label + ": walk in across the laser", () -> {
            if (f.install == null || f.install.lasers.size() < 2) return;
            f.tripsBefore = f.install.trips;
            f.dispatchesBefore = PoliceResponse.dispatches();
            Playtest.focusWindow();
            Playtest.keyDown("w");
        }, () -> f.install == null || f.install.lasers.size() < 2 || f.spot.past(Minecraft.getInstance().player.position()) > 3.2, 200);
        Playtest.queue(label + ": stop", () -> Playtest.keyUp("w"));
        Playtest.wait("Core reads the laser", 20);
        Playtest.queue(label + ": look back at the laser and the siren", () -> {
            if (f.install == null || f.install.lasers.isEmpty()) return;
            var mc = Minecraft.getInstance();
            Vec3 mid = Vec3.atCenterOf(f.install.lasers.get(0)).add(Vec3.atCenterOf(f.install.lasers.get(1))).scale(0.5);
            Scenarios.standLooking(mc.player.position(), f.install.alarms.isEmpty() ? mid : Vec3.atCenterOf(f.install.alarms.get(0)).add(mid).scale(0.5));
        });
        Playtest.wait("hold the aim", 20);
        Playtest.queue(label + ": screenshot of the hall", () -> Playtest.screenshotNextFrame("uc-security-" + label + "-3-hall"));
        Playtest.wait("frame", 8);
        Playtest.queue(label + ": what Core made of the crossing", () -> {
            if (f.install == null || f.install.lasers.size() < 2) return;
            boolean called = false;
            for (int i = f.dispatchesBefore; i < PoliceResponse.dispatches(); i++) if (PoliceResponse.dispatchAt(i).why.contains("alarm")) called = true;
            Playtest.log("SECURITY " + label + " laser trips " + f.tripsBefore + " -> " + f.install.trips + ", siren " + (f.install.sirenUntil > 0 ? "sounding" : "quiet") + ", alarm dispatch " + called + "; police " + PoliceResponse.status());
            if (expectAlarm && f.install.trips != f.tripsBefore + 1) Playtest.fail(label + ": crossing the laser was not seen");
            if (expectAlarm && !called && PoliceResponse.dispatches() == f.dispatchesBefore) Playtest.fail(label + ": crossing the laser raised no alarm");
            if (expectAlarm && f.install.sirenUntil == 0) Playtest.fail(label + ": the siren is quiet");
            if (!expectAlarm && f.install.trips != f.tripsBefore) Playtest.fail(label + ": a keyholder crossing the laser counted as a trip");
            if (!expectAlarm && PoliceResponse.dispatches() != f.dispatchesBefore) Playtest.fail(label + ": a keyholder crossing raised the police");
        });
    }

    /** From inside: right-click the door; Core opens it without the code. */
    private static void leaveFromInside(String label, Facts f) {
        Playtest.queue(label + ": shut the door behind (server)", () -> {
            if (f.spot == null) return;
            var server = Minecraft.getInstance().getSingleplayerServer();
            server.execute(() -> {
                var level = server.overworld();
                var state = level.getBlockState(f.spot.door());
                if (state.getBlock() instanceof DoorBlock door && state.getValue(DoorBlock.OPEN)) door.setOpen(null, level, state, f.spot.door(), false);
            });
        });
        Playtest.wait("settle", 10);
        Playtest.queue(label + ": face the door from inside and right-click it", () -> {
            if (f.spot == null) return;
            var mc = Minecraft.getInstance();
            Vec3 inside = Vec3.atBottomCenterOf(f.spot.door()).add(-f.spot.out().getStepX() * 1.6, 0, -f.spot.out().getStepZ() * 1.6);
            Scenarios.standLooking(inside, Vec3.atCenterOf(f.spot.door()).add(0, 0.4, 0));
        });
        Playtest.wait("hold the aim", 20);
        Playtest.queue(label + ": click", () -> { Playtest.focusWindow(); Playtest.mouseButton(3, true); });
        Playtest.wait("hold", 4);
        Playtest.queue(label + ": release", () -> Playtest.mouseButton(3, false));
        Playtest.wait("the server answers", 15);
        Playtest.queue(label + ": the door opened for the person leaving, without a code screen", () -> {
            if (f.spot == null) return;
            var mc = Minecraft.getInstance();
            boolean open = DoorWalk.doorOpen(mc.getSingleplayerServer().overworld(), f.spot);
            Playtest.log("SECURITY " + label + " leaving: door " + (open ? "OPEN" : "closed") + ", screen " + (mc.screen == null ? "none" : mc.screen.getClass().getSimpleName()));
            if (!open) Playtest.fail(label + ": the keypad door did not open from the inside");
            if (mc.screen != null) { Playtest.fail(label + ": leaving asked for a code"); mc.setScreen(null); }
        });
    }
}
