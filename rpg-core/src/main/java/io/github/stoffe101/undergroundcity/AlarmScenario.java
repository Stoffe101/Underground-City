package io.github.stoffe101.undergroundcity;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;

/**
 * An alarm panel worked through a real client (D-128): {@code -PucPlaytest=alarm}. In a brand-new furnished archipelago world a registered Technician with Bypass Tooling picks the street door of a
 * real generated Ironhaven tenement whose alarm is armed, sees the panel start its entry delay instead of the police being called, right-clicks the panel, watches the signal flash on the real trace
 * screen and keys it back with real key presses: the panel goes blue and nobody is called, even after the entry delay would have run out. A second armed building is then entered the same way and
 * keyed back wrongly on purpose: the alarm rings, a response is raised and heat goes up by one.
 *
 * <p>What is scripted rather than played: the registration and the skill (a command and a server-side unlock, as the terminal trace scenario does; the career's progression is the journey's evidence,
 * not this one's), the Bypass Kits, the choice of building (the nearest to Ironhaven's centre whose panel is in the world and whose alarm is armed), the player's place in front of each door and the step
 * from the doorway to the panel (a teleport into the cell in front of it, looking at it). The lock, the right click on the panel, the signal and the answer are the real screens and real input.
 */
final class AlarmScenario {
    private AlarmScenario() {}

    private static final class Facts {
        volatile DoorWalk.Spot spot;
        volatile CityBuildings.Building building;
        volatile BlockPos panel, stand;
        volatile Direction facing = Direction.NORTH;
        volatile boolean searched;
        volatile int heatBefore, dispatchesBefore, length;
    }

    static void build() {
        Playtest.keepScreens = true;
        Playtest.queue("the client has the ground under the player", () -> { }, Scenarios::groundBelowPlayer, 1200);
        Playtest.queue("the first-login registration screen opens by itself", () -> { }, () -> Minecraft.getInstance().screen instanceof RegistrationScreen, 900);
        Playtest.queue("a registered Ironhaven technician (by command; the real registration screen is the journey's)", () -> {
            Minecraft.getInstance().setScreen(null);
            Playtest.command("gamemode survival");
            Playtest.command("uc register ironhaven technician");
        });
        Playtest.wait("let the server answer", 40);
        Playtest.queue("Bypass Tooling (server side)", () -> {
            var mc = Minecraft.getInstance();
            var server = mc.getSingleplayerServer();
            var uuid = mc.player.getUUID();
            server.execute(() -> {
                var citizens = CitizenState.get(server);
                citizens.addCareerXp(uuid, 5000);
                Scenarios.unlockWithPrerequisites(citizens, uuid, AlarmPanels.SKILL);
            });
        });
        Playtest.wait("settle", 20);
        Facts first = new Facts(), second = new Facts();
        part("jam", first, null, false);
        part("ring", second, first, true);
        Playtest.queue("ALARM summary", () -> Playtest.log("ALARM panels: " + AlarmPanels.status() + "; police: " + PoliceResponse.status()));
    }

    private static void part(String label, Facts f, Facts other, boolean wrong) {
        Playtest.queue(label + ": find an armed tenement with its panel in the world (on the server thread)", () -> {
            var server = Minecraft.getInstance().getSingleplayerServer();
            server.execute(() -> {
                try {
                    var level = server.overworld();
                    f.spot = DoorWalk.find(level, true, "flat_block", b -> {
                        if (other != null && other.building != null && other.building.key() == b.key()) return false;
                        if (b.security() == null || b.security().response() == null) return false;
                        level.getChunk(b.chunkX(), b.chunkZ());
                        return AlarmPanels.placedPanel(level, b).isPresent() && PoliceResponse.armed(level, b, b.security().response());
                    }).orElse(null);
                    if (f.spot == null) return;
                    f.building = CityBuildings.INSTANCE.at(level, f.spot.door()).orElse(null);
                    if (f.building == null) return;
                    f.panel = AlarmPanels.placedPanel(level, f.building).orElse(null);
                    f.facing = level.getBlockState(f.panel).getValue(AlarmPanelBlock.FACING);
                    // the first cell in front of the panel a person can stand in
                    for (int out = 1; out <= 2 && f.stand == null; out++) {
                        BlockPos feet = f.panel.relative(f.facing, out).below();
                        if (level.getBlockState(feet).getCollisionShape(level, feet).isEmpty() && level.getBlockState(feet.above()).getCollisionShape(level, feet.above()).isEmpty()
                            && !level.getBlockState(feet.below()).getCollisionShape(level, feet.below()).isEmpty()) f.stand = feet;
                    }
                } finally { f.searched = true; }
            });
        }, () -> f.searched, 24000);
        Playtest.queue(label + ": which building", () -> {
            if (f.spot == null || f.building == null || f.panel == null) { Playtest.fail(label + ": no armed tenement with a panel within sixteen chunks of Ironhaven's centre"); return; }
            if (f.stand == null) { Playtest.fail(label + ": nowhere to stand in front of the panel at " + f.panel.toShortString()); return; }
            Playtest.log("ALARM " + label + " target: " + f.building.type() + " class " + f.building.security().id() + " at chunk " + f.building.chunkX() + "," + f.building.chunkZ() + ", door " + f.spot.door().toShortString()
                + ", panel " + f.panel.toShortString() + " facing " + f.facing + ", stand " + f.stand.toShortString());
        });

        Playtest.queue(label + ": stand in front of the door with picks and two Bypass Kits", () -> {
            if (f.spot == null) return;
            var mc = Minecraft.getInstance();
            var server = mc.getSingleplayerServer();
            server.execute(() -> {
                var player = server.getPlayerList().getPlayer(mc.player.getUUID());
                DoorWalk.prepare(player, f.spot, true);
                player.getInventory().add(new ItemStack(UndergroundCity.item("bypass_kit"), 2));
                f.heatBefore = WorkOrderLedger.get(server).heat(player.getUUID(), f.building.city());
                f.dispatchesBefore = PoliceResponse.dispatches();
            });
        }, () -> f.spot == null || Minecraft.getInstance().player.position().distanceTo(f.spot.stand()) < 1.5, 600);
        Playtest.wait("settle", 25);
        final int[] ticks = {0};
        Playtest.queue(label + ": hold W and right-click the door until the lock screen opens", () -> { ticks[0] = 0; if (f.spot != null) { Playtest.focusWindow(); Playtest.keyDown("w"); } }, () -> {
            if (f.spot == null) return true;
            var mc = Minecraft.getInstance();
            ticks[0]++;
            mc.player.setYRot(f.spot.yaw());
            mc.player.setXRot(0);
            if (mc.screen instanceof LockpickScreen) return true;
            if (f.spot.past(mc.player.position()) > -1.0) { if (ticks[0] % 4 == 1) Playtest.mouseButton(3, true); else if (ticks[0] % 4 == 2) Playtest.mouseButton(3, false); }
            return false;
        }, 400);
        Playtest.queue(label + ": let go", () -> { Playtest.keyUp("w"); Playtest.mouseButton(3, false); });
        Playtest.wait("the screen draws", 10);
        // a strained pick, as in the burglary scenario: a clean one slips past an alarm that can be slipped past (D-122), and there would be no entry delay to see
        BurglaryScenario.solve(label + ": the door's lock", true);
        Playtest.wait("the door opens", 15);
        Playtest.queue(label + ": the door is open, the panel counts down and nobody has been called", () -> {
            if (f.spot == null) return;
            var server = Minecraft.getInstance().getSingleplayerServer();
            var level = server.overworld();
            boolean open = DoorWalk.doorOpen(level, f.spot);
            Playtest.log("ALARM " + label + " door " + (open ? "OPEN" : "closed") + "; panel " + AlarmPanels.status() + "; police " + PoliceResponse.status());
            if (!open) Playtest.fail(label + ": the door did not open after the lock was solved on the real screen");
            else if (!AlarmPanels.inEntry(f.building.key())) Playtest.fail(label + ": coming in did not start the panel's entry delay: " + AlarmPanels.status());
            else if (PoliceResponse.dispatches() != f.dispatchesBefore) Playtest.fail(label + ": the police were called before the entry delay ran out: " + PoliceResponse.status());
        });

        Playtest.queue(label + ": step to the panel (scripted) and look at it", () -> {
            if (f.stand == null) return;
            Vec3 at = Vec3.atBottomCenterOf(f.stand);
            Vec3 plate = Vec3.atCenterOf(f.panel).add(-f.facing.getStepX() * 0.45, -0.06, -f.facing.getStepZ() * 0.45);
            Scenarios.standLooking(at, plate);
        });
        Playtest.wait("hold the aim (a software-rendered frame is slower than a tick)", 30);
        Playtest.queue(label + ": screenshot of the panel counting down", () -> Playtest.screenshotNextFrame("uc-alarm-" + label + "-1-entry"));
        Playtest.wait("frame", 8);
        Playtest.queue(label + ": right-click the panel until the trace screen opens", () -> {
            var mc = Minecraft.getInstance();
            String aimed = mc.hitResult instanceof net.minecraft.world.phys.BlockHitResult block && block.getType() == net.minecraft.world.phys.HitResult.Type.BLOCK
                ? block.getBlockPos().toShortString() + " " + mc.level.getBlockState(block.getBlockPos()).getBlock() : String.valueOf(mc.hitResult == null ? null : mc.hitResult.getType());
            Playtest.log("ALARM " + label + " cursor: " + aimed + " from " + mc.player.position() + ", holding " + mc.player.getMainHandItem());
            Playtest.focusWindow();
            Playtest.mouseButton(3, true);
        }, () -> Minecraft.getInstance().screen instanceof ChallengeScreen, 200);
        Playtest.queue(label + ": release the button", () -> {
            Playtest.mouseButton(3, false);
            var mc = Minecraft.getInstance();
            mc.getSingleplayerServer().execute(() -> { var session = AlarmPanels.sessionOf(mc.player.getUUID()); f.length = session == null ? -1 : session.sequence().length; });
        });
        final java.util.List<Integer> seen = new java.util.ArrayList<>();
        Playtest.queue(label + ": screenshot while the pads flash", () -> Playtest.screenshotNextFrame("uc-alarm-" + label + "-2-signal"));
        Scenarios.watchTrace(seen, label);
        Playtest.queue(label + ": every symbol was seen", () -> {
            Playtest.log("ALARM " + label + " seen " + seen + " of " + f.length);
            if (seen.size() != f.length) Playtest.fail(label + ": the screen flashed " + seen.size() + " symbols, the panel's signal has " + f.length);
        });
        Playtest.wait(label + ": settle", 4);
        Scenarios.typeTrace(seen, wrong, label);
        Playtest.wait(label + ": the server answers", 30);
        if (!wrong) {
            Playtest.queue(label + ": the panel is jammed, blue, and nobody has been called", () -> {
                var server = Minecraft.getInstance().getSingleplayerServer();
                var level = server.overworld();
                var light = AlarmPanels.light(level, f.building);
                Playtest.log("ALARM " + label + " light " + light + "; " + AlarmPanels.status() + "; police " + PoliceResponse.status());
                if (!AlarmPanels.jammed(level, f.building)) Playtest.fail(label + ": the right answer on the real screen did not jam the panel");
                else if (light != AlarmPanelBlock.Light.JAMMED) Playtest.fail(label + ": a jammed panel shows " + light);
                else if (AlarmPanels.inEntry(f.building.key())) Playtest.fail(label + ": the entry delay still runs on a jammed panel");
            });
            Playtest.wait("the light reaches the client", 50);
            Playtest.queue(label + ": screenshot of the blue panel", () -> Playtest.screenshotNextFrame("uc-alarm-" + label + "-3-jammed"));
            Playtest.wait("frame", 8);
            Playtest.wait("wait out what was left of the 25-second entry delay", 20 * (AlarmPanels.ENTRY_SECONDS + 5));
            Playtest.queue(label + ": still nobody called", () -> {
                if (PoliceResponse.dispatches() != f.dispatchesBefore) Playtest.fail(label + ": the police were called for a jammed building: " + PoliceResponse.status());
                else Playtest.log("ALARM " + label + " PASS: the panel was jammed on the real trace screen and the building called nobody; " + AlarmPanels.status());
            });
        } else {
            Playtest.queue(label + ": the alarm rang, a response was raised and heat rose", () -> {
                var mc = Minecraft.getInstance();
                var server = mc.getSingleplayerServer();
                var level = server.overworld();
                int heat = WorkOrderLedger.get(server).heat(mc.player.getUUID(), f.building.city());
                boolean called = false;
                for (int i = 0; i < PoliceResponse.dispatches(); i++) {
                    var dispatch = PoliceResponse.dispatchAt(i);
                    if (dispatch.buildingKey == f.building.key() && dispatch.why.contains("alarm")) called = true;
                }
                Playtest.log("ALARM " + label + " heat " + f.heatBefore + " -> " + heat + ", light " + AlarmPanels.light(level, f.building) + "; " + AlarmPanels.status() + "; police " + PoliceResponse.status());
                if (!called) Playtest.fail(label + ": a wrong answer on the real screen did not ring the alarm: " + PoliceResponse.status());
                else if (heat <= f.heatBefore) Playtest.fail(label + ": a wrong answer did not raise heat (" + f.heatBefore + " -> " + heat + ")");
                else if (AlarmPanels.jammed(level, f.building)) Playtest.fail(label + ": a wrong answer jammed the panel");
                else Playtest.log("ALARM " + label + " PASS: a wrong answer on the real trace screen rang the alarm and raised heat");
            });
            Playtest.wait("the light reaches the client", 50);
            Playtest.queue(label + ": screenshot after the alarm rang", () -> Playtest.screenshotNextFrame("uc-alarm-" + label + "-3-rang"));
            Playtest.wait("frame", 8);
        }
    }
}
