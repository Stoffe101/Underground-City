package io.github.stoffe101.undergroundcity;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.Vec3;

import java.util.List;

/**
 * One complete high-risk burglary through a real client (D-122): {@code -PucPlaytest=burglary}. In a brand-new archipelago world a registered survival player, with lockpicks and some bread, walks up to
 * the door of a real generated apartment block whose alarm is armed and whose stash exists, picks the door on the real lock screen with the real pointer (pin by pin), is inside when the alarm rings and the
 * police are called, picks the stash's own lock the same way, takes an item through the real container screen, steps out into the street, and waits for the officers who arrive, walk to them and arrest them.
 *
 * <p>What is scripted rather than played: the walk between the door and the stash (the player is placed beside the safe, as the doorwalk scenario places them in front of a door), the arrival time (the
 * shared active clock is moved on to the dispatch time, because the clock advances only while a real player is online and a two-minute wait adds nothing), and the choice of building (one whose
 * alarm is armed and whose stash exists, from the same hashes the game uses; an unarmed one would show only the heat). Everything the officers do is the game's own code on the real server thread.
 * It is evidence for one burglary of one building by a script, not for how it feels to a person.
 */
final class BurglaryScenario {
    private BurglaryScenario() {}

    private static final class Facts {
        volatile DoorWalk.Spot spot;
        volatile CityBuildings.Building building;
        volatile BlockPos safe;
        volatile Direction safeFacing = Direction.NORTH;
        volatile int heatBefore, arrestsBefore;
        volatile double dispatchSeconds;
        volatile boolean searched, safeSearched;
    }

    static void build() {
        Facts f = new Facts();
        Playtest.keepScreens = true;
        Playtest.queue("the client has the ground under the player", () -> { }, Scenarios::groundBelowPlayer, 1200);
        Playtest.queue("the first-login registration screen opens by itself", () -> { }, () -> Minecraft.getInstance().screen instanceof RegistrationScreen, 900);
        Playtest.wait("let the screen render", 20);
        Playtest.queue("click the first city", () -> {
            Playtest.focusWindow();
            int[] at = ((RegistrationScreen) Minecraft.getInstance().screen).cardCentre(0);
            Playtest.clickGui(at[0], at[1]);
        }, () -> ((RegistrationScreen) Minecraft.getInstance().screen).step() == 1, 100);
        Playtest.queue("click the first profession", () -> {
            int[] at = ((RegistrationScreen) Minecraft.getInstance().screen).cardCentre(0);
            Playtest.clickGui(at[0], at[1]);
        }, () -> ((RegistrationScreen) Minecraft.getInstance().screen).step() == 2, 100);
        Playtest.wait("settle", 10);
        Playtest.queue("click CONFIRM", () -> {
            int[] at = ((RegistrationScreen) Minecraft.getInstance().screen).confirmCentre();
            Playtest.clickGui(at[0], at[1]);
        }, () -> !(Minecraft.getInstance().screen instanceof RegistrationScreen), 200);
        Playtest.wait("let the server answer", 20);
        Playtest.queue("survival mode, as a player would be", () -> Playtest.command("gamemode survival"));
        Playtest.wait("settle", 10);

        Playtest.queue("find a street door of an apartment block with an armed alarm and a stash (on the server thread)", () -> {
            var server = Minecraft.getInstance().getSingleplayerServer();
            server.execute(() -> {
                try {
                    var level = server.overworld();
                    f.spot = DoorWalk.find(level, true, "flat_block", b -> b.security() != null && b.security().response() != null && PoliceResponse.armed(level, b, b.security().response())
                        && BuildingStash.roll(level, b, 1) < b.security().stashChance()).orElse(null);
                    if (f.spot != null) f.building = CityBuildings.INSTANCE.at(level, f.spot.door()).orElse(null);
                } finally { f.searched = true; }
            });
        }, () -> f.searched, 24000);
        Playtest.queue("which building", () -> {
            if (f.spot == null || f.building == null) { Playtest.fail("no apartment block with an armed alarm and a stash within sixteen chunks of Ironhaven's centre"); return; }
            var response = f.building.security().response();
            Playtest.log("BURGLARY target: " + f.building.type() + " of " + f.building.city() + " class " + f.building.security().id() + " at chunk " + f.building.chunkX() + "," + f.building.chunkZ()
                + ", door " + f.spot.door().toShortString() + "; alarm armed (hash), police " + response.dispatchSeconds() + " s after, " + response.officers() + " officer(s), looking for " + response.windowSeconds() + " s");
            f.dispatchSeconds = response.dispatchSeconds();
        });

        // 1. the door, on the real lock screen
        Playtest.queue("stand in front of the door with picks and some bread", () -> {
            if (f.spot == null) return;
            var mc = Minecraft.getInstance();
            var server = mc.getSingleplayerServer();
            server.execute(() -> {
                var player = server.getPlayerList().getPlayer(mc.player.getUUID());
                DoorWalk.prepare(player, f.spot, true);
                player.getInventory().add(new ItemStack(Items.BREAD, 5));
                f.heatBefore = WorkOrderLedger.get(server).heat(player.getUUID(), f.building.city());
                f.arrestsBefore = (int) PoliceResponse.arrests;
            });
        }, () -> f.spot == null || Minecraft.getInstance().player.position().distanceTo(f.spot.stand()) < 1.5, 600);
        Playtest.wait("settle", 25);
        final int[] ticks = {0};
        Playtest.queue("hold W and right-click the door until the lock screen opens", () -> { if (f.spot != null) { Playtest.focusWindow(); Playtest.keyDown("w"); } }, () -> {
            if (f.spot == null) return true;
            var mc = Minecraft.getInstance();
            ticks[0]++;
            mc.player.setYRot(f.spot.yaw());
            mc.player.setXRot(0);
            if (mc.screen instanceof LockpickScreen) return true;
            if (f.spot.past(mc.player.position()) > -1.0) { if (ticks[0] % 4 == 1) Playtest.mouseButton(3, true); else if (ticks[0] % 4 == 2) Playtest.mouseButton(3, false); }
            return false;
        }, 400);
        Playtest.queue("let go", () -> { Playtest.keyUp("w"); Playtest.mouseButton(3, false); });
        Playtest.wait("the screen draws", 10);
        Playtest.queue("screenshot of the door's lock", () -> Playtest.screenshotNextFrame("uc-burglary-1-door-lock"));
        Playtest.wait("frame", 8);
        solve("the door's lock", true);
        Playtest.wait("the door opens", 15);
        Playtest.queue("the door is open and the alarm has raised a response", () -> {
            if (f.spot == null) return;
            var server = Minecraft.getInstance().getSingleplayerServer();
            boolean open = DoorWalk.doorOpen(server.overworld(), f.spot);
            Playtest.log("BURGLARY door: " + (open ? "OPEN" : "closed") + "; police: " + PoliceResponse.status());
            if (!open) Playtest.fail("the door did not open after the lock was solved on the real screen");
            if (PoliceResponse.dispatches() < 1 || !PoliceResponse.dispatchAt(0).why.contains("alarm")) Playtest.fail("an armed alarm did not raise a police response: " + PoliceResponse.status());
        });
        Playtest.queue("screenshot of the open door and its notice", () -> Playtest.screenshotNextFrame("uc-burglary-2-alarm"));
        Playtest.wait("frame", 8);

        // 2. the stash, on its own lock screen
        Playtest.queue("find the stash and stand beside it (the walk through the rooms is scripted)", () -> {
            var mc = Minecraft.getInstance();
            var server = mc.getSingleplayerServer();
            server.execute(() -> {
                var level = server.overworld();
                var b = f.building;
                for (int y = b.minY(); y < b.maxY() && f.safe == null; y++) for (int x = 0; x < 16 && f.safe == null; x++) for (int z = 0; z < 16 && f.safe == null; z++) {
                    BlockPos pos = new BlockPos(b.chunkX() * 16 + x, y, b.chunkZ() * 16 + z);
                    if (level.getBlockState(pos).is(UndergroundCity.PROPERTY_CACHE.get())) f.safe = pos;
                }
                if (f.safe == null) { f.safeSearched = true; return; }
                f.safeFacing = level.getBlockState(f.safe).getValue(PropertyCacheBlock.FACING);
                BlockPos stand = f.safe.relative(f.safeFacing);
                var player = server.getPlayerList().getPlayer(mc.player.getUUID());
                player.teleportTo(level, stand.getX() + 0.5, stand.getY(), stand.getZ() + 0.5, f.safeFacing.getOpposite().toYRot(), 50f);
                player.setDeltaMovement(Vec3.ZERO);
                f.safeSearched = true;
            });
        }, () -> f.safeSearched, 300);
        Playtest.queue("a stash was placed", () -> {
            if (f.safe == null) Playtest.fail("the building's stash does not exist although its hash said it would");
            else Playtest.log("BURGLARY stash at " + f.safe.toShortString() + " facing " + f.safeFacing);
        });
        Playtest.wait("settle", 20);
        final int[] clicks = {0};
        Playtest.queue("right-click the safe until its lock screen opens", () -> { if (f.safe != null) Playtest.focusWindow(); }, () -> {
            if (f.safe == null) return true;
            var mc = Minecraft.getInstance();
            clicks[0]++;
            mc.player.setYRot(f.safeFacing.getOpposite().toYRot());
            mc.player.setXRot(50f);
            if (mc.screen instanceof LockpickScreen) return true;
            if (clicks[0] % 6 == 1) Playtest.mouseButton(3, true); else if (clicks[0] % 6 == 2) Playtest.mouseButton(3, false);
            return false;
        }, 400);
        Playtest.queue("let go", () -> Playtest.mouseButton(3, false));
        Playtest.wait("the screen draws", 10);
        Playtest.queue("screenshot of the stash's lock", () -> Playtest.screenshotNextFrame("uc-burglary-3-stash-lock"));
        Playtest.wait("frame", 8);
        solve("the stash's lock", false);
        Playtest.wait("the safe opens", 15);
        Playtest.queue("take the first item through the real container screen", () -> {
            var mc = Minecraft.getInstance();
            var screen = mc.screen;
            if (!(screen instanceof net.minecraft.client.gui.screens.inventory.AbstractContainerScreen<?> container)) { Playtest.fail("the safe's container screen did not open (screen " + screen + ")"); return; }
            var menu = container.getMenu();
            for (int i = 0; i < 9 && i < menu.slots.size(); i++) {
                if (!menu.slots.get(i).getItem().isEmpty()) {
                    Playtest.log("BURGLARY taking slot " + i + ": " + menu.slots.get(i).getItem());
                    mc.gameMode.handleInventoryMouseClick(menu.containerId, i, 0, ClickType.QUICK_MOVE, mc.player);
                    return;
                }
            }
            Playtest.fail("the stash was empty");
        });
        Playtest.wait("the server answers", 10);
        Playtest.queue("screenshot of the open stash", () -> Playtest.screenshotNextFrame("uc-burglary-4-loot"));
        Playtest.wait("frame", 8);
        Playtest.queue("what is carried", () -> {
            int stolen = 0, bread = 0;
            for (ItemStack stack : Minecraft.getInstance().player.getInventory().items) { if (StolenGoods.is(stack)) stolen += stack.getCount(); if (stack.is(Items.BREAD)) bread += stack.getCount(); }
            Playtest.log("BURGLARY carrying " + stolen + " stolen item(s) and " + bread + " bread");
            if (stolen == 0) Playtest.fail("what was taken from the forced safe is not marked as stolen");
        });
        Playtest.queue("close the screen", () -> { if (Minecraft.getInstance().screen != null) Playtest.tap("escape"); });
        Playtest.wait("settle", 10);

        // 3. the street, the police
        Playtest.queue("leave by the door and stand in the street; the clock moves on to the police's arrival", () -> {
            var mc = Minecraft.getInstance();
            var server = mc.getSingleplayerServer();
            server.execute(() -> {
                var player = server.getPlayerList().getPlayer(mc.player.getUUID());
                var level = server.overworld();
                player.teleportTo(level, f.spot.stand().x, f.spot.stand().y, f.spot.stand().z, f.spot.yaw() + 180f, 5f);
                player.setDeltaMovement(Vec3.ZERO);
                WorkOrderLedger.get(server).advance((long) (f.dispatchSeconds * 20) + 40);
                Playtest.log("BURGLARY the shared clock moved on by " + (long) (f.dispatchSeconds * 20 + 40) + " ticks to the dispatch time; the player stands at " + f.spot.stand());
            });
        });
        Playtest.queue("wait for officers to come into view", () -> { }, () -> {
            var mc = Minecraft.getInstance();
            return !officers(mc).isEmpty();
        }, 1800);
        Playtest.queue("officers", () -> {
            var mc = Minecraft.getInstance();
            var list = officers(mc);
            double nearest = list.stream().mapToDouble(o -> o.distanceTo(mc.player)).min().orElse(-1);
            Playtest.log("BURGLARY officers in view: " + list.size() + ", the nearest " + String.format(java.util.Locale.ROOT, "%.1f", nearest) + " blocks away; " + PoliceResponse.status());
        });
        Playtest.queue("turn to face the nearest officer", () -> lookAtNearestOfficer());
        Playtest.wait("the view turns", 6);
        Playtest.queue("screenshot of the officers arriving", () -> Playtest.screenshotNextFrame("uc-burglary-5-officers"));
        Playtest.wait("frame", 8);
        final int[] waited = {0};
        Playtest.queue("the thief stands still and waits to be arrested", () -> { }, () -> {
            if (PoliceResponse.arrests > f.arrestsBefore) return true;
            if (++waited[0] % 100 == 1) {
                var mc = Minecraft.getInstance();
                var server = mc.getSingleplayerServer();
                server.execute(() -> {
                    var thief = server.getPlayerList().getPlayer(mc.player.getUUID());
                    var found = server.overworld().getEntitiesOfClass(TownspersonEntity.class, thief.getBoundingBox().inflate(120), e -> e.police());
                    StringBuilder what = new StringBuilder();
                    for (var o : found) {
                        var path = o.getNavigation().getPath();
                        what.append(String.format(java.util.Locale.ROOT, " [%s at %.1f %.1f %.1f, %.1f blocks from the thief, errand %s, navigation %s, path %s, on ground %s]", o.getName().getString(), o.getX(), o.getY(), o.getZ(),
                            o.distanceTo(thief), o.errandPlace(), o.getNavigation().isDone() ? "idle" : "walking", path == null ? "none" : path.getNodeCount() + " nodes ending " + path.getEndNode() + (path.canReach() ? " (reaches)" : " (does not reach)"), o.onGround()));
                    }
                    Playtest.log("BURGLARY wait " + waited[0] + ": thief at " + thief.blockPosition() + ", officers " + found.size() + what + "; " + PoliceResponse.status());
                });
            }
            return false;
        }, 2400);
        Playtest.wait("the notice arrives", 15);
        Playtest.queue("turn to face the officer who made the arrest", () -> lookAtNearestOfficer());
        Playtest.wait("the view turns", 6);
        Playtest.queue("screenshot of the arrest", () -> Playtest.screenshotNextFrame("uc-burglary-6-arrest"));
        Playtest.wait("frame", 8);
        Playtest.queue("what the arrest did", () -> {
            var mc = Minecraft.getInstance();
            var server = mc.getSingleplayerServer();
            int stolen = 0, bread = 0;
            for (ItemStack stack : mc.player.getInventory().items) { if (StolenGoods.is(stack)) stolen += stack.getCount(); if (stack.is(Items.BREAD)) bread += stack.getCount(); }
            int heat = WorkOrderLedger.get(server).heat(mc.player.getUUID(), f.building.city());
            Playtest.log("BURGLARY after the arrest: " + stolen + " stolen item(s) left, " + bread + " bread left, heat in " + f.building.city() + " " + f.heatBefore + " -> " + heat + "; " + PoliceResponse.status());
            if (stolen != 0) Playtest.fail("stolen goods survived an arrest");
            if (bread != 5) Playtest.fail("honest goods were taken in the arrest (" + bread + " of 5 bread left)");
            if (heat < f.heatBefore + PoliceResponse.ARREST_HEAT) Playtest.fail("an arrest did not raise the city's heat by " + PoliceResponse.ARREST_HEAT);
        });
    }

    /** Turns the player's view to the nearest officer so that a photograph shows who is coming (the officers arrive from wherever the street spot was found, usually behind the camera). */
    private static void lookAtNearestOfficer() {
        var mc = Minecraft.getInstance();
        var server = mc.getSingleplayerServer();
        server.execute(() -> {
            var thief = server.getPlayerList().getPlayer(mc.player.getUUID());
            TownspersonEntity nearest = null;
            for (var o : server.overworld().getEntitiesOfClass(TownspersonEntity.class, thief.getBoundingBox().inflate(80), e -> e.police())) {
                if (nearest == null || o.distanceTo(thief) < nearest.distanceTo(thief)) nearest = o;
            }
            if (nearest != null) thief.lookAt(net.minecraft.commands.arguments.EntityAnchorArgument.Anchor.EYES, nearest.getEyePosition());
        });
    }

    private static List<TownspersonEntity> officers(Minecraft mc) {
        return mc.level.getEntitiesOfClass(TownspersonEntity.class, mc.player.getBoundingBox().inflate(80), e -> e.getName().getString().startsWith("Officer "));
    }

    /**
     * Solves the lock on the screen in front of the player with the real pointer, pin by pin: the pointer goes to the pin's sweet spot (asked of the server, one tick old) and the button is held until
     * the pin sets. With {@code sloppy} the hand first presses a fifth of the bar away from the sweet spot until the pick is strained to about 45 percent (a noisy pick, which trips an alarm that a clean
     * one would slip past), then works the lock honestly.
     */
    static void solve(String label, boolean sloppy) {
        final int[] state = {sloppy ? -1 : 0}, wait = {0}, pinsBefore = {0}, guard = {0}, geometry = new int[3], retries = {0};
        final double[] sweet = {-1}, scale = {1}, strain = {0};
        Playtest.queue("solve " + label + " on the real screen", () -> { }, () -> {
            var mc = Minecraft.getInstance();
            if (!(mc.screen instanceof LockpickScreen screen)) { Playtest.mouseButton(1, false); return state[0] > 0 || guard[0] > 0; }
            if (++guard[0] > 2400) { Playtest.fail(label + " was not solved in 2400 ticks"); return true; }
            var server = mc.getSingleplayerServer();
            if (state[0] != 3) server.execute(() -> { var active = LockService.activeFor(mc.player.getUUID()); if (active != null) { sweet[0] = active.session.sweetSpot(); strain[0] = active.session.strainPercent(); } });
            else server.execute(() -> { var active = LockService.activeFor(mc.player.getUUID()); if (active != null) strain[0] = active.session.strainPercent(); });
            switch (state[0]) {
                case -1 -> {
                    // the sloppy phase: geometry first, then press well off the sweet spot until the pick is strained
                    if (geometry[2] == 0) { System.arraycopy(screen.debugBar(), 0, geometry, 0, 3); scale[0] = mc.getWindow().getGuiScale(); }
                    if (sweet[0] < 0) return false;
                    double off = sweet[0] < 0.5 ? sweet[0] + 0.2 : sweet[0] - 0.2;
                    Playtest.input("move " + Math.round((geometry[0] + off * geometry[2]) * scale[0]) + " " + Math.round((geometry[1] + 10) * scale[0]));
                    wait[0] = 8; state[0] = -2;
                }
                case -2 -> { if (--wait[0] <= 0) { Playtest.mouseButton(1, true); wait[0] = 0; state[0] = -3; } }
                case -3 -> {
                    wait[0]++;
                    if (strain[0] >= 45 || wait[0] > 60) { Playtest.mouseButton(1, false); Playtest.log("BURGLARY " + label + ": a sloppy press strained the pick to " + Math.round(strain[0]) + " percent"); sweet[0] = -1; state[0] = 1; }
                }
                case 0 -> { System.arraycopy(screen.debugBar(), 0, geometry, 0, 3); scale[0] = mc.getWindow().getGuiScale(); state[0] = 1; }
                case 1 -> {
                    if (sweet[0] < 0) return false;
                    Playtest.input("move " + Math.round((geometry[0] + sweet[0] * geometry[2]) * scale[0]) + " " + Math.round((geometry[1] + 10) * scale[0]));
                    wait[0] = 8; state[0] = 2;
                }
                case 2 -> { if (--wait[0] <= 0) { pinsBefore[0] = screen.debugPinsSet(); Playtest.mouseButton(1, true); wait[0] = 0; state[0] = 3; } }
                case 3 -> {
                    wait[0]++;
                    if (screen.debugPinsSet() > pinsBefore[0]) { Playtest.mouseButton(1, false); sweet[0] = -1; state[0] = 1; retries[0] = 0; }
                    else if (wait[0] > 90) { Playtest.mouseButton(1, false); sweet[0] = -1; state[0] = 1; if (++retries[0] > 4) { Playtest.fail(label + ": a pin would not set after five tries"); return true; } }
                }
                default -> { }
            }
            return false;
        }, 2600);
        Playtest.queue("the screen has closed", () -> {
            Playtest.mouseButton(1, false);
            if (Minecraft.getInstance().screen instanceof LockpickScreen) Playtest.fail(label + " is still on the screen");
        });
    }
}
