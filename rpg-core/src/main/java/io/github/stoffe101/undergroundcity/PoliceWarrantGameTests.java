package io.github.stoffe101.undergroundcity;

import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestAssertException;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

import java.util.List;
import java.util.Optional;

import static io.github.stoffe101.undergroundcity.CrewGameTests.check;
import static io.github.stoffe101.undergroundcity.CrewGameTests.player;

/**
 * The police of the twelfth pass: officers who run between a walking and a sprinting player, warrants that outlive a response (an escape, a logout, a restart) only when somebody saw the face,
 * officers on the beat and checkpoints who serve them, repeat offenders who pay more and are warned no more, and a conviction settled once with the fine.
 */
@GameTestHolder(UndergroundCity.MOD_ID)
@PrefixGameTestTemplate(false)
public final class PoliceWarrantGameTests {

    /** The officer runs its chase on a straight stone track high above the grid for {@link #RUN_TICKS} ticks after a short start; its speed is the distance over the time. */
    static final int RUN_TICKS = 60, START_TICKS = 10;

    @GameTest(template = "empty", batch = "uc_police_speed", timeoutTicks = 200)
    public static void anOfficerOnTheChaseRunsFasterThanAWalkAndSlowerThanASprint(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos base = helper.absolutePos(new BlockPos(2, 2, 2));
        BlockPos start = base.above(level.getMaxBuildHeight() - 30 - base.getY());
        for (int dx = -2; dx <= 70; dx++) for (int dz = -2; dz <= 2; dz++) {
            level.setBlock(start.offset(dx, -1, dz), Blocks.STONE.defaultBlockState(), 2);
            for (int dy = 0; dy <= 3; dy++) level.setBlock(start.offset(dx, dy, dz), Blocks.AIR.defaultBlockState(), 2);
        }
        List<net.minecraft.world.level.ChunkPos> forced = new java.util.ArrayList<>();
        for (int cx = (start.getX() - 2) >> 4; cx <= (start.getX() + 70) >> 4; cx++) if (level.setChunkForced(cx, start.getZ() >> 4, true)) forced.add(new net.minecraft.world.level.ChunkPos(cx, start.getZ() >> 4));
        TownspersonEntity officer = UndergroundCity.TOWNSPERSON.get().create(level);
        officer.moveTo(start.getX() + 0.5, start.getY(), start.getZ() + 0.5, -90f, 0f);
        officer.becomeOfficer("valewood", "Officer Speed", 0);
        officer.setPersistenceRequired();
        check(level.addFreshEntity(officer), "the officer was not added");
        officer.errand(start.offset(44, 0, 0), PoliceResponse.CHASE_SPEED);   // within an officer's follow range (48), so the route is whole
        double[] from = new double[1];
        helper.startSequence()
            .thenIdle(START_TICKS)
            .thenExecute(() -> from[0] = officer.getX())
            .thenIdle(RUN_TICKS)
            .thenExecute(() -> {
                try {
                    double speed = (officer.getX() - from[0]) / (RUN_TICKS / 20.0);
                    com.mojang.logging.LogUtils.getLogger().info("[UC-TEST] an officer on the chase ran {} blocks a second (walking player 4.3, sprinting 5.6)", String.format(java.util.Locale.ROOT, "%.2f", speed));
                    // a walking thief (4.3) is caught and a sprinting one (5.6) gains: the officer runs between them
                    check(speed > 4.4 && speed < 5.5, "an officer on the chase ran " + String.format(java.util.Locale.ROOT, "%.2f", speed) + " blocks a second, not between a walk (4.3) and a sprint (5.6)");
                } catch (AssertionError failed) { throw new GameTestAssertException(failed.getMessage()); }
                finally {
                    officer.discard();
                    for (var c : forced) level.setChunkForced(c.x, c.z, false);
                }
            })
            .thenSucceed();
    }

    @GameTest(template = "empty", batch = "uc_police_warrant_a", timeoutTicks = 200)
    public static void aWitnessedCrimeLeavesAWarrantThatOutlivesTheResponseAndAnAlarmAloneLeavesNone(GameTestHelper helper) {
        try (CrimeGameTests.Street s = new CrimeGameTests.Street(helper, "uc_warr_a")) {
            WorkOrderLedger ledger = WorkOrderLedger.get(s.level.getServer());
            var security = new CityBuildings.SecurityClass("test_wired", "commercial", true, "", 0, "test premises", new CityBuildings.Response(1.0, 60, 2, 120), 2, "");
            CityBuildings.Building b = new CityBuildings.Building("valewood", s.centre.getX() >> 4, s.centre.getZ() >> 4, "test", "test_building", 2, 0, s.centre.getY() - 1, security);
            // an alarm alone saw nobody's face: a response, and no warrant
            PoliceResponse.raise(s.striker, s.level, b, s.centre, true, false, false);
            check(PoliceResponse.dispatches() == 1 && !PoliceResponse.dispatchAt(0).identified, "an alarm's response was not raised, or claims somebody saw the thief");
            check(ledger.warrant(s.striker.getUUID(), "valewood") == null, "an alarm that saw nobody left a warrant");
            new PoliceResponse().loggedOut(new PlayerEvent.PlayerLoggedOutEvent(s.striker));
            check(PoliceResponse.dispatches() == 0 && ledger.warrant(s.striker.getUUID(), "valewood") == null, "the alarm's response outlived the logout, or left a warrant");
            // a passer-by who saw the face: a response, and a warrant that outlives it
            CityLaws.Offence assault = CityLaws.INSTANCE.offence("valewood", CityLaws.ASSAULT);
            PoliceResponse.reportStreetCrime(s.striker, s.level, "valewood", s.centre, assault, "witness", assault.reportSeconds());
            WorkOrderLedger.Warrant w = ledger.warrant(s.striker.getUUID(), "valewood");
            check(w != null && w.offence().equals(CityLaws.ASSAULT) && w.fine() == assault.fine() && "witness".equals(w.source()), "a witnessed assault left no warrant, or the wrong one: " + w);
            check(w.expires() == ledger.clock() + WorkOrderLedger.WARRANT_TICKS, "the warrant does not last its active minutes");
            new PoliceResponse().loggedOut(new PlayerEvent.PlayerLoggedOutEvent(s.striker));
            check(PoliceResponse.dispatches() == 0, "the response outlived its offender's session");
            check(ledger.warrant(s.striker.getUUID(), "valewood") != null, "a logout wiped the warrant of a witnessed crime");
            // the worse charge stands: a break-in seen by a witness replaces the assault; a lesser one later does not
            CityLaws.Offence breakIn = CityLaws.INSTANCE.offence("valewood", CityLaws.BREAK_IN);
            ledger.issueWarrant(s.striker.getUUID(), "valewood", breakIn.id(), breakIn.fine(), breakIn.heat(), "a witness");
            ledger.issueWarrant(s.striker.getUUID(), "valewood", assault.id(), assault.fine(), assault.heat(), "a witness");
            check(CityLaws.BREAK_IN.equals(ledger.warrant(s.striker.getUUID(), "valewood").offence()), "a lesser charge replaced the worse one on the warrant");
            check(ledger.warrant(s.striker.getUUID(), "ironhaven") == null && ledger.warrants(s.striker.getUUID()).size() == 1, "a warrant leaked into another city");
        }
        helper.succeed();
    }

    @GameTest(template = "empty", batch = "uc_police_warrant_b", timeoutTicks = 200)
    public static void anOfficerOnTheBeatRecognisesAWantedPersonButNotTheirPartnerAndTheArrestServesTheWarrant(GameTestHelper helper) {
        CrimeGameTests.Street s = new CrimeGameTests.Street(helper, "uc_warr_b");
        ServerPlayer partner = player(helper, "uc_warr_mate", "smith");
        partner.setPos(s.striker.getX() + 1.0, s.striker.getY(), s.striker.getZ());
        TownspersonEntity officer = s.officer(8, 2);
        CrimeGameTests.settled(helper, s, () -> {
            WorkOrderLedger ledger = WorkOrderLedger.get(s.level.getServer());
            CityLaws.Offence assault = CityLaws.INSTANCE.offence("valewood", CityLaws.ASSAULT);
            ledger.issueWarrant(s.striker.getUUID(), "valewood", assault.id(), assault.fine(), assault.heat(), "the victim");
            PolicePatrols.cityAt = (level, pos) -> Optional.of("valewood");
            PolicePatrols.online = server -> List.of(s.striker, partner);
            PolicePatrols.Patrol patrol = new PolicePatrols.Patrol("valewood", s.level.dimension(), List.of(s.centre.offset(8, 0, 2), s.centre.offset(8, 0, 8)), LockService.clock(s.level.getServer()) + 6000);
            patrol.officers.add(officer.getUUID());
            patrol.next.add(0);
            PolicePatrols.putForTest(patrol);
            PolicePatrols.pass(s.level.getServer());
            check(PoliceResponse.dispatches() == 1, "an officer on the beat who saw a wanted person raised " + PoliceResponse.dispatches() + " responses: " + PoliceResponse.status());
            PoliceResponse.Dispatch d = PoliceResponse.dispatchAt(0);
            check(d.burglar.equals(s.striker.getUUID()) && d.why.contains("wanted for") && d.officers.contains(officer.getUUID()) && "pursuit".equals(d.state),
                "the pursuit is not for the wanted person, by the officer who saw them: " + d.why + ", state " + d.state);
            check(PoliceResponse.recognised == 1 && patrol.officers.isEmpty(), "the recognition was not counted or the officer stayed on the beat");
            // the officer beside the two of them: only the wanted person is arrested, and the arrest serves the warrant and records the conviction
            officer.moveTo(s.striker.getX() + 1.0, s.striker.getY(), s.striker.getZ() - 1.0, 0, 0);
            ItemStack honest = new ItemStack(Items.IRON_INGOT, 4);
            partner.getInventory().setItem(0, honest);
            PoliceResponse.pass(s.level.getServer());
            check(PoliceResponse.arrests == 1 && PoliceResponse.arrestsOnWarrant == 1 && PoliceResponse.dispatches() == 0, "the officer beside the wanted person did not arrest them on the warrant: " + PoliceResponse.status());
            check(ledger.warrant(s.striker.getUUID(), "valewood") == null && ledger.priors(s.striker.getUUID(), "valewood") == 1, "the arrest did not serve the warrant and record one conviction");
            check(ledger.warrant(partner.getUUID(), "valewood") == null && ledger.priors(partner.getUUID(), "valewood") == 0 && partner.getInventory().countItem(Items.IRON_INGOT) == 4,
                "the partner beside them was touched by the police");
        });
    }

    @GameTest(template = "empty", batch = "uc_police_warrant_c", timeoutTicks = 200)
    public static void aCheckpointArrestsAWantedPersonAndARepeatOffenderPaysMoreAndIsWarnedNoMore(GameTestHelper helper) {
        check(LawMath.escalatedFine(40, 0) == 40 && LawMath.escalatedFine(40, 1) == 60 && LawMath.escalatedFine(40, 2) == 80 && LawMath.escalatedFine(40, 3) == 100 && LawMath.escalatedFine(40, 9) == 100,
            "the fine does not rise by half for each prior conviction, to at most two and a half times");
        check(LawMath.escalatedFine(900, 3) == 1000 && LawMath.escalatedHeat(2, 0) == 2 && LawMath.escalatedHeat(2, 5) == 5, "the escalation is not capped");
        try (CrimeGameTests.Street s = new CrimeGameTests.Street(helper, "uc_warr_c")) {
            WorkOrderLedger ledger = WorkOrderLedger.get(s.level.getServer());
            var citizen = CitizenState.get(s.level.getServer()).find(s.striker.getUUID()).orElseThrow();
            var law = CityLaws.INSTANCE.find("valewood");
            var gate = new CheckpointCatalog.Checkpoint("test_gate", "Test gate", "valewood", "minecraft:overworld", 0, 0, 48, "inspection");
            ItemStack loot = new ItemStack(Items.GOLD_INGOT, 2);
            StolenGoods.mark(loot, "valewood");
            s.striker.getInventory().setItem(0, loot);
            s.striker.getInventory().setItem(1, new ItemStack(Items.BREAD, 3));
            CityLaws.Offence theft = CityLaws.INSTANCE.offence("valewood", CityLaws.THEFT);
            check(CrimeWatch.cleanRecord(s.striker, "valewood"), "a new citizen does not have a clean record");
            ledger.issueWarrant(s.striker.getUUID(), "valewood", theft.id(), theft.fine(), theft.heat(), "a resident");
            check(!CrimeWatch.cleanRecord(s.striker, "valewood"), "somebody wanted has a clean record");
            int heat = ledger.heat(s.striker.getUUID(), "valewood");
            var result = CheckpointService.arrestWanted(s.striker, citizen, gate, law, ledger, ledger.warrant(s.striker.getUUID(), "valewood"));
            check(result.outcome() == CheckpointService.Outcome.SEIZED && result.stacksSeized() == 1, "the checkpoint's arrest of a wanted person did not take the stolen gold: " + result);
            check(s.striker.getInventory().countItem(Items.GOLD_INGOT) == 0 && s.striker.getInventory().countItem(Items.BREAD) == 3, "honest goods were taken, or the stolen ones left");
            check(ledger.warrant(s.striker.getUUID(), "valewood") == null && ledger.priors(s.striker.getUUID(), "valewood") == 1, "the checkpoint's arrest did not serve the warrant");
            check(ledger.heat(s.striker.getUUID(), "valewood") == Math.min(WorkOrderLedger.MAX_HEAT, heat + theft.heat()), "a first arrest added more than the charge's heat");
            check(!CrimeWatch.cleanRecord(s.striker, "valewood"), "a convicted person still has a clean record (and would be warned)");
            // the second time, the fine and the heat are escalated by the one prior
            ledger.issueWarrant(s.striker.getUUID(), "valewood", theft.id(), theft.fine(), theft.heat(), "a resident");
            int heat2 = ledger.heat(s.striker.getUUID(), "valewood");
            CheckpointService.arrestWanted(s.striker, citizen, gate, law, ledger, ledger.warrant(s.striker.getUUID(), "valewood"));
            check(ledger.priors(s.striker.getUUID(), "valewood") == 2, "the second conviction was not recorded");
            check(ledger.heat(s.striker.getUUID(), "valewood") == Math.min(WorkOrderLedger.MAX_HEAT, heat2 + LawMath.escalatedHeat(theft.heat(), 1)), "a repeat offender's arrest did not add the escalated heat");
            var history = ledger.searches(s.striker.getUUID());
            String last = history.getCompound(history.size() - 1).getString("post");
            check(last.startsWith("checkpoint:test_gate:warrant:"), "the arrest on a warrant is not in the search history: " + last);
        }
        helper.succeed();
    }

    @GameTest(template = "empty", batch = "uc_police_warrant_d", timeoutTicks = 200)
    public static void aConvictionIsSettledOnceWithTheFineEvenIfTheProcessDiesAfterTheDecisionIsOnDisk(GameTestHelper helper) throws Exception {
        java.nio.file.Path path = helper.getLevel().getServer().getWorldPath(net.minecraft.world.level.storage.LevelResource.ROOT).resolve("uc-test-journals/conviction-" + java.util.UUID.randomUUID() + ".dat");
        try (CrimeGameTests.Street s = new CrimeGameTests.Street(helper, "uc_warr_d")) {
            WorkOrderLedger ledger = new WorkOrderLedger(path);
            ledger.issueWarrant(s.striker.getUUID(), "valewood", CityLaws.ASSAULT, 40, 2, "witness");
            var citizen = CitizenState.get(s.level.getServer()).find(s.striker.getUUID()).orElseThrow();
            CheckpointService.crashAfterPrepare = true;
            try { CheckpointService.arrest(s.striker, citizen, "police:test", "valewood", CityLaws.INSTANCE.find("valewood"), ledger, 40, 2); throw new AssertionError("the simulated crash did not happen"); }
            catch (CrewService.SimulatedCrash died) { /* the decision is on disk, nothing applied */ }
            finally { CheckpointService.crashAfterPrepare = false; }
            check(ledger.warrant(s.striker.getUUID(), "valewood") != null && ledger.priors(s.striker.getUUID(), "valewood") == 0, "the warrant was served before the arrest was settled");
            WorkOrderLedger reread = new WorkOrderLedger(path);   // the process died: a new one reads the journal
            WorkOrderService.recover(s.striker, reread);
            WorkOrderService.recover(s.striker, reread);
            check(reread.warrant(s.striker.getUUID(), "valewood") == null && reread.priors(s.striker.getUUID(), "valewood") == 1, "recovery did not serve the warrant and record the conviction exactly once");
        } finally {
            java.nio.file.Files.deleteIfExists(path);
        }
        helper.succeed();
    }
}
