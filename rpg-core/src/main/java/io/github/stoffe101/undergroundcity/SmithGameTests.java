package io.github.stoffe101.undergroundcity;

import com.mojang.authlib.GameProfile;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraftforge.common.util.FakePlayer;
import net.minecraftforge.common.util.FakePlayerFactory;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.Random;
import java.util.Set;
import java.util.UUID;

/**
 * The Smith's hands-on work (eighth pass), headless: the drawing's standard is the fewest passes a search finds; a part is graded by its passes and a sleeve saves a spoiled one; a drive starts
 * off its plate and can always be geared onto it; a turned part is made only when it is finished and leaves its jig, which a Drill Press and a Lathe repeat at the grade the rules allow; a repair
 * pays the first Smith once, restarts the machine and runs a works' catch-up shift; a shop runs short while its machine is down. None of it shows the screens in a real client; the engineer
 * scenario does that.
 */
@GameTestHolder(UndergroundCity.MOD_ID)
@PrefixGameTestTemplate(false)
public final class SmithGameTests {
    private SmithGameTests() {}

    private static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }

    private static FakePlayer player(GameTestHelper helper, String name, String career) {
        FakePlayer player = FakePlayerFactory.get(helper.getLevel(), new GameProfile(UUID.randomUUID(), name));
        player.getInventory().clearContent();
        CitizenState.get(player.server).register(player.getUUID(), "ironhaven", career);
        return player;
    }

    // ------------------------------------------------------------------------------------------------ the drawing

    /** Every pass of a drawing that spoils nothing, searched breadth first: the fewest passes that finish it. */
    private static int searchFewest(int[] target, int rough) {
        int n = target.length;
        record State(int[] radius, boolean[] marked) {
            long key() { long k = 0; for (int i = 0; i < radius.length; i++) k = k * 32 + radius[i] * 2 + (marked[i] ? 1 : 0); return k; }
        }
        int[] start = new int[n];
        java.util.Arrays.fill(start, SmithPuzzles.Turning.STOCK);
        ArrayDeque<State> queue = new ArrayDeque<>();
        ArrayDeque<Integer> depth = new ArrayDeque<>();
        Set<Long> seen = new HashSet<>();
        State first = new State(start, new boolean[n]);
        queue.add(first); depth.add(0); seen.add(first.key());
        while (!queue.isEmpty()) {
            State s = queue.poll();
            int d = depth.poll();
            boolean done = true;
            for (int i = 0; i < n; i++) done &= s.radius[i] == target[i] && !s.marked[i];
            if (done) return d;
            for (int a = 0; a < n; a++) for (int b = a; b < n; b++) for (int line = 1; line < SmithPuzzles.Turning.STOCK; line++) for (int f = 0; f < 2; f++) {
                SmithPuzzles.Turning t = new SmithPuzzles.Turning(target, rough);
                System.arraycopy(s.radius, 0, t.radius, 0, n);
                System.arraycopy(s.marked, 0, t.marked, 0, n);
                if (t.cut(a, b, line, f == 1) != null) continue;
                boolean spoiled = false;
                for (int i = 0; i < n; i++) spoiled |= t.spoiled(i);
                if (spoiled) continue;
                State next = new State(t.radius.clone(), t.marked.clone());
                if (seen.add(next.key())) { queue.add(next); depth.add(d + 1); }
            }
        }
        return -1;
    }

    /** Turns a fresh drawing by the planner's plan: every pass must be accepted. Returns the passes it took. */
    static int turnToStandard(SmithPuzzles.Turning t) {
        for (int[] pass : SmithPuzzles.Turning.plan(t.target, t.rough)) check(t.cut(pass[0], pass[1], pass[2], pass[3] == 1) == null, "a pass of the plan was refused: " + java.util.Arrays.toString(pass));
        return t.passes;
    }

    /** Finishes whatever is left one step at a time, section by section (always allowed, never the fewest). */
    static void finishStepwise(SmithPuzzles.Turning t) {
        for (int guard = 0; guard < 200 && !t.done(); guard++)
            for (int i = 0; i < t.size(); i++) if (t.radius[i] > t.target[i]) check(t.cut(i, i, t.radius[i] - 1, true) == null, "a one-step finishing pass was refused");
    }

    @GameTest(template = "empty", timeoutTicks = 1200)
    public static void aDrawingsStandardIsTheFewestPassesASearchFinds(GameTestHelper helper) {
        Random r = new Random(5908);
        for (int k = 0; k < 40; k++) {
            int n = 3 + r.nextInt(2), rough = 2 + r.nextInt(3);
            int[] target = new int[n];
            for (int i = 0; i < n; i++) target[i] = 2 + r.nextInt(6);
            int standard = SmithPuzzles.Turning.fewestPasses(target, rough), found = searchFewest(target, rough);
            check(standard == found, "drawing " + java.util.Arrays.toString(target) + " at roughing " + rough + ": the standard is " + standard + " but a search finds " + found);
        }
        for (long seed = 1; seed <= 60; seed++) for (int rough = 2; rough <= 5; rough++) {
            SmithPuzzles.Turning t = SmithPuzzles.Turning.generate(seed, 4 + (int) (seed % 7), rough);
            boolean deep = false;
            for (int v : t.target) { check(v >= SmithPuzzles.Turning.MIN_LINE && v < SmithPuzzles.Turning.STOCK, "a line off the bar"); deep |= v <= SmithPuzzles.Turning.STOCK - 3; }
            check(deep, "a drawing with nothing to rough");
            int passes = turnToStandard(t);
            check(t.done() && passes == t.standard && t.grade() == 2, "turning to the standard did not finish the part at Reserve (seed " + seed + ")");
        }
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void aPartIsGradedByItsPassesAndASleeveSavesASpoiledSection(GameTestHelper helper) {
        int[] drawing = {6, 6, 3, 3, 5, 5};
        SmithPuzzles.Turning t = new SmithPuzzles.Turning(drawing, 2);
        check(t.cut(0, 5, 7, true) == null, "a finishing pass one step down was refused");
        check(t.cut(0, 5, 4, false) != null, "a roughing pass three steps deep was let through at a depth of two");
        check(t.cut(2, 3, 5, true) != null, "a finishing pass two steps deep was let through");
        check(t.cut(0, 0, 7, false) != null, "a pass that touches nothing was counted");
        check(t.passes == 1, "refused passes were counted: " + t.passes);
        // rough section 3 onto its line: it cannot be finished there any more
        check(t.cut(2, 2, 5, false) == null && t.cut(2, 2, 3, false) == null && t.spoiled(2), "a section roughed onto its line is not spoiled");
        check(t.sleeve(1) != null, "a sound section was sleeved");
        check(t.sleeve(2) == null && t.radius[2] == 4 && t.marked[2] && t.sleeves == 1, "the sleeve did not build the section back up a step");
        finishStepwise(t);
        check(t.done() && t.grade() <= 1 && t.grade() >= 0, "a sleeved part was graded " + t.grade() + ", not Fine at most");
        SmithPuzzles.Turning slow = new SmithPuzzles.Turning(drawing, 2);
        for (int line = 7; line >= 3; line--) for (int i = 0; i < 6; i++) if (slow.radius[i] > drawing[i] && slow.radius[i] - 1 >= drawing[i]) slow.cut(i, i, slow.radius[i] - 1, true);
        check(slow.done() && slow.passes > slow.standard + 2 && slow.grade() == 0, "a part turned a step at a time is not Standard: " + slow.passes + " passes, standard " + slow.standard);
        helper.succeed();
    }

    // ------------------------------------------------------------------------------------------------ the drive

    /** A gearing of the open stages that meets the plate, found by trying them all; null if none does. */
    static int[][] solve(SmithPuzzles.Drive d) {
        int open = 0;
        for (boolean o : d.open) if (o) open++;
        int options = SmithPuzzles.TEETH.length * SmithPuzzles.TEETH.length * 2;
        int total = (int) Math.pow(options, open);
        for (int code = 0; code < total; code++) {
            int[] driver = d.driver.clone(), driven = d.driven.clone();
            boolean[] idler = d.idler.clone();
            int c = code;
            for (int s = 0; s < d.stages; s++) {
                if (!d.open[s]) continue;
                int pick = c % options; c /= options;
                driver[s] = pick % SmithPuzzles.TEETH.length;
                driven[s] = (pick / SmithPuzzles.TEETH.length) % SmithPuzzles.TEETH.length;
                idler[s] = pick / (SmithPuzzles.TEETH.length * SmithPuzzles.TEETH.length) == 1;
            }
            double out = SmithPuzzles.Drive.output(d.motor, driver, driven);
            if (out >= d.lo && out <= d.hi && SmithPuzzles.Drive.clockwise(idler) == d.clockwise) {
                int[] flags = new int[idler.length];
                for (int s = 0; s < idler.length; s++) flags[s] = idler[s] ? 1 : 0;
                return new int[][] {driver, driven, flags};
            }
        }
        return null;
    }

    @GameTest(template = "empty")
    public static void aDriveStartsOffItsPlateAndCanBeGearedOntoIt(GameTestHelper helper) {
        for (long seed = 1; seed <= 120; seed++) for (int tier = 1; tier <= 6; tier++) {
            SmithPuzzles.Drive d = SmithPuzzles.Drive.generate(seed, tier, 3);
            int worn = 0;
            for (boolean w : d.worn) if (w) worn++;
            check(worn == (tier >= 4 ? 2 : 1), "a tier " + tier + " drive has " + worn + " worn parts");
            check(d.stages == (tier >= 5 ? 3 : tier >= 3 ? 2 : 1), "a tier " + tier + " drive has " + d.stages + " stages");
            check(d.lo < d.hi && d.lo > 0, "a plate with no band");
            int[][] solution = solve(d);
            check(solution != null, "no gearing meets the plate (seed " + seed + ", tier " + tier + ")");
            // found as it is, it does not run: a worn part is in it, and the open stages' gearing misses the plate
            SmithPuzzles.Drive found = SmithPuzzles.Drive.generate(seed, tier, 3);
            check(!found.run().isEmpty(), "a drive ran before anything was mended");
            for (int i = 0; i < d.parts.length; i++) if (d.worn[i]) check(d.replace(i) == null, "a worn part could not be replaced");
            for (int i = 0; i < d.parts.length; i++) if (!d.worn[i]) check(d.replace(i) != null, "a sound part was replaced");
            System.arraycopy(solution[0], 0, d.driver, 0, d.stages);
            System.arraycopy(solution[1], 0, d.driven, 0, d.stages);
            for (int s = 0; s < d.stages; s++) d.idler[s] = solution[2][s] == 1;
            check(d.run().isEmpty(), "the drive did not run with its worn parts replaced and the plate's gearing");
            int gearSets = 0;
            for (String id : d.uses()) if (id.equals("gear_set")) gearSets++;
            int openStages = 0;
            for (boolean o : d.open) if (o) openStages++;
            check(gearSets >= openStages, "an open stage was geared without a gear set");
        }
        SmithPuzzles.Drive d = SmithPuzzles.Drive.generate(7, 3, 3);
        int closed = -1;
        for (int s = 0; s < d.stages; s++) if (!d.open[s]) closed = s;
        if (closed >= 0) check(d.gear(closed, true, 1) != null && d.idler(closed) != null, "a closed stage could be geared");
        helper.succeed();
    }

    // ------------------------------------------------------------------------------------------------ turning at a real bench

    @GameTest(template = "empty", timeoutTicks = 400)
    public static void aTurnedPartIsMadeOnlyWhenFinishedAndTheBenchKeepsItsJig(GameTestHelper helper) {
        BlockPos pos = helper.absolutePos(new BlockPos(1, 2, 1));
        helper.getLevel().setBlock(pos, UndergroundCity.WORKSHOP_BLOCKS.get("machinist_bench").get().defaultBlockState(), 3);
        WorkshopBlock.Entity bench = (WorkshopBlock.Entity) helper.getLevel().getBlockEntity(pos);
        check(bench != null, "no bench entity");
        SmithWork.TESTING = true;
        try {
            FakePlayer smith = player(helper, "uc_turner", "smith");
            smith.moveTo(pos.getX() + 1.5, pos.getY(), pos.getZ() + 0.5);
            smith.getInventory().add(new ItemStack(Items.IRON_INGOT, 3));
            FakePlayer farmer = player(helper, "uc_turner_farmer", "farmer");
            farmer.getInventory().add(new ItemStack(Items.IRON_INGOT, 3));
            check(SmithWork.startTurning(farmer, helper.getLevel(), bench, "drive_shaft") != null, "a Farmer turned a part by hand at a Machinist's Bench");
            check(SmithWork.startTurning(smith, helper.getLevel(), bench, "fasteners") != null, "fasteners were turned by hand");
            check(SmithWork.startTurning(smith, helper.getLevel(), bench, "drive_shaft") == null, "a Smith could not open the drawing of a drive shaft");
            check(SmithWork.startTurning(smith, helper.getLevel(), bench, "drive_shaft") != null, "a second drawing opened while one was open");
            SmithWork.close(smith);
            check(smith.getInventory().countItem(Items.IRON_INGOT) == 3, "closing the drawing took the iron");
            check(SmithWork.startTurning(smith, helper.getLevel(), bench, "drive_shaft") == null, "the drawing would not open again");
            SmithWork.Turn turn = SmithWork.turnOf(smith.getUUID());
            check(turn != null && turn.t.rough == 2, "a Vice and Files does not rough two steps");
            int xpBefore = CitizenState.get(smith.server).find(smith.getUUID()).orElseThrow().careerXp();
            // turn it through the moves a player sends, to the standard
            java.util.List<int[]> moves = SmithPuzzles.Turning.plan(turn.t.target, turn.t.rough);
            check(moves.size() == turn.t.standard, "the plan takes " + moves.size() + " passes against a standard of " + turn.t.standard);
            for (int[] m : moves) SmithWork.cut(smith, m[0], m[1], m[2], m[3] == 1);
            check(SmithWork.turnOf(smith.getUUID()) == null, "the drawing stayed open after the last pass");
            ItemStack shaft = ItemStack.EMPTY;
            for (ItemStack s : smith.getInventory().items) if (s.is(SmithWork.item("drive_shaft"))) shaft = s;
            check(!shaft.isEmpty() && Workshops.grade(shaft) == 2, "the shaft turned to the standard is not Reserve");
            check(smith.getInventory().countItem(Items.IRON_INGOT) == 0, "the iron was not taken when the part was finished");
            check(CitizenState.get(smith.server).find(smith.getUUID()).orElseThrow().careerXp() == xpBefore + 30 + 15, "a Reserve shaft did not pay half again in XP");
            check(bench.jigs.getOrDefault("drive_shaft", -1) == 2, "the bench kept no jig at Reserve");
            // the jig: none at a Vice and Files; Standard on a Drill Press, Fine on a Lathe, a step better with Production Tooling
            smith.getInventory().add(new ItemStack(Items.IRON_INGOT, 6));
            check(WorkshopBlock.start(smith, helper.getLevel(), bench, "drive_shaft", 1).startsWith("A Vice and Files has no jig"), "a Vice and Files ran a jig");
            CitizenState.Citizen c = CitizenState.get(smith.server).find(smith.getUUID()).orElseThrow();
            bench.tier = 2;
            check(WorkshopBlock.jigGrade(bench, bench.station().recipe("drive_shaft"), c) == 0, "a Drill Press jig without Production Tooling is not Standard");
            bench.tier = 3;
            check(WorkshopBlock.jigGrade(bench, bench.station().recipe("drive_shaft"), c) == 1, "a Lathe jig without Production Tooling is not Fine");
            check(WorkshopBlock.start(smith, helper.getLevel(), bench, "drive_shaft", 1).startsWith("Started"), "a Lathe did not run the jig");
            check(bench.grade == 1 && smith.getInventory().countItem(Items.IRON_INGOT) == 3, "the jig run did not take the iron or make Fine");
        } finally {
            SmithWork.TESTING = false;
            SmithWork.resetForTest();
        }
        helper.succeed();
    }


    // ------------------------------------------------------------------------------------------------ machinery

    @GameTest(template = "empty")
    public static void aRepairPaysTheFirstSmithOnceRestartsTheMachineAndRunsACatchUpShift(GameTestHelper helper) {
        if (!QShopEconomy.available()) { helper.succeed(); return; }   // the jobs that run the GameTests without Q Shop have no wallet to pay into
        var server = helper.getLevel().getServer();
        BlockPos door = helper.absolutePos(new BlockPos(1, 2, 1));
        MachineService.Works w = MachineService.works(server);
        long key = 900_000_000L + new Random().nextInt(1_000_000);
        MachineService.Machine m = new MachineService.Machine(key, null, door, "ironhaven", 2, "conveyor", "", "test works", "1 Test Yard");
        RegionalStock iron = RegionalCatalog.INSTANCE.forCity("ironhaven").stream().filter(s -> s.item().toString().equals("minecraft:iron_ingot")).findFirst().orElse(null);
        WorkOrderLedger ledger = WorkOrderLedger.get(server);
        SmithWork.TESTING = true;
        try {
            w.machines.put(key, m);
            MachineService.Breakdown b = MachineService.open(server, helper.getLevel(), m, LockService.clock(server));
            check(MachineService.broken(server, key), "the breakdown is not open");
            FakePlayer farmer = player(helper, "uc_mech_farmer", "farmer");
            farmer.getInventory().add(new ItemStack(SmithWork.item("engineer_toolkit")));
            check(SmithWork.startRepair(farmer, helper.getLevel(), m, b) != null, "a Farmer was let into a machine's drive");
            FakePlayer smith = player(helper, "uc_mech_smith", "smith");
            smith.moveTo(door.getX() + 0.5, door.getY(), door.getZ() + 1.5);
            QShopEconomy.applyBalance(smith, 0);
            smith.getInventory().add(new ItemStack(SmithWork.item("engineer_toolkit")));
            SmithPuzzles.Drive preview = SmithWork.driveOf(b, m, 3);
            for (String id : preview.uses()) smith.getInventory().add(new ItemStack(SmithWork.item(id)));
            check(SmithWork.startRepair(smith, helper.getLevel(), m, b) == null, "a Smith could not open a tier II drive");
            SmithWork.Repair r = SmithWork.repairOf(smith.getUUID());
            check(r != null && r.d.tests == 3, "no repair with three test runs");
            SmithWork.run(smith);
            check(SmithWork.repairOf(smith.getUUID()) != null && r.d.tests == 2 && MachineService.broken(server, key), "a failed test run ended the repair or mended the machine");
            for (int i = 0; i < r.d.parts.length; i++) if (r.d.worn[i]) SmithWork.replace(smith, i);
            int[][] solution = solve(r.d);
            check(solution != null, "no gearing meets the plate");
            System.arraycopy(solution[0], 0, r.d.driver, 0, r.d.stages);
            System.arraycopy(solution[1], 0, r.d.driven, 0, r.d.stages);
            for (int s = 0; s < r.d.stages; s++) r.d.idler[s] = solution[2][s] == 1;
            if (iron != null) {
                ledger.setRegionalUnitsForTest(iron, 0);
                check(MachineService.stalled(iron), "the iron line is not stopped while the works stands idle");
                ledger.advance(iron.intervalTicks() * 3L);
                check(ledger.regionalUnits(iron) == 0, "the iron line gained " + ledger.regionalUnits(iron) + " units while its works stood idle");
            }
            int xpBefore = CitizenState.get(server).find(smith.getUUID()).orElseThrow().careerXp();
            SmithWork.run(smith);
            CitizenState.Citizen c = CitizenState.get(server).find(smith.getUUID()).orElseThrow();
            int fee = SkillRules.payout(MachineService.fee(2), false, SkillCatalog.INSTANCE.tree(c.career), c.skills());
            check(SmithWork.repairOf(smith.getUUID()) == null && !MachineService.broken(server, key), "the right gearing did not end the repair and restart the machine");
            check(QShopEconomy.balance(smith) == fee && c.careerXp() > xpBefore, "the repair paid " + QShopEconomy.balance(smith) + " credits, not " + fee + ", or no XP");
            for (String id : preview.uses()) check(smith.getInventory().countItem(SmithWork.item(id)) == 0, "the repair did not use its " + id);
            check(MachineService.runsWell(server, key), "a machine put right by a player does not run well");
            if (iron != null) {
                int shift = ledger.regionalUnits(iron);
                check(shift == Math.min(iron.capacity(), Math.max(1, (int) Math.round(iron.capacity() * MachineService.RULES.restockShare()))) && !MachineService.stalled(iron),
                    "the works ran no catch-up shift on the iron line (" + shift + ") or the line is still stopped");
                ledger.advance(iron.intervalTicks());
                check(ledger.regionalUnits(iron) == Math.min(iron.capacity(), shift + iron.unitsPerInterval()), "the iron line counted the idle time, or did not resume: " + ledger.regionalUnits(iron));
            }
            check(!TechWork.settle(smith, b.id(), "smith_repair", fee, 10, java.util.List.of()), "the same breakdown paid twice");
            check(MachineService.breakdown(server, helper.getLevel(), "ironhaven", new Random(1), LockService.clock(server)) == null
                || MachineService.works(server).breakdowns.get(key) == null, "a machine running well broke down again at once");
        } finally {
            w.machines.remove(key);
            w.breakdowns.remove(key);
            w.runsWell.remove(key);
            MachineService.refreshShort(server);
            SmithWork.TESTING = false;
            SmithWork.resetForTest();
        }
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void aShopRunsShortWhileItsMachineIsDownAndATierBeyondReachIsRefused(GameTestHelper helper) {
        var server = helper.getLevel().getServer();
        BlockPos door = helper.absolutePos(new BlockPos(1, 2, 1));
        MachineService.Works w = MachineService.works(server);
        long key = 800_000_000L + new Random().nextInt(1_000_000);
        MachineService.Machine saw = new MachineService.Machine(key, null, door, "ironhaven", 3, "timber saw", "shop:builders", "builders' yard", "2 Test Yard");
        RegionalStock gears = RegionalCatalog.INSTANCE.find("yard_gears_ironhaven");
        SmithWork.TESTING = true;
        try {
            int before = gears == null ? 0 : RegionalMarket.unitPrice(gears, null);
            w.machines.put(key, saw);
            MachineService.Breakdown b = MachineService.open(server, helper.getLevel(), saw, LockService.clock(server));
            check(MachineService.shortage(server, "shop:builders", "ironhaven"), "the yard does not run short while its saw is down");
            if (gears != null) check(RegionalMarket.unitPrice(gears, null) == MachineService.marked(before) && before < MachineService.marked(before), "the yard's lines do not cost more while it runs short");
            FakePlayer smith = player(helper, "uc_saw_smith", "smith");
            smith.getInventory().add(new ItemStack(SmithWork.item("engineer_toolkit")));
            check(SmithWork.startRepair(smith, helper.getLevel(), saw, b) != null, "a Smith without Millwright opened a tier III drive");
            CitizenState.get(server).unlockSkill(smith.getUUID(), "smith.fundamentals");
            CitizenState.get(server).unlockSkill(smith.getUUID(), "smith.millwright");
            check(SmithWork.startRepair(smith, helper.getLevel(), saw, b) == null && SmithWork.repairOf(smith.getUUID()).d.tests == 4, "a Millwright could not open a tier III drive with four test runs");
            SmithWork.close(smith);
            w.breakdowns.remove(key);
            MachineService.refreshShort(server);
            if (gears != null) check(RegionalMarket.unitPrice(gears, null) == before, "the yard still runs short after its saw was put right");
        } finally {
            w.machines.remove(key);
            w.breakdowns.remove(key);
            MachineService.refreshShort(server);
            SmithWork.TESTING = false;
            SmithWork.resetForTest();
        }
        helper.succeed();
    }
}
