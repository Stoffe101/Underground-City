package io.github.stoffe101.undergroundcity;

import net.minecraft.client.gui.screens.GenericDirtMessageScreen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;
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
 * The Technician's trade (D-158) through a real client ({@code -PucPlaytest=tech}, a new furnished archipelago world): a new Technician registers and has a toolkit, a computer and circuit
 * boards; utility panels appear beside the street doors near them; a service call comes to one; the computer's service board lists it and Track points at it; the repair at the panel is worked
 * with real clicks (probes read test points, a binary search, one replacement) and pays once; the Electronics Bench makes a toolkit from carried parts and refuses a design that needs a skill; an
 * install job at a panel is worked on the patch grid and arms a monitored alarm; and the record holds across a save, quit and reopen.
 *
 * <p>Navigation (D-161): Track on the board makes the call the player's destination, which with the {@code navigation} set is one waypoint on Xaero's Minimap (also in its waypoint list and on
 * the world map); the player walks to the panel with the movement keys; arriving keeps the marker, finishing the job removes it, tracking the install job replaces it, and a call still open when
 * the world is saved and reopened is tracked again, once. Without Xaero the same steps pass on the status display alone.
 *
 * <p>Scripted rather than played, each logged as SCRIPTED: the clock, the travel to Valewood, the service calls (the service raises one every 8 to 15 minutes), placing the computers and the
 * bench (a home holds them), two iron ingots, the skills and XP of an Installer, a Municipal Security Array, the install job, and the patch grid's answer (read from the server; a person works it
 * out by eye; every turn was a real click). The walk to the panel is a teleport only for whatever the walker cannot reach, and says so.
 */
final class TechScenario {
    private TechScenario() {}

    private static final class Facts {
        TechService.Panel panel, second;
        BlockPos computer, bench, nearComputer;
        Vec3 stand;
        int ownWaypoints = -1;
        double balanceBefore;
        int xpBefore;
        int[] solution;
    }

    private static final Facts F = new Facts();

    static void build() {
        ThiefScenario.tag = "TECH";
        Playtest.keepScreens = true;
        ThiefScenario.register(4, "the Technician card", "1 a new citizen registers as a Technician through the registration screen");
        Playtest.queue("the starter kit", () -> {
            int kits = count(item("repair_kit")), pcs = count(item("personal_computer")), boards = count(item("control_circuits"));
            Playtest.log("TECH kit: toolkits " + kits + ", computers " + pcs + ", circuit boards " + boards);
            if (kits < 1 || pcs < 1 || boards < 2) Playtest.fail("the Technician's kit lacks the toolkit, the computer or the boards");
            else pass("2 a new Technician starts with a toolkit, a personal computer and two circuit boards");
        });
        Playtest.queue("the clock: 13:00 with the daylight cycle stopped", () -> {
            Playtest.command("gamerule doDaylightCycle false");
            Playtest.command("time set 7000");
            scripted("the time of day was set to 13:00 and the daylight cycle stopped");
        });
        ThiefScenario.travel("to the middle of Valewood", () -> ThiefScenario.centre("valewood"), 4);
        Playtest.queue("down to a street door (scripted)", () -> {
            DoorWalk.Spot spot = server(() -> {
                ServerLevel level = level();
                BlockPos here = me().blockPosition();
                for (int r = 0; r <= 5; r++) for (int dx = -r; dx <= r; dx++) for (int dz = -r; dz <= r; dz++) {
                    if (Math.max(Math.abs(dx), Math.abs(dz)) != r) continue;
                    int cx = (here.getX() >> 4) + dx, cz = (here.getZ() >> 4) + dz;
                    if (!level.hasChunk(cx, cz)) continue;
                    var b = CityBuildings.INSTANCE.ofChunk(level, cx, cz).orElse(null);
                    if (b == null || !b.locked() || Thievery.tier(b) <= 0) continue;
                    DoorWalk.Spot found = ThiefScenario.streetDoor(level, b);
                    if (found != null) return found;
                }
                return null;
            });
            if (spot == null) { Playtest.fail("no street door near the middle of Valewood"); return; }
            scripted("the player was put on the street at " + spot.door().toShortString() + " in survival");
            ThiefScenario.standAt(spot);
        });
        until("the ground", Scenarios::groundBelowPlayer, 400);
        panels();
        Playtest.queue("stop if no panel", () -> { if (F.panel == null) { Playtest.log("TECH stopping: no panel"); ThiefScenario.summary(); Playtest.finish(); } });
        serviceCall();
        computer();
        repair();
        bench();
        install();
        reopen();
        Playtest.queue("summary", ThiefScenario::summary);
    }

    /**
     * The Technician's back channel (D-166) through a real client ({@code -PucPlaytest=backchannel}): a prepared Technician with Bypass Tooling and a Bypass Kit finds a quiet job on the computer's
     * board at a tier III or IV building, tracks it, walks to the panel, crouches with the toolkit in hand and right-clicks it, and works the patch grid with real clicks. The building's alarm line
     * is quiet for a while, the pay lands once, the kit is used up, the job leaves the board, and the record holds across a save and reopen. Fictional: an abstract tile puzzle, nothing of a real
     * system. Scripted, each logged: the clock, the travel, the start at a street door, the level, the three skills and the Bypass Kit (seven levels of play and a purchase at the Den), the job at
     * the chosen panel (the service posts one at tier III and up every few minutes), the computer set down, and the patch grid's answer (read from the server; every turn a real click).
     */
    static void buildQuiet() {
        ThiefScenario.tag = "BACKCHANNEL";
        Playtest.keepScreens = true;
        ThiefScenario.register(4, "the Technician card", "1 a new citizen registers as a Technician through the registration screen");
        Playtest.queue("the clock: 02:00 with the daylight cycle stopped", () -> {
            Playtest.command("gamerule doDaylightCycle false");
            Playtest.command("time set 20000");
            scripted("the time of day was set to 02:00 and the daylight cycle stopped (quiet work is done when the street is empty)");
        });
        ThiefScenario.travel("to the middle of Valewood", () -> ThiefScenario.centre("valewood"), 4);
        Playtest.queue("down to a street door (scripted)", () -> {
            DoorWalk.Spot spot = server(() -> {
                ServerLevel level = level();
                BlockPos here = me().blockPosition();
                for (int r = 0; r <= 5; r++) for (int dx = -r; dx <= r; dx++) for (int dz = -r; dz <= r; dz++) {
                    if (Math.max(Math.abs(dx), Math.abs(dz)) != r) continue;
                    int cx = (here.getX() >> 4) + dx, cz = (here.getZ() >> 4) + dz;
                    if (!level.hasChunk(cx, cz)) continue;
                    var b = CityBuildings.INSTANCE.ofChunk(level, cx, cz).orElse(null);
                    if (b == null || !b.locked() || Thievery.tier(b) < 3) continue;
                    DoorWalk.Spot found = ThiefScenario.streetDoor(level, b);
                    if (found != null) return found;
                }
                return null;
            });
            if (spot == null) { Playtest.fail("no street door of a tier III or better building near the middle of Valewood"); return; }
            scripted("the player was put on the street at " + spot.door().toShortString() + " in survival");
            ThiefScenario.standAt(spot);
        });
        until("the ground", Scenarios::groundBelowPlayer, 400);
        until("utility panels beside the street doors nearby", () -> server(() -> {
            BlockPos here = me().blockPosition();
            return TechService.systems(level().getServer()).panels.values().stream().filter(p -> p.pos() != null && p.pos().distSqr(here) < 64 * 64).count() >= 3;
        }), 1200);
        Playtest.queue("choose a tier III or IV panel", () -> {
            F.panel = server(() -> {
                BlockPos here = me().blockPosition();
                TechService.Panel best = null;
                for (TechService.Panel p : TechService.systems(level().getServer()).panels.values()) {
                    if (p.pos() == null || p.tier() < 3 || p.tier() > 4 || p.pos().distSqr(here) > 64 * 64 || standFor(p) == null) continue;
                    if (best == null || p.pos().distSqr(here) < best.pos().distSqr(here)) best = p;
                }
                return best;
            });
            if (F.panel == null) { Playtest.fail("no tier III or IV panel near the middle of Valewood"); return; }
            Playtest.log("BACKCHANNEL panel: " + F.panel.label() + ", " + F.panel.address() + ", tier " + F.panel.tier() + " (" + F.panel.category() + ") at " + F.panel.pos().toShortString());
        });
        ThiefScenario.stopIfFailed("a panel");
        Playtest.queue("prepared: a Technician with Bypass Tooling, a Bypass Kit and a quiet job (scripted)", () -> {
            scripted("PREPARED: Technician level 7, Fundamentals, Municipal Repairs and Bypass Tooling, a Bypass Kit, and a quiet job at " + F.panel.address() + " were given");
            server(() -> {
                var server = mc().getSingleplayerServer();
                var citizens = CitizenState.get(server);
                CitizenState.Citizen c = citizens.find(me().getUUID()).orElseThrow();
                int need = ProgressionRules.xpAtLevel(7);
                if (c.careerXp() < need) citizens.addCareerXp(me().getUUID(), need - c.careerXp());
                for (String skill : new String[] {"technician.fundamentals", "technician.municipal_repairs", TechWork.QUIET_SKILL}) citizens.unlockSkill(me().getUUID(), skill);
                me().getInventory().add(new ItemStack(item("bypass_kit")));
                TechService.Systems s = TechService.systems(server);
                s.jobs.values().removeIf(j -> j.key() == F.panel.key());
                TechService.newJob(s, "quiet", F.panel, TechService.quietFee(F.panel.tier()), TechService.quietXp(F.panel.tier()), LockService.clock(server));
                return null;
            });
        });
        trackFromComputer("the quiet job", () -> F.panel.address(), "quiet");
        Playtest.queue("the board's quiet job", () -> {
            var o = ClientNavigation.objectiveOr(null);
            if (o == null || o.target() == null) Playtest.fail("the quiet job is not tracked");
            else pass("2 the computer's board lists the quiet job at " + F.panel.address() + " for a Technician with Bypass Tooling and a Bypass Kit; Track points at the panel");
        });
        shot("q1-tracked");
        Playtest.queue("where to stand", () -> F.stand = server(() -> standFor(F.panel)));
        ThiefScenario.walkOrTeleport("walk to the panel", () -> F.stand == null ? mc().player.position() : F.stand, 0.8, 1500);
        Playtest.queue("before the job", () -> {
            F.balanceBefore = server(() -> QShopEconomy.balance(me()));
            F.xpBefore = server(() -> CitizenState.get(me().server).find(me().getUUID()).map(CitizenState.Citizen::careerXp).orElse(0));
            Playtest.keyDown("left.shift");
        });
        Playtest.wait("crouch", 10);
        useBlock("the panel, crouched, with the toolkit", () -> F.panel.pos(), () -> mc().screen instanceof PatchScreen, item("repair_kit"));
        Playtest.queue("stand up", () -> Playtest.keyUp("left.shift"));
        Playtest.wait("draw", 10);
        shot("q2-patch");
        Playtest.queue("the grid's answer (read from the server)", () -> {
            TechWork.Session ses = server(() -> TechWork.sessionOf(me().getUUID()));
            if (ses == null || ses.patch == null || !"quiet".equals(ses.kind)) { Playtest.fail("no quiet session at the panel"); return; }
            F.solution = ses.patch.solution.clone();
            scripted("the patch grid's answer was read from the server; the turns were real clicks");
            Playtest.log("BACKCHANNEL patch grid " + ses.patch.w + "x" + ses.patch.h + ", " + ses.patch.outputs.length + " devices");
        });
        final int[] t = {0};
        Playtest.queue("turn the tiles with real clicks", () -> t[0] = 0, () -> {
            if (!(mc().screen instanceof PatchScreen ps)) return true;
            if (++t[0] % 10 != 5) return false;
            TechNetwork.PatchView v = ps.view();
            for (int i = 0; i < v.tiles().length; i++) {
                if (v.tiles()[i] == F.solution[i]) continue;
                int[] c = ps.cellCentre(i);
                Playtest.clickGui(c[0], c[1]);
                return false;
            }
            return false;
        }, 2400);
        Playtest.wait("the pay lands", 30);
        Playtest.queue("the line is quiet", () -> {
            long paid = Math.round(server(() -> QShopEconomy.balance(me())) - F.balanceBefore);
            int xp = server(() -> CitizenState.get(me().server).find(me().getUUID()).map(CitizenState.Citizen::careerXp).orElse(0)) - F.xpBefore;
            boolean quiet = server(() -> {
                Long until = TechService.systems(level().getServer()).quieted.get(F.panel.key());
                return until != null && LockService.clock(level().getServer()) < until;
            });
            boolean gone = server(() -> TechService.systems(level().getServer()).jobs.values().stream().noneMatch(j -> j.key() == F.panel.key() && j.kind().equals("quiet")));
            int kits = count(item("bypass_kit"));
            Playtest.log("BACKCHANNEL done: " + ClientTech.lastEnd + "; paid " + paid + ", XP +" + xp + ", quiet " + quiet + ", job gone " + gone + ", Bypass Kits left " + kits);
            if (!ClientTech.lastDone || paid <= 0 || !quiet || !gone || kits != 0) Playtest.fail("the quiet job did not pay once, quieten the line, leave the board and use the kit");
            else pass("3 the patch grid worked with real clicks quietens the building's alarm line for a while: +" + paid + " credits off the books, +" + xp + " XP, the Bypass Kit used, the job off the board");
        });
        shot("q3-done");
        Playtest.queue("the same panel again", () -> F.balanceBefore = server(() -> QShopEconomy.balance(me())));
        Playtest.queue("crouch again", () -> Playtest.keyDown("left.shift"));
        Playtest.wait("crouch", 10);
        useBlock("the panel again, crouched", () -> F.panel.pos(), () -> true, item("repair_kit"));
        Playtest.wait("the server answers", 40);
        Playtest.queue("nothing twice", () -> {
            Playtest.keyUp("left.shift");
            boolean session = server(() -> TechWork.sessionOf(me().getUUID()) != null);
            long paid = Math.round(server(() -> QShopEconomy.balance(me())) - F.balanceBefore);
            if (session || paid != 0 || mc().screen instanceof PatchScreen) Playtest.fail("the finished job could be worked or paid again");
            else pass("4 the finished job cannot be started or paid a second time");
        });
        JourneyScenario.closeScreen("close anything open");
        StationSteps.reconnect(() -> List.of(server(() -> QShopEconomy.balance(me())), server(() -> TechService.systems(level().getServer()).quieted.containsKey(F.panel.key())), server(() -> TechService.systems(level().getServer()).quiets)),
            (before, after) -> {
                if (!before.equals(after)) Playtest.fail("the back-channel record changed across the save and reopen");
                else pass("5 saved, left and reopened: the wallet, the quiet line's record and the count of quiet jobs unchanged");
            });
        Playtest.queue("summary", ThiefScenario::summary);
    }

    private static Item item(String id) { return TechWork.item(id); }   // the registry, so block items (the computer) are found too
    private static int count(Item item) { return mc().player.getInventory().countItem(item); }

    // ------------------------------------------------------------------------------------------------ panels

    /** Waits for the service to set panels beside the street doors nearby, and takes the one of the lowest tier (a new Technician may repair tiers I and II). */
    private static void panels() {
        until("utility panels beside the street doors nearby", () -> server(() -> {
            BlockPos here = me().blockPosition();
            return TechService.systems(level().getServer()).panels.values().stream().filter(p -> p.pos() != null && p.pos().distSqr(here) < 64 * 64).count() >= 3;
        }), 1200);
        Playtest.queue("choose a panel", () -> {
            F.panel = server(() -> {
                BlockPos here = me().blockPosition();
                TechService.Panel best = null;
                for (TechService.Panel p : TechService.systems(level().getServer()).panels.values()) {
                    if (p.pos() == null || p.pos().distSqr(here) > 64 * 64 || "commercial".equals(p.category()) || standFor(p) == null) continue;
                    if (best == null || p.tier() < best.tier() || p.tier() == best.tier() && p.pos().distSqr(here) < best.pos().distSqr(here)) best = p;
                }
                return best;
            });
            int all = server(() -> TechService.systems(level().getServer()).panels.size());
            if (F.panel == null) { Playtest.fail("no usable utility panel near the middle of Valewood (" + all + " panels)"); return; }
            Playtest.log(String.format(Locale.ROOT, "TECH panel chosen: %s, %s, tier %d (%s) at %s, door %s; %d panels so far", F.panel.label(), F.panel.address(), F.panel.tier(), F.panel.category(),
                F.panel.pos().toShortString(), F.panel.door().toShortString(), all));
            pass("3 utility panels appear beside the street doors near the player (" + all + "), one per building, set into the wall");
        });
    }

    /** Where to stand to work panel {@code p}: two blocks out on the street, at the door's height. */
    private static Vec3 standFor(TechService.Panel p) {
        ServerLevel level = level();
        BlockState state = level.getBlockState(p.pos());
        if (!(state.getBlock() instanceof TechBlocks.UtilityPanel)) return null;
        Direction out = state.getValue(TechBlocks.UtilityPanel.FACING);
        BlockPos cell = p.pos().below().relative(out, 2);
        if (!StaffZones.walkable(level, cell)) return null;
        return Vec3.atBottomCenterOf(cell);
    }

    // ------------------------------------------------------------------------------------------------ the service call

    private static void serviceCall() {
        Playtest.queue("a service call at the panel (scripted)", () -> {
            scripted("a fault was raised at once at " + F.panel.address() + " (the service raises one in a city somebody is in every 8 to 15 minutes)");
            ClientNotice.lastTitle = "";
            server(() -> TechService.open(level().getServer(), level(), F.panel, LockService.clock(level().getServer())));
        });
        until("the notice", () -> "Service call".equals(ClientNotice.lastTitle), 100);
        Playtest.wait("draw", 20);
        shot("1-service-call");
        Playtest.queue("the panel's lamp", () -> {
            boolean fault = server(() -> level().getBlockState(F.panel.pos()).getValue(TechBlocks.UtilityPanel.STATE) == TechBlocks.UtilityPanel.State.FAULT);
            boolean down = server(() -> TechService.systems(level().getServer()).faults.containsKey(F.panel.key()));
            if (!fault || !down) Playtest.fail("no fault at the panel (lamp " + fault + ", record " + down + ")");
            else pass("4 a service call: the Technician is told, and the panel's lamp turns red (" + (F.panel.category().equals("commercial") ? "the shop cannot pay suppliers" : "the cameras are off") + ")");
        });
    }

    // ------------------------------------------------------------------------------------------------ the computer

    private static void computer() {
        Playtest.queue("a computer beside the player (scripted)", () -> {
            scripted("a personal computer was set on the street beside the player (a home holds one; the kit's is the same block)");
            F.computer = server(() -> {
                var p = me();
                BlockPos c = freeCellNear(p.blockPosition(), null);
                if (c != null) level().setBlock(c, UndergroundCity.PERSONAL_COMPUTER.get().defaultBlockState().setValue(TechBlocks.Computer.FACING, Direction.fromYRot(p.getYRot()).getOpposite()), 3);
                return c;
            });
            if (F.computer == null) Playtest.fail("no room for the computer");
        });
        useBlock("the computer", () -> F.computer, () -> mc().screen instanceof ComputerScreen, null);
        Playtest.wait("draw", 10);
        shot("2-computer-service");
        Playtest.queue("the service board", () -> {
            if (!(mc().screen instanceof ComputerScreen screen)) { Playtest.fail("no computer screen"); return; }
            List<TechWork.Call> calls = screen.calls();
            Playtest.log("TECH service board: " + calls.stream().map(c -> c.kind() + " " + c.address() + " tier " + c.tier() + " " + c.credits() + " cr" + (c.blocked().isEmpty() ? "" : " (" + c.blocked() + ")")).toList());
            int at = -1;
            for (int i = 0; i < calls.size() && at < 0; i++) if (calls.get(i).address().equals(F.panel.address()) && calls.get(i).kind().startsWith("repair")) at = i;
            if (at < 0) { Playtest.fail("the service board does not list the call at " + F.panel.address()); return; }
            screen.select(at);
            pass("5 the computer's service board lists the call: " + calls.get(at).address() + ", " + calls.get(at).credits() + " credits" + (calls.get(at).blocked().isEmpty() ? ", can take it" : ", " + calls.get(at).blocked()));
        });
        JourneyScenario.click("Track", true);
        until("tracking", () -> ClientNavigation.current() != null && mc().screen == null, 100);
        Playtest.wait("draw", 20);
        shot("3-tracking");
        Playtest.queue("the arrow", () -> {
            var o = ClientNavigation.objectiveOr(null);
            if (o == null || o.target() == null) Playtest.fail("no tracking card");
            else pass("6 Track points the status display's next-step card at the panel (" + o.targetName() + ")");
        });
        xaeroShows("6b", "the service call", "Till repair|Alarm repair");
        xaeroScreens("3");
    }

    // ------------------------------------------------------------------------------------------------ Xaero's Minimap (D-161)

    private static boolean noXaero() { return XaeroWaypoints.state() == XaeroWaypoints.State.ABSENT; }

    /** The tracked destination is one waypoint of ours on Xaero's minimap, named for the job (or, without the mod, the status display alone points the way). */
    private static void xaeroShows(String step, String what, String names) {
        final int[] polls = {0};
        until("the waypoint on Xaero's minimap", () -> {
            if (++polls[0] % 60 == 0) Playtest.log("TECH xaero waiting: " + XaeroWaypoints.describe() + " (scenario loader " + XaeroWaypoints.class.getClassLoader() + ")");
            return noXaero() || XaeroWaypoints.state() == XaeroWaypoints.State.FAILED || XaeroWaypoints.showing();
        }, 400);
        Playtest.wait("draw", 20);
        Playtest.queue("on Xaero's minimap: " + what, () -> {
            Navigation.Destination d = ClientNavigation.current();
            int ours = XaeroWaypoints.ours(), own = XaeroWaypoints.playersOwn();
            if (F.ownWaypoints < 0) F.ownWaypoints = own;
            Playtest.log("TECH xaero: " + XaeroWaypoints.describe() + "; ours " + ours + ", the player's own " + own + "; tracking " + (d == null ? "nothing" : d.title() + " at " + d.pos().toShortString()));
            if (noXaero()) { pass(step + " Xaero's Minimap is not installed: the status display's arrow points the way to " + what + " and nothing failed"); return; }
            if (d == null || !XaeroWaypoints.showing() || ours != 1 || !d.title().matches("(" + names + ").*")) Playtest.fail("no single waypoint for " + what + " on Xaero's minimap (" + XaeroWaypoints.describe() + ", ours " + ours + ")");
            else pass(step + " " + what + " is one waypoint on Xaero's minimap, \"" + XaeroWaypoints.name(d) + "\" at " + d.pos().toShortString() + "; the player's own waypoints: " + own);
        });
        shot(step + "-minimap");
    }

    /** Xaero's own waypoint list (U) and world map (M) with the destination on them. */
    private static void xaeroScreens(String n) {
        Playtest.queue("Xaero's waypoint list (U)", () -> { if (!noXaero()) { Playtest.focusWindow(); Playtest.tap("u"); } },
            () -> noXaero() || mc().screen != null && mc().screen.getClass().getName().contains("Waypoint"), 100);
        Playtest.wait("draw", 20);
        Playtest.queue("the waypoint list", () -> { if (!noXaero()) Playtest.log("TECH xaero waypoint list: " + (mc().screen == null ? "none" : mc().screen.getClass().getName())); });
        shot(n + "c-xaero-waypoints");
        Playtest.queue("close it", () -> { if (mc().screen != null) mc().setScreen(null); });
        Playtest.wait("settle", 10);
        Playtest.queue("Xaero's world map (M)", () -> { if (!noXaero()) { Playtest.focusWindow(); Playtest.tap("m"); } },
            () -> noXaero() || mc().screen != null && mc().screen.getClass().getName().contains("GuiMap"), 200);
        Playtest.wait("the map draws", 80);
        Playtest.queue("the world map", () -> { if (!noXaero()) Playtest.log("TECH xaero world map: " + (mc().screen == null ? "none" : mc().screen.getClass().getName())); });
        shot(n + "d-xaero-world-map");
        Playtest.queue("close it", () -> { if (mc().screen != null) mc().setScreen(null); });
        Playtest.wait("settle", 10);
    }

    /** Nothing is tracked any more and our waypoint has left Xaero's minimap; the player's own are as they were. */
    private static void xaeroCleared(String step, String why) {
        until("tracking ended", () -> ClientNavigation.current() == null, 200);
        until("the waypoint gone from Xaero's minimap", () -> noXaero() || XaeroWaypoints.ours() == 0, 100);
        Playtest.queue("cleared: " + why, () -> {
            int ours = XaeroWaypoints.ours(), own = XaeroWaypoints.playersOwn();
            Playtest.log("TECH xaero after " + why + ": " + XaeroWaypoints.describe() + "; ours " + ours + ", the player's own " + own);
            if (ClientNavigation.current() != null) Playtest.fail("still tracking after " + why);
            else if (!noXaero() && (ours != 0 || own != F.ownWaypoints)) Playtest.fail("after " + why + " our waypoints are " + ours + " and the player's own " + own + " (were " + F.ownWaypoints + ")");
            else pass(step + " " + why + " ends the tracking" + (noXaero() ? "" : ": our waypoint is gone from Xaero's minimap and the player's own " + own + " are untouched"));
        });
        shot(step + "-cleared");
    }

    /** A computer beside the player (scripted), its service board, the job at {@code address} of {@code kind} selected and Track clicked. */
    private static void trackFromComputer(String label, java.util.function.Supplier<String> address, String kind) {
        Playtest.queue("a computer beside the player (scripted)", () -> {
            scripted("a personal computer was set down beside the player for " + label);
            F.nearComputer = server(() -> {
                var p = me();
                BlockPos c = freeCellNear(p.blockPosition(), null);
                if (c != null) level().setBlock(c, UndergroundCity.PERSONAL_COMPUTER.get().defaultBlockState().setValue(TechBlocks.Computer.FACING, Direction.fromYRot(p.getYRot()).getOpposite()), 3);
                return c;
            });
            if (F.nearComputer == null) Playtest.fail("no room for a computer");
        });
        useBlock("the computer", () -> F.nearComputer, () -> mc().screen instanceof ComputerScreen, null);
        Playtest.wait("draw", 10);
        Playtest.queue("select " + label, () -> {
            if (!(mc().screen instanceof ComputerScreen screen)) { Playtest.fail("no computer screen"); return; }
            List<TechWork.Call> calls = screen.calls();
            for (int i = 0; i < calls.size(); i++) if (calls.get(i).address().equals(address.get()) && calls.get(i).kind().startsWith(kind)) { screen.select(i); return; }
            Playtest.fail("the service board does not list " + label + " (" + calls.stream().map(c -> c.kind() + " " + c.address()).toList() + ")");
        });
        JourneyScenario.click("Track", true);
        until("tracking " + label, () -> ClientNavigation.current() != null && mc().screen == null, 100);
    }

    /** A free cell on the ground within three blocks of {@code from}, not the player's own and not {@code besides}, with air above it, and farther from the panel than the player (never in the way). */
    private static BlockPos freeCellNear(BlockPos from, BlockPos besides) {
        ServerLevel level = level();
        for (int r = 2; r <= 3; r++) for (int dx = -r; dx <= r; dx++) for (int dz = -r; dz <= r; dz++) {
            if (Math.max(Math.abs(dx), Math.abs(dz)) != r) continue;
            for (int dy = 0; dy >= -1; dy--) {
                BlockPos c = from.offset(dx, dy, dz);
                if (F.panel != null && c.distSqr(F.panel.pos()) <= from.distSqr(F.panel.pos())) continue;
                if (c.equals(besides) || !level.getBlockState(c).isAir() || !level.getBlockState(c.above()).isAir() || !level.getBlockState(c.below()).isSolidRender(level, c.below())) continue;
                if (BuildingGuard.protectedAt(level, c).isPresent() && !level.canSeeSky(c)) continue;   // not inside somebody's building
                // in plain sight of the player's eyes, so a person could reach out and click it (a ledge or a lamp post in the way is no good)
                var eye = me().getEyePosition();
                if (level.clip(new net.minecraft.world.level.ClipContext(eye, Vec3.atCenterOf(c), net.minecraft.world.level.ClipContext.Block.OUTLINE,
                        net.minecraft.world.level.ClipContext.Fluid.NONE, me())).getType() != net.minecraft.world.phys.HitResult.Type.MISS) continue;
                return c.immutable();
            }
        }
        return null;
    }

    /** Right-clicks block {@code pos} with the hotbar slot that holds {@code tool} (none: whatever is in hand) until {@code open}. */
    private static void useBlock(String label, Supplier<BlockPos> pos, java.util.function.BooleanSupplier open, Item tool) {
        final int[] t = {0};
        Playtest.queue("right-click " + label, () -> {
            t[0] = 0;
            Playtest.focusWindow();
            if (tool != null) {
                int slot = -1;
                for (int i = 0; i < 9; i++) if (mc().player.getInventory().getItem(i).is(tool)) slot = i;
                if (slot < 0) {
                    // a full hotbar: the item is moved to the last hotbar slot, as a person would drag it there
                    scripted(tool + " was moved from the backpack rows to the last hotbar slot");
                    server(() -> {
                        var inv = me().getInventory();
                        for (int i = 9; i < inv.items.size(); i++) if (inv.getItem(i).is(tool)) { ItemStack moved = inv.getItem(i); inv.setItem(i, inv.getItem(8)); inv.setItem(8, moved); break; }
                        return null;
                    });
                    slot = 8;
                }
                Playtest.tap(String.valueOf(slot + 1));
            }
        }, () -> {
            if (open.getAsBoolean()) return true;
            BlockPos at = pos.get();
            if (at == null) return true;
            look(Vec3.atCenterOf(at));
            if (++t[0] % 24 == 12 && aimedAt(at)) Playtest.click(3);
            return false;
        }, 400);
    }

    // ------------------------------------------------------------------------------------------------ the repair

    private static void repair() {
        Playtest.queue("a Technician who can take this building (scripted when it is above tier II)", () -> {
            if (F.panel.tier() <= 2) return;
            scripted("Bench Electronics and Municipal Repairs were given for a tier " + F.panel.tier() + " panel");
            server(() -> {
                var citizens = CitizenState.get(mc().getSingleplayerServer());
                citizens.unlockSkill(me().getUUID(), "technician.fundamentals");
                citizens.unlockSkill(me().getUUID(), "technician.municipal_repairs");
                return null;
            });
        });
        Playtest.queue("where to stand at the panel", () -> {
            F.stand = server(() -> standFor(F.panel));
            Playtest.log(String.format(Locale.ROOT, "TECH the panel is %.0f blocks away; walking there", mc().player.position().distanceTo(F.stand)));
        });
        ThiefScenario.walkOrTeleport("walk to the panel", () -> F.stand, 1.2, 1600);
        Playtest.queue("still tracked on arrival", () -> {
            boolean kept = ClientNavigation.current() != null && (noXaero() || XaeroWaypoints.showing());
            if (!kept) Playtest.fail("the tracking ended on arriving, before the work was done");
            else pass("7b arriving at the panel does not end the tracking: the marker stays until the repair is done");
            F.balanceBefore = server(() -> QShopEconomy.balance(me()));
            F.xpBefore = ThiefScenario.citizen().careerXp();
        });
        shot("3e-arrived");
        Playtest.wait("settle", 20);
        useBlock("the panel with the toolkit", () -> F.panel.pos(), () -> mc().screen instanceof TraceScreen, item("repair_kit"));
        Playtest.wait("draw", 10);
        shot("4-repair-trace");
        Playtest.queue("the circuit", () -> {
            if (!(mc().screen instanceof TraceScreen s)) { Playtest.fail("the repair screen did not open"); return; }
            TechNetwork.TraceView v = s.view();
            Playtest.log("TECH circuit: trunk " + v.trunk() + ", branches " + v.branch() + (v.split() ? " (split)" : "") + ", load A " + (v.loadA() ? "lit" : "dark") + ", load B " + (v.loadB() ? "lit" : "dark") + ", tries " + v.tries());
            pass("7 the toolkit at the panel opens the repair: a circuit of " + v.parts().length + " parts" + (v.split() ? " in two branches" : "") + ", " + v.tries() + " tries, " + v.secondsLeft() + " s");
        });
        solveTrace();
        until("the repair ends", () -> !(mc().screen instanceof TraceScreen), 200);
        Playtest.wait("the pay lands", 30);
        shot("5-repaired");
        Playtest.queue("the repair's pay and the building", () -> {
            long paid = Math.round(server(() -> QShopEconomy.balance(me())) - F.balanceBefore);
            int xp = ThiefScenario.citizen().careerXp() - F.xpBefore;
            int expected = server(() -> {
                CitizenState.Citizen c = CitizenState.get(mc().getSingleplayerServer()).find(me().getUUID()).orElseThrow();
                return SkillRules.payout(TechService.repairFee(F.panel.tier()), false, SkillCatalog.INSTANCE.tree(c.career), c.skills());
            });
            boolean cleared = server(() -> !TechService.systems(level().getServer()).faults.containsKey(F.panel.key()));
            boolean lamp = server(() -> level().getBlockState(F.panel.pos()).getValue(TechBlocks.UtilityPanel.STATE) == TechBlocks.UtilityPanel.State.OK);
            Playtest.log("TECH repair: " + ClientTech.lastEnd + " (paid " + paid + ", expected " + expected + ", XP +" + xp + ", cleared " + cleared + ", lamp ok " + lamp + ")");
            if (!ClientTech.lastDone || paid != expected || xp <= 0 || !cleared || !lamp) Playtest.fail("the repair did not pay once and put the building right");
            else pass("8 the repair, worked with real clicks, pays " + paid + " credits and " + xp + " XP once, clears the fault and turns the lamp green");
        });
        xaeroCleared("8b", "the repair");
    }

    /**
     * Works the circuit as a person would from what the screen shows: the loads tell which line has the dead part (both dark: the trunk; one dark: its branch), a binary search with probes on that
     * line finds it, then Replace on it. Every probe and the replacement are real clicks on the parts.
     */
    private static void solveTrace() {
        final int[] state = {0, 0, -1, 0};   // ticks, waiting probes, chosen part, replaced
        final int[] range = new int[2];
        final boolean[] ranged = {false};
        Playtest.queue("find the dead part with probes, then replace it", () -> { state[0] = 0; ranged[0] = false; }, () -> {
            if (!(mc().screen instanceof TraceScreen s)) return true;
            TechNetwork.TraceView v = s.view();
            if (++state[0] % 14 != 7) return false;
            if (!ranged[0]) {
                int line = !v.split() ? 0 : !v.loadA() && !v.loadB() ? 0 : !v.loadA() ? 1 : 2;
                range[0] = line == 0 ? 0 : line == 1 ? v.trunk() : v.trunk() + v.branch();
                range[1] = line == 0 ? v.trunk() - 1 : range[0] + v.branch() - 1;
                if (v.scanLo() >= 0) { range[0] = Math.max(range[0], v.scanLo()); range[1] = Math.min(range[1], v.scanHi()); }
                ranged[0] = true;
                Playtest.log("TECH the loads put the dead part in parts " + range[0] + " to " + range[1]);
            }
            // narrow the range with the readings so far: a live point after part i puts the dead part after i; a dead point puts it at or before i
            for (int i = range[0]; i <= range[1]; i++) {
                if (v.readings()[i] == 1) range[0] = Math.max(range[0], i + 1);
                if (v.readings()[i] == 0) range[1] = Math.min(range[1], i);
            }
            if (range[0] >= range[1]) {
                if (s.replacing()) {
                    if (state[3] == 0) {
                        int[] c = s.partCentre(range[0]);
                        Playtest.clickGui(c[0], c[1]);
                        state[3] = 1;
                        Playtest.log("TECH replace part " + range[0] + " after " + v.probes() + " probes");
                    }
                } else {
                    Playtest.tap("r");
                }
                return false;
            }
            int mid = (range[0] + range[1]) / 2;
            if (v.readings()[mid] < 0) {
                int[] c = s.partCentre(mid);
                Playtest.clickGui(c[0], c[1]);
            }
            return false;
        }, 1600);
    }

    // ------------------------------------------------------------------------------------------------ the bench

    private static void bench() {
        Playtest.queue("a bench beside the player and two iron ingots (scripted)", () -> {
            scripted("an Electronics Bench was set beside the player and two iron ingots given (the bench is crafted from planks, iron, copper and redstone)");
            F.bench = server(() -> {
                var p = me();
                p.getInventory().add(new ItemStack(Items.IRON_INGOT, 2));
                BlockPos c = freeCellNear(p.blockPosition(), F.computer);
                if (c != null) level().setBlock(c, UndergroundCity.ELECTRONICS_BENCH.get().defaultBlockState().setValue(TechBlocks.Bench.FACING, Direction.fromYRot(p.getYRot()).getOpposite()), 3);
                return c;
            });
            if (F.bench == null) Playtest.fail("no room for the bench");
        });
        useBlock("the bench", () -> F.bench, () -> mc().screen instanceof BenchScreen, null);
        Playtest.wait("draw", 10);
        final int[] toolkits = {0};
        Playtest.queue("the bench's designs", () -> {
            if (!(mc().screen instanceof BenchScreen s)) { Playtest.fail("the bench screen did not open"); return; }
            Playtest.log("TECH bench: " + s.designs().stream().map(d -> d.id() + (d.allowed() ? "" : " (needs " + d.skill() + ")")).toList());
            boolean boardsGated = s.designs().stream().anyMatch(d -> d.id().equals("circuit_boards") && !d.allowed());
            boolean kitOpen = s.designs().stream().anyMatch(d -> d.id().equals("repair_kit") && d.allowed());
            if (!kitOpen) { Playtest.fail("the toolkit design is not open to a new Technician"); return; }
            if (!boardsGated && F.panel.tier() <= 2) Playtest.fail("circuit boards were open without Bench Electronics");
            if (!s.designs().stream().anyMatch(d -> d.id().equals("security_array") && !d.allowed())) Playtest.fail("the security array was open without Automation Installer");
            s.select("repair_kit");
            toolkits[0] = count(item("repair_kit"));
        });
        Playtest.wait("draw", 6);
        shot("6-bench");
        JourneyScenario.click("Assemble", true);
        until("the bench says it was made", () -> mc().screen instanceof BenchScreen s && s.note() != null && !s.note().isEmpty(), 100);
        Playtest.queue("the toolkit", () -> {
            String note = mc().screen instanceof BenchScreen s ? s.note() : "";
            int now = count(item("repair_kit"));
            Playtest.log("TECH bench: " + note + " (toolkits " + toolkits[0] + " -> " + now + ")");
            if (!note.startsWith("Made") || now != toolkits[0] + 1) Playtest.fail("the bench did not make a toolkit from the carried parts: " + note);
            else pass("9 the Electronics Bench makes a toolkit from carried iron, copper and a board (a real click on Assemble), and shows the security array as needing Automation Installer");
        });
        shot("7-bench-made");
        JourneyScenario.closeScreen("close the bench");
    }

    // ------------------------------------------------------------------------------------------------ the install

    private static void install() {
        Playtest.queue("an Installer with a security array, and an install job at the panel (scripted)", () -> {
            scripted("Technician level 5, Bench Electronics, Municipal Repairs and Automation Installer, a Municipal Security Array, and an install job at the same building were given");
            server(() -> {
                var server = mc().getSingleplayerServer();
                var citizens = CitizenState.get(server);
                CitizenState.Citizen c = citizens.find(me().getUUID()).orElseThrow();
                int need = 125 * 4 * 4;   // career level 5 (ProgressionRules.levelForXp)
                if (c.careerXp() < need) citizens.addCareerXp(me().getUUID(), need - c.careerXp());
                for (String skill : new String[] {"technician.fundamentals", "technician.municipal_repairs", "technician.automation_install"}) citizens.unlockSkill(me().getUUID(), skill);
                me().getInventory().add(new ItemStack(item("security_array")));
                TechService.Systems s = TechService.systems(server);
                // the service never opens two jobs at one building: the job is given only when the building has none already (tech run 6 tracked the city's own while the panel took the script's)
                if (s.jobs.values().stream().noneMatch(j -> j.key() == F.panel.key()))
                    TechService.newJob(s, "install", F.panel, TechService.installFee(F.panel.tier()), TechService.installXp(F.panel.tier()), LockService.clock(server));
                else if (s.jobs.values().stream().noneMatch(j -> j.key() == F.panel.key() && j.kind().equals("install"))) {
                    s.jobs.values().removeIf(j -> j.key() == F.panel.key());
                    TechService.newJob(s, "install", F.panel, TechService.installFee(F.panel.tier()), TechService.installXp(F.panel.tier()), LockService.clock(server));
                }
                return null;
            });
        });
        trackFromComputer("the install job", () -> F.panel.address(), "install");
        xaeroShows("9b", "the install job", "Alarm install");
        Playtest.queue("back to the panel", () -> F.balanceBefore = server(() -> QShopEconomy.balance(me())));
        useBlock("the panel with the security array", () -> F.panel.pos(), () -> mc().screen instanceof PatchScreen, item("security_array"));
        Playtest.wait("draw", 10);
        shot("8-install-patch");
        Playtest.queue("the grid's answer (read from the server)", () -> {
            TechWork.Session s = server(() -> TechWork.sessionOf(me().getUUID()));
            if (s == null || s.patch == null) { Playtest.fail("no install session"); return; }
            F.solution = s.patch.solution.clone();
            scripted("the patch grid's answer was read from the server; the turns were real clicks");
            Playtest.log("TECH patch grid " + s.patch.w + "x" + s.patch.h + ", " + s.patch.outputs.length + " devices");
        });
        final int[] t = {0};
        Playtest.queue("turn the tiles with real clicks", () -> t[0] = 0, () -> {
            if (!(mc().screen instanceof PatchScreen s)) return true;
            if (++t[0] % 10 != 5) return false;
            TechNetwork.PatchView v = s.view();
            for (int i = 0; i < v.tiles().length; i++) {
                if (v.tiles()[i] == F.solution[i]) continue;
                int[] c = s.cellCentre(i);
                Playtest.clickGui(c[0], c[1]);
                return false;
            }
            return false;
        }, 2400);
        Playtest.wait("the pay lands", 30);
        Playtest.queue("the monitored alarm", () -> {
            long paid = Math.round(server(() -> QShopEconomy.balance(me())) - F.balanceBefore);
            boolean monitored = server(() -> {
                Long until = TechService.systems(level().getServer()).monitored.get(F.panel.key());
                return until != null && LockService.clock(level().getServer()) < until;
            });
            Playtest.log("TECH install: " + ClientTech.lastEnd + " (paid " + paid + ", monitored " + monitored + ")");
            if (!ClientTech.lastDone || paid <= 0 || !monitored) Playtest.fail("the install did not pay and fit a monitored alarm");
            else pass("10 the install, worked on the patch grid with real clicks, pays " + paid + " credits and fits a monitored alarm (always armed)");
        });
        shot("9-installed");
        xaeroCleared("10b", "the install");
    }

    // ------------------------------------------------------------------------------------------------ the record across a reopen

    private static void reopen() {
        Playtest.queue("a second call, at another building (scripted)", () -> {
            F.second = server(() -> {
                BlockPos here = me().blockPosition();
                TechService.Panel best = null;
                for (TechService.Panel p : TechService.systems(level().getServer()).panels.values()) {
                    if (p.pos() == null || p.key() == F.panel.key() || p.tier() > 2 || p.pos().distSqr(here) > 96 * 96) continue;
                    if (best == null || p.pos().distSqr(here) < best.pos().distSqr(here)) best = p;
                }
                if (best != null) TechService.open(level().getServer(), level(), best, LockService.clock(level().getServer()));
                return best;
            });
            if (F.second == null) Playtest.fail("no second panel for a call");
            else scripted("a second fault was raised at " + F.second.address());
        });
        trackFromComputer("the second call", () -> F.second.address(), "repair");
        xaeroShows("11a", "the second call", "Till repair|Alarm repair");
        final long[] before = new long[3];
        Playtest.queue("save and quit to the title screen, then open the world again", () -> {
            server(() -> {
                TechService.Systems s = TechService.systems(mc().getSingleplayerServer());
                before[0] = s.repairs; before[1] = s.installs; before[2] = s.panels.size();
                return null;
            });
            var mc = mc();
            String folder = System.getProperty("uc.playtest.world", "UC-Playtest");
            Playtest.log("TECH leaving world " + folder + ": repairs " + before[0] + ", installs " + before[1] + ", panels " + before[2]);
            mc.level.disconnect();
            mc.clearLevel(new GenericDirtMessageScreen(Component.translatable("menu.savingLevel")));
            mc.setScreen(new TitleScreen());
            mc.createWorldOpenFlows().loadLevel(mc.screen, folder);
        }, () -> mc().player != null && mc().level != null && Scenarios.groundBelowPlayer(), 6000);
        Playtest.wait("settle", 60);
        Playtest.queue("the record after the reopen", () -> {
            List<String> wrong = new ArrayList<>();
            server(() -> {
                TechService.Systems s = TechService.systems(mc().getSingleplayerServer());
                if (s.repairs != before[0] || s.installs != before[1]) wrong.add("counts " + s.repairs + "/" + s.installs);
                if (s.panels.size() < before[2]) wrong.add("panels " + s.panels.size());
                if (s.faults.containsKey(F.panel.key())) wrong.add("the repaired fault came back");
                Long until = s.monitored.get(F.panel.key());
                if (until == null || LockService.clock(mc().getSingleplayerServer()) >= until) wrong.add("the monitored alarm was lost");
                return null;
            });
            Playtest.log("TECH after the reopen: " + (wrong.isEmpty() ? "as before" : wrong));
            if (!wrong.isEmpty()) Playtest.fail("the Technician's record changed across the reopen: " + wrong);
            else pass("11 after a save, quit and reopen the panels, the repair and the monitored alarm are as they were");
        });
        until("the open call is tracked again", () -> ClientNavigation.current() != null, 200);
        until("its waypoint back on Xaero's minimap", () -> noXaero() || XaeroWaypoints.showing(), 400);
        Playtest.wait("draw", 20);
        Playtest.queue("the tracking after the reopen", () -> {
            Navigation.Destination d = ClientNavigation.current();
            int ours = XaeroWaypoints.ours();
            Playtest.log("TECH tracking after the reopen: " + (d == null ? "nothing" : d.title()) + "; " + XaeroWaypoints.describe() + ", ours " + ours);
            if (d == null || !d.title().contains(F.second.address())) Playtest.fail("the open call was not tracked after the reopen");
            else if (!noXaero() && ours != 1) Playtest.fail("after the reopen Underground City has " + ours + " waypoints on Xaero's map, not one");
            else pass("12 after the reopen the still-open call is tracked again" + (noXaero() ? "" : ", one waypoint on Xaero's minimap and no duplicate"));
        });
        shot("12-reopened");
    }
}
