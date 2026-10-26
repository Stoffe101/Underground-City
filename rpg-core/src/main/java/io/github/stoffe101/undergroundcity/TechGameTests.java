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

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/**
 * The Technician's trade (D-158), headless: the two puzzles (one dead part that the probes find; a patch grid that its own solution solves), a repair that pays the first Technician once and puts the
 * building right, who may take which job, the bench, and what a fault and a monitored alarm do to a building's alarm and a shop. None of it shows the screens in a real client; the Technician
 * scenario does that.
 */
@GameTestHolder(UndergroundCity.MOD_ID)
@PrefixGameTestTemplate(false)
public final class TechGameTests {
    private TechGameTests() {}

    private static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }

    private static FakePlayer player(GameTestHelper helper, String name, String career) {
        FakePlayer player = FakePlayerFactory.get(helper.getLevel(), new GameProfile(UUID.randomUUID(), name));
        player.getInventory().clearContent();
        CitizenState.get(player.server).register(player.getUUID(), "valewood", career);
        return player;
    }

    /** A building record of its own far chunk (tests run side by side in one world, and the service's record is keyed by building). */
    private static CityBuildings.Building building(BlockPos at, int tier, String classId, CityBuildings.Response response) {
        var security = new CityBuildings.SecurityClass(classId, "deadbolt", true, "", 0, "test office", response, tier, "");
        int cx = 200_000 + java.util.concurrent.ThreadLocalRandom.current().nextInt(100_000), cz = 200_000 + java.util.concurrent.ThreadLocalRandom.current().nextInt(100_000);
        return new CityBuildings.Building("valewood", cx, cz, "test", "test_building", 2, 0, at.getY() - 1, security);
    }

    // ------------------------------------------------------------------------------------------------ the puzzles

    @GameTest(template = "empty")
    public static void aTraceHasOneDeadPartThatTheProbesFind(GameTestHelper helper) {
        for (long seed = 1; seed <= 60; seed++) for (int tier = 1; tier <= 6; tier++) {
            TechPuzzles.Trace t = TechPuzzles.Trace.generate(seed, tier);
            check(t.faulty >= 0 && t.faulty < t.size(), "the dead part is outside the circuit");
            check(t.split == (tier >= 3), "a tier-" + tier + " circuit is " + (t.split ? "" : "not ") + "split");
            for (int i = 0; i < t.size(); i++) {
                boolean onPath = t.path(i).contains(t.faulty);
                check(t.live(i) == !onPath, "the reading after part " + i + " does not follow the dead part");
            }
            int[] scan = t.scan();
            check(scan[0] <= t.faulty && t.faulty <= scan[1], "the scanner's stretch misses the dead part");
            check(!t.split ? t.loadLit(0) == (t.faulty >= t.trunk) : true, "the single load's lamp is wrong");
            if (t.split && t.line(t.faulty) == 0) check(!t.loadLit(0) && !t.loadLit(1), "a dead trunk part left a load lit");
            if (t.split && t.line(t.faulty) == 1) check(!t.loadLit(0) && t.loadLit(1), "a dead part on branch A did not darken only load A");
            check(t.optimalProbes() >= 1 && t.optimalProbes() <= 3, "the best number of probes is off: " + t.optimalProbes());
        }
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void aPatchGridIsSolvedByItsOwnSolutionAndStartsUnsolved(GameTestHelper helper) {
        for (long seed = 1; seed <= 40; seed++) for (int size = 0; size <= 2; size++) {
            TechPuzzles.Patch p = TechPuzzles.Patch.generate(seed, size);
            check(!p.solved(), "a patch grid started solved (seed " + seed + ", size " + size + ")");
            Set<Integer> rows = new HashSet<>();
            for (int row : p.outputs) rows.add(row);
            check(rows.size() == p.outputs.length && p.outputs.length == 2 + size, "the devices are not on distinct rows");
            TechPuzzles.Patch solved = new TechPuzzles.Patch(p.w, p.h, p.source, p.outputs, p.solution, p.solution.clone());
            check(solved.solved(), "a grid's own solution does not feed every device (seed " + seed + ")");
            int before = p.tiles[0];
            for (int k = 0; k < 4; k++) p.turn(0);
            check(p.tiles[0] == before, "four quarter turns do not bring a tile back");
        }
        helper.succeed();
    }

    // ------------------------------------------------------------------------------------------------ the work

    @GameTest(template = "empty")
    public static void aRepairPaysTheFirstTechnicianOnceAndPutsTheBuildingRight(GameTestHelper helper) {
        if (!QShopEconomy.available()) { helper.succeed(); return; }   // the jobs that run the GameTests without Q Shop (the onboarding and world smoke jobs) have no wallet to pay into
        BlockPos door = helper.absolutePos(new BlockPos(1, 2, 1));
        CityBuildings.Building b = building(door, 2, "flat_block", new CityBuildings.Response(1.0, 60, 1, 60));
        TechService.Systems s = TechService.systems(helper.getLevel().getServer());
        TechWork.TESTING = true;
        try {
            s.panels.put(b.key(), new TechService.Panel(b.key(), null, door, "valewood", 2, "residential", "test house", "1 Test Street"));
            TechService.Fault fault = new TechService.Fault(UUID.randomUUID(), b.key(), false, LockService.clock(helper.getLevel().getServer()));
            s.faults.put(b.key(), fault);
            // D-165: a fault blinds the cameras but leaves the alarm armed (this building's alarm chance is 1.0)
            check(TechService.securityDown(helper.getLevel(), b) && PoliceResponse.armed(helper.getLevel(), b, b.security().response()), "a fault turned the alarm off");
            FakePlayer farmer = player(helper, "uc_tech_farmer", "farmer");
            farmer.getInventory().add(new ItemStack(TechWork.item("repair_kit")));
            check(TechWork.startRepair(farmer, helper.getLevel(), s.panels.get(b.key()), fault) != null, "a Farmer was let into a building's systems");
            FakePlayer tech = player(helper, "uc_tech_a", "technician");
            QShopEconomy.applyBalance(tech, 0);
            tech.getInventory().add(new ItemStack(TechWork.item("repair_kit")));
            check(TechWork.startRepair(tech, helper.getLevel(), s.panels.get(b.key()), fault) == null, "a Technician could not start a repair of a tier II building");
            TechWork.Session session = TechWork.sessionOf(tech.getUUID());
            check(session != null && session.trace != null && session.tries == 2, "no repair session with two tries");
            int wrong = (session.trace.faulty + 1) % session.trace.size();
            TechWork.replace(tech, wrong);
            check(TechWork.sessionOf(tech.getUUID()) != null && TechWork.sessionOf(tech.getUUID()).tries == 1 && s.faults.containsKey(b.key()), "a wrong part did not cost a try, or ended the repair");
            int xpBefore = CitizenState.get(tech.server).find(tech.getUUID()).orElseThrow().careerXp();
            TechWork.replace(tech, session.trace.faulty);
            check(TechWork.sessionOf(tech.getUUID()) == null && !s.faults.containsKey(b.key()), "the right part did not end the repair and clear the fault");
            CitizenState.Citizen c = CitizenState.get(tech.server).find(tech.getUUID()).orElseThrow();
            int fee = SkillRules.payout(TechService.repairFee(2), false, SkillCatalog.INSTANCE.tree(c.career), c.skills());
            check(QShopEconomy.balance(tech) == fee, "the repair paid " + QShopEconomy.balance(tech) + ", not " + fee);
            check(CitizenState.get(tech.server).find(tech.getUUID()).orElseThrow().careerXp() > xpBefore, "the repair paid no XP");
            check(!TechWork.settle(tech, fault.id(), "tech_repair", fee, 10, java.util.List.of()), "the same fault paid twice");
            check(QShopEconomy.balance(tech) == fee, "a second settle changed the wallet");
            check(PoliceResponse.armed(helper.getLevel(), b, b.security().response()), "the alarm did not come back after the repair");
        } finally {
            s.panels.remove(b.key());
            s.faults.remove(b.key());
            TechWork.TESTING = false;
            TechWork.resetForTest();
        }
        helper.succeed();
    }

    /** Two players on one service call (the co-op case): both may start it, the first to find the dead part is paid, the other's repair ends with nothing paid and nothing used. */
    @GameTest(template = "empty")
    public static void twoTechniciansOnOneCallOnlyTheFirstIsPaid(GameTestHelper helper) {
        if (!QShopEconomy.available()) { helper.succeed(); return; }   // the jobs that run the GameTests without Q Shop (the onboarding and world smoke jobs) have no wallet to pay into
        BlockPos door = helper.absolutePos(new BlockPos(1, 2, 1));
        CityBuildings.Building b = building(door, 3, "flat_block", new CityBuildings.Response(1.0, 60, 1, 60));
        TechService.Systems s = TechService.systems(helper.getLevel().getServer());
        TechWork.TESTING = true;
        try {
            TechService.Panel p = new TechService.Panel(b.key(), null, door, "valewood", 3, "residential", "test house", "4 Test Street");
            s.panels.put(b.key(), p);
            TechService.Fault fault = new TechService.Fault(UUID.randomUUID(), b.key(), false, 0);
            s.faults.put(b.key(), fault);
            FakePlayer first = player(helper, "uc_tech_first", "technician"), second = player(helper, "uc_tech_second", "technician");
            for (FakePlayer t : new FakePlayer[] {first, second}) {
                CitizenState.get(t.server).unlockSkill(t.getUUID(), "technician.fundamentals");
                t.getInventory().add(new ItemStack(TechWork.item("repair_kit")));
                t.getInventory().add(new ItemStack(TechWork.item("control_circuits")));
                QShopEconomy.applyBalance(t, 0);
            }
            check(TechWork.startRepair(first, helper.getLevel(), p, fault) == null && TechWork.startRepair(second, helper.getLevel(), p, fault) == null, "both could not start the same call");
            TechWork.replace(first, TechWork.sessionOf(first.getUUID()).trace.faulty);
            check(QShopEconomy.balance(first) > 0 && !s.faults.containsKey(b.key()), "the first did not get paid for putting it right");
            check(first.getInventory().countItem(TechWork.item("control_circuits")) == 0, "the first's repair of a tier III building did not use a circuit board");
            TechWork.replace(second, TechWork.sessionOf(second.getUUID()).trace.faulty);
            check(TechWork.sessionOf(second.getUUID()) == null, "the second's repair did not end");
            check(QShopEconomy.balance(second) == 0 && second.getInventory().countItem(TechWork.item("control_circuits")) == 1, "the second was paid, or lost a board, for a call already put right");
        } finally {
            s.panels.remove(b.key());
            s.faults.remove(b.key());
            TechWork.TESTING = false;
            TechWork.resetForTest();
        }
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void whoMayTakeWhichJob(GameTestHelper helper) {
        BlockPos door = helper.absolutePos(new BlockPos(1, 2, 1));
        CityBuildings.Building b = building(door, 4, "office", new CityBuildings.Response(0.5, 60, 1, 60));
        TechService.Systems s = TechService.systems(helper.getLevel().getServer());
        TechWork.TESTING = true;
        try {
            TechService.Panel p = new TechService.Panel(b.key(), null, door, "valewood", 4, "office", "test office", "2 Test Street");
            s.panels.put(b.key(), p);
            TechService.Fault fault = new TechService.Fault(UUID.randomUUID(), b.key(), false, 0);
            FakePlayer novice = player(helper, "uc_tech_novice", "technician");
            novice.getInventory().add(new ItemStack(TechWork.item("repair_kit")));
            String refused = TechWork.startRepair(novice, helper.getLevel(), p, fault);
            check(refused != null && refused.contains("Bench Electronics"), "a new Technician was let repair a tier IV building: " + refused);
            CitizenState.get(novice.server).unlockSkill(novice.getUUID(), "technician.fundamentals");
            CitizenState.get(novice.server).unlockSkill(novice.getUUID(), "technician.municipal_repairs");
            refused = TechWork.startRepair(novice, helper.getLevel(), p, fault);
            check(refused != null && refused.contains("circuit board"), "a tier IV repair did not ask for a circuit board: " + refused);
            novice.getInventory().add(new ItemStack(TechWork.item("control_circuits")));
            check(TechWork.startRepair(novice, helper.getLevel(), p, fault) == null && TechWork.sessionOf(novice.getUUID()).tries == 3, "Municipal Repairs did not give a third try");
            TechWork.abandon(novice);
            TechService.Job install = new TechService.Job(UUID.randomUUID(), "install", b.key(), 100, 200, Long.MAX_VALUE);
            check(TechWork.startInstall(novice, helper.getLevel(), p, install) != null, "an install was taken without the Installer skill");
            CitizenState.get(novice.server).unlockSkill(novice.getUUID(), "technician.automation_install");
            check(TechWork.startInstall(novice, helper.getLevel(), p, install).contains("Security Array"), "an install did not ask for a kit");
            TechService.Job upgrade = new TechService.Job(UUID.randomUUID(), "upgrade", b.key(), 100, 200, Long.MAX_VALUE);
            check(TechWork.startUpgrade(novice, helper.getLevel(), p, upgrade) != null, "an upgrade was taken without Systems Integrator");
        } finally {
            s.panels.remove(b.key());
            TechWork.TESTING = false;
            TechWork.resetForTest();
        }
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void aMonitoredAlarmIsAlwaysArmedAndAShopDownCannotSell(GameTestHelper helper) {
        BlockPos door = helper.absolutePos(new BlockPos(1, 2, 1));
        CityBuildings.Building b = building(door, 3, "flat_block", new CityBuildings.Response(0.0, 60, 1, 60));
        TechService.Systems s = TechService.systems(helper.getLevel().getServer());
        try {
            check(!PoliceResponse.armed(helper.getLevel(), b, b.security().response()), "an alarm with no chance was armed");
            s.monitored.put(b.key(), LockService.clock(helper.getLevel().getServer()) + 1000);
            check(TechService.monitored(helper.getLevel(), b) && PoliceResponse.armed(helper.getLevel(), b, b.security().response()), "a monitored alarm was not armed");
            s.monitored.put(b.key(), LockService.clock(helper.getLevel().getServer()) - 1);
            check(!TechService.monitored(helper.getLevel(), b), "a monitored alarm did not run out");
            s.faults.put(b.key(), new TechService.Fault(UUID.randomUUID(), b.key(), true, 0));
            // D-165: a shop whose till is down stays open and sells, but cannot pay a supplier until a Technician comes
            check(TechService.shopDown(helper.getLevel(), b) && ShopSupply.tillRefusal(helper.getLevel(), b) != null, "a shop with its till down still bought from a supplier");
        } finally {
            s.monitored.remove(b.key());
            s.faults.remove(b.key());
        }
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void theBenchMakesOnlyFromCarriedPartsAndWithItsSkill(GameTestHelper helper) {
        check(TechDesigns.INSTANCE.find("circuit_boards") != null && TechDesigns.INSTANCE.find("repair_kit") != null, "the bench's designs did not load");
        FakePlayer tech = player(helper, "uc_tech_bench", "technician");
        tech.getInventory().add(new ItemStack(TechWork.item("e_scrap"), 3));
        tech.getInventory().add(new ItemStack(Items.COPPER_INGOT, 1));
        tech.getInventory().add(new ItemStack(Items.REDSTONE, 1));
        String refused = TechWork.assemble(tech, "circuit_boards");
        check(refused != null && refused.contains("Bench Electronics"), "circuit boards were made without the skill: " + refused);
        CitizenState.get(tech.server).unlockSkill(tech.getUUID(), "technician.fundamentals");
        check(TechWork.assemble(tech, "circuit_boards") == null, "circuit boards could not be made with the skill and the parts");
        check(tech.getInventory().countItem(TechWork.item("control_circuits")) == 2 && tech.getInventory().countItem(TechWork.item("e_scrap")) == 0
            && tech.getInventory().countItem(Items.COPPER_INGOT) == 0, "the bench did not take the parts and give two boards");
        check(TechWork.assemble(tech, "circuit_boards").startsWith("Missing parts"), "the bench made boards from nothing");
        helper.succeed();
    }

    /**
     * A crouched right click with the toolkit reaches a utility panel (the back channel, D-166). Vanilla skips a block's own click when a player sneaks with an item in hand; the event
     * now allows it for the toolkit on a panel only: not standing, not with another item, not on another block (back-channel client run 3, eighth pass).
     */
    @GameTest(template = "empty")
    public static void aCrouchedToolkitClickReachesAUtilityPanelAndNothingElse(GameTestHelper helper) {
        var level = helper.getLevel();
        BlockPos panel = helper.absolutePos(new BlockPos(1, 2, 1)), stone = helper.absolutePos(new BlockPos(3, 2, 1));
        level.setBlock(panel, UndergroundCity.UTILITY_PANEL.get().defaultBlockState(), 3);
        level.setBlock(stone, net.minecraft.world.level.block.Blocks.STONE.defaultBlockState(), 3);
        FakePlayer p = FakePlayerFactory.get(level, new GameProfile(UUID.randomUUID(), "uc_sneak_tech"));
        java.util.function.BiFunction<BlockPos, ItemStack, net.minecraftforge.eventbus.api.Event.Result> click = (pos, held) -> {
            p.setItemInHand(net.minecraft.world.InteractionHand.MAIN_HAND, held);
            var hit = new net.minecraft.world.phys.BlockHitResult(net.minecraft.world.phys.Vec3.atCenterOf(pos), net.minecraft.core.Direction.NORTH, pos, false);
            var event = new net.minecraftforge.event.entity.player.PlayerInteractEvent.RightClickBlock(p, net.minecraft.world.InteractionHand.MAIN_HAND, pos, hit);
            net.minecraftforge.common.MinecraftForge.EVENT_BUS.post(event);
            return event.getUseBlock();
        };
        try {
            p.setShiftKeyDown(true);
            check(click.apply(panel, new ItemStack(TechWork.item("repair_kit"))) == net.minecraftforge.eventbus.api.Event.Result.ALLOW, "a crouched toolkit click does not reach the panel");
            check(click.apply(panel, new ItemStack(Items.STICK)) != net.minecraftforge.eventbus.api.Event.Result.ALLOW, "a crouched click with another item was let through to the panel");
            check(click.apply(stone, new ItemStack(TechWork.item("repair_kit"))) != net.minecraftforge.eventbus.api.Event.Result.ALLOW, "a crouched toolkit click was let through to another block");
            p.setShiftKeyDown(false);
            check(click.apply(panel, new ItemStack(TechWork.item("repair_kit"))) != net.minecraftforge.eventbus.api.Event.Result.ALLOW, "a standing click was changed (it reaches the panel already)");
        } finally {
            p.setShiftKeyDown(false);
            level.setBlock(panel, net.minecraft.world.level.block.Blocks.AIR.defaultBlockState(), 3);
            level.setBlock(stone, net.minecraft.world.level.block.Blocks.AIR.defaultBlockState(), 3);
        }
        helper.succeed();
    }
}
