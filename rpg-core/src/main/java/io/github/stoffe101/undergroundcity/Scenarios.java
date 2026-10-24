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
            case "arrival" -> arrival();
            case "doorwalk" -> doorwalk();
            case "burglary" -> BurglaryScenario.build();
            case "thief" -> ThiefScenario.build();
            case "heist" -> HeistScenario.build();
            case "cannabis" -> CannabisScenario.build();
            case "cannabislaw" -> CannabisScenario.buildLaw();
            case "patrol" -> PatrolScenario.build();
            case "freight" -> FreightScenario.build();
            case "places" -> PlacesScenario.build();
            case "tech" -> TechScenario.build();
            case "organic" -> OrganicScenario.build();
            case "crowd" -> CrowdScenario.build();
            case "hitch" -> HitchScenario.build();
            case "raid" -> RaidScenario.build();
            case "civic" -> CivicScenario.build();
            case "workshop" -> WorkshopScenario.build();
            case "sccamera" -> CameraMeasureScenario.build();
            case "alarm" -> AlarmScenario.build();
            case "security" -> SecurityScenario.build();
            case "journey" -> JourneyScenario.build();
            case "lockjitter" -> lockjitter();
            case "walk" -> walk();
            case "row" -> row();
            case "posts" -> posts();
            case "markers" -> markers();
            case "shelter" -> shelter();
            case "qol" -> qol();
            case "survival" -> survival();
            case "tracker" -> tracker();
            case "trace" -> trace();
            case "car" -> car();
            case "boat" -> boat();
            case "gun" -> gun();
            case "gallery" -> Gallery.build();
            case "modgallery" -> Gallery.mods();
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
     * The first minutes of a brand-new archipelago world through real input (D-107, D-109, D-111): where the player arrives, the first-login registration by mouse, the welcome pack, the
     * status display in a real world beside the other mods' screen furniture (the minimap, the survival bars), the next step of the first hours after registering, a walk up the Row's lane with
     * the keys, and a minute in an Ironhaven street (who comes out, what the passes cost). What the harness does that a player does not: it creates the world, sets the game mode (a command, so
     * that the survival bars are on screen) and moves the player to the street. It is a scripted client in a software-rendered virtual display, not a person.
     */
    private static void arrival() {
        Playtest.keepScreens = true;
        Playtest.queue("the client has the ground under the player", () -> { }, Scenarios::groundBelowPlayer, 1200);
        Playtest.queue("the player arrived on dry, open ground near the landing and Founders Row", Scenarios::checkArrival);
        Playtest.queue("the first-login registration screen opens by itself", () -> { }, () -> Minecraft.getInstance().screen instanceof RegistrationScreen, 900);
        Playtest.wait("let the screen render", 20);
        Playtest.queue("screenshot of the first screen", () -> Playtest.screenshotNextFrame("uc-arrival-register-1"));
        Playtest.wait("frame", 8);
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
        Playtest.queue("the server created the citizen and the welcome pack arrived", () -> {
            var mc = Minecraft.getInstance();
            var citizen = CitizenState.get(mc.getSingleplayerServer()).find(mc.player.getUUID());
            if (citizen.isEmpty()) { Playtest.fail("the server has no citizen record after the click path"); return; }
            Playtest.log("citizen " + citizen.get().city + "/" + citizen.get().career);
            boolean terminal = false;
            for (var stack : mc.player.getInventory().items) if (stack.is(UndergroundCity.TERMINAL_ITEM.get())) terminal = true;
            if (!terminal) Playtest.fail("the welcome pack did not arrive");
            expectStep("after registering", "Meet your employer");
        });
        Playtest.queue("survival mode, as a player would be", () -> Playtest.command("gamemode survival"));
        Playtest.queue("the status display has the citizen's state", () -> { }, () -> ClientHud.state().registered(), 400);
        Playtest.queue("the status display's own words, as the client holds them", () -> {
            var state = ClientHud.state();
            Playtest.log("status: place '" + state.place() + "' kind " + state.placeKind() + ", home city " + state.homeCity() + ", trade " + state.career() + " level " + state.level() + ", credits " + state.credits());
        });
        Playtest.wait("let the display and the world draw", 40);
        Playtest.queue("screenshot of the status display in the real world", () -> Playtest.screenshotNextFrame("uc-arrival-hud-1"));
        Playtest.wait("frame", 8);

        // the walk from the arrival to the registrar by the Row's gate, with the keys, in survival. The player arrives at the foot of the Row's lane: the route goes up the lane first and only
        // then across to the registrar (a straight line would walk into the buildings beside the lane). The heading is set on the client each tick; the keys do the moving.
        final Vec3[] gate = new Vec3[1], from = new Vec3[1];
        final int[] ticks = {0};
        Playtest.queue("walk from the arrival to the registrar with the keys", () -> {
            var mc = Minecraft.getInstance();
            var registrar = citizen(null, citizenIndex("registrar", null));
            if (registrar == null) { Playtest.fail("the registrar is not in the world"); return; }
            gate[0] = registrar.position();
            from[0] = mc.player.position();
            Playtest.log("walking " + String.format(java.util.Locale.ROOT, "%.1f", Math.hypot(gate[0].x - from[0].x, gate[0].z - from[0].z)) + " blocks, from " + mc.player.blockPosition().toShortString() + " to the registrar at " + registrar.blockPosition().toShortString());
            Playtest.focusWindow();
            Playtest.keyDown("w");
            Playtest.keyDown("ctrl");
        }, () -> {
            var mc = Minecraft.getInstance();
            if (gate[0] == null) return true;
            var me = mc.player;
            double distance = Math.hypot(gate[0].x - me.getX(), gate[0].z - me.getZ());
            // up the lane (the arrival's own x) until level with the registrar, then across
            double aimX = Math.abs(gate[0].z - me.getZ()) > 3.0 ? from[0].x : gate[0].x, aimZ = gate[0].z;
            me.setYRot((float) Math.toDegrees(Math.atan2(-(aimX - me.getX()), aimZ - me.getZ())));
            me.setXRot(0);
            ticks[0]++;
            if (ticks[0] % 100 == 0) Playtest.log("walking: at " + me.blockPosition().toShortString() + ", " + String.format(java.util.Locale.ROOT, "%.1f", distance) + " blocks from the registrar, on ground " + me.onGround() + ", horizontal collision " + me.horizontalCollision);
            if (ticks[0] == 500) Playtest.screenshotNextFrame("uc-arrival-walk-stuck");
            if (distance < 6.5) { Playtest.log("reached the lane beside the registrar after " + ticks[0] + " ticks (" + String.format(java.util.Locale.ROOT, "%.0f", ticks[0] / 20.0) + " s), " + me.blockPosition().toShortString()); return true; }
            return false;
        }, 1600);
        Playtest.queue("stop walking", () -> { Playtest.keyUp("ctrl"); Playtest.keyUp("w"); });
        Playtest.queue("the walk ended at the gate", () -> {
            var mc = Minecraft.getInstance();
            if (gate[0] == null) return;
            double distance = Math.hypot(gate[0].x - mc.player.getX(), gate[0].z - mc.player.getZ());
            if (distance >= 6.5) Playtest.fail("the walk from the arrival did not reach the registrar by the gate (" + String.format(java.util.Locale.ROOT, "%.1f", distance) + " blocks left after " + ticks[0] / 20 + " s)");
        });
        Playtest.wait("settle", 20);
        Playtest.queue("screenshot at the gate", () -> Playtest.screenshotNextFrame("uc-arrival-gate"));
        Playtest.wait("frame", 8);

        // the city's people: stand in a street of Ironhaven for a minute of play and count who came out, and what the passes cost
        Playtest.queue("stand in an Ironhaven street", () -> Playtest.command("tp @s 8 72 40 0 4"));
        Playtest.wait("a minute of play for the people to come out", 1200);
        Playtest.queue("who is about, and what the passes cost", () -> {
            var mc = Minecraft.getInstance();
            var box = mc.player.getBoundingBox().inflate(64);
            int about = level().getEntitiesOfClass(TownspersonEntity.class, box).size();
            Playtest.log("people within 64 blocks of the player: " + about);
            Playtest.log("population: " + PopulationService.status(mc.getSingleplayerServer()));
        });
        Playtest.queue("screenshot of the street", () -> Playtest.screenshotNextFrame("uc-arrival-street"));
        Playtest.wait("frame", 8);
        Playtest.queue("turn to the nearest person, if anybody is about", () -> {
            var mc = Minecraft.getInstance();
            TownspersonEntity nearest = null;
            for (TownspersonEntity person : level().getEntitiesOfClass(TownspersonEntity.class, mc.player.getBoundingBox().inflate(64))) {
                if (nearest == null || person.distanceToSqr(mc.player) < nearest.distanceToSqr(mc.player)) nearest = person;
            }
            if (nearest == null) { Playtest.log("nobody to look at"); return; }
            Playtest.log("the nearest person is " + nearest.getName().getString() + " (" + nearest.role() + "), " + String.format(java.util.Locale.ROOT, "%.0f", Math.sqrt(nearest.distanceToSqr(mc.player))) + " blocks away");
            standLooking(mc.player.position(), nearest.position().add(0, nearest.getBbHeight() * 0.85, 0));
        });
        Playtest.wait("settle", 20);
        Playtest.queue("screenshot of a person in the street", () -> Playtest.screenshotNextFrame("uc-arrival-person"));
        Playtest.wait("frame", 8);
    }

    /**
     * The survival bypass of the first human playtest, through real input: in survival, hold W toward a locked door of a generated building and right-click it while walking, with and without
     * lockpicks, for a door that faces the street and one that faces the room. The player must end up outside, the door shut, and (with picks) in front of the lock screen.
     */
    private static void doorwalk() {
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

        final DoorWalk.Spot[] spots = new DoorWalk.Spot[2];   // [0] a door that faces the street, [1] one that faces the room
        final boolean[] searched = {false};
        Playtest.queue("find a real exterior door of each orientation (on the server thread)", () -> {
            var server = Minecraft.getInstance().getSingleplayerServer();
            server.execute(() -> {
                try {
                    spots[0] = DoorWalk.find(server.overworld(), true, "flat_block").orElse(null);
                    spots[1] = DoorWalk.find(server.overworld(), false, "flat_block").orElse(null);
                } finally { searched[0] = true; }
            });
        }, () -> searched[0], 12000);
        Playtest.queue("which doors", () -> {
            for (int i = 0; i < 2; i++) {
                DoorWalk.Spot spot = spots[i];
                Playtest.log("door " + (i == 0 ? "facing the street" : "facing the room") + ": " + (spot == null ? "none found" : spot.type() + " class " + spot.securityClass() + " at " + spot.door().toShortString() + " facing " + spot.facing() + ", street to the " + spot.out()));
                if (spot == null) Playtest.fail("no generated exterior door " + (i == 0 ? "facing the street" : "facing the room") + " near Ironhaven's centre");
            }
        });
        for (int i = 0; i < 2; i++) for (boolean armed : new boolean[] {false, true}) walkIntoDoor(spots, i, armed);
        for (int i = 0; i < 2; i++) walkOutOfDoor(spots, i);
    }

    /**
     * The lock screen under a real mouse: a lock in the player's hands, the pointer slid across the bar the way a hand does, held still near the edge of a band, then pressed on the spot. It reports what the
     * first human playtest called jitter as numbers: does the pick follow the pointer exactly, does the reading change by itself, how far does the needle wobble under a press, and how long a press takes to
     * set a pin (with {@code -PucLockLatency=<ticks>} the client's reports are held back to play it against a slow connection). Window size: {@code -PucPtWidth/-PucPtHeight}, GUI scale: {@code -PucPtGuiscale}.
     */
    private static void lockjitter() {
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
        Playtest.queue("survival, and the GUI scale the run asks for", () -> {
            Playtest.command("gamemode survival");
            var mc = Minecraft.getInstance();
            int scale = Integer.getInteger("uc.playtest.guiscale", 2);
            mc.options.guiScale().set(scale);
            mc.resizeDisplay();
        });
        Playtest.wait("settle", 20);
        final double[] sweet = {-1};
        final double[] tolerance = {0};
        Playtest.queue("put a deadbolt in the player's hands (server side)", () -> {
            var mc = Minecraft.getInstance();
            var server = mc.getSingleplayerServer();
            server.execute(() -> {
                var player = server.getPlayerList().getPlayer(mc.player.getUUID());
                player.getInventory().add(new net.minecraft.world.item.ItemStack(UndergroundCity.item("lockpick"), 30));
                String refusal = LockService.begin(player, server.overworld(), new LockService.Target(LockService.Kind.DOOR, player.blockPosition(), "deadbolt", "ironhaven", "flat_block", "Deadbolt", 1));
                if (refusal != null) Playtest.fail("the lock did not start: " + refusal);
                var active = LockService.activeFor(player.getUUID());
                if (active != null) { sweet[0] = active.session.sweetSpot(); tolerance[0] = active.session.profile().tolerance(); }
            });
        }, () -> Minecraft.getInstance().screen instanceof LockpickScreen && sweet[0] >= 0, 400);
        Playtest.wait("let the screen draw", 20);
        final int[] geometry = new int[3];
        final double[] scale = {1};
        Playtest.queue("the bar, in GUI pixels and in device pixels", () -> {
            var mc = Minecraft.getInstance();
            var screen = (LockpickScreen) mc.screen;
            System.arraycopy(screen.debugBar(), 0, geometry, 0, 3);
            scale[0] = mc.getWindow().getGuiScale();
            Playtest.log("LOCKJITTER window " + mc.getWindow().getScreenWidth() + "x" + mc.getWindow().getScreenHeight() + ", GUI scale " + (int) scale[0] + ", bar " + geometry[2] + " GUI px = " + Math.round(geometry[2] * scale[0])
                + " device px wide; one device pixel of the mouse moves the pick by " + String.format(java.util.Locale.ROOT, "%.4f", 1.0 / (geometry[2] * scale[0])) + " of the bar (the band the pick must hold is " + String.format(java.util.Locale.ROOT, "%.3f", tolerance[0] * 2) + " wide, "
                + Math.round(tolerance[0] * 2 * geometry[2] * scale[0]) + " device px); latency " + Integer.getInteger("uc.lock.latency", 0) + " ticks");
        });

        // 1. slide across the bar like a hand: a smooth start and stop over six seconds
        final int[] t = {0};
        final double[] expected = {0};
        final double[] maxGap = {0}, maxStep = {0}, lastPosition = {-1};
        final int[] reversals = {0}, feelChanges = {0}, lastFeel = {-1};
        Playtest.queue("put the pointer at the left end of the bar", () -> Playtest.moveGui(geometry[0], geometry[1] + 10));
        Playtest.wait("the pointer arrives", 15);
        Playtest.queue("slide the pointer across the bar", () -> { }, () -> {
            var mc = Minecraft.getInstance();
            if (!(mc.screen instanceof LockpickScreen screen)) return true;
            t[0]++;
            double x = t[0] / 120.0, eased = x * x * (3 - 2 * x);
            int deviceX = (int) Math.round((geometry[0] + eased * geometry[2]) * scale[0]);
            Playtest.input("move " + deviceX + " " + Math.round((geometry[1] + 10) * scale[0]));
            double position = screen.debugPosition();
            if (lastPosition[0] >= 0) {
                if (position < lastPosition[0] - 0.0005) reversals[0]++;
                maxStep[0] = Math.max(maxStep[0], Math.abs(position - lastPosition[0]));
            }
            lastPosition[0] = position;
            expected[0] = ((double) deviceX / scale[0] - geometry[0]) / geometry[2];
            if (lastFeel[0] >= 0 && screen.debugShownFeel() != lastFeel[0]) feelChanges[0]++;
            lastFeel[0] = screen.debugShownFeel();
            return t[0] >= 120;
        }, 400);
        Playtest.wait("the last events arrive", 6);
        Playtest.queue("the slide's numbers", () -> {
            var screen = (LockpickScreen) Minecraft.getInstance().screen;
            double gap = Math.abs(screen.debugPosition() - expected[0]);
            Playtest.log("LOCKJITTER slide: the pick ended " + String.format(java.util.Locale.ROOT, "%.4f", gap) + " of the bar (" + String.format(java.util.Locale.ROOT, "%.1f", gap * geometry[2] * scale[0]) + " device px) from where the pointer is; "
                + reversals[0] + " reversals while the pointer only moved forward; largest step in one tick " + String.format(java.util.Locale.ROOT, "%.4f", maxStep[0]) + " of the bar; the reading changed " + feelChanges[0] + " times in six seconds");
            if (gap > 0.004) Playtest.fail("the pick is not where the pointer is after a slide: " + gap);
            if (reversals[0] > 0) Playtest.fail("the pick moved backwards while the pointer moved forward " + reversals[0] + " times");
            if (feelChanges[0] > 12) Playtest.fail("the reading changed " + feelChanges[0] + " times in one slide across the bar");
        });

        // 2. hold still at the very edge of the close band: the reading must not flicker
        final int[] still = {0}, stillChanges = {0}, stillFeel = {-1};
        Playtest.queue("rest the pick at the edge of the close band", () -> {
            double edge = Math.min(0.99, sweet[0] + tolerance[0] * 2.6 + 0.002);
            if (edge >= 0.99) edge = Math.max(0.01, sweet[0] - tolerance[0] * 2.6 - 0.002);
            Playtest.input("move " + Math.round((geometry[0] + edge * geometry[2]) * scale[0]) + " " + Math.round((geometry[1] + 10) * scale[0]));
        });
        Playtest.wait("settle", 10);
        Playtest.queue("hold still for four seconds, counting how often the reading changes", () -> { }, () -> {
            if (!(Minecraft.getInstance().screen instanceof LockpickScreen screen)) return true;
            if (stillFeel[0] >= 0 && screen.debugShownFeel() != stillFeel[0]) stillChanges[0]++;
            stillFeel[0] = screen.debugShownFeel();
            return ++still[0] >= 80;
        }, 200);
        Playtest.queue("the stillness numbers", () -> {
            Playtest.log("LOCKJITTER still: at the edge of the close band the reading changed " + stillChanges[0] + " times in four seconds");
            if (stillChanges[0] > 0) Playtest.fail("the reading changed " + stillChanges[0] + " times while the pick was held still");
        });

        // 3. press on the spot: the needle wobbles with the hand's tremor, and the pin sets after the hold
        final int[] pressed = {0}, pinSetAfter = {-1};
        final float[] wobbleMin = {0}, wobbleMax = {0};
        Playtest.queue("move onto the sweet spot and press", () -> {
            Playtest.input("move " + Math.round((geometry[0] + sweet[0] * geometry[2]) * scale[0]) + " " + Math.round((geometry[1] + 10) * scale[0]));
        });
        Playtest.wait("settle", 12);
        Playtest.queue("press and hold", () -> Playtest.mouseButton(1, true), () -> {
            if (!(Minecraft.getInstance().screen instanceof LockpickScreen screen)) return true;
            pressed[0]++;
            wobbleMin[0] = Math.min(wobbleMin[0], screen.debugWobblePixels());
            wobbleMax[0] = Math.max(wobbleMax[0], screen.debugWobblePixels());
            if (pinSetAfter[0] < 0 && screen.debugPinsSet() > 0) pinSetAfter[0] = pressed[0];
            return pinSetAfter[0] >= 0 || pressed[0] >= 120;
        }, 300);
        Playtest.queue("let go", () -> Playtest.mouseButton(1, false));
        Playtest.queue("the press numbers", () -> {
            Playtest.log("LOCKJITTER press: pin 1 set " + (pinSetAfter[0] < 0 ? "never (in 6 s)" : pinSetAfter[0] + " ticks after the press began (the hold takes 8; " + Integer.getInteger("uc.lock.latency", 0) + " ticks of latency were played)")
                + "; the needle wobbled between " + String.format(java.util.Locale.ROOT, "%.1f", wobbleMin[0]) + " and " + String.format(java.util.Locale.ROOT, "%.1f", wobbleMax[0]) + " GUI px while pressed");
            if (pinSetAfter[0] < 0) Playtest.fail("pressing on the sweet spot did not set the pin");
        });
        Playtest.queue("screenshot of the lock", () -> Playtest.screenshotNextFrame("uc-lockjitter-" + Minecraft.getInstance().getWindow().getScreenWidth() + "x" + Minecraft.getInstance().getWindow().getScreenHeight() + "-gui" + (int) scale[0]));
        Playtest.wait("frame", 8);
        Playtest.queue("close the lock screen", () -> { if (Minecraft.getInstance().screen instanceof LockpickScreen) Playtest.tap("escape"); });
        Playtest.wait("settle", 10);
    }

    /** One try: stand three blocks from the door, hold W, right-click it every few ticks for three and a half seconds, then read what the server says. */
    private static void walkIntoDoor(DoorWalk.Spot[] spots, int index, boolean armed) {
        String label = (index == 0 ? "street" : "room") + "-" + (armed ? "pick" : "bare");
        final int[] ticks = {0};
        final boolean[] screen = {false};
        Playtest.queue("[" + label + "] stand in front of the door", () -> {
            DoorWalk.Spot spot = spots[index];
            if (spot == null) return;
            var mc = Minecraft.getInstance();
            var server = mc.getSingleplayerServer();
            server.execute(() -> DoorWalk.prepare(server.getPlayerList().getPlayer(mc.player.getUUID()), spot, armed));
        }, () -> {
            DoorWalk.Spot spot = spots[index];
            if (spot == null) return true;
            var mc = Minecraft.getInstance();
            return mc.player.position().distanceTo(spot.stand()) < 1.5 && mc.level.getBlockState(spot.door()).getBlock() instanceof net.minecraft.world.level.block.DoorBlock;
        }, 600);
        Playtest.wait("[" + label + "] settle", 25);
        Playtest.queue("[" + label + "] hold W and right-click the door", () -> {
            if (spots[index] == null) return;
            if (index == 0 && !armed) Playtest.focusWindow();   // later tries must not warp the pointer: a warp over a grabbed cursor turns the camera
            Playtest.keyDown("w");
        }, () -> {
            if (spots[index] == null) return true;
            var mc = Minecraft.getInstance();
            ticks[0]++;
            mc.player.setYRot(spots[index].yaw());   // the door stays in the crosshair, as a person aiming at it would keep it
            mc.player.setXRot(0);
            if (mc.screen instanceof LockpickScreen) { screen[0] = true; return true; }
            // with a pick the first click would start the lock three blocks away, so the clicking starts when the player is standing against the door (still holding W): that is the case the playtest found
            boolean against = spots[index].past(mc.player.position()) > -1.0;
            if (!armed || against) {
                if (ticks[0] % 4 == 1) Playtest.mouseButton(3, true);
                else if (ticks[0] % 4 == 2) Playtest.mouseButton(3, false);
            }
            if (ticks[0] == 12) Playtest.screenshotNextFrame("uc-doorwalk-" + label);
            return ticks[0] >= 70;
        }, 300);
        Playtest.queue("[" + label + "] let go", () -> { Playtest.keyUp("w"); Playtest.mouseButton(3, false); });
        Playtest.wait("[" + label + "] let the server settle", 10);
        Playtest.queue("[" + label + "] the server's account", () -> {
            DoorWalk.Spot spot = spots[index];
            if (spot == null) return;
            var mc = Minecraft.getInstance();
            var server = mc.getSingleplayerServer();
            var player = server.getPlayerList().getPlayer(mc.player.getUUID());
            double past = spot.past(player.position());
            boolean open = DoorWalk.doorOpen(server.overworld(), spot);
            boolean walkedIn = past > 0.6;
            Playtest.log("DOORWALK " + label + ": door facing " + spot.facing() + ", " + (spot.frontIsStreet() ? "front on the street" : "front on the room side") + "; after " + ticks[0] + " ticks the player is "
                + String.format(java.util.Locale.ROOT, "%.2f", past) + " blocks past the door's centre (server), the door is " + (open ? "OPEN" : "closed") + ", the lock screen " + (screen[0] ? "opened" : "did not open") + ", client sees the door " + (mc.level.getBlockState(spot.door()).getValue(net.minecraft.world.level.block.DoorBlock.OPEN) ? "open" : "closed"));
            if (walkedIn) Playtest.fail(label + ": the player walked into the building through a locked door");
            if (open) Playtest.fail(label + ": the locked door stood open at the end");
            if (armed && !screen[0] && !open) Playtest.fail(label + ": with a lockpick the click did not start the lock");
            if (!armed && screen[0]) Playtest.fail(label + ": the lock screen opened without a lockpick");
        });
        Playtest.queue("[" + label + "] close the lock screen", () -> { if (Minecraft.getInstance().screen instanceof LockpickScreen) Playtest.tap("escape"); });
        Playtest.wait("[" + label + "] settle", 15);
    }

    /** The other way: somebody inside stands against the same door, holds W and right-clicks. They must get out, and the door must be shut again a few seconds later. */
    private static void walkOutOfDoor(DoorWalk.Spot[] spots, int index) {
        String label = (index == 0 ? "street" : "room") + "-exit";
        final int[] ticks = {0};
        Playtest.queue("[" + label + "] stand inside, against the door, facing out", () -> {
            DoorWalk.Spot spot = spots[index];
            if (spot == null) return;
            var mc = Minecraft.getInstance();
            var server = mc.getSingleplayerServer();
            Vec3 at = BuildingSecurityGameTests.flush(spot.door(), spot.facing(), false);
            float yaw = (float) Math.toDegrees(Math.atan2(-spot.out().getStepX(), spot.out().getStepZ()));
            server.execute(() -> {
                var player = server.getPlayerList().getPlayer(mc.player.getUUID());
                DoorWalk.prepare(player, spot, false);
                player.teleportTo(server.overworld(), at.x, spot.door().getY(), at.z, yaw, 0f);
            });
        }, () -> {
            DoorWalk.Spot spot = spots[index];
            if (spot == null) return true;
            var mc = Minecraft.getInstance();
            return spot.past(mc.player.position()) > 0.0 && mc.player.position().distanceTo(Vec3.atBottomCenterOf(spot.door())) < 2.0;
        }, 600);
        Playtest.wait("[" + label + "] settle", 25);
        Playtest.queue("[" + label + "] hold W and right-click the door from the inside", () -> {
            if (spots[index] != null) Playtest.keyDown("w");
        }, () -> {
            DoorWalk.Spot spot = spots[index];
            if (spot == null) return true;
            var mc = Minecraft.getInstance();
            ticks[0]++;
            mc.player.setYRot((float) Math.toDegrees(Math.atan2(-spot.out().getStepX(), spot.out().getStepZ())));
            mc.player.setXRot(0);
            if (ticks[0] % 4 == 1) Playtest.mouseButton(3, true);
            else if (ticks[0] % 4 == 2) Playtest.mouseButton(3, false);
            return ticks[0] >= 60;
        }, 300);
        Playtest.queue("[" + label + "] let go", () -> { Playtest.keyUp("w"); Playtest.mouseButton(3, false); });
        Playtest.queue("[" + label + "] the server's account of the way out", () -> {
            DoorWalk.Spot spot = spots[index];
            if (spot == null) return;
            var mc = Minecraft.getInstance();
            var server = mc.getSingleplayerServer();
            var player = server.getPlayerList().getPlayer(mc.player.getUUID());
            double past = spot.past(player.position());
            boolean open = DoorWalk.doorOpen(server.overworld(), spot);
            Playtest.log("DOORWALK " + label + ": the player is " + String.format(java.util.Locale.ROOT, "%.2f", past) + " blocks past the door's centre toward the street side (negative: outside), the door is " + (open ? "open" : "closed"));
            if (past > -1.0) Playtest.fail(label + ": somebody inside could not get out through their own door");
        });
        Playtest.wait("[" + label + "] the door's few seconds", 160);
        Playtest.queue("[" + label + "] the door shut behind them", () -> {
            DoorWalk.Spot spot = spots[index];
            if (spot == null) return;
            var server = Minecraft.getInstance().getSingleplayerServer();
            boolean open = DoorWalk.doorOpen(server.overworld(), spot);
            Playtest.log("DOORWALK " + label + ": eight seconds later the door is " + (open ? "OPEN" : "closed"));
            if (open) Playtest.fail(label + ": the door that somebody left through stood open eight seconds later");
        });
    }

    /** The first-minutes checks: dry, open ground, near the layout's landing anchor, with Founders Row built beside it. */
    private static void checkArrival() {
        var mc = Minecraft.getInstance();
        BlockPos feet = mc.player.blockPosition();
        var anchor = WorldLayout.INSTANCE.arrivalAnchor();
        BlockPos row = DistrictBuilder.originOf(level(), "founders_row");
        Playtest.log("arrived at " + feet.toShortString() + ", landing anchor " + anchor.map(BlockPos::toShortString).orElse("none") + ", Founders Row origin " + (row == null ? "none" : row.toShortString()));
        if (anchor.isEmpty()) { Playtest.fail("the world has no landing (run with -PucWorldLayout=archipelago)"); return; }
        if (!mc.level.getFluidState(feet.below()).isEmpty() || !mc.level.getFluidState(feet).isEmpty()) Playtest.fail("the player arrived in or on water");
        if (!mc.level.canSeeSky(feet)) Playtest.fail("the player arrived under cover");
        double fromAnchor = Math.hypot(feet.getX() - anchor.get().getX(), feet.getZ() - anchor.get().getZ());
        if (fromAnchor > 120) Playtest.fail("the player arrived " + Math.round(fromAnchor) + " blocks from the landing anchor");
        if (row == null) { Playtest.fail("Founders Row was not built at the landing"); return; }
        double fromRow = Math.hypot(feet.getX() - row.getX(), feet.getZ() - row.getZ());
        Playtest.log("the arrival is " + Math.round(fromRow) + " blocks from the Row's origin");
        if (fromRow > 90) Playtest.fail("Founders Row is " + Math.round(fromRow) + " blocks from the arrival");
    }

    /** The title of the next step of the first hours, as the server computes it for the real player. */
    private static void expectStep(String when, String expected) {
        var mc = Minecraft.getInstance();
        var player = mc.getSingleplayerServer().getPlayerList().getPlayer(mc.player.getUUID());
        var objective = Journey.next(player);
        String title = objective == null ? "(finished)" : objective.title();
        Playtest.log("next step " + when + ": " + title);
        if (!title.equals(expected)) Playtest.fail("the next step " + when + " is '" + title + "', not '" + expected + "'");
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
    static boolean groundBelowPlayer() {
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
    static void standLooking(Vec3 at, Vec3 target) {
        Vec3 delta = target.subtract(at.add(0, 1.62, 0)); // the view starts at the eyes, not the feet
        float yaw = (float) Math.toDegrees(Math.atan2(-delta.x, delta.z));
        float pitch = (float) -Math.toDegrees(Math.atan2(delta.y, Math.hypot(delta.x, delta.z)));
        Playtest.command(String.format(java.util.Locale.ROOT, "tp @s %.2f %.2f %.2f %.1f %.1f", at.x, at.y, at.z, yaw, pitch));
    }

    /** The district whose citizens {@link #citizen} and {@link #talkTo} look up: Founders Row unless a scenario (the wayside shelter) says otherwise. */
    private static String talkDistrict = "founders_row";
    /** Set for a district that stands in many places (the wayside shelters): the citizen talked to is the one nearest here, not the first the world lists. */
    private static BlockPos talkNear;

    private static net.minecraft.world.entity.npc.Villager citizen(BlockPos origin, int index) {
        var uuids = DistrictBuilder.citizensForTest(level(), talkDistrict);
        BlockPos near = origin != null ? origin : talkNear;
        if (near != null) {
            net.minecraft.world.entity.npc.Villager best = null;
            for (var uuid : uuids) {
                if (level().getEntity(uuid) instanceof net.minecraft.world.entity.npc.Villager villager
                    && (best == null || villager.distanceToSqr(Vec3.atCenterOf(near)) < best.distanceToSqr(Vec3.atCenterOf(near)))) best = villager;
            }
            return best;
        }
        var entity = level().getEntity(uuids.get(index));
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

    /** The first operation of a kind in Founders Row's data (an employer's terminal, the secure terminal, the Public Ledger...). */
    private static DistrictCatalog.Op rowOp(String kind) {
        return DistrictCatalog.INSTANCE.find("founders_row").ops().stream().filter(op -> op.kind().equals(kind)).findFirst().orElseThrow();
    }

    private static BlockPos secureCell(BlockPos row) { return row.offset(rowOp("secure_terminal").a()); }

    /**
     * Where to stand to speak to a citizen: the nearest of a few spots around them (toward the lane first, then every side, near then a little farther)
     * where there is room for a player and nothing solid between the player's eyes and the citizen's head. A desk, a counter or a wall decides which.
     */
    private static Vec3 standingSpot(net.minecraft.world.entity.npc.Villager villager) {
        BlockPos row = DistrictBuilder.originOf(level(), "founders_row"); // away from Founders Row (a wayside shelter) there is no lane: try the east side first
        double laneX = row == null ? villager.getX() + 1 : Vec3.atLowerCornerOf(row).x + 16.5;
        double toward = Math.signum(laneX - villager.getX()) == 0 ? 1 : Math.signum(laneX - villager.getX());
        double[][] offsets = {{toward * 1.5, 0}, {-toward * 1.5, 0}, {0, 1.5}, {0, -1.5}, {toward * 2.2, 0}, {-toward * 2.2, 0}, {0, 2.2}, {0, -2.2},
            {toward * 1.5, 1.5}, {toward * 1.5, -1.5}, {-toward * 1.5, 1.5}, {-toward * 1.5, -1.5}};
        Vec3 head = villager.position().add(0, villager.getBbHeight() * 0.85, 0);
        for (double[] offset : offsets) {
            Vec3 spot = villager.position().add(offset[0], 0, offset[1]);
            BlockPos feet = BlockPos.containing(spot);
            boolean room = level().getBlockState(feet).getCollisionShape(level(), feet).isEmpty()
                && level().getBlockState(feet.above()).getCollisionShape(level(), feet.above()).isEmpty()
                && !level().getBlockState(feet.below()).getCollisionShape(level(), feet.below()).isEmpty();
            if (!room) continue;
            Vec3 eye = spot.add(0, 1.62, 0);
            var hit = level().clip(new net.minecraft.world.level.ClipContext(eye, head, net.minecraft.world.level.ClipContext.Block.COLLIDER, net.minecraft.world.level.ClipContext.Fluid.NONE, villager));
            if (hit.getType() == net.minecraft.world.phys.HitResult.Type.MISS) return spot;
        }
        return villager.position().add(toward * 1.5, 0, 0);
    }

    /**
     * Speaks to a citizen with a real right click while their AI is running: they walk, so each attempt stands a step in front of where they are now,
     * aims at their head, presses, and looks at what opened. A miss is simply tried again (up to six times) from wherever they have got to.
     * {@code expect} says what should open: "dialogue", "registration", "terminal" or "ledger".
     */
    private static void talkTo(String label, int index, String expect, String shot) {
        talkTo(label, () -> citizen(null, index), expect, shot);
    }

    /** As above, for a citizen found by what they do (the way the guided journey finds them), not by their place in the district's list. */
    private static void talkTo(String label, java.util.function.Supplier<net.minecraft.world.entity.npc.Villager> who, String expect, String shot) {
        final int[] phase = {0}, tick = {0}, attempts = {0};
        final Vec3[] stand = new Vec3[1];
        Playtest.queue("talk to " + label + " (expecting " + expect + ")", () -> Playtest.focusWindow(), () -> {
            var mc = Minecraft.getInstance();
            if (matches(mc.screen, expect)) return true;
            switch (phase[0]) {
                case 0 -> {
                    var villager = who.get();
                    if (villager == null) { Playtest.fail(label + " is not in the world"); return true; }
                    Vec3 where = standingSpot(villager);
                    stand[0] = where;
                    standLooking(where, villager.position().add(0, villager.getBbHeight() * 0.85, 0));
                    tick[0] = 0; phase[0] = 1;
                }
                case 1 -> { if (++tick[0] >= 3) { var v = who.get(); if (v != null) standLooking(stand[0], v.position().add(0, v.getBbHeight() * 0.85, 0)); Playtest.mouseButton(3, true); tick[0] = 0; phase[0] = 2; } }
                case 2 -> { if (++tick[0] >= 3) { Playtest.mouseButton(3, false); tick[0] = 0; phase[0] = 3; } }
                default -> {
                    if (++tick[0] >= 14) {
                        if (++attempts[0] > 6) { Playtest.fail(label + ": nothing opened after six attempts, screen=" + mc.screen + ", cursor " + describeCursor()); return true; }
                        if (mc.screen != null && !(mc.screen instanceof net.minecraft.client.gui.screens.ChatScreen)) mc.setScreen(null);
                        phase[0] = 0;
                    }
                }
            }
            return false;
        }, 900);
        Playtest.wait("let the screen draw", 16);
        if (shot != null) Playtest.queue("screenshot " + shot, () -> Playtest.screenshotNextFrame(shot));
        Playtest.wait("frame", 8);
    }

    private static boolean matches(net.minecraft.client.gui.screens.Screen screen, String expect) {
        return switch (expect) {
            case "dialogue" -> screen instanceof DialogueScreen;
            case "registration" -> screen instanceof RegistrationScreen;
            case "terminal" -> screen instanceof TerminalScreen;
            case "ledger" -> screen instanceof LedgerScreen;
            default -> false;
        };
    }

    /** Clicks the n-th choice of the open conversation with the mouse, then waits (up to 20 seconds) for what should open, or a short while when nothing should. */
    private static void chooseOption(String label, int choice, String expectNext, String shot) {
        Playtest.queue("choose '" + label + "'", () -> {
            if (!(Minecraft.getInstance().screen instanceof DialogueScreen screen)) { Playtest.fail("choose " + label + ": no conversation is open"); return; }
            int[] at = screen.choiceCentre(choice);
            Playtest.log("clicking '" + label + "' at GUI " + at[0] + "," + at[1] + " of " + screen.width + "x" + screen.height);
            Playtest.moveGui(at[0], at[1]);
        });
        Playtest.wait("the pointer settles on '" + label + "'", 6);
        Playtest.queue("press '" + label + "'", Playtest::pressLeft);
        final int[] waited = {0};
        Playtest.queue("the answer to '" + label + "' arrives", () -> { }, () -> {
            waited[0]++;
            if (expectNext != null) return matches(Minecraft.getInstance().screen, expectNext);
            return waited[0] >= 30;
        }, 400);
        Playtest.wait("let it draw", 12);
        if (shot != null) Playtest.queue("screenshot " + shot, () -> Playtest.screenshotNextFrame(shot));
        Playtest.wait("frame", 8);
        if (expectNext != null) Playtest.queue("what opened after '" + label + "'", () -> {
            if (!matches(Minecraft.getInstance().screen, expectNext)) Playtest.fail("after '" + label + "' expected " + expectNext + " but the screen is " + Minecraft.getInstance().screen);
        });
    }

    /** Leaves whatever screen is open with Escape and waits until none is. */
    private static void closeScreen(String label) {
        Playtest.queue("close " + label + " with Escape", () -> Playtest.tap("escape"), () -> Minecraft.getInstance().screen == null, 100);
    }

    /** A camera in the world: the eye at {@code e}, looking at {@code t}, both relative to Founders Row's north-west corner; then a frame is taken. */
    private static void view(String name, double ex, double ey, double ez, double tx, double ty, double tz) {
        Playtest.queue("camera for " + name, () -> {
            Vec3 o = Vec3.atLowerCornerOf(DistrictBuilder.originOf(level(), "founders_row"));
            standLooking(o.add(ex, ey - 1.62, ez), o.add(tx, ty, tz));
        });
        Playtest.wait("settle " + name, 22);
        Playtest.queue("photograph " + name, () -> Playtest.screenshotNextFrame("uc-row-" + name));
        Playtest.wait("frame " + name, 8);
    }

    /** Checks that a citizen being spoken to stands still, and that they are free to walk again once the conversation is over. */
    private static void checkHold(String label, int index) {
        final Vec3[] at = new Vec3[1];
        Playtest.queue(label + ": note where they stand while we talk", () -> { var v = citizen(null, index); at[0] = v == null ? null : v.position(); });
        Playtest.wait("keep talking", 60);
        Playtest.queue(label + ": they have not wandered off mid-conversation", () -> {
            var v = citizen(null, index);
            if (v == null || at[0] == null) { Playtest.fail(label + " vanished"); return; }
            double moved = v.position().distanceTo(at[0]);
            Playtest.log(label + " moved " + String.format("%.2f", moved) + " blocks in three seconds of conversation; trading partner " + level().getEntity(v.getUUID()));
            if (moved > 1.2) Playtest.fail(label + " walked " + String.format("%.1f", moved) + " blocks away while being spoken to");
        });
    }

    private static void checkReleased(String label, int index) {
        Playtest.wait("let the server hear the screen close", 30);
        Playtest.queue(label + ": released after the conversation", () -> {
            var villager = citizen(null, index);
            if (villager == null) { Playtest.fail(label + " vanished"); return; }
            var real = level().getEntity(villager.getUUID());
            boolean held = real instanceof net.minecraft.world.entity.npc.Villager v && v.getTradingPlayer() != null;
            Playtest.log(label + " still held after the conversation: " + held);
            if (held) Playtest.fail(label + " is still held after the conversation ended");
        });
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
        Playtest.queue("build the firing range beside it", () -> { }, () -> {
            if (DistrictBuilder.isBuilt(level(), "firing_range")) return true;
            if (attempts[0]++ % 40 == 0) {
                var row = DistrictBuilder.originOf(level(), "founders_row");
                var range = DistrictCatalog.INSTANCE.find("firing_range");
                int width = range.max().getX() - range.min().getX() + 1;
                // the same place the dev launch puts it: the range's north-west corner is one range-width west of the Row's and four blocks south
                Playtest.command("tp @s " + (row.getX() - width) + " " + row.getY() + " " + (row.getZ() + 4));
                Playtest.command("uc_district build firing_range");
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
        Playtest.queue("log where the Row stands", () -> {
            var row = DistrictBuilder.originOf(level(), "founders_row");
            var range = DistrictBuilder.originOf(level(), "firing_range");
            Playtest.log("Founders Row origin " + row + ", firing range origin " + range);
        });
        // The citizens keep their AI for the whole run: they walk, work and gather at the bell, and every conversation below is with someone who is moving.

        // ---- A free camera (spectator: no collisions, no hand) over the district: the whole, the street, the buildings and what is inside them.
        if (!"false".equals(System.getProperty("uc.playtest.views"))) rowViews();
        rowPeople();
    }

    private static void rowViews() {
        Playtest.queue("spectator camera, no HUD", () -> { Playtest.command("gamemode spectator"); Minecraft.getInstance().options.hideGui = true; });
        Playtest.queue("time of day: noon", () -> { Playtest.command("time set 6000"); Playtest.command("gamerule doDaylightCycle false"); Playtest.command("weather clear"); Playtest.command("gamerule sendCommandFeedback false"); });
        waitForMeshes(1500);
        view("overview-nw", -9, 24, -16, 16, 0, 22);
        view("overview-ne", 42, 22, -14, 16, 0, 22);
        view("gate-north", 16.5, 5, 58, 16.5, 2, 26);
        view("lane-eye-level", 16.5, 1.7, 45, 16.5, 2.2, 10);
        view("north-terrace", 16.5, 5, 18, 16.5, 1.5, 2);
        view("plaza-wide", 16.5, 7, 40, 16.5, 1, 25);
        view("plaza-bell", 10.5, 1.8, 19, 17, 1.6, 26);
        view("ledger", 15.5, 1.9, 25.5, 11.5, 1.2, 25.5);
        view("stall-baker", 17.2, 2.1, 22, 21.5, 1.4, 22.5);
        view("stall-fish", 17.2, 2.1, 28, 21.5, 1.4, 28.5);
        view("gunsmith-front", 14.5, 2.6, 12, 8, 2, 11);
        view("foundry-front", 14.5, 2.6, 20, 8, 3, 19);
        view("farm-front", 18.5, 2.6, 12, 24, 2, 11);
        view("registry-front", 18.5, 2.6, 36, 24, 2, 35);
        view("greenhouse", 27.5, 9, 14, 27.5, 1, 3);
        view("network-office", 20.5, 2.2, 8.5, 20.5, 1.6, 2);
        view("gunsmith-inside", 7.2, 2.3, 12.4, 3, 1, 9.5);
        view("foundry-inside", 7.2, 2.3, 20.4, 3, 1, 17.5);
        view("farm-inside", 25.2, 2.3, 12.4, 29, 1, 9.5);
        view("registry-inside", 25.2, 2.3, 36.2, 29, 1, 34);
        view("cottage-inside", 7.2, 2.3, 28.4, 3, 1, 25);
        view("cellar-front", 18.5, 2.6, 44, 24, 2, 43);
        view("cellar-inside", 25.2, 2.3, 44.4, 29, 1, 41.5);
        view("vineyard", 12, 3.5, 40.5, 5, 0.6, 43);
        // the firing range, from the line and from above
        Playtest.queue("camera for the firing range", () -> {
            Vec3 o = Vec3.atLowerCornerOf(DistrictBuilder.originOf(level(), "firing_range"));
            standLooking(o.add(38.5, 1.8 - 1.62, 7.5), o.add(5, 1.4, 7.5));
        });
        Playtest.wait("settle", 40);
        Playtest.queue("photograph the range from the line", () -> Playtest.screenshotNextFrame("uc-row-range-line"));
        Playtest.wait("frame", 8);
        Playtest.queue("camera over the range", () -> {
            Vec3 o = Vec3.atLowerCornerOf(DistrictBuilder.originOf(level(), "firing_range"));
            standLooking(o.add(41, 12 - 1.62, 7.5), o.add(15, 0, 7.5));
        });
        Playtest.wait("settle", 40);
        Playtest.queue("photograph the range from above", () -> Playtest.screenshotNextFrame("uc-row-range-above"));
        Playtest.wait("frame", 8);

    }

    private static void rowPeople() {
        // ---- The people, with their AI on. Creative mode so the player can stand anywhere and nothing can hurt them.
        Playtest.queue("creative mode, HUD back on", () -> { Playtest.command("gamemode creative"); Minecraft.getInstance().options.hideGui = false; });
        Playtest.wait("settle", 20);
        int registrar = citizenIndex("registrar", null), gunsmith = citizenIndex("employer", "gunsmith"), farmer = citizenIndex("employer", "farmer"), vintner = citizenIndex("employer", "vintner");
        int vendor = citizenIndex("vendor", null), guard = citizenIndex("guard", null);
        talkTo("the registrar (a newcomer)", registrar, "dialogue", "uc-row-dialogue-registrar");
        checkHold("the registrar", registrar);
        chooseOption("Register me", 0, "registration", "uc-row-register-opens");
        closeScreen("the registration screen");
        checkReleased("the registrar", registrar);
        Playtest.queue("register through the server as a Valewood gunsmith", () -> Playtest.command("uc register valewood gunsmith"));
        Playtest.wait("let the server answer", 30);
        talkTo("the registrar again", registrar, "dialogue", null);
        chooseOption("What should I do next?", 0, "dialogue", "uc-row-dialogue-next-step");
        closeScreen("the conversation");
        talkTo("the farm co-op's employer (not this citizen's trade)", farmer, "dialogue", "uc-row-dialogue-wrong-employer");
        closeScreen("the conversation");
        talkTo("the cellar master (not this citizen's trade)", vintner, "dialogue", "uc-row-dialogue-vintner");
        closeScreen("the conversation");
        // The gunsmith's employer is probably at the bell: asking for her orders sends her back to her desk, and she is held only while we talk.
        talkTo("the gunsmith's employer", gunsmith, "dialogue", "uc-row-dialogue-employer");
        chooseOption("Open my orders", 0, null, "uc-row-dialogue-employer-orders");
        Playtest.queue("if the terminal opened, close it; otherwise close the conversation", () -> { if (Minecraft.getInstance().screen != null) Playtest.tap("escape"); }, () -> Minecraft.getInstance().screen == null, 100);
        final int[] polls = {0};
        Playtest.queue("wait until the gunsmith is at her desk", () -> { }, () -> {
            var villager = citizen(null, gunsmith);
            if (villager == null) return false;
            var real = level().getEntity(villager.getUUID());
            if (!(real instanceof net.minecraft.world.entity.npc.Villager v)) return false;
            var tag = v.getPersistentData().getCompound(CityNpcs.TAG);
            if (!tag.contains("terminal")) return false;
            var terminal = Vec3.atCenterOf(BlockPos.of(tag.getLong("terminal")));
            double distance = Math.hypot(v.getX() - terminal.x, v.getZ() - terminal.z);
            if (polls[0]++ % 100 == 0) Playtest.log("the gunsmith is " + String.format("%.1f", distance) + " blocks from her desk; she is at " + v.position() + ", time of day " + (v.level().getDayTime() % 24000));
            return distance < 6.5;   // inside her office: the desk is at the far wall
        }, 2400);
        talkTo("the gunsmith's employer, at her desk", gunsmith, "dialogue", null);
        chooseOption("Open my orders", 0, "terminal", "uc-row-employer-terminal");
        closeScreen("the employer's terminal");
        talkTo("the market vendor", vendor, "dialogue", "uc-row-dialogue-vendor");
        chooseOption("What is on the exchange today?", 0, "dialogue", "uc-row-dialogue-vendor-stock");
        closeScreen("the conversation");
        talkTo("the gate guard", guard, "dialogue", "uc-row-dialogue-guard");
        chooseOption("What is the law here?", 0, "dialogue", "uc-row-dialogue-guard-law");
        closeScreen("the conversation");
        checkReleased("the gate guard", guard);
        // The Public Ledger: a real right click on the block.
        Playtest.queue("stand at the Public Ledger and use it", () -> {
            var row = DistrictBuilder.originOf(level(), "founders_row");
            BlockPos ledger = row.offset(rowOp("ledger").a());
            standLooking(Vec3.atCenterOf(ledger).add(2.6, -0.8, 0), Vec3.atCenterOf(ledger).add(0, 0.2, 0));
        });
        Playtest.wait("settle", 15);
        Playtest.queue("right-click the ledger", () -> { Playtest.focusWindow(); Playtest.mouseButton(3, true); });
        Playtest.wait("hold", 4);
        Playtest.queue("release", () -> Playtest.mouseButton(3, false), () -> Minecraft.getInstance().screen instanceof LedgerScreen, 120);
        Playtest.wait("let the screen draw", 20);
        Playtest.queue("screenshot of the ledger", () -> Playtest.screenshotNextFrame("uc-row-ledger-screen"));
        Playtest.wait("frame", 8);
        Playtest.queue("the ledger opened", () -> { if (!(Minecraft.getInstance().screen instanceof LedgerScreen)) Playtest.fail("the Public Ledger did not open, screen=" + Minecraft.getInstance().screen); });
        closeScreen("the ledger");
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

    static void unlockWithPrerequisites(CitizenState citizens, java.util.UUID uuid, String node) {
        var skill = SkillCatalog.INSTANCE.find(node);
        for (String parent : skill.requires()) unlockWithPrerequisites(citizens, uuid, parent);
        if (!citizens.find(uuid).orElseThrow().skills().contains(node)) citizens.unlockSkill(uuid, node);
    }

    /** Opens the secure terminal with a real right click from the data office's floor and waits for the trace screen. */
    private static void startTrace(BlockPos[] origin, String label) {
        Playtest.queue(label + ": stand at the secure terminal and use it", () -> {
            // The district's own origin (the platform the test built it on is one block lower than the Row's first layer), read when the step runs.
            BlockPos row = DistrictBuilder.originOf(level(), "founders_row");
            Vec3 terminal = Vec3.atCenterOf(secureCell(row));
            standLooking(Vec3.atLowerCornerOf(secureCell(row).offset(0, 0, 2)).add(0.5, 0, 0.5), terminal.add(0, 0.2, 0));
        });
        Playtest.wait("settle", 15);
        Playtest.queue(label + ": right-click", () -> {
            var mc = Minecraft.getInstance();
            var lvl = level();
            BlockPos row = DistrictBuilder.originOf(lvl, "founders_row");
            Playtest.log("row origin " + row.toShortString() + " (platform " + origin[0].toShortString() + "); terminal cell " + lvl.getBlockState(secureCell(row)).getBlock() + ", below it " + lvl.getBlockState(secureCell(row).below()).getBlock()
                + ", block at the stand point " + lvl.getBlockState(secureCell(row).offset(0, 0, 2)).getBlock());
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
    static void watchTrace(java.util.List<Integer> seen, String label) {
        final int[] previous = {-1};
        Playtest.queue(label + ": watch the pads", () -> { seen.clear(); previous[0] = -1; }, () -> {
            if (!(Minecraft.getInstance().screen instanceof ChallengeScreen screen)) return true;
            int lit = traceLit(screen);
            if (lit >= 0 && previous[0] < 0) seen.add(lit);
            previous[0] = lit;
            return !traceShowing(screen);
        }, 400);
    }

    static void typeTrace(java.util.List<Integer> seen, boolean wrong, String label) {
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

    private static final java.util.concurrent.atomic.AtomicBoolean TERMINAL_LOCKED = new java.util.concurrent.atomic.AtomicBoolean();

    /**
     * Whether the secured terminal is locked down. A block entity can only be read on the server thread (Level.getBlockEntity answers null from any other thread, which
     * made every earlier answer "not locked"), so this asks the server and returns what it said the last time it was asked; polled every tick it is at most a tick or two stale.
     */
    private static boolean terminalLocked(BlockPos platform) {
        var server = Minecraft.getInstance().getSingleplayerServer();
        server.execute(() -> {
            var entity = server.overworld().getBlockEntity(secureCell(DistrictBuilder.originOf(server.overworld(), "founders_row")));
            TERMINAL_LOCKED.set(entity instanceof SecureTerminalBlockEntity terminal && terminal.lockedAt(WorkOrderLedger.get(server).clock()));
        });
        return TERMINAL_LOCKED.get();
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
        Playtest.queue("success: screenshot while the pads flash", () -> Playtest.screenshotNextFrame("uc-trace-showing"));
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
        // The alarm is the server's doing and the server can be seconds behind the client in this container: wait for it instead of asking once.
        Playtest.queue("wrong answer: wait for the alarm", () -> { }, () -> terminalLocked(origin[0]), 600);
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
        Playtest.queue("abandon: wait for the alarm", () -> { }, () -> terminalLocked(origin[0]), 600);
        Playtest.queue("abandon: the alarm tripped", () -> {
            Playtest.log("kits " + inventoryCount("underground_city:bypass_kit") + ", locked " + terminalLocked(origin[0]));
            if (inventoryCount("underground_city:bypass_kit") != 1) Playtest.fail("the kit is spent when the attempt starts");
            if (!terminalLocked(origin[0])) Playtest.fail("walking away should lock the terminal down");
        });

        spendTime("timeout");
        startTrace(origin, "timeout");
        Playtest.queue("timeout: do nothing until the window closes", () -> { }, () -> !(Minecraft.getInstance().screen instanceof ChallengeScreen), 20 * 40);
        Playtest.wait("timeout: the server answers", 30);
        Playtest.queue("timeout: wait for the alarm", () -> { }, () -> terminalLocked(origin[0]), 1200);
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
            if (ride != null) Playtest.screenshotNextFrame("uc-" + label.replace(' ', '-') + "-seated");
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
        if (entity == null) return null;
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
        if (vehicle == null) return "(no vehicle in reach)";
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
        lightenClient();
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
            Playtest.screenshotNextFrame("uc-car-placed");
        });
        Playtest.wait("let the tank update reach the client", 20);
        Playtest.queue("log the fuel state", () -> Playtest.log("after fuelling: " + describeVehicle(ivVehicle(vehicleNear()))));
        board(Scenarios::vehicleNear, () -> roadHeading[0], "car", true);
        final RoadDriver[] driver = new RoadDriver[1];
        Playtest.queue("drive along the road with W, A and D", () -> {
            var plan = roadPlan(road);
            var hit = plan.project(Minecraft.getInstance().player.getX(), Minecraft.getInstance().player.getZ());
            driver[0] = new RoadDriver(RoadDriver.of(plan), hit.s() + driveBlocks[0], "quad on " + road).steerByWheelModel(() -> vehicleMotion(vehicleNear())).cruiseAt(5);
            Playtest.log("driving from s=" + (int) hit.s() + " for " + driveBlocks[0] + " blocks; " + describeVehicle(ivVehicle(vehicleNear())));
        }, () -> driver[0].tick(), 6000);
        Playtest.queue("report the drive", () -> {
            Playtest.log("drive: " + driver[0].summary());
            Playtest.screenshotNextFrame("uc-car-driving");
            if (driver[0].summary().isEmpty()) { driver[0].release(); Playtest.fail("the drive did not finish within its time budget at s=" + (int) driver[0].progress()); }
        });
        Playtest.queue("brake with S and look at the stopping distance", () -> {
            Playtest.keyDown("s");
        });
        Playtest.wait("brake", 80);
        Playtest.queue("release S and screenshot", () -> { Playtest.keyUp("s"); Playtest.log("after braking: " + describeVehicle(ivVehicle(vehicleNear())) + " at " + Minecraft.getInstance().player.position()); Playtest.screenshotNextFrame("uc-car-braked"); });
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
     * The scripted client renders in software and, left alone, draws as many frames as it can, which starves the integrated server of CPU: the server then falls
     * minutes behind, and a vehicle whose controls go to the server and whose physics come back from it cannot be steered at all. A lower frame-rate cap and a
     * shorter view distance give the server its share back; they change how far the screenshots see, not what the game does.
     */
    private static void lightenClient() {
        Playtest.queue("cap the frame rate and shorten the view distance so the integrated server keeps up", () -> {
            var options = Minecraft.getInstance().options;
            options.framerateLimit().set(30);
            options.renderDistance().set(6);
            options.simulationDistance().set(6);
            Playtest.log("frame rate capped at 30, render distance 6, simulation distance 6");
        });
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
                    int cx = net.minecraft.world.level.ChunkPos.getX(packed), cz = net.minecraft.world.level.ChunkPos.getZ(packed);
                    server.overworld().getChunk(cx, cz);
                    // Held loaded: left to itself the server would start unloading and saving all of these the moment the last one is made, right while the vehicle drives.
                    server.overworld().setChunkForced(cx, cz, true);
                }
                if (queue.isEmpty()) finished.set(true); else server.execute(task[0]);
            };
            server.execute(task[0]);
        }, finished::get, 12000);
        // Generating that many chunks occupies the server thread for minutes (it then logs "Can't keep up" and skips ahead), so wait for it to be back to normal pace
        // before a vehicle is placed: a vehicle simulated by an overloaded server cannot be steered.
        Playtest.queue("let the server catch up with its ticks", () -> { }, () -> {
            var server = Minecraft.getInstance().getSingleplayerServer();
            return server != null && net.minecraft.Util.getMillis() - server.getNextTickTime() < 1500;
        }, 12000);
        Playtest.wait("let the server settle after generating", 200);
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
        lightenClient();
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
            Playtest.screenshotNextFrame("uc-boat-placed");
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
            driver[0] = new RoadDriver(route, run[1] - 20, "motorboat under the bridge of " + road).steerByYaw().faceForward().cruiseAt(9)
                .at(run[0] + 40, () -> Playtest.screenshotNextFrame("uc-boat-approach"))
                .at((run[0] + run[1]) / 2, () -> { Playtest.log("under the deck: " + describeBoat(boatNear())); Playtest.screenshotNextFrame("uc-boat-under-bridge"); })
                .at(run[1] - 40, () -> Playtest.screenshotNextFrame("uc-boat-leaving"));
        }, () -> driver[0].tick(), 6000);
        Playtest.queue("report the crossing", () -> {
            Playtest.log("sail: " + driver[0].summary());
            Playtest.screenshotNextFrame("uc-boat-across");
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
    /**
     * The waymarkers (D-078): arrive on a road a little before its first marker, wait for the server to build the post, then photograph its front (the face a
     * traveller heading for the far city reads), its back, and the road with the post in the distance.
     */
    private static net.minecraft.core.Direction dirOf(Vec3 along) { return RoadMarkers.direction(along.x, along.z); }

    private static void markers() {
        final String road = System.getProperty("uc.playtest.route", "ironhaven_valewood");
        final double[] ideal = new double[1];
        final Vec3[] post = new Vec3[1], along = new Vec3[1];
        arriveOnRoadLater(road, () -> {
            var plan = roadPlan(road);
            var all = RoadMarkers.positions(plan, RoadMarkers.SPACING, RoadMarkers.EDGE);
            if (all.isEmpty()) { Playtest.fail("the road has no marker positions"); return 100; }
            ideal[0] = all.get(0);
            return (int) Math.max(0, ideal[0] - 70);
        }, "creative");
        Playtest.queue("the first post is built", () -> { }, () -> PlacedSites.get(level(), PlacedSites.MARKERS).where.containsKey(road + "#0"), 6000);
        Playtest.queue("find the post and the direction of the road", () -> {
            var plan = roadPlan(road);
            BlockPos base = BlockPos.of(PlacedSites.get(level(), PlacedSites.MARKERS).where.get(road + "#0"));
            double[] at = plan.pointAt(ideal[0]), ahead = plan.pointAt(ideal[0] + 3), behind = plan.pointAt(ideal[0] - 3);
            double tx = ahead[0] - behind[0], tz = ahead[1] - behind[1], length = Math.hypot(tx, tz);
            along[0] = new Vec3(tx / length, 0, tz / length);
            post[0] = Vec3.atCenterOf(base);
            Playtest.log("post at " + base.toShortString() + " for " + road + " at " + Math.round(ideal[0]) + " m, road direction " + along[0]);
            // the server's level refuses block-entity reads from this thread: read the sign as the client received it
            var level = Minecraft.getInstance().level;
            var front = level.getBlockEntity(base.above().relative(dirOf(along[0]).getOpposite()));
            var back = level.getBlockEntity(base.above().relative(dirOf(along[0])));
            if (!(front instanceof net.minecraft.world.level.block.entity.SignBlockEntity f) || !(back instanceof net.minecraft.world.level.block.entity.SignBlockEntity b)) {
                for (int dy = -1; dy <= 3; dy++) Playtest.log("  base" + (dy >= 0 ? "+" : "") + dy + ": " + level.getBlockState(base.above(dy)));
                Playtest.fail("no signs on the pillar at " + base.toShortString());
                return;
            }
            Playtest.log("front: " + f.getFrontText().getMessage(0, false).getString() + " | " + f.getFrontText().getMessage(1, false).getString() + " | " + f.getFrontText().getMessage(2, false).getString());
            Playtest.log("back: " + b.getFrontText().getMessage(0, false).getString() + " | " + b.getFrontText().getMessage(1, false).getString() + " | " + b.getFrontText().getMessage(2, false).getString());
            // five blocks back along the road's axis, in front of the sign a traveller heading for the far city reads
            Vec3 axis = Vec3.atLowerCornerOf(dirOf(along[0]).getNormal());
            standLooking(post[0].subtract(axis.scale(5)).add(0, -0.4, 0), post[0].add(0, 0.6, 0).subtract(axis.scale(0.5)));
        });
        Playtest.queue("let the chunks and the post's meshes arrive", () -> { }, () -> groundBelowPlayer(), 6000);
        waitForMeshes(1500);
        Playtest.wait("settle", 120);
        Playtest.queue("screenshot of the front of the post", () -> Playtest.screenshotNextFrame("uc-marker-front"));
        Playtest.wait("frame", 10);
        Playtest.queue("five blocks ahead, in front of the sign for the near city", () -> {
            Vec3 axis = Vec3.atLowerCornerOf(dirOf(along[0]).getNormal());
            standLooking(post[0].add(axis.scale(5)).add(0, -0.4, 0), post[0].add(0, 0.6, 0).add(axis.scale(0.5)));
        });
        Playtest.wait("settle", 60);
        Playtest.queue("screenshot of the back of the post", () -> Playtest.screenshotNextFrame("uc-marker-back"));
        Playtest.wait("frame", 10);
        Playtest.queue("thirty blocks back, on the road, looking along it", () -> {
            Vec3 stand = post[0].subtract(along[0].scale(30)).add(along[0].z * 6.6, 0.1, -along[0].x * 6.6);
            standLooking(stand.add(0, 0, 0), post[0].add(along[0].scale(10)).add(along[0].z * 6.6, 1.0, -along[0].x * 6.6));
        });
        Playtest.wait("settle", 90);
        Playtest.queue("screenshot of the road with the post", () -> Playtest.screenshotNextFrame("uc-marker-road"));
        Playtest.wait("frame", 10);
    }

    /**
     * The wayside shelters (D-079): hop to every shelter's spot on every road and report what the server did (built where, or given up), then, at the first
     * shelter of one road, photograph it from the road, from inside, and talk to the warden: where the road leads, and what the law says at either end.
     */
    private static void shelter() {
        final String road = System.getProperty("uc.playtest.route", "ironhaven_valewood");
        final int stop = Integer.getInteger("uc.playtest.stop", 0); // which of the road's stops to photograph (the kinds alternate along a road)
        final double[] ideal = new double[1];
        final Vec3[] centre = new Vec3[1], toRoad = new Vec3[1];
        final BlockPos[] origin = new BlockPos[1];
        talkDistrict = WaysideShelters.kindOf(road, stop);
        Playtest.queue("what the world already remembers of the stops", () -> {
            var state = PlacedSites.get(level(), PlacedSites.SHELTERS);
            Playtest.log("[UC-SHELTER-STATE] decided " + state.done.size() + ", built " + state.where.size() + ": " + new java.util.TreeMap<>(state.where).keySet()
                + "; districts standing: " + WaysideShelters.KINDS.stream().map(kind -> kind + " x" + DistrictBuilder.count(level(), kind)).toList());
        });
        // 1. the survey: how many shelters each road should have, and which of them found ground
        String surveyed = System.getProperty("uc.playtest.roads", "ironhaven_valewood,ironhaven_blackwater,valewood_blackwater"); // empty: skip the survey
        for (String each : surveyed.isBlank() ? new String[0] : surveyed.split(",")) {
            for (int index = 0; index < 4; index++) {
                final int i = index;
                final String key = each + "#" + i;
                java.util.function.Supplier<java.util.List<Double>> all = () -> {
                    try { return WaysideShelters.positions(roadPlan(each), WaysideShelters.MAX_GAP, WaysideShelters.EDGE); } catch (RuntimeException absent) { return java.util.List.of(); }
                };
                arriveOnRoadLater(each, () -> all.get().size() > i ? (int) Math.max(0, all.get().get(i) - 30) : 100, "creative");
                Playtest.queue("shelter " + key + " is decided", () -> { }, () -> all.get().size() <= i || PlacedSites.get(level(), PlacedSites.SHELTERS).done.contains(key), 3000);
                Playtest.queue("report on " + key, () -> {
                    var spots = all.get();
                    if (spots.size() <= i) return;
                    var plan = roadPlan(each);
                    var state = PlacedSites.get(level(), PlacedSites.SHELTERS);
                    Long where = state.where.get(key);
                    Playtest.log("[UC-SHELTER] " + key + " (open road " + Math.round(RoadMarkers.firstOpen(plan)) + " to " + Math.round(RoadMarkers.lastOpen(plan)) + " m, ideal spot "
                        + Math.round(spots.get(i)) + " m of " + spots.size() + "): " + (where != null ? "built at " + BlockPos.of(where).toShortString() : state.done.contains(key) ? "GIVEN UP, no suitable ground" : "NOT DECIDED"));
                });
            }
        }
        // 2. the first shelter of one road, up close
        arriveOnRoadLater(road, () -> {
            var all = WaysideShelters.positions(roadPlan(road), WaysideShelters.MAX_GAP, WaysideShelters.EDGE);
            if (all.isEmpty()) { Playtest.fail("the road has no shelter positions"); return 100; }
            ideal[0] = all.get(Math.min(stop, all.size() - 1));
            return (int) Math.max(0, ideal[0] - 30);
        }, "creative");
        Playtest.queue("shelter " + stop + " of " + road + " is built", () -> { }, () -> {
            var state = PlacedSites.get(level(), PlacedSites.SHELTERS);
            if (state.done.contains(road + "#" + stop) && !state.where.containsKey(road + "#" + stop)) Playtest.fail("the shelter was given up: no suitable ground near " + Math.round(ideal[0]) + " m");
            return state.where.containsKey(road + "#" + stop);
        }, 6000);
        Playtest.queue("noon, clear, no command feedback in the pictures", () -> {
            Playtest.command("time set 6000"); Playtest.command("gamerule doDaylightCycle false"); Playtest.command("weather clear"); Playtest.command("gamerule sendCommandFeedback false");
        });
        Playtest.queue("find the shelter and the road beside it", () -> {
            origin[0] = BlockPos.of(PlacedSites.get(level(), PlacedSites.SHELTERS).where.get(road + "#" + stop));
            talkNear = origin[0].offset(5, 0, 4);
            var warden = citizen(null, 0);
            if (warden == null) { Playtest.fail("the shelter's warden is not in the world"); return; }
            Playtest.log("the warden stands at " + warden.blockPosition().toShortString() + " (the lot's corner is " + origin[0].toShortString() + ")");
            centre[0] = Vec3.atLowerCornerOf(origin[0]).add(5.5, 0, 4.5);
            var plan = roadPlan(road);
            double[] near = plan.pointAt(plan.project(centre[0].x, centre[0].z).s());
            Vec3 away = new Vec3(near[0] - centre[0].x, 0, near[1] - centre[0].z);
            toRoad[0] = away.scale(1.0 / Math.max(1e-6, away.length()));
            Playtest.log("shelter at " + origin[0].toShortString() + " for " + road + " at " + Math.round(ideal[0]) + " m, " + Math.round(away.length()) + " blocks from the road");
            // A free camera (spectator: no ground needed, no hand, no HUD): the ground outside the lot may be a bank or a ditch, and the views are wide enough to show all of it.
            Playtest.command("gamemode spectator");
            Minecraft.getInstance().options.hideGui = true;
            standLooking(centre[0].add(toRoad[0].scale(13)).add(0, 2.4, 0), centre[0].add(0, 1.4, 0));
        });
        Playtest.queue("let the chunks and the shelter's meshes arrive", () -> { }, () -> Minecraft.getInstance().level != null && Minecraft.getInstance().level.hasChunkAt(Minecraft.getInstance().player.blockPosition()), 6000);
        waitForMeshes(1500);
        Playtest.wait("settle", 120);
        Playtest.queue("screenshot from the road", () -> Playtest.screenshotNextFrame("uc-shelter-road"));
        Playtest.wait("frame", 10);
        Playtest.queue("a corner of the lot, looking in", () -> {
            standLooking(centre[0].subtract(toRoad[0].scale(13)).add(0, 2.4, 0), centre[0].add(0, 1.4, 0));
        });
        Playtest.wait("settle", 60);
        Playtest.queue("screenshot of the pavilion", () -> Playtest.screenshotNextFrame("uc-shelter-outside"));
        Playtest.wait("frame", 10);
        Playtest.queue("under the roof, facing the middle", () -> standLooking(Vec3.atLowerCornerOf(origin[0]).add(1.5, 0.1, 4.5), centre[0].add(0.4, 0.8, 0)));
        Playtest.wait("settle", 60);
        Playtest.queue("screenshot under the roof", () -> Playtest.screenshotNextFrame("uc-shelter-inside"));
        Playtest.wait("frame", 10);
        Playtest.queue("back to creative with the HUD, where the warden stands now", () -> {
            Playtest.command("gamemode creative");
            Minecraft.getInstance().options.hideGui = false;
            var warden = citizen(null, 0);
            if (warden != null) Playtest.log("the warden is now at " + warden.blockPosition().toShortString() + ", " + Math.round(Math.hypot(warden.getX() - centre[0].x, warden.getZ() - centre[0].z)) + " blocks from the pavilion's middle");
        });
        screensStayOpenFromNow();
        talkTo("the road warden", 0, "dialogue", "uc-shelter-dialogue");
        chooseOption("Where am I on the road?", 0, "dialogue", "uc-shelter-where");
        chooseOption("What does the law say at either end?", 2, "dialogue", "uc-shelter-law");
        Playtest.queue("goodbye", () -> Minecraft.getInstance().setScreen(null));
        Playtest.queue("restore the district the talk helper looks in", () -> { talkDistrict = "founders_row"; talkNear = null; });
    }

    /** One look at a block of the Row from a standing position (Row-relative eye and target), long enough for a tooltip mod to notice the crosshair, then a photograph. */
    private static void glance(String name, double ex, double ey, double ez, double tx, double ty, double tz) {
        Playtest.queue("look at " + name, () -> {
            Vec3 o = Vec3.atLowerCornerOf(DistrictBuilder.originOf(level(), "founders_row"));
            standLooking(o.add(ex, ey - 1.62, ez), o.add(tx, ty, tz));
        });
        Playtest.wait("let the overlay for " + name + " appear", 50);
        Playtest.queue("photograph " + name, () -> Playtest.screenshotNextFrame("uc-" + name));
        Playtest.wait("frame " + name, 8);
    }

    /** The opt-in quality-of-life mods (set {@code qol}): Jade's block tooltip over our blocks, and JEI's item list in the inventory, with a search. */
    private static void qol() {
        final BlockPos[] origin = new BlockPos[1];
        buildRowInTheSky(origin);
        screensStayOpenFromNow();
        Playtest.queue("noon, clear, no command feedback", () -> {
            Playtest.command("gamemode creative");
            Playtest.command("time set 6000"); Playtest.command("gamerule doDaylightCycle false"); Playtest.command("weather clear"); Playtest.command("gamerule sendCommandFeedback false");
        });
        waitForMeshes(1500);
        glance("jade-terminal", 4.5, 1.7, 6.5, 4.5, 0.5, 2.5);
        glance("jade-ledger", 14.5, 1.7, 25.5, 11.5, 0.5, 25.5);
        Playtest.queue("a cannabis crop in the greenhouse bed, nearly grown", () -> {
            BlockPos o = DistrictBuilder.originOf(level(), "founders_row");
            Playtest.command("setblock " + (o.getX() + 27) + " " + o.getY() + " " + (o.getZ() + 3) + " underground_city:cannabis_plant[age=2,nourished=false]");
        });
        glance("jade-crop", 27.5, 1.7, 6.5, 27.5, 0.4, 3.5);
        Playtest.queue("look at the sky and open the inventory", () -> {
            Vec3 o = Vec3.atLowerCornerOf(DistrictBuilder.originOf(level(), "founders_row"));
            standLooking(o.add(16.5, 0.1, 40), o.add(16.5, 30, 60));
        });
        Playtest.wait("settle", 20);
        Playtest.queue("open the inventory", () -> Playtest.tap("e"));
        Playtest.wait("let the screen and JEI draw", 60);
        Playtest.queue("photograph the inventory with JEI's list", () -> Playtest.screenshotNextFrame("uc-jei-inventory"));
        Playtest.wait("frame", 8);
        Playtest.queue("focus JEI's search box (ctrl + f)", () -> { Playtest.keyDown("ctrl"); Playtest.tap("f"); Playtest.keyUp("ctrl"); });
        Playtest.wait("settle", 10);
        Playtest.queue("type 'cannabis'", () -> { for (String key : new String[]{"c", "a", "n", "n", "a", "b", "i", "s"}) Playtest.tap(key); });
        Playtest.wait("let the list filter", 40);
        Playtest.queue("photograph the search", () -> Playtest.screenshotNextFrame("uc-jei-search"));
        Playtest.wait("frame", 8);
    }

    /**
     * The opt-in survival set (D-085) in a real client: survival mode with thirst on, the set's goods in the inventory, the HUD with the thirst bar, the inventory screen with
     * the backpack, canteen, cooking pot and sleeping bag, and the backpack put on with a real right click and opened with its own key. Frames are looked at by a person afterwards.
     */
    private static void survival() {
        final BlockPos[] origin = new BlockPos[1];
        buildRowInTheSky(origin);
        screensStayOpenFromNow();
        Playtest.queue("survival mode, noon, clear, no command feedback, and the set's goods", () -> {
            Playtest.command("gamerule sendCommandFeedback false");
            Playtest.command("time set 6000"); Playtest.command("gamerule doDaylightCycle false"); Playtest.command("weather clear");
            for (String item : new String[]{"sophisticatedbackpacks:backpack", "thirstwastaken2:waterskin", "thirstwastaken2:copper_canteen", "thirstwastaken2:iron_flask",
                "farmersdelight:cooking_pot", "farmersdelight:cutting_board", "comforts:sleeping_bag_red", "comforts:hammock_blue", "farmersdelight:pumpkin_slice 8", "minecraft:bread 6"}) {
                Playtest.command("give @s " + item);
            }
            Playtest.command("gamemode survival");
        });
        Playtest.queue("stand on the Row's lane looking down it", () -> {
            Vec3 o = Vec3.atLowerCornerOf(DistrictBuilder.originOf(level(), "founders_row"));
            standLooking(o.add(16.5, 0.1, 30), o.add(16.5, 1.6, 20));
        });
        waitForMeshes(1500);
        Playtest.wait("let the HUD settle", 80);
        Playtest.queue("photograph the HUD with the thirst bar", () -> Playtest.screenshotNextFrame("uc-survival-hud"));
        Playtest.wait("frame", 8);
        Playtest.queue("open the inventory", () -> Playtest.tap("e"));
        Playtest.wait("let the screen draw", 60);
        Playtest.queue("photograph the inventory with the set's goods", () -> Playtest.screenshotNextFrame("uc-survival-inventory"));
        Playtest.wait("frame", 8);
        Playtest.queue("close the inventory; the backpack is in the selected hotbar slot", () -> { Playtest.tap("e"); Playtest.command("clear @s minecraft:bread"); });
        Playtest.wait("settle", 20);
        Playtest.queue("press the right mouse button with the backpack in hand", () -> Playtest.mouseButton(3, true));
        Playtest.wait("hold it a few ticks", 4);
        Playtest.queue("release it", () -> Playtest.mouseButton(3, false));
        Playtest.wait("let the backpack settle on the player's back", 30);
        Playtest.queue("open the worn backpack with its own key (B)", () -> Playtest.tap("b"));
        Playtest.wait("let the backpack screen draw", 60);
        Playtest.queue("photograph the backpack screen", () -> Playtest.screenshotNextFrame("uc-survival-backpack"));
        Playtest.wait("frame", 8);
    }

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
            Playtest.command("summon minecraft:zombie ~ ~ ~7 {NoAI:1b,Silent:1b,PersistenceRequired:1b,Tags:[\"uc_permitted_hostile\"]}");   // a target dummy: the simulation world keeps ordinary zombies out (D-094) unless they carry the tag
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
