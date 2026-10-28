package io.github.stoffe101.undergroundcity;

import com.mojang.authlib.GameProfile;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestAssertException;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.common.util.FakePlayerFactory;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

import java.util.UUID;

/**
 * Fitness, Driving and Shooting (D-200), on a server-side player the test moves itself: a straight walk pays and a walk in circles hardly does, a player who never turns their
 * view earns nothing, a teleport is not a walk, the hourly cap holds, a hit on a target pays by distance and once per block in two seconds, and each skill does what its level says
 * (a quicker walk, a cheaper vehicle within the shared cap, a shot turned toward the aim with its speed kept).
 */
@GameTestHolder(UndergroundCity.MOD_ID)
@PrefixGameTestTemplate(false)
public final class GeneralActivityGameTests {
    private static void check(boolean condition, String message) { if (!condition) throw new GameTestAssertException(message); }

    private static ServerPlayer citizen(GameTestHelper helper, String name) {
        ServerPlayer player = FakePlayerFactory.get(helper.getLevel(), new GameProfile(UUID.randomUUID(), name));
        CitizenState.get(player.server).register(player.getUUID(), "valewood", "farmer");
        return player;
    }

    private static int xp(ServerPlayer player, String skill) { return CitizenState.get(player.server).find(player.getUUID()).orElseThrow().generalXp.getOrDefault(skill, 0); }

    /** Ten one-second samples moving {@code step} a second (turning the view by {@code turn} degrees a second), starting at tick {@code from}; returns the tick after. */
    private static long walk(ServerPlayer player, Vec3 step, float turn, long from) { return walk(player, step, turn, from, false); }

    /** The same; {@code pace} turns the walker round every second (back and forth inside the window). */
    private static long walk(ServerPlayer player, Vec3 step, float turn, long from, boolean pace) {
        long t = from;
        for (int i = 0; i <= GeneralActivity.WINDOW_SAMPLES; i++) {
            if (i > 0) player.setPos(player.position().add(pace && i % 2 == 0 ? step.reverse() : step));
            player.setYRot(player.getYRot() + turn);
            GeneralActivity.sample(player, t);
            t += GeneralActivity.SAMPLE_TICKS;
        }
        return t;
    }

    @GameTest(template = "empty")
    public static void aStraightWalkPaysFitnessAndCirclesStandingStillAndTeleportsDoNot(GameTestHelper helper) {
        GeneralActivity.clearForTest();
        ServerPlayer walker = citizen(helper, "uc_walker");
        walker.setPos(0, 100, 0);
        check(GeneralActivity.mode(walker) == GeneralActivity.Mode.FOOT, "a player standing on nothing in particular is on foot: " + GeneralActivity.mode(walker));
        long t = walk(walker, new Vec3(5, 0, 0), 2, 1_000_000);   // fifty blocks in ten seconds, turning the view
        int straight = xp(walker, GeneralSkills.FITNESS);
        check(straight == 2, "fifty blocks in a straight line pay two Fitness XP (one per 25): " + straight);
        // pacing back and forth: the window's straight line is a few blocks at most
        for (int i = 0; i < 4; i++) t = walk(walker, new Vec3(5, 0, 0), 2, t, true);
        check(xp(walker, GeneralSkills.FITNESS) - straight <= 1, "walking back and forth paid " + (xp(walker, GeneralSkills.FITNESS) - straight));
        // a player who never turns their view (a water stream, a moving platform) earns nothing once a minute has passed since they last looked round
        GeneralActivity.clearForTest();
        ServerPlayer drifter = citizen(helper, "uc_drifter");
        drifter.setPos(0, 100, 0);
        long u = 2_000_000;
        for (int i = 0; i < 9; i++) u = walk(drifter, new Vec3(5, 0, 0), 0, u);
        int drift = xp(drifter, GeneralSkills.FITNESS);
        check(drift == 0, "a player who never turned their view earned " + drift);
        // a teleport inside a window is not a walk
        GeneralActivity.clearForTest();
        ServerPlayer jumper = citizen(helper, "uc_jumper");
        jumper.setPos(0, 100, 0);
        walk(jumper, new Vec3(GeneralActivity.TELEPORT_STEP + 10, 0, 0), 2, 3_000_000);
        check(xp(jumper, GeneralSkills.FITNESS) == 0, "a teleport paid " + xp(jumper, GeneralSkills.FITNESS));
        helper.succeed();
    }

    /** Ten one-second samples of a driver whose boat moves {@code step} a second and turns {@code boatTurn} degrees a second, the driver's own view held still; returns the tick after. */
    private static long drive(ServerPlayer driver, net.minecraft.world.entity.vehicle.Boat boat, Vec3 step, float boatTurn, long from) {
        long t = from;
        for (int i = 0; i <= GeneralActivity.WINDOW_SAMPLES; i++) {
            if (i > 0) boat.setPos(boat.position().add(step));
            boat.setYRot(boat.getYRot() + boatTurn);
            boat.positionRider(driver);
            GeneralActivity.sample(driver, t);
            t += GeneralActivity.SAMPLE_TICKS;
        }
        return t;
    }

    /**
     * At the controls the driver steers with keys while the camera may stay put (eleventh pass, found preparing the real-client drive): a boat that turns as it is steered counts as looking
     * round, so a straight road still pays; a boat drifting straight with a still view for over a minute pays nothing, as a still walker does.
     */
    @GameTest(template = "empty")
    public static void aDriverWhoSteersEarnsDrivingWithAStillCameraAndADriftingBoatDoesNot(GameTestHelper helper) {
        GeneralActivity.clearForTest();
        ServerPlayer driver = citizen(helper, "uc_steerer");
        var boat = new net.minecraft.world.entity.vehicle.Boat(helper.getLevel(), 0, 100, 0);
        helper.getLevel().addFreshEntity(boat);
        try {
            check(driver.startRiding(boat, true) && GeneralActivity.mode(driver) == GeneralActivity.Mode.DRIVE, "a player in a boat is driving: " + GeneralActivity.mode(driver));
            long t = 6_000_000;
            for (int i = 0; i < 9; i++) t = drive(driver, boat, new Vec3(10, 0, 0), 1.0f, t);   // ninety seconds, steering, the camera still
            int steered = xp(driver, GeneralSkills.DRIVING);
            check(steered >= 15, "a driver steering with a still camera earned " + steered + " Driving XP over 900 blocks");
            GeneralActivity.clearForTest();
            ServerPlayer drifter = citizen(helper, "uc_boatdrifter");
            boat.ejectPassengers();
            check(drifter.startRiding(boat, true), "the second player boarded");
            long u = 7_000_000;
            for (int i = 0; i < 9; i++) u = drive(drifter, boat, new Vec3(10, 0, 0), 0f, u);
            check(xp(drifter, GeneralSkills.DRIVING) == 0, "a boat drifting straight with a still view paid " + xp(drifter, GeneralSkills.DRIVING));
        } finally {
            boat.ejectPassengers();
            boat.discard();
        }
        helper.succeed();
    }

    /** The hour's cap is kept with the world (eleventh pass): a restart and a re-login used to start a new hour; now neither does, and the next hour of play pays again. */
    @GameTest(template = "empty")
    public static void aCapReachedBeforeARestartOrAReloginIsStillReachedAfterIt(GameTestHelper helper) {
        GeneralActivity.clearForTest();
        ServerPlayer runner = citizen(helper, "uc_capkeeper");
        long now = 9_000_000;
        int paid = 0;
        for (int i = 0; i < 300; i++) paid += GeneralActivity.earn(runner, GeneralActivity.track(runner.getUUID()), GeneralSkills.DRIVING, 2.8, GeneralActivity.DRIVING_CAP, now + i * 100L);
        check(paid == GeneralActivity.DRIVING_CAP, "the hour paid " + paid);
        // logging out and in again forgets the movement window, not the hour
        GeneralActivity.clearForTest();
        check(GeneralActivity.earn(runner, GeneralActivity.track(runner.getUUID()), GeneralSkills.DRIVING, 5, GeneralActivity.DRIVING_CAP, now + 40_000) == 0, "a re-login started a new hour");
        // a restart reads the caps back from the world
        HourlyCaps saved = HourlyCaps.get(runner.server);
        HourlyCaps read = HourlyCaps.load(saved.save(new net.minecraft.nbt.CompoundTag()));
        String key = runner.getUUID() + "|" + GeneralSkills.DRIVING;
        check(read.room(key, GeneralActivity.DRIVING_CAP, now + 40_000) == 0, "after a restart the cap has room again");
        // an hour of the world's active time later it pays again; time with nobody playing does not count, so being away costs nothing either way
        check(GeneralActivity.earn(runner, GeneralActivity.track(runner.getUUID()), GeneralSkills.DRIVING, 5, GeneralActivity.DRIVING_CAP, now + HourlyCaps.HOUR_TICKS + 1) == 5, "the next hour did not pay");
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void theHourlyCapHoldsAndANewHourPaysAgain(GameTestHelper helper) {
        GeneralActivity.clearForTest();
        ServerPlayer runner = citizen(helper, "uc_runner");
        GeneralActivity.Track track = GeneralActivity.track(runner.getUUID());
        long now = 5_000_000;
        int paid = 0;
        for (int i = 0; i < 300; i++) paid += GeneralActivity.earn(runner, track, GeneralSkills.FITNESS, 2.8, GeneralActivity.FITNESS_CAP, now + i * 200L);   // 840 offered within the hour
        check(paid == GeneralActivity.FITNESS_CAP, "an hour paid " + paid + ", not the cap of " + GeneralActivity.FITNESS_CAP);
        int later = GeneralActivity.earn(runner, track, GeneralSkills.FITNESS, 2.8, GeneralActivity.FITNESS_CAP, now + GeneralActivity.HOUR_TICKS + 1);
        check(later == 2, "the next hour paid " + later);
        check(xp(runner, GeneralSkills.FITNESS) == GeneralActivity.FITNESS_CAP + 2, "the citizen holds " + xp(runner, GeneralSkills.FITNESS));
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void aTargetHitPaysShootingFromTenBlocksOncePerBlockInTwoSeconds(GameTestHelper helper) {
        GeneralActivity.clearForTest();
        ServerPlayer shooter = citizen(helper, "uc_shooter");
        BlockPos target = new BlockPos(10, 64, 10);
        check(GeneralActivity.targetHit(shooter, target, 10, 6) == 0, "a hit from six blocks paid");
        int bull = GeneralActivity.targetHit(shooter, target, 10, 20);
        check(bull == GeneralSkills.shotXp(10) && bull == 7, "a bull's-eye from twenty blocks paid " + bull);
        check(GeneralActivity.targetHit(shooter, target, 10, 20) == 0, "the same block paid twice within two seconds");
        check(GeneralActivity.targetHit(shooter, target.east(), 0, 20) == 2, "another block's edge did not pay the 2 for a hit");
        check(xp(shooter, GeneralSkills.SHOOTING) == 9, "the citizen holds " + xp(shooter, GeneralSkills.SHOOTING) + " Shooting XP");
        // somebody who is not a citizen earns nothing and nothing is stored
        ServerPlayer stranger = FakePlayerFactory.get(helper.getLevel(), new GameProfile(UUID.randomUUID(), "uc_stranger"));
        check(GeneralActivity.targetHit(stranger, target.west(), 10, 20) == 0, "a stranger was paid");
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void eachSkillDoesWhatItsLevelSays(GameTestHelper helper) {
        GeneralActivity.clearForTest();
        ServerPlayer player = citizen(helper, "uc_fit");
        CitizenState state = CitizenState.get(player.server);
        // Fitness 3: two per cent quicker on foot, kept as one modifier however often it is applied
        double base = player.getAttribute(Attributes.MOVEMENT_SPEED).getValue();
        state.addGeneralXp(player.getUUID(), GeneralSkills.FITNESS, ProgressionRules.xpAtLevel(3));
        GeneralActivity.applyFitness(player);
        GeneralActivity.applyFitness(player);
        double quick = player.getAttribute(Attributes.MOVEMENT_SPEED).getValue();
        check(Math.abs(quick - base * 1.02) < 1e-9, "Fitness 3 walks at " + quick + " for a base of " + base);
        check(player.getAttribute(Attributes.MOVEMENT_SPEED).getModifiers().stream().filter(m -> m.getId().equals(GeneralActivity.FITNESS_SPEED)).count() == 1, "the modifier is not one");
        // Driving: a vehicle line is cheaper, any other line is not, and nothing passes the shared cap
        RegionalStock bicycle = new RegionalStock("underground_city:vehicles/bicycle_valewood", "valewood", new net.minecraft.resources.ResourceLocation("minecraft", "stick"), 60, 1, 1, 1, 72000, 1);
        RegionalStock bread = new RegionalStock("underground_city:shops/bread_valewood", "valewood", new net.minecraft.resources.ResourceLocation("minecraft", "bread"), 60, 1, 1, 1, 72000, 1);
        CitizenState.Citizen c = state.find(player.getUUID()).orElseThrow();
        state.addGeneralXp(player.getUUID(), GeneralSkills.DRIVING, ProgressionRules.xpAtLevel(6));
        check(Math.abs(GeneralSkills.exchangeDiscount(c, bicycle) - GeneralSkills.exchangeDiscount(c) - 0.06) < 1e-9, "Driving 6 takes 6% off a vehicle: " + GeneralSkills.exchangeDiscount(c, bicycle));
        check(GeneralSkills.exchangeDiscount(c, bread) == GeneralSkills.exchangeDiscount(c), "Driving changed the price of bread");
        state.addGeneralXp(player.getUUID(), GeneralSkills.TRADING, ProgressionRules.xpAtLevel(10));
        state.addGeneralXp(player.getUUID(), GeneralSkills.DRIVING, ProgressionRules.xpAtLevel(10));
        check(GeneralSkills.exchangeDiscount(c, bicycle) <= SkillNode.MAX_TOTAL_DISCOUNT, "Trading and Driving together passed the 10% cap");
        // Shooting: a shot turned toward the aim, its speed the same
        Vec3 motion = new Vec3(1, 0.2, 0.3).normalize().scale(3), look = new Vec3(1, 0, 0);
        Vec3 steady = GeneralActivity.steady(motion, look, GeneralSkills.steadiness(3));
        double before = Math.acos(motion.normalize().dot(look)), after = Math.acos(steady.normalize().dot(look));
        check(after < before && Math.abs(steady.length() - motion.length()) < 1e-9, "the shot was not steadied: " + before + " -> " + after + ", speed " + steady.length());
        check(GeneralActivity.steady(motion, look, GeneralSkills.steadiness(2)).equals(motion) || GeneralSkills.steadiness(2) == 0, "level 2 changed the shot");
        // every one of the five is shown as used and says how it is earned
        for (String skill : GeneralSkills.SHOWN) check(!GeneralSkills.earned(skill).startsWith("Nothing"), skill + " still says nothing earns it");
        helper.succeed();
    }
    /** A city's good name (D-204): earned within its hourly cap, a discount only in that city from 50, lost by an arrest, and kept by a save. */
    @GameTest(template = "empty")
    public static void aCitysGoodNameIsEarnedSlowlyGivesASmallDiscountThereAndIsLostByAnArrest(GameTestHelper helper) {
        CityStanding.clearForTest(helper.getLevel().getServer());
        ServerPlayer player = citizen(helper, "uc_standing");
        CitizenState state = CitizenState.get(player.server);
        int paid = 0;
        for (int i = 0; i < 30; i++) paid += CityStanding.earn(player, "valewood", CityStanding.DELIVERY + CityStanding.CIVIC);
        check(paid == CityStanding.HOURLY_CAP, "an hour earned " + paid + " standing, not the cap of " + CityStanding.HOURLY_CAP);
        CitizenState.Citizen c = state.find(player.getUUID()).orElseThrow();
        check(CityStanding.of(c, "valewood") == CityStanding.HOURLY_CAP && CityStanding.of(c, "ironhaven") == 0, "standing leaked to another city");
        state.changeStanding(player.getUUID(), "valewood", CityStanding.RESPECTED);
        RegionalStock vale = new RegionalStock("underground_city:test/bread_valewood", "valewood", new net.minecraft.resources.ResourceLocation("minecraft", "bread"), 50, 1, 1, 1, 72000, 1);
        RegionalStock iron = new RegionalStock("underground_city:test/bread_ironhaven", "ironhaven", new net.minecraft.resources.ResourceLocation("minecraft", "bread"), 50, 1, 1, 1, 72000, 1);
        check(Math.abs(GeneralSkills.exchangeDiscount(c, vale) - GeneralSkills.exchangeDiscount(c) - 0.02) < 1e-9 && GeneralSkills.exchangeDiscount(c, iron) == GeneralSkills.exchangeDiscount(c),
            "a respected name takes 2% off in its own city only: " + GeneralSkills.exchangeDiscount(c, vale) + " / " + GeneralSkills.exchangeDiscount(c, iron));
        check(CityStanding.greeting(c, "valewood", "Ann").startsWith("You're the one") && CityStanding.greeting(c, "ironhaven", "Ann").isEmpty(), "the greeting is wrong");
        int before = CityStanding.of(c, "valewood");
        CityStanding.arrested(player.server, player, "valewood");
        check(CityStanding.of(c, "valewood") == before - CityStanding.ARREST, "an arrest did not cost " + CityStanding.ARREST);
        CitizenState loaded = CitizenState.load(state.save(new net.minecraft.nbt.CompoundTag()));
        check(CityStanding.of(loaded.find(player.getUUID()).orElseThrow(), "valewood") == before - CityStanding.ARREST, "the standing did not survive a save");
        net.minecraft.nbt.CompoundTag old = state.save(new net.minecraft.nbt.CompoundTag());
        old.getList("citizens", 10).forEach(t -> ((net.minecraft.nbt.CompoundTag) t).remove("standing"));
        check(CityStanding.of(CitizenState.load(old).find(player.getUUID()).orElseThrow(), "valewood") == 0, "an older save without standing did not load with none");
        helper.succeed();
    }
}
