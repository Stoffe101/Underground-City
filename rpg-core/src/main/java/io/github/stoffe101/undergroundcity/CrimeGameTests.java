package io.github.stoffe101.undergroundcity;

import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

import java.util.List;
import java.util.Optional;

import static io.github.stoffe101.undergroundcity.CrewGameTests.check;
import static io.github.stoffe101.undergroundcity.CrewGameTests.player;

/**
 * Crimes the police answer besides a break-in (D-189): an assault seen by a passer-by, by nobody else, by an officer, on an officer; officers who arrive where there is no street; an arrest that
 * charges what was reported; and the noises a burglary makes (a search heard, a sensor tripped). Through real clients: the police scenario.
 */
@GameTestHolder(UndergroundCity.MOD_ID)
@PrefixGameTestTemplate(false)
public final class CrimeGameTests {

    /** An open stone floor high above the test grid with clear air round it (so lines of sight do not depend on the neighbouring tests), a striker, and the police and the watch reset. */
    private static final class Street implements AutoCloseable {
        final ServerLevel level;
        final BlockPos centre;
        final ServerPlayer striker;
        Street(GameTestHelper helper, String name) {
            level = helper.getLevel();
            BlockPos base = helper.absolutePos(new BlockPos(2, 2, 2));
            centre = base.above(level.getMaxBuildHeight() - 40 - base.getY());
            for (int dx = -24; dx <= 24; dx++) for (int dz = -24; dz <= 24; dz++) {
                level.setBlock(centre.offset(dx, -1, dz), Blocks.STONE.defaultBlockState(), 2);
                for (int dy = 0; dy <= 5; dy++) level.setBlock(centre.offset(dx, dy, dz), Blocks.AIR.defaultBlockState(), 2);
            }
            striker = player(helper, name, "farmer");
            striker.setGameMode(GameType.SURVIVAL);
            striker.setPos(centre.getX() + 0.5, centre.getY(), centre.getZ() + 0.5);
            PoliceResponse.resetForTest();
            CrimeWatch.resetForTest();
            PolicePatrols.resetForTest();
            BlockPos far = centre.offset(20, 0, 20);
            PoliceResponse.spots = (lvl, chunk, random, players) -> Optional.of(far);
        }
        TownspersonEntity person(int dx, int dz, int index) {
            PopulationCatalog.Def def = PopulationCatalog.INSTANCE.city("valewood");
            var who = PopulationPlan.roster("valewood", 5908L, def.count(), def.mix(), def.names(), def.reachChunks()).get(index);
            TownspersonEntity body = UndergroundCity.TOWNSPERSON.get().create(level);
            BlockPos at = centre.offset(dx, 0, dz);
            body.moveTo(at.getX() + 0.5, at.getY(), at.getZ() + 0.5, 0, 0);
            body.become(who, at);
            check(level.addFreshEntity(body), "a townsperson was not added");
            return body;
        }
        TownspersonEntity officer(int dx, int dz) {
            TownspersonEntity officer = UndergroundCity.TOWNSPERSON.get().create(level);
            BlockPos at = centre.offset(dx, 0, dz);
            officer.moveTo(at.getX() + 0.5, at.getY(), at.getZ() + 0.5, 0, 0);
            officer.becomeOfficer("valewood", "Officer Test", 0);
            check(level.addFreshEntity(officer), "an officer was not added");
            return officer;
        }
        @Override public void close() {
            for (TownspersonEntity t : level.getEntitiesOfClass(TownspersonEntity.class, new AABB(centre).inflate(48))) t.discard();
            PoliceResponse.resetForTest();
            CrimeWatch.resetForTest();
            PolicePatrols.resetForTest();
        }
    }

    @GameTest(template = "empty", batch = "uc_crime_a", timeoutTicks = 200)
    public static void aBlowAPasserBySeesBringsThePoliceOnceHoweverManyBlows(GameTestHelper helper) {
        try (Street s = new Street(helper, "uc_crime_a")) {
            TownspersonEntity victim = s.person(1, 0, 0), passerBy = s.person(6, 3, 1);
            WorkOrderLedger ledger = WorkOrderLedger.get(s.level.getServer());
            int heat = ledger.heat(s.striker.getUUID(), "valewood");
            long now = LockService.clock(s.level.getServer());
            for (int i = 0; i < 5; i++) CrimeWatch.assault(s.striker, s.level, victim, "valewood", false);
            check(PoliceResponse.dispatches() == 1, "five blows in one incident raised " + PoliceResponse.dispatches() + " responses");
            PoliceResponse.Dispatch d = PoliceResponse.dispatchAt(0);
            CityLaws.Offence assault = CityLaws.INSTANCE.offence("valewood", CityLaws.ASSAULT);
            check(d.offence.id().equals(CityLaws.ASSAULT) && !d.burglary, "the response is not for an assault: " + d.offence.id());
            check(d.arriveAt == now + assault.reportSeconds() * 20L, "a passer-by's call does not bring officers in the city's report time: " + (d.arriveAt - now) / 20 + " s");
            check(ledger.heat(s.striker.getUUID(), "valewood") == heat + PopulationService.HEAT_FOR_STRIKING, "heat rose more than once for one incident");
            check(CrimeWatch.assaultsReported == 1, "the call was not counted");
            // a blow on somebody else is another incident, but joins the response already out
            TownspersonEntity second = s.person(-2, 1, 2);
            CrimeWatch.assault(s.striker, s.level, second, "valewood", false);
            check(PoliceResponse.dispatches() == 1, "a second victim raised a second response instead of joining the first");
            passerBy.discard();
        }
        helper.succeed();
    }

    @GameTest(template = "empty", batch = "uc_crime_b", timeoutTicks = 200)
    public static void aBlowNobodyElseSeesIsReportedLaterByTheVictimOrNotAtAll(GameTestHelper helper) {
        try (Street s = new Street(helper, "uc_crime_b")) {
            TownspersonEntity victim = s.person(1, 0, 3);
            long tick = s.level.getGameTime(), now = LockService.clock(s.level.getServer());
            long seed = LawMath.mix(s.striker.getUUID().getMostSignificantBits(), victim.getUUID().getLeastSignificantBits(), tick / CrimeWatch.INCIDENT_TICKS, 0xA55A17L);
            boolean reports = LawMath.roll(CrimeWatch.VICTIM_REPORTS, seed);
            CrimeWatch.assault(s.striker, s.level, victim, "valewood", false);
            CityLaws.Offence assault = CityLaws.INSTANCE.offence("valewood", CityLaws.ASSAULT);
            if (reports) {
                check(PoliceResponse.dispatches() == 1, "the victim's report raised no response");
                check(PoliceResponse.dispatchAt(0).arriveAt == now + assault.reportSeconds() * CrimeWatch.VICTIM_DELAY_FACTOR * 20L, "a victim's own report is not slower than a passer-by's call");
            } else {
                check(PoliceResponse.dispatches() == 0 && CrimeWatch.assaultsUnreported == 1, "an unreported blow raised a response");
            }
        }
        helper.succeed();
    }

    @GameTest(template = "empty", batch = "uc_crime_c", timeoutTicks = 200)
    public static void anOfficerWhoSeesAFirstBlowWarnsAndPursuesTheNext(GameTestHelper helper) {
        try (Street s = new Street(helper, "uc_crime_c")) {
            TownspersonEntity victim = s.person(1, 0, 4);
            TownspersonEntity officer = s.officer(5, 2);
            PolicePatrols.Patrol patrol = new PolicePatrols.Patrol("valewood", s.level.dimension(), List.of(s.centre.offset(5, 0, 2)), LockService.clock(s.level.getServer()) + 6000);
            patrol.officers.add(officer.getUUID());
            patrol.next.add(0);
            PolicePatrols.putForTest(patrol);
            CrimeWatch.assault(s.striker, s.level, victim, "valewood", false);
            check(PoliceResponse.dispatches() == 0 && CrimeWatch.warnings == 1, "an officer who saw a first blow did not give a warning: dispatches " + PoliceResponse.dispatches() + ", warnings " + CrimeWatch.warnings);
            CrimeWatch.assault(s.striker, s.level, victim, "valewood", false);   // the next blow, in front of the same officer
            check(PoliceResponse.dispatches() == 1, "the blow after a warning was not pursued");
            PoliceResponse.Dispatch d = PoliceResponse.dispatchAt(0);
            check(d.arrived && d.officers.contains(officer.getUUID()) && d.offence.id().equals(CityLaws.ASSAULT), "the officer who saw it is not pursuing for assault");
        }
        helper.succeed();
    }

    @GameTest(template = "empty", batch = "uc_crime_d", timeoutTicks = 200)
    public static void strikingAnOfficerIsPursuedAtOnceAndChargedAsSuch(GameTestHelper helper) {
        try (Street s = new Street(helper, "uc_crime_d")) {
            TownspersonEntity officer = s.officer(2, 0);
            CrimeWatch.assault(s.striker, s.level, officer, "valewood", true);
            CrimeWatch.assault(s.striker, s.level, officer, "valewood", true);
            check(PoliceResponse.dispatches() == 1, "striking an officer raised " + PoliceResponse.dispatches() + " responses");
            PoliceResponse.Dispatch d = PoliceResponse.dispatchAt(0);
            check(d.arrived && d.officers.contains(officer.getUUID()) && d.offence.id().equals(CityLaws.ASSAULT_OFFICER), "the officer struck is not pursuing for assaulting an officer: " + d.offence.id());
            // the officer next to the striker arrests, for that charge, with the city's fine for it
            officer.moveTo(s.striker.getX() + 1.0, s.striker.getY(), s.striker.getZ(), 0, 0);
            WorkOrderLedger ledger = WorkOrderLedger.get(s.level.getServer());
            int heat = ledger.heat(s.striker.getUUID(), "valewood");
            PoliceResponse.pass(s.level.getServer());
            check(PoliceResponse.arrests == 1 && PoliceResponse.dispatches() == 0, "the officer beside the striker did not arrest them: " + PoliceResponse.status());
            int expected = Math.min(WorkOrderLedger.MAX_HEAT, heat + CityLaws.INSTANCE.offence("valewood", CityLaws.ASSAULT_OFFICER).heat());
            check(ledger.heat(s.striker.getUUID(), "valewood") == expected, "the arrest did not add the charge's heat: " + ledger.heat(s.striker.getUUID(), "valewood") + " for " + expected);
        }
        helper.succeed();
    }

    @GameTest(template = "empty", batch = "uc_crime_e", timeoutTicks = 200)
    public static void officersComeFromOpenGroundWhereNoStreetIsFoundAndOutOfTheOffendersSight(GameTestHelper helper) {
        try (Street s = new Street(helper, "uc_crime_e")) {
            PoliceResponse.spots = (lvl, chunk, random, players) -> Optional.empty();   // no street of the street population anywhere near
            List<BlockPos> spots = PoliceResponse.fallbackSpots(s.level, s.striker, s.centre);
            check(!spots.isEmpty(), "no open ground was found round the scene");
            for (BlockPos p : spots) {
                check(CityArrival.standable(s.level, p), "an officer would arrive on ground nobody can stand on: " + p.toShortString());
                check(s.striker.distanceToSqr(p.getX() + 0.5, p.getY(), p.getZ() + 0.5) >= 14 * 14, "an officer would arrive beside the offender");
            }
            TownspersonEntity victim = s.person(1, 0, 5), passerBy = s.person(4, 4, 6);
            CrimeWatch.assault(s.striker, s.level, victim, "valewood", false);
            PoliceResponse.Dispatch d = PoliceResponse.dispatchAt(0);
            d.arriveAt = LockService.clock(s.level.getServer());
            PoliceResponse.pass(s.level.getServer());
            check(d.arrived && !d.officers.isEmpty(), "with no street spot the reported assault brought no officers: " + PoliceResponse.status());
            passerBy.discard();
        }
        helper.succeed();
    }

    @GameTest(template = "empty", batch = "uc_crime_f", timeoutTicks = 200)
    public static void aSensorOrSomebodyAtHomeCanCatchASearchAndNothingCatchesItWhenNobodyIs(GameTestHelper helper) {
        try (Street s = new Street(helper, "uc_crime_f")) {
            var security = new CityBuildings.SecurityClass("test_wired", "commercial", true, "", 0, "test premises", new CityBuildings.Response(1.0, 60, 2, 120), 4, "");
            CityBuildings.Building b = new CityBuildings.Building("valewood", s.centre.getX() >> 4, s.centre.getZ() >> 4, "test", "test_building", 2, 0, s.centre.getY() - 1, security);
            CrimeWatch.forceRollsForTest(0.99);
            CrimeWatch.searched(s.striker, s.level, b, s.centre);
            check(PoliceResponse.dispatches() == 0, "a search nobody heard and no sensor saw raised a response");
            CrimeWatch.forceRollsForTest(0.0);
            CrimeWatch.searched(s.striker, s.level, b, s.centre);
            check(PoliceResponse.dispatches() == 1 && CrimeWatch.sensorsTripped == 1, "a wired building's sensor did not catch the search: " + CrimeWatch.status());
            // damage with an armed alarm and glass: the glass-break alarm rings for certain (a response is already out, so it joins it rather than raising another)
            CrimeWatch.forceRollsForTest(0.99);
            check(!CrimeWatch.damaged(s.striker, s.level, b, s.centre, true) && PoliceResponse.dispatches() == 1, "a second noise raised a second response");
        }
        helper.succeed();
    }
}
