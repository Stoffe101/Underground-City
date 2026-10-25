package io.github.stoffe101.undergroundcity;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

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
 * The Smith as an engineer through a real client (eighth pass, D-182): turning by hand and the cities' machinery. Two tracks, never mixed up.
 *
 * <p><b>Track A, organic:</b> a new Smith places the kit's Machinist's Bench near Ironhaven's Builders' Yard and opens a drive shaft's drawing with a real click on Turn. On the first
 * drawing they make a mistake on purpose (a section roughed onto its line, which spoils it) and sleeve it back with a nugget, then close the drawing: nothing is taken. They open a
 * new drawing and turn it to the shop's standard with real clicks on the sections, the tool, the depth and Cut; the shaft comes out Reserve and the bench keeps its jig. Scripted,
 * each logged as SCRIPTED: the clock (13:00, stopped), the trip to Ironhaven and the start in front of the yard.
 *
 * <p><b>Track B, prepared:</b> the Smith's XP is raised to level 5 and 300 credits are added, as after a few hours of work (SCRIPTED). Then real clicks: Forge Fundamentals and
 * Millwright on the Skills page of the hardware shop's public terminal. A breakdown is raised at once at a machine near the player (SCRIPTED: the service raises one every 10 to 16
 * minutes of play in a city somebody is in). The Smith hears the call, opens the toolkit's list of calls (a right click in the air) and tracks it, buys the parts the drive needs
 * over the yard's counter, walks to the machine, opens its drive with the toolkit, runs it once before mending anything (a refusal that costs a test run), replaces the worn parts,
 * gears the open stages to the plate with real clicks and runs it: paid once, the machine running again. The world is saved, left and opened again.
 */
final class EngineerScenario {
    private EngineerScenario() {}

    private static final class Facts {
        double walletBefore;
        int xpBefore, ironBefore, nuggetsBefore;
        MachineService.Machine machine;
        UUID breakdown;
        List<String> needs = List.of();
        boolean wasShort;
    }

    private static final Facts E = new Facts();
    private static final SmithScenario.Facts F = SmithScenario.F;
    /** The yard's shop line for each part a drive takes. */
    private static final Map<String, String> YARD = Map.of(
        "gear_set", UndergroundCity.MOD_ID + ":shops/yard_gears_ironhaven", "bearing_set", UndergroundCity.MOD_ID + ":shops/yard_bearings_ironhaven",
        "steel_bracket", UndergroundCity.MOD_ID + ":shops/yard_brackets_ironhaven", "springs", UndergroundCity.MOD_ID + ":shops/yard_springs_ironhaven");

    static void build() {
        ThiefScenario.tag = "ENGINEER";
        Playtest.keepScreens = true;
        // ------------------------------------------------------------------ Track A
        ThiefScenario.register(2, "the Smith card", "1 (A) a new citizen registers as a Smith through the registration screen");
        Playtest.queue("the kit", () -> {
            int toolkits = JourneyScenario.count(SmithWork.item("engineer_toolkit")), bearings = JourneyScenario.count(SmithWork.item("bearing_set"));
            int iron = JourneyScenario.count(Items.IRON_INGOT), nuggets = JourneyScenario.count(Items.IRON_NUGGET);
            Playtest.log("ENGINEER kit: toolkit " + toolkits + ", bearing sets " + bearings + ", iron " + iron + ", nuggets " + nuggets);
            if (toolkits != 1 || bearings != 2 || iron < 6 || nuggets < 12) Playtest.fail("the Smith's kit does not hold an Engineer's Toolkit, two bearing sets, six iron and twelve nuggets");
            else pass("2 (A) a new Smith's kit holds the bench, six iron, twelve nuggets, an Engineer's Toolkit and two bearing sets");
        });
        Playtest.queue("the clock: 13:00 with the daylight cycle stopped", () -> {
            Playtest.command("gamerule doDaylightCycle false");
            Playtest.command("time set 7000");
            scripted("the time of day was set to 13:00 and the daylight cycle stopped (the yard and the hardware desk are open by day)");
        });
        ThiefScenario.travel("to the middle of Ironhaven", () -> ThiefScenario.centre("ironhaven"), 6);
        Playtest.queue("the Builders' Yard and Hardware and Metals near the middle of Ironhaven", () -> server(() -> {
            F.yard = SmithScenario.nearestOpen("ih_warehouse");
            F.hardware = SmithScenario.nearestOpen("ih_workshop");
            if (F.yard != null) F.yardDoor = ThiefScenario.streetDoor(level(), F.yard);
            if (F.hardware != null) F.hardwareDoor = ThiefScenario.streetDoor(level(), F.hardware);
            if (F.yard == null || F.yardDoor == null || F.hardware == null || F.hardwareDoor == null) Playtest.fail("no open Builders' Yard and Hardware and Metals with street doors near the middle of Ironhaven");
            return null;
        }));
        ThiefScenario.stopIfFailed("an open yard and hardware shop");
        Playtest.queue("start on the street outside the yard (scripted)", () -> {
            scripted("the player was put on the street outside the Builders' Yard's door at " + F.yardDoor.door().toShortString() + " in survival");
            ThiefScenario.standAt(F.yardDoor);
        }, () -> mc().player.position().distanceTo(F.yardDoor.stand()) < 0.8, 200);
        Playtest.wait("settle", 20);
        SmithScenario.placeBench();
        ThiefScenario.stopIfFailed("placing the bench");
        SmithScenario.openBench("a drive shaft's drawing");
        selectRow("drive_shaft");
        Playtest.queue("the drive shaft is turned by hand", () -> {
            var row = mc().screen instanceof WorkshopScreen s ? s.view().rows().stream().filter(r -> r.id().equals("drive_shaft")).findFirst().orElse(null) : null;
            if (row == null || !row.turned() || row.jigSet() >= 0) Playtest.fail("the bench does not offer the drive shaft as a part turned by hand with no jig yet");
        });
        shot("1-bench-turn");
        JourneyScenario.click("Turn", true);
        until("the drawing opens", () -> mc().screen instanceof TurningScreen, 200);
        Playtest.wait("draw", 12);
        Playtest.queue("the drawing", () -> {
            var v = ((TurningScreen) mc().screen).view();
            E.ironBefore = JourneyScenario.count(Items.IRON_INGOT);
            E.nuggetsBefore = JourneyScenario.count(Items.IRON_NUGGET);
            Playtest.log("ENGINEER drawing " + java.util.Arrays.toString(v.target()) + ", roughing " + v.rough() + ", standard " + v.standard());
            if (v.rough() != 2 || v.standard() < 1) Playtest.fail("a Vice and Files drawing does not rough two steps or has no standard");
            else pass("3 (A) Turn (a real click) opens the drive shaft's drawing: " + v.target().length + " sections, roughing two steps, a standard of " + v.standard() + " passes");
        });
        shot("2-drawing");
        // the mistake: a section roughed onto its own line (tool marks that can only be finished away below it), then a sleeve
        final int[] spoilt = {-1};
        Playtest.queue("a section to spoil on purpose", () -> {
            var v = ((TurningScreen) mc().screen).view();
            int best = 0;
            for (int i = 1; i < v.target().length; i++) if (v.target()[i] > v.target()[best]) best = i;
            spoilt[0] = best;
        });
        clicks("rough section onto its line (a mistake)", () -> {
            var v = ((TurningScreen) mc().screen).view();
            List<Click> out = new ArrayList<>();
            int i = spoilt[0], r = v.radius()[i];
            boolean finishing = ((TurningScreen) mc().screen).finishing();
            while (r > v.target()[i]) {
                int line = Math.max(v.target()[i], r - v.rough());
                selectSection(out, i, i);
                tool(out, false, finishing);
                finishing = false;
                depth(out, Math.max(1, r - 1), line);
                out.add(serverClick("Cut", () -> turning() == null ? null : turning().centreOf("cut"), EngineerScenario::turnView));
                r = line;
            }
            return out;
        });
        Playtest.queue("the section is spoiled", () -> {
            var v = turnView();
            int i = spoilt[0];
            if (v == null || v.radius()[i] != v.target()[i] || !v.marked()[i]) Playtest.fail("the section was not roughed onto its line");
        });
        shot("3-spoiled");
        clicks("sleeve the spoiled section", () -> {
            List<Click> out = new ArrayList<>();
            selectSection(out, spoilt[0], spoilt[0]);
            out.add(serverClick("Sleeve", () -> turning() == null ? null : turning().centreOf("sleeve"), EngineerScenario::turnView));
            return out;
        });
        Playtest.queue("sleeved", () -> {
            var v = turnView();
            int i = spoilt[0];
            if (v == null || v.sleeves() != 1 || v.radius()[i] != v.target()[i] + 1) Playtest.fail("the sleeve did not build the section back up a step");
            else pass("4 (A) a mistake on purpose: a section roughed onto its line is spoiled (it can only be finished below the line), and Sleeve (a real click) builds it back up a step; the part can be Fine at best");
        });
        shot("4-sleeved");
        JourneyScenario.closeScreen("close the drawing");
        Playtest.wait("settle", 10);
        Playtest.queue("nothing was taken", () -> {
            int iron = JourneyScenario.count(Items.IRON_INGOT), nuggets = JourneyScenario.count(Items.IRON_NUGGET), shafts = JourneyScenario.count(SmithWork.item("drive_shaft"));
            Playtest.log("ENGINEER after closing: iron " + iron + ", nuggets " + nuggets + ", shafts " + shafts);
            if (iron != E.ironBefore || nuggets != E.nuggetsBefore || shafts != 0) Playtest.fail("closing the drawing took something or made something");
            else pass("5 (A) closing the drawing (Escape) takes nothing: the iron and the sleeve's nugget are still carried, no shaft made");
        });
        JourneyScenario.closeScreen("close the bench");
        SmithScenario.openBench("a new drawing");
        selectRow("drive_shaft");
        Playtest.queue("before turning", () -> { CitizenState.Citizen c = ThiefScenario.citizen(); E.xpBefore = c == null ? 0 : c.careerXp(); });
        JourneyScenario.click("Turn", true);
        until("the new drawing opens", () -> mc().screen instanceof TurningScreen, 200);
        Playtest.wait("draw", 12);
        turnToStandard();
        until("the shaft is made", () -> JourneyScenario.count(SmithWork.item("drive_shaft")) == 1 && !(mc().screen instanceof TurningScreen), 200);
        Playtest.queue("a Reserve drive shaft and the jig", () -> {
            ItemStack shaft = ItemStack.EMPTY;
            for (ItemStack s : mc().player.getInventory().items) if (s.is(SmithWork.item("drive_shaft"))) shaft = s;
            CitizenState.Citizen c = ThiefScenario.citizen();
            int xp = c == null ? 0 : c.careerXp();
            Integer jig = server(() -> level().getBlockEntity(F.bench) instanceof WorkshopBlock.Entity e ? e.jigs.get("drive_shaft") : null);
            Playtest.log("ENGINEER made: shaft grade " + Workshops.grade(shaft) + ", XP " + E.xpBefore + " -> " + xp + ", jig " + jig + ", iron " + JourneyScenario.count(Items.IRON_INGOT) + "; " + ClientSmith.lastEnd);
            if (Workshops.grade(shaft) != 2 || xp != E.xpBefore + 45 || jig == null || jig != 2) Playtest.fail("the shaft turned to the standard is not Reserve, paid the wrong XP or left no jig");
            else pass("6 (A) a new drawing turned to the shop's standard with real clicks on the sections, the tool, the depth and Cut: a Reserve drive shaft (+45 XP, half again for Reserve), three iron taken, and the bench keeps a Reserve jig for it");
        });
        shot("5-turned");
        // ------------------------------------------------------------------ Track B
        Playtest.queue("Track B: prepared (scripted)", () -> {
            scripted("TRACK B begins: the Smith's XP was raised to level 5 (2,000 XP) and 300 credits were added, as after a few hours of work");
            server(() -> {
                CitizenState state = CitizenState.get(mc().getSingleplayerServer());
                int xp = state.find(me().getUUID()).map(CitizenState.Citizen::careerXp).orElse(0);
                state.addCareerXp(me().getUUID(), Math.max(0, 2000 - xp));
                QShopEconomy.applyBalance(me(), QShopEconomy.balance(me()) + 300);
                return null;
            });
        });
        Playtest.queue("the hardware shop's public terminal", () -> {
            F.publicTerminal = server(() -> SmithScenario.terminalIn(F.hardware));
            if (F.publicTerminal == null) Playtest.fail("Hardware and Metals has no public terminal");
        });
        ThiefScenario.stopIfFailed("a public terminal");
        SmithScenario.enterBuilding("Hardware and Metals", () -> F.hardwareDoor);
        ThiefScenario.walkOrTeleport("walk to the public terminal", () -> SmithScenario.standBeside(F.publicTerminal), 0.8, 900);
        JourneyScenario.use("open the public terminal", () -> F.publicTerminal, () -> mc().screen instanceof TerminalScreen, 400);
        StationSteps.learn("smith.fundamentals", "Forge Fundamentals");
        StationSteps.learn("smith.millwright", "Millwright");
        Playtest.queue("both skills", () -> {
            CitizenState.Citizen c = ThiefScenario.citizen();
            if (c == null || !c.skills().contains("smith.fundamentals") || !c.skills().contains("smith.millwright")) Playtest.fail("the skills were not learned");
            else pass("7 (B) Forge Fundamentals and Millwright learned with real clicks on the Skills page (machinery to tier IV, a fourth test run)");
        });
        shot("6-millwright");
        JourneyScenario.closeScreen("close the terminal");
        // the call
        until("a machine within reach is known near the player", () -> pickMachine() != null, 1200);
        Playtest.queue("a breakdown at it (scripted)", () -> {
            E.machine = pickMachine();
            if (E.machine == null) { Playtest.fail("no machine of tier IV or below with a drive housing near the player"); return; }
            ClientNotice.lastTitle = "";
            scripted("a breakdown was raised at once at the " + E.machine.name() + " of " + E.machine.label() + ", " + E.machine.address() + " (tier " + E.machine.tier()
                + "; the service raises one every 10 to 16 minutes of play in a city somebody is in)");
            MachineService.Breakdown b = server(() -> MachineService.open(level().getServer(), level(), E.machine, LockService.clock(level().getServer())));
            E.breakdown = b.id();
            E.needs = server(() -> SmithWork.driveOf(b, E.machine, 4).uses());
            E.wasShort = !E.machine.industrial();
            Playtest.log("ENGINEER the drive will take " + E.needs);
        });
        ThiefScenario.stopIfFailed("a breakdown");
        until("the notice", () -> "Service call".equals(ClientNotice.lastTitle), 200);
        Playtest.queue("the call", () -> {
            boolean stopped = server(() -> !level().getBlockState(E.machine.pos()).getValue(MachineBlock.RUNNING));
            if (!stopped) Playtest.fail("the machine's housing does not show it stopped");
            else pass("8 (B) a service call: the Smith in the city is told, and the " + E.machine.name() + "'s housing shows it stopped" + (E.machine.industrial() ? " (the works stands idle)" : " (the shop runs short)"));
        });
        shot("7-service-call");
        holdToolkit();
        Playtest.queue("right-click in the air with the toolkit", () -> {
            Playtest.focusWindow();
            look(mc().player.getEyePosition().add(0, 30, 0.01));
        });
        Playtest.wait("aim", 10);
        Playtest.queue("use the toolkit", () -> { Playtest.mouseButton(3, true); Playtest.mouseButton(3, false); }, () -> mc().screen instanceof ServiceCallsScreen, 200);
        Playtest.wait("draw", 12);
        Playtest.queue("the call on the list", () -> {
            var calls = ((ServiceCallsScreen) mc().screen).calls();
            SmithWork.Call call = calls.stream().filter(c -> c.id().equals(E.breakdown)).findFirst().orElse(null);
            Playtest.log("ENGINEER calls: " + calls.stream().map(c -> c.machine() + " at " + c.address() + " (" + c.needs() + c.blocked() + ")").toList());
            if (call == null || !call.blocked().isEmpty()) Playtest.fail("the toolkit's list does not show the call, or shows it out of reach");
        });
        shot("8-calls");
        JourneyScenario.clickAt("Track on the call", () -> {
            if (!(mc().screen instanceof ServiceCallsScreen s)) return null;
            var calls = s.calls();
            for (int i = 0; i < calls.size(); i++) if (calls.get(i).id().equals(E.breakdown)) return s.trackCentre(i);
            return null;
        });
        until("tracking", () -> ClientNavigation.current() != null, 100);
        Playtest.queue("tracked", () -> {
            var d = ClientNavigation.current();
            if (d == null) Playtest.fail("Track did not make the machine the destination");
            else pass("9 (B) the toolkit (a right click in the air) lists the call with what to bring; Track (a real click) points the way to it (" + d.title() + ")");
        });
        JourneyScenario.closeScreen("close the list");
        buyParts();
        // the repair
        final Vec3[] stand = new Vec3[1];
        Playtest.queue("a place in front of the machine", () -> stand[0] = server(() -> {
            BlockPos p = E.machine.pos();
            Direction out = level().getBlockState(p).getValue(MachineBlock.FACING);
            BlockPos cell = p.relative(out, 2);
            return StaffZones.walkable(level(), cell) ? Vec3.atBottomCenterOf(cell) : Vec3.atBottomCenterOf(p.relative(out));
        }));
        ThiefScenario.walkOrTeleport("walk to the machine", () -> stand[0] == null ? mc().player.position() : stand[0], 0.8, 2400);
        holdToolkit();
        Playtest.queue("before the repair", () -> { E.walletBefore = JourneyScenario.wallet(); CitizenState.Citizen c = ThiefScenario.citizen(); E.xpBefore = c == null ? 0 : c.careerXp(); });
        JourneyScenario.use("open the drive with the toolkit", () -> E.machine.pos(), () -> mc().screen instanceof DriveScreen, 600);
        Playtest.wait("draw", 12);
        Playtest.queue("the drive", () -> {
            var v = driveView();
            if (v == null) { Playtest.fail("the drive did not open"); return; }
            int worn = 0;
            for (int s : v.state()) if (s == 1) worn++;
            Playtest.log("ENGINEER drive: motor " + v.motor() + ", plate " + v.lo() + "-" + v.hi() + (v.clockwise() ? " cw" : " ccw") + ", output " + v.output() + ", worn " + worn + ", tests " + v.tests() + ", needs " + v.needs());
            if (worn < 1 || v.tests() != 4) Playtest.fail("the drive shows no worn part, or not the four test runs a Millwright has");
        });
        shot("9-drive");
        clicks("Run test before mending (a mistake)", () -> List.of(serverClick("Run test", () -> drive() == null ? null : drive().centreOf("run"), EngineerScenario::driveView)));
        Playtest.queue("refused", () -> {
            var v = driveView();
            Playtest.log("ENGINEER the first run: " + (v == null ? "?" : v.note() + " (" + v.tests() + " left)"));
            if (v == null || v.tests() != 3 || MachineService.works(mc().getSingleplayerServer()).breakdowns.get(E.machine.key()) == null) Playtest.fail("a run before mending did not cost a test run, or ended the call");
            else pass("10 (B) a test run before mending anything is refused with the reason (\"" + v.note() + "\") and costs one of the four runs; nothing is used");
        });
        shot("10-refused");
        clicks("replace the worn parts", () -> {
            var v = driveView();
            List<Click> out = new ArrayList<>();
            for (int i = 0; i < v.state().length; i++) if (v.state()[i] == 1) { int part = i; out.add(serverClick("worn part " + i, () -> drive() == null ? null : drive().partCentre(part), EngineerScenario::driveView)); }
            return out;
        });
        Playtest.queue("replaced", () -> {
            var v = driveView();
            for (int s : v.state()) if (s == 1) { Playtest.fail("a worn part is still in: " + v.note()); return; }
        });
        clicks("gear the open stages to the plate", () -> {
            var v = driveView();
            int[][] goal = solve(v);
            if (goal == null) { Playtest.fail("no gearing of the open stages meets the plate"); return List.of(); }
            List<Click> out = new ArrayList<>();
            for (int s = 0; s < v.open().length; s++) {
                if (!v.open()[s]) continue;
                int stage = s;
                for (int k = v.driver()[s]; k != goal[0][s]; k += goal[0][s] > k ? 1 : -1) { String id = "driver_" + stage + (goal[0][s] > k ? "_up" : "_down"); out.add(serverClick(id, () -> drive() == null ? null : drive().centreOf(id), EngineerScenario::driveView)); }
                for (int k = v.driven()[s]; k != goal[1][s]; k += goal[1][s] > k ? 1 : -1) { String id = "driven_" + stage + (goal[1][s] > k ? "_up" : "_down"); out.add(serverClick(id, () -> drive() == null ? null : drive().centreOf(id), EngineerScenario::driveView)); }
                if (v.idler()[s] != (goal[2][s] == 1)) out.add(serverClick("idler_" + stage, () -> drive() == null ? null : drive().centreOf("idler_" + stage), EngineerScenario::driveView));
            }
            return out;
        });
        Playtest.queue("on the plate", () -> {
            var v = driveView();
            Playtest.log("ENGINEER geared: output " + v.output() + (v.outClockwise() ? " cw" : " ccw") + " against " + v.lo() + "-" + v.hi() + (v.clockwise() ? " cw" : " ccw"));
            if (v.output() < v.lo() || v.output() > v.hi() || v.outClockwise() != v.clockwise()) Playtest.fail("the gearing does not meet the plate");
        });
        shot("11-geared");
        Playtest.queue("Run test", () -> ClientSmith.lastEnd = "");
        clicks("Run test", () -> List.of(click("Run test", () -> drive() == null ? null : drive().centreOf("run"))));
        until("the machine runs", () -> !ClientSmith.lastEnd.isEmpty(), 200);
        Playtest.wait("settle", 10);
        Playtest.queue("paid once, the machine running", () -> {
            CitizenState.Citizen c = ThiefScenario.citizen();
            double paid = JourneyScenario.wallet() - E.walletBefore;
            int xp = c == null ? 0 : c.careerXp();
            boolean running = server(() -> level().getBlockState(E.machine.pos()).getValue(MachineBlock.RUNNING));
            boolean open = server(() -> MachineService.works(level().getServer()).breakdowns.containsKey(E.machine.key()));
            boolean well = server(() -> MachineService.runsWell(level().getServer(), E.machine.key()));
            int fee = server(() -> SkillRules.payout(MachineService.fee(E.machine.tier()), false, SkillCatalog.INSTANCE.tree("smith"), ThiefScenario.citizen().skills()));
            Playtest.log("ENGINEER repaired: " + ClientSmith.lastEnd + " paid " + paid + " (fee " + fee + "), XP " + E.xpBefore + " -> " + xp + ", running " + running + ", call open " + open + ", runs well " + well + ", tracking " + ClientNavigation.current());
            if (!ClientSmith.lastDone || Math.abs(paid - fee) > 0.01 || xp <= E.xpBefore || !running || open || !well) Playtest.fail("the repair was not paid once with the machine running again");
            else pass("11 (B) the worn parts replaced and the open stages geared to the plate with real clicks; Run test: +" + Math.round(paid) + " credits once and +" + (xp - E.xpBefore)
                + " XP, the " + E.machine.name() + " running again and running well" + (E.machine.industrial() ? ", the works' catch-up shift on the exchange" : ", the shop supplied again"));
        });
        until("the tracking ends", () -> ClientNavigation.current() == null, 200);
        shot("12-running");
        reconnect();
        Playtest.queue("summary", ThiefScenario::summary);
    }

    // ------------------------------------------------------------------------------------------------ the bench and the drawing

    static void selectRow(String recipe) {
        final int[] wheel = {0};
        Playtest.queue("turn the wheel until the " + recipe + " row is in view", () -> wheel[0] = 0, () -> {
            if (!(mc().screen instanceof WorkshopScreen s) || s.rowCentre(recipe) != null) return true;
            if (wheel[0]++ % 6 == 0) Playtest.click(5);
            return wheel[0] > 90;
        }, 120);
        StationSteps.selectRow(recipe);
        Playtest.wait("select", 4);
    }

    private static TurningScreen turning() { return mc().screen instanceof TurningScreen t ? t : null; }
    private static SmithNetwork.TurnView turnView() { TurningScreen t = turning(); return t == null ? null : t.view(); }
    private static DriveScreen drive() { return mc().screen instanceof DriveScreen d ? d : null; }
    private static SmithNetwork.DriveView driveView() { DriveScreen d = drive(); return d == null ? null : d.view(); }

    /** A left click on the first section and, for a stretch, a right click on the last (the screen's own way of stretching a selection). */
    private static void selectSection(List<Click> out, int a, int b) {
        out.add(click("section " + (a + 1), () -> turning() == null ? null : turning().sectionCentre(a)));
        if (b != a) out.add(new Click("section " + (b + 1) + " (right click)", () -> turning() == null ? null : turning().sectionCentre(b), 3, null, null));
        out.add(check("the stretch " + (a + 1) + " to " + (b + 1), () -> turning() != null && turning().selection()[0] == Math.min(a, b) && turning().selection()[1] == Math.max(a, b)));
    }

    /** The tool: a click on Roughing or Finishing when it is not already chosen. */
    private static void tool(List<Click> out, boolean finish, boolean finishingNow) {
        if (finish != finishingNow) out.add(click(finish ? "Finishing" : "Roughing", () -> turning() == null ? null : turning().centreOf(finish ? "finish" : "rough")));
        out.add(check(finish ? "finishing" : "roughing", () -> turning() != null && turning().finishing() == finish));
    }

    /** The depth: from the line a click on the section set ({@code from}) to {@code to}, a click on + or - per step. */
    private static void depth(List<Click> out, int from, int to) {
        for (int k = from; k != to; k += to > k ? 1 : -1) { boolean up = to > k; out.add(click(up ? "+" : "-", () -> turning() == null ? null : turning().centreOf(up ? "up" : "down"))); }
        out.add(check("the tool at line " + to, () -> turning() != null && turning().line() == to));
    }

    /** The drawing turned by the planner's passes, every pass made with real clicks. */
    static void turnToStandard() {
        clicks("turn the drawing to the standard", () -> {
            var v = turnView();
            List<int[]> plan = SmithPuzzles.Turning.plan(v.target(), v.rough());
            Playtest.log("ENGINEER the plan for " + java.util.Arrays.toString(v.target()) + ": " + plan.stream().map(java.util.Arrays::toString).toList() + " (standard " + v.standard() + ")");
            SmithPuzzles.Turning sim = new SmithPuzzles.Turning(v.target(), v.rough());
            boolean finishing = turning().finishing();
            List<Click> out = new ArrayList<>();
            for (int[] p : plan) {
                int a = p[0], b = p[1], line = p[2];
                boolean finish = p[3] == 1;
                selectSection(out, a, b);
                tool(out, finish, finishing);
                finishing = finish;
                depth(out, Math.max(1, sim.radius[a] - 1), line);
                out.add(serverClick("Cut", () -> turning() == null ? null : turning().centreOf("cut"), () -> turning() == null ? "closed" : turning().view()));
                sim.cut(a, b, line, finish);
            }
            return out;
        });
    }

    // ------------------------------------------------------------------------------------------------ the machine

    /** The nearest machine of Ironhaven with a drive housing, running, of tier IV or below (a Millwright's reach). */
    private static MachineService.Machine pickMachine() {
        return server(() -> {
            MachineService.Works w = MachineService.works(level().getServer());
            BlockPos here = me().blockPosition();
            MachineService.Machine best = null;
            for (MachineService.Machine m : w.machines.values()) {
                if (m.pos() == null || !m.city().equals("ironhaven") || m.tier() > 4 || w.breakdowns.containsKey(m.key()) || !level().isLoaded(m.pos())) continue;
                if (best == null || m.pos().distSqr(here) < best.pos().distSqr(here)) best = m;
            }
            return best;
        });
    }

    private static void holdToolkit() {
        Item toolkit = SmithWork.item("engineer_toolkit");
        Playtest.queue("the toolkit in hand", () -> {
            Playtest.focusWindow();
            int slot = -1;
            for (int i = 0; i < 9; i++) if (mc().player.getInventory().getItem(i).is(toolkit)) slot = i;
            if (slot < 0) {
                scripted("the toolkit was moved from the backpack rows to the last hotbar slot");
                server(() -> {
                    var inv = me().getInventory();
                    for (int i = 9; i < inv.items.size(); i++) if (inv.getItem(i).is(toolkit)) { ItemStack moved = inv.getItem(i); inv.setItem(i, inv.getItem(8)); inv.setItem(8, moved); break; }
                    return null;
                });
                slot = 8;
            }
            Playtest.tap(String.valueOf(slot + 1));
        }, () -> mc().player.getMainHandItem().is(toolkit), 100);
    }

    /** The parts the drive will take that the Smith does not carry, bought over the Builders' Yard's counter with real clicks. */
    private static void buyParts() {
        SmithScenario.enterBuilding("the Builders' Yard", () -> F.yardDoor);
        final Vec3[] floor = new Vec3[1];
        Playtest.queue("a place on the yard's public floor in front of the keeper", () -> {
            TownspersonEntity k = SmithScenario.yardKeeper();
            floor[0] = StationSteps.beforeKeeper(F.yard, k);
            if (floor[0] == null) Playtest.fail("no public floor in sight of the yard's keeper");
        });
        ThiefScenario.walkOrTeleport("walk across the yard to the desk", () -> floor[0] == null ? mc().player.position() : floor[0], 0.6, 900);
        JourneyScenario.speakTo("the yard keeper", SmithScenario::yardKeeper);
        JourneyScenario.choose("What do you sell?", "terminal");
        JourneyScenario.click("TRADE", true);
        Playtest.wait("draw", 10);
        for (String id : List.of("gear_set", "bearing_set", "steel_bracket", "springs")) {
            Item item = SmithWork.item(id);
            final int[] want = {0};
            Playtest.queue("how many " + id + " to buy", () -> {
                long need = E.needs.stream().filter(id::equals).count();
                want[0] = (int) Math.max(0, need - JourneyScenario.count(item));
                Playtest.log("ENGINEER " + id + ": the drive takes " + need + ", carried " + JourneyScenario.count(item) + ", to buy " + want[0]);
            });
            // the steps are queued before the run: queue the most a drive takes of one part (four) and skip the purchases not wanted when they come up
            for (int n = 0; n < 4; n++) {
                int round = n;
                buyIf(id, YARD.get(id), item, () -> want[0] > round);
            }
        }
        Playtest.queue("the parts", () -> {
            for (String id : E.needs) {
                long need = E.needs.stream().filter(id::equals).count();
                if (JourneyScenario.count(SmithWork.item(id)) < need) { Playtest.fail("short of " + id + " for the drive"); return; }
            }
            pass("12 (B) the parts the drive takes and the Smith does not carry bought over the Builders' Yard's counter with real clicks (" + E.needs + "); no Smith ever has to make them first");
        });
        JourneyScenario.closeScreen("close the counter");
    }

    /** One purchase on a line with a real click on its Buy button, only when {@code wanted} holds as the step comes up. */
    private static void buyIf(String label, String line, Item item, BooleanSupplier wanted) {
        final int[] had = {0}, since = {0};
        final boolean[] skip = {false};
        Playtest.queue("the Buy button for " + label + " is lit", () -> skip[0] = !wanted.getAsBoolean(), () -> skip[0] || mc().screen instanceof TerminalScreen t && t.buyReady(line), 400);
        Playtest.queue("buy " + label, () -> {
            if (skip[0]) return;
            had[0] = JourneyScenario.count(item); since[0] = 0;
            if (!(mc().screen instanceof TerminalScreen t) || !t.clickBuyOffer(line)) Playtest.fail("no active BUY 1 on " + line);
        });
        Playtest.queue("bought " + label, () -> { }, () -> skip[0] || JourneyScenario.count(item) > had[0] || ++since[0] > 300, 320);
        Playtest.wait("draw", 6);
    }

    /** A gearing of the open stages that meets the plate (every choice tried, as the GameTests do); {driver, driven, idler flags}, or null. */
    private static int[][] solve(SmithNetwork.DriveView v) {
        int stages = v.driver().length, open = 0;
        for (boolean o : v.open()) if (o) open++;
        int options = SmithPuzzles.TEETH.length * SmithPuzzles.TEETH.length * 2, total = (int) Math.pow(options, open);
        int[][] best = null;
        int bestMoves = Integer.MAX_VALUE;
        for (int code = 0; code < total; code++) {
            int[] driver = v.driver().clone(), driven = v.driven().clone();
            boolean[] idler = v.idler().clone();
            int c = code, moves = 0;
            for (int s = 0; s < stages; s++) {
                if (!v.open()[s]) continue;
                int pick = c % options; c /= options;
                driver[s] = pick % SmithPuzzles.TEETH.length;
                driven[s] = (pick / SmithPuzzles.TEETH.length) % SmithPuzzles.TEETH.length;
                idler[s] = pick / (SmithPuzzles.TEETH.length * SmithPuzzles.TEETH.length) == 1;
                moves += Math.abs(driver[s] - v.driver()[s]) + Math.abs(driven[s] - v.driven()[s]) + (idler[s] != v.idler()[s] ? 1 : 0);
            }
            double out = SmithPuzzles.Drive.output(v.motor(), driver, driven);
            if (out >= v.lo() && out <= v.hi() && SmithPuzzles.Drive.clockwise(idler) == v.clockwise() && moves < bestMoves) {
                int[] flags = new int[stages];
                for (int s = 0; s < stages; s++) flags[s] = idler[s] ? 1 : 0;
                best = new int[][] {driver, driven, flags};
                bestMoves = moves;
            }
        }
        return best;
    }

    // ------------------------------------------------------------------------------------------------ real clicks, one after another

    /**
     * One click of the real pointer: moved onto {@code where}, a few ticks, pressed ({@code button} 1 left, 3 right); then, when {@code watch} is given, the step waits until what it
     * returns changes (the server's answer). A click with no {@code where} is a check: {@code ok} must hold.
     */
    record Click(String what, Supplier<int[]> where, int button, Supplier<Object> watch, BooleanSupplier ok) {}

    private static Click click(String what, Supplier<int[]> where) { return new Click(what, where, 1, null, null); }
    private static Click serverClick(String what, Supplier<int[]> where, Supplier<Object> watch) { return new Click(what, where, 1, watch, null); }
    private static Click check(String what, BooleanSupplier ok) { return new Click(what, null, 0, null, ok); }

    /** A run of clicks worked out when the step comes up (from what the screen shows then), each made with the real pointer and each waited for. */
    private static void clicks(String label, Supplier<List<Click>> make) {
        final ArrayDeque<Click> todo = new ArrayDeque<>();
        final Click[] now = {null};
        final int[] t = {0};
        final Object[] before = {null};
        Playtest.queue(label, () -> { todo.clear(); now[0] = null; Playtest.focusWindow(); todo.addAll(make.get()); }, () -> {
            if (now[0] == null) {
                now[0] = todo.poll();
                t[0] = 0;
                if (now[0] == null) return true;
            }
            Click c = now[0];
            if (c.where() == null) {
                if (c.ok() != null && !c.ok().getAsBoolean()) { Playtest.fail(label + ": " + c.what() + " does not hold"); todo.clear(); return true; }
                now[0] = null;
                return false;
            }
            if (t[0] == 0) {
                int[] at = c.where().get();
                if (at == null) { Playtest.fail(label + ": " + c.what() + " is not on the screen"); todo.clear(); return true; }
                Playtest.moveGui(at[0], at[1]);
            } else if (t[0] == 4) {
                before[0] = c.watch() == null ? null : c.watch().get();
                Playtest.mouseButton(c.button(), true);
                Playtest.mouseButton(c.button(), false);
            } else if (t[0] >= 10) {
                if (c.watch() == null || c.watch().get() != before[0]) now[0] = null;
                else if (t[0] > 200) { Playtest.fail(label + ": no answer to " + c.what()); todo.clear(); return true; }
            }
            t[0]++;
            return false;
        }, 20 * 60 * 4);
        Playtest.wait("the last click lands", 6);
    }

    // ------------------------------------------------------------------------------------------------ save and return

    private static void reconnect() {
        final Object[] before = new Object[5];
        Playtest.queue("before leaving", () -> {
            CitizenState.Citizen c = ThiefScenario.citizen();
            before[0] = JourneyScenario.wallet();
            before[1] = c == null ? 0 : c.careerXp();
            before[2] = c == null ? java.util.Set.of() : java.util.Set.copyOf(c.skills());
            before[3] = JourneyScenario.count(SmithWork.item("drive_shaft"));
            before[4] = server(() -> level().getBlockEntity(F.bench) instanceof WorkshopBlock.Entity e ? e.jigs.get("drive_shaft") : null);
            Playtest.log("ENGINEER before leaving: wallet " + before[0] + ", XP " + before[1] + ", skills " + before[2] + ", shafts " + before[3] + ", jig " + before[4]);
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
            int shafts = JourneyScenario.count(SmithWork.item("drive_shaft"));
            ItemStack shaft = ItemStack.EMPTY;
            for (ItemStack s : mc().player.getInventory().items) if (s.is(SmithWork.item("drive_shaft"))) shaft = s;
            Integer jig = server(() -> level().getBlockEntity(F.bench) instanceof WorkshopBlock.Entity e ? e.jigs.get("drive_shaft") : null);
            boolean well = server(() -> MachineService.runsWell(level().getServer(), E.machine.key()) && !MachineService.works(level().getServer()).breakdowns.containsKey(E.machine.key()));
            Playtest.log("ENGINEER after reopening: wallet " + w + ", XP " + xp + ", skills " + skills + ", shafts " + shafts + " (grade " + Workshops.grade(shaft) + "), jig " + jig + ", machine running well " + well);
            if (w != (double) before[0] || xp != (int) before[1] || !skills.equals(before[2]) || shafts != (int) before[3] || Workshops.grade(shaft) != 2 || !java.util.Objects.equals(jig, before[4]) || !well)
                Playtest.fail("something changed across the save and reopen");
            else pass("13 (B) saved, left to the title screen and opened again: wallet, Smith XP, the skills, the Reserve shaft, the bench's jig and the machine running well, nothing doubled");
        });
    }
}
