package io.github.stoffe101.undergroundcity;

import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.phys.AABB;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static io.github.stoffe101.undergroundcity.CrewGameTests.check;
import static io.github.stoffe101.undergroundcity.CrewGameTests.player;

/**
 * The police response to a noticed break-in (D-122). Headless, on the real server loop with fake players: it proves the rules (an alarm or a witness is needed, one dispatch per thief and building,
 * officers arrive at a street spot and wait at the door, an officer who reaches the thief arrests them through the crash-safe settlement, a thief who gets away or leaves is never caught, nobody is policed
 * while away or in creative) and the evidence mark on goods from a forced safe. It does not show a person being chased through a real street, nor what any of it feels like.
 */
@GameTestHolder(UndergroundCity.MOD_ID)
@PrefixGameTestTemplate(false)
public final class PoliceResponseGameTests {
    private static final CityBuildings.Response ALARMED = new CityBuildings.Response(1.0, 60, 2, 120);

    private static CityBuildings.Building building(GameTestHelper helper, BlockPos door, CityBuildings.Response response) {
        var security = new CityBuildings.SecurityClass("test_alarmed", "commercial", true, "", 0, "test premises", response);
        return new CityBuildings.Building("valewood", door.getX() >> 4, door.getZ() >> 4, "test", "test_building", 2, 0, door.getY() - 1, security);
    }

    private static BuildingBreachEvent opened(ServerPlayer p, BlockPos door, long tick) {
        return new BuildingBreachEvent(p.getUUID(), door, "valewood", "test_alarmed", BuildingBreachEvent.Outcome.OPENED, tick, false, p);
    }

    private static final class Scene implements AutoCloseable {
        final GameTestHelper helper;
        final ServerLevel level;
        final BlockPos door, street;
        final ServerPlayer thief;
        final CityBuildings.Building building;
        Scene(GameTestHelper helper, String name, CityBuildings.Response response) { this(helper, name, response, false); }

        /**
         * With {@code aloft}, the scene stands on a stone floor of its own high above the test grid, with clear air round it: the GameTest template is one block, so a scene on the flat floor
         * reaches into its neighbours' cells, and whether an officer can see the thief (D-146) would depend on what the neighbouring tests happen to have built there (hosted run 281).
         */
        Scene(GameTestHelper helper, String name, CityBuildings.Response response, boolean aloft) {
            this.helper = helper;
            level = helper.getLevel();
            BlockPos base = helper.absolutePos(new BlockPos(2, 2, 2));
            int lift = aloft ? level.getMaxBuildHeight() - 40 - base.getY() : 0;
            door = base.above(lift);
            street = helper.absolutePos(new BlockPos(9, 2, 9)).above(lift);
            if (aloft) {
                var stone = net.minecraft.world.level.block.Blocks.STONE.defaultBlockState();
                var air = net.minecraft.world.level.block.Blocks.AIR.defaultBlockState();
                for (int dx = -18; dx <= 18; dx++) for (int dz = -18; dz <= 18; dz++) {
                    level.setBlock(door.offset(dx, -1, dz), stone, 2);
                    for (int dy = 0; dy <= 4; dy++) level.setBlock(door.offset(dx, dy, dz), air, 2);
                }
            }
            thief = player(helper, name, "farmer");
            thief.setPos(door.getX() + 0.5, door.getY(), door.getZ() + 1.5);
            building = building(helper, door, response);
            PoliceResponse.resetForTest();
            CityBuildings.INSTANCE.overrideForTest((cx, cz) -> Optional.of(building));
            PoliceResponse.spots = (lvl, chunk, random, players) -> Optional.of(street);
        }
        List<TownspersonEntity> officers() {
            return level.getEntitiesOfClass(TownspersonEntity.class, new AABB(door).inflate(64), TownspersonEntity::police);
        }
        @Override public void close() {
            for (TownspersonEntity officer : officers()) officer.discard();
            PoliceResponse.resetForTest();
            CityBuildings.INSTANCE.overrideForTest(null);
            CheckpointService.crashAfterPrepare = false;
        }
    }

    // ------------------------------------------------------------------------------------------------ the data

    @GameTest(template = "empty")
    public static void everyClassWithAnAlarmHasAnAnswerAndTheMoreValuableTheQuicker(GameTestHelper helper) {
        var cottage = CityBuildings.INSTANCE.securityClass("cottage");
        var flats = CityBuildings.INSTANCE.securityClass("flat_block").response();
        var tower = CityBuildings.INSTANCE.securityClass("flat_tower").response();
        var office = CityBuildings.INSTANCE.securityClass("office").response();
        var vault = CityBuildings.INSTANCE.securityClass("vault").response();
        check(cottage != null && cottage.response() == null, "a cottage has no alarm of its own (only a neighbour can call the police)");
        check(flats != null && tower != null && office != null && vault != null, "the stock classes lost their response profiles");
        check(vault.alarmChance() == 1.0 && vault.alarmChance() > office.alarmChance() && office.alarmChance() > flats.alarmChance(), "alarms are not more likely the more valuable the premises");
        check(vault.dispatchSeconds() < office.dispatchSeconds() && office.dispatchSeconds() < tower.dispatchSeconds() && tower.dispatchSeconds() < flats.dispatchSeconds(), "police are not quicker the more valuable the premises");
        check(vault.officers() > flats.officers(), "high-value premises do not draw more officers");
        for (var c : CityBuildings.INSTANCE.classes().values()) {
            if (c.response() == null) continue;
            check(c.response().dispatchSeconds() >= 30, c.id() + ": police arrive before a thief has had half a minute to work");
        }
        boolean refused = false;
        try { new CityBuildings.Response(1.5, 60, 1, 60); } catch (IllegalArgumentException expected) { refused = true; }
        check(refused, "an alarm chance above 1 must be refused");
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void whetherAnAlarmIsArmedIsAFixedFunctionOfTheBuildingAndTheChance(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        var sure = new CityBuildings.Response(1.0, 60, 1, 60);
        var never = new CityBuildings.Response(0.0, 60, 1, 60);
        var half = new CityBuildings.Response(0.5, 60, 1, 60);
        int armed = 0;
        for (int x = 0; x < 400; x++) {
            var b = new CityBuildings.Building("valewood", x, x * 7 - 13, "t", "t", 2, 0, 70, null);
            check(PoliceResponse.armed(level, b, sure) && !PoliceResponse.armed(level, b, never), "a certain alarm must always be armed and an impossible one never");
            boolean once = PoliceResponse.armed(level, b, half);
            check(once == PoliceResponse.armed(level, b, half), "the same building must give the same answer every time");
            if (once) armed++;
        }
        check(armed > 150 && armed < 250, "an even chance armed " + armed + " of 400 buildings");
        helper.succeed();
    }

    // ------------------------------------------------------------------------------------------------ noticing

    @GameTest(template = "empty")
    public static void anArmedAlarmRaisesOneResponseAndAnotherNoiseOnlyBringsItSooner(GameTestHelper helper) {
        try (Scene s = new Scene(helper, "uc_pol_a", ALARMED)) {
            long now = LockService.clock(s.level.getServer());
            // officers of a neighbouring test may stand within reach (the qshop smoke's layout, October 26): this dispatch must add none, all in this one tick
            var before = new java.util.HashSet<>(s.officers());
            PoliceResponse.noticed(s.thief, opened(s.thief, s.door, now));
            check(PoliceResponse.dispatches() == 1, "an armed alarm raised " + PoliceResponse.dispatches() + " responses");
            var d = PoliceResponse.dispatchAt(0);
            // the alarm's own answer: its time and its officers (a townsperson of a neighbouring test who happens to see the thief adds "and witness" to the reason, nothing else; hosted layouts vary)
            check(d.why.startsWith("alarm") && d.arriveAt == now + 60 * 20L && d.officersWanted == 2, "the response is not the class's: " + d.why + " " + (d.arriveAt - now) + " " + d.officersWanted);
            long first = d.arriveAt;
            PoliceResponse.noticed(s.thief, new BuildingBreachEvent(s.thief.getUUID(), s.door, "valewood", "test_alarmed", BuildingBreachEvent.Outcome.SNAPPED, now + 100, true, s.thief));
            check(PoliceResponse.dispatches() == 1 && PoliceResponse.dispatchAt(0).arriveAt <= first, "a second noise raised a second response or delayed the first");
            check(before.containsAll(s.officers()), "officers were there before anybody could have arrived");
        }
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void withNoAlarmAndNobodyWatchingNothingHappens(GameTestHelper helper) {
        var none = new CityBuildings.Response(0.0, 60, 1, 60);
        try (Scene s = new Scene(helper, "uc_pol_b", none)) {
            long now = LockService.clock(s.level.getServer());
            for (int t = 0; t < 300; t++) PoliceResponse.noticed(s.thief, opened(s.thief, s.door, now + t));
            check(PoliceResponse.dispatches() == 0, "the police came to a break-in that nobody noticed");
        }
        try (Scene s = new Scene(helper, "uc_pol_b2", null)) {
            long now = LockService.clock(s.level.getServer());
            for (int t = 0; t < 100; t++) PoliceResponse.noticed(s.thief, opened(s.thief, s.door, now + t));
            check(PoliceResponse.dispatches() == 0, "a building with no alarm class raised a response by itself");
        }
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void aPersonWhoSawTheThiefCallsThePoliceButAnOfficerWhoSawDoesNotNeedTo(GameTestHelper helper) {
        var none = new CityBuildings.Response(0.0, 60, 1, 60);
        try (Scene s = new Scene(helper, "uc_pol_c", none)) {
            // "in plain sight": the template is one block and GameTest clears only three blocks round it, so the street out to the witness is cleared here (October 23: blocks left
            // at this grid slot by an earlier batch's test stood between them, and no witness saw anything in 400 tries)
            for (BlockPos at : BlockPos.betweenClosed(s.door.offset(-1, 0, 1), s.door.offset(8, 2, 2))) s.level.setBlock(at, net.minecraft.world.level.block.Blocks.AIR.defaultBlockState(), 2);
            TownspersonEntity seer = UndergroundCity.TOWNSPERSON.get().create(s.level);
            seer.moveTo(s.door.getX() + 6.5, s.door.getY(), s.door.getZ() + 1.5, 0, 0);
            s.level.addFreshEntity(seer);
            long now = LockService.clock(s.level.getServer());
            int tries = 0;
            while (PoliceResponse.dispatches() == 0 && tries < 400) PoliceResponse.noticed(s.thief, opened(s.thief, s.door, now + tries++));
            check(PoliceResponse.dispatches() == 1 && PoliceResponse.dispatchAt(0).why.equals("witness"), "a person in plain sight never called the police in " + tries + " tries (sees the thief "
                + seer.hasLineOfSight(s.thief) + ")");
            check(PoliceResponse.dispatchAt(0).arriveAt >= now + 150 * 20L, "a neighbour's call arrived faster than the slowest class");
            seer.discard();
        }
        // the control: the same street with only a police officer in it raises nothing, since officers are the response and not the audience
        try (Scene s = new Scene(helper, "uc_pol_c2", new CityBuildings.Response(0.0, 60, 1, 60))) {
            TownspersonEntity officer = UndergroundCity.TOWNSPERSON.get().create(s.level);
            officer.becomeOfficer("valewood", "Test Officer", 10);
            officer.moveTo(s.door.getX() + 6.5, s.door.getY(), s.door.getZ() + 1.5, 0, 0);
            s.level.addFreshEntity(officer);
            long now = LockService.clock(s.level.getServer());
            for (int t = 0; t < 200; t++) PoliceResponse.noticed(s.thief, opened(s.thief, s.door, now + t));
            check(PoliceResponse.dispatches() == 0, "an officer already on the scene counted as a witness");
        }
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void aCleanPickSlipsPastAnAlarmThatCanBeSlippedPastAndNotPastOneInEveryBuilding(GameTestHelper helper) {
        var half = new CityBuildings.Response(0.75, 60, 1, 120);
        try (Scene s = new Scene(helper, "uc_pol_q", half)) {
            // any chunk is a building of this class; look for one whose alarm is armed (a hash of its place) and one whose is not
            CityBuildings.INSTANCE.overrideForTest((cx, cz) -> Optional.of(new CityBuildings.Building("valewood", cx, cz, "test", "test_building", 2, 0, s.door.getY() - 1,
                new CityBuildings.SecurityClass("test_alarmed", "commercial", true, "", 0, "test premises", half))));
            ChunkPosPick armed = null, unarmed = null;
            for (int k = 0; k < 200 && (armed == null || unarmed == null); k++) {
                int cx = (s.door.getX() >> 4) + k, cz = s.door.getZ() >> 4;
                var b = new CityBuildings.Building("valewood", cx, cz, "test", "test_building", 2, 0, s.door.getY() - 1, null);
                if (PoliceResponse.armed(s.level, b, half)) { if (armed == null) armed = new ChunkPosPick(cx, cz); } else if (unarmed == null) unarmed = new ChunkPosPick(cx, cz);
            }
            check(armed != null && unarmed != null, "setup: no armed and unarmed building among two hundred");
            BlockPos inArmed = new BlockPos(armed.cx * 16 + 2, s.door.getY(), armed.cz * 16 + 2), inUnarmed = new BlockPos(unarmed.cx * 16 + 2, s.door.getY(), unarmed.cz * 16 + 2);
            long now = LockService.clock(s.level.getServer());
            // a noisy pick trips it, a clean one slips past it
            PoliceResponse.noticed(s.thief, new BuildingBreachEvent(s.thief.getUUID(), inArmed, "valewood", "test_alarmed", BuildingBreachEvent.Outcome.OPENED, now, false, s.thief).quiet(false));
            check(PoliceResponse.dispatches() == 1 && PoliceResponse.dispatchAt(0).why.startsWith("alarm"), "a noisy pick did not trip an armed alarm");
            PoliceResponse.resetForTest();
            PoliceResponse.noticed(s.thief, new BuildingBreachEvent(s.thief.getUUID(), inArmed, "valewood", "test_alarmed", BuildingBreachEvent.Outcome.OPENED, now, false, s.thief).quiet(true));
            check(PoliceResponse.dispatches() == 0, "a clean pick still tripped an alarm that can be slipped past");
            // an unarmed building is nothing either way
            PoliceResponse.noticed(s.thief, new BuildingBreachEvent(s.thief.getUUID(), inUnarmed, "valewood", "test_alarmed", BuildingBreachEvent.Outcome.OPENED, now, false, s.thief).quiet(false));
            check(PoliceResponse.dispatches() == 0, "an unarmed building raised an alarm");
            // a pick that snapped and rang the lock's own bell is not quiet whatever it did before
            PoliceResponse.noticed(s.thief, new BuildingBreachEvent(s.thief.getUUID(), inUnarmed, "valewood", "test_alarmed", BuildingBreachEvent.Outcome.SNAPPED, now, true, s.thief).quiet(true));
            check(PoliceResponse.dispatches() == 1, "a snapped pick that rang a bell was forgiven because it had been clean before");
        }
        // an alarm in every building of its class cannot be slipped past
        try (Scene s = new Scene(helper, "uc_pol_q2", ALARMED)) {
            long now = LockService.clock(s.level.getServer());
            PoliceResponse.noticed(s.thief, new BuildingBreachEvent(s.thief.getUUID(), s.door, "valewood", "test_alarmed", BuildingBreachEvent.Outcome.OPENED, now, false, s.thief).quiet(true));
            check(PoliceResponse.dispatches() == 1, "a clean pick slipped past the alarm of a certain class");
        }
        helper.succeed();
    }

    private record ChunkPosPick(int cx, int cz) {}

    @GameTest(template = "empty")
    public static void creativeAndSpectatorPlayersAreNeverPoliced(GameTestHelper helper) {
        try (Scene s = new Scene(helper, "uc_pol_d", ALARMED)) {
            long now = LockService.clock(s.level.getServer());
            s.thief.setGameMode(GameType.CREATIVE);
            PoliceResponse.noticed(s.thief, opened(s.thief, s.door, now));
            s.thief.setGameMode(GameType.SPECTATOR);
            PoliceResponse.noticed(s.thief, opened(s.thief, s.door, now));
            s.thief.setGameMode(GameType.SURVIVAL);
            check(PoliceResponse.dispatches() == 0, "a creative or spectating player was policed");
        }
        helper.succeed();
    }

    // ------------------------------------------------------------------------------------------------ arriving, arresting, escaping

    private static boolean arrestRecorded(net.minecraft.nbt.ListTag searches) {
        for (int i = 0; i < searches.size(); i++) {
            var record = searches.getCompound(i);
            if (record.getString("outcome").equals("arrested") && record.getInt("stacks") == 1) return true;
        }
        return false;
    }

    private static void arrive(Scene s) {
        long now = LockService.clock(s.level.getServer());
        PoliceResponse.noticed(s.thief, opened(s.thief, s.door, now));
        PoliceResponse.dispatchAt(0).arriveAt = 0;
        PoliceResponse.pass(s.level.getServer());
    }

    @GameTest(template = "empty")
    public static void officersArriveAtAStreetSpotAndAreInvulnerableAndUnsaved(GameTestHelper helper) {
        try (Scene s = new Scene(helper, "uc_pol_e", ALARMED)) {
            arrive(s);
            var officers = s.officers();
            check(officers.size() == 2 && PoliceResponse.dispatchAt(0).arrived, "two officers were due and " + officers.size() + " came");
            for (TownspersonEntity officer : officers) {
                check(officer.getName().getString().startsWith("Officer ") && officer.city().equals("valewood") && officer.skin() % PopulationPlan.OUTFITS == 10, "an officer is not dressed or named as one: " + officer.getName().getString() + " skin " + officer.skin());
                check(!officer.shouldBeSaved(), "an officer would be written to the world");
                check(officer.isInvulnerableTo(s.level.damageSources().generic()), "an officer can be hurt by an ordinary blow");
                check(officer.errandPlace() != null && officer.errandPlace().equals(s.door), "an officer was not sent to the door");
                // an officer goes through doors (D-189: a thief who stayed inside was never caught); townspeople still never do
                check(officer.getNavigation().getNodeEvaluator().canOpenDoors() && officer.getNavigation().getNodeEvaluator().canPassDoors(), "an officer cannot go through a building's doors");
            }
            check(PoliceResponse.status().contains("arrived 1"), "the status does not count the arrival: " + PoliceResponse.status());
        }
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void officersFollowAThiefWhoMovesAndOnlyTheThiefIsArrested(GameTestHelper helper) {
        try (Scene s = new Scene(helper, "uc_pol_chase", ALARMED, true)) {
            arrive(s);
            BlockPos fled = s.door.offset(12, 0, 6);
            s.thief.setPos(fled.getX() + 0.5, fled.getY(), fled.getZ() + 0.5);
            PoliceResponse.pass(s.level.getServer());
            for (TownspersonEntity officer : s.officers()) {
                check(fled.equals(officer.errandPlace()), "an officer did not turn after the thief who moved: going to " + officer.errandPlace() + ", the thief is at " + fled);
            }
            // a partner who did not break in stands right beside an officer, with marked goods in their pockets: the response is not theirs
            ServerPlayer partner = CrewGameTests.player(helper, "uc_pol_mate", "smith");
            ItemStack loot = new ItemStack(Items.GOLD_INGOT, 4);
            StolenGoods.mark(loot, "valewood");
            partner.getInventory().setItem(0, loot);
            var officer = s.officers().get(0);
            partner.setPos(officer.getX() + 1.0, officer.getY(), officer.getZ());
            PoliceResponse.pass(s.level.getServer());
            check(PoliceResponse.arrests == 0 && partner.getInventory().countItem(Items.GOLD_INGOT) == 4, "a partner who did not break in was arrested or searched");
            check(PoliceResponse.dispatches() == 1, "the response for the thief ended because of the partner");
        }
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void eachThiefAndEachBuildingHasItsOwnResponseAndAWitnessCallIsSlowerThanAnAlarm(GameTestHelper helper) {
        try (Scene s = new Scene(helper, "uc_pol_two", ALARMED)) {
            long now = LockService.clock(s.level.getServer());
            ServerPlayer second = CrewGameTests.player(helper, "uc_pol_two_b", "smith");
            second.setPos(s.thief.getX() + 2, s.thief.getY(), s.thief.getZ());
            PoliceResponse.noticed(s.thief, opened(s.thief, s.door, now));
            PoliceResponse.noticed(second, opened(second, s.door, now));
            check(PoliceResponse.dispatches() == 2, "two thieves at one building raised " + PoliceResponse.dispatches() + " responses");
            var other = new CityBuildings.Building("valewood", s.building.chunkX() + 3, s.building.chunkZ(), "test", "test_building_2", 2, 0, s.building.streetY(), s.building.security());
            PoliceResponse.raise(s.thief, s.level, other, s.door.offset(48, 0, 0), true, false, false);
            check(PoliceResponse.dispatches() == 3, "the same thief at a second building did not raise a second response");
        }
        try (Scene s = new Scene(helper, "uc_pol_slow", ALARMED)) {
            long now = LockService.clock(s.level.getServer());
            PoliceResponse.raise(s.thief, s.level, s.building, s.door, false, false, true);
            long witness = PoliceResponse.dispatchAt(0).arriveAt - now;
            check(witness == Math.max(ALARMED.dispatchSeconds(), PoliceResponse.NEIGHBOUR.dispatchSeconds()) * 20L, "a witness's call brings the police in " + witness + " ticks");
            PoliceResponse.raise(s.thief, s.level, s.building, s.door, true, false, false);
            check(PoliceResponse.dispatches() == 1 && PoliceResponse.dispatchAt(0).arriveAt - now == ALARMED.dispatchSeconds() * 20L, "the alarm that followed did not bring the same response sooner");
        }
        helper.succeed();
    }

    /** A real client: the nearest street spot lay behind the building, the officer's path to the thief ended at a wall, and nobody was arrested. Built in the sky, clear of the other tests. */
    @GameTest(template = "empty")
    public static void officersAppearWhereTheyCanWalkToTheThiefAndNotInsideAWall(GameTestHelper helper) {
        try (Scene s = new Scene(helper, "uc_pol_reach", ALARMED)) {
            ServerLevel level = s.level;
            int cx = s.door.getX(), cz = s.door.getZ(), y = level.getMaxBuildHeight() - 50;
            var stone = net.minecraft.world.level.block.Blocks.STONE.defaultBlockState();
            var air = net.minecraft.world.level.block.Blocks.AIR.defaultBlockState();
            for (int dx = -14; dx <= 14; dx++) for (int dz = -14; dz <= 14; dz++) {
                level.setBlock(new BlockPos(cx + dx, y, cz + dz), stone, 2);
                for (int dy = 1; dy <= 5; dy++) level.setBlock(new BlockPos(cx + dx, y + dy, cz + dz), air, 2);
            }
            BlockPos sealed = new BlockPos(cx - 9, y + 1, cz), open = new BlockPos(cx + 9, y + 1, cz);
            for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++) {
                if (dx == 0 && dz == 0) continue;
                for (int dy = 1; dy <= 3; dy++) level.setBlock(sealed.offset(dx, dy - 1, dz), stone, 2);
            }
            for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++) level.setBlock(sealed.offset(dx, 3, dz), stone, 2);
            try {
                s.thief.setPos(cx + 0.5, y + 1, cz + 0.5);
                final int[] calls = {0};
                PoliceResponse.spots = (lvl, chunk, random, players) -> Optional.of(calls[0]++ == 0 ? sealed : open);
                arrive(s);
                var officers = level.getEntitiesOfClass(TownspersonEntity.class, new AABB(open).inflate(40), TownspersonEntity::police);   // the arena is in the sky, far from the scene's own search box
                check(officers.size() == 2, "two officers were due and " + officers.size() + " came");
                for (TownspersonEntity officer : officers) {
                    check(officer.position().distanceTo(net.minecraft.world.phys.Vec3.atBottomCenterOf(open)) < 3.0, "an officer was put at " + officer.blockPosition() + ", not at the open spot " + open + " (the other is walled in)");
                }
            } finally {
                for (TownspersonEntity officer : level.getEntitiesOfClass(TownspersonEntity.class, new AABB(open).inflate(40), TownspersonEntity::police)) officer.discard();
                for (int dx = -14; dx <= 14; dx++) for (int dz = -14; dz <= 14; dz++) for (int dy = 0; dy <= 5; dy++) level.setBlock(new BlockPos(cx + dx, y + dy, cz + dz), air, 2);
            }
        }
        helper.succeed();
    }

    /** A suspect who stands still beside a two-high bush, an officer two blocks away on its far side (police cases run 8: the officer stood there two minutes and the response ended as an escape). */
    @GameTest(template = "empty")
    public static void anOfficerABushAwayStepsRoundItAndArrests(GameTestHelper helper) {
        try (Scene s = new Scene(helper, "uc_pol_bush", ALARMED, true)) {
            arrive(s);
            BlockPos at = s.door.offset(6, 0, 6);
            s.thief.setPos(at.getX() + 0.5, at.getY(), at.getZ() + 0.5);
            var leaves = net.minecraft.world.level.block.Blocks.OAK_LEAVES.defaultBlockState().setValue(net.minecraft.world.level.block.LeavesBlock.PERSISTENT, true);
            s.level.setBlock(at.east(), leaves, 2);
            s.level.setBlock(at.east().above(), leaves, 2);
            List<TownspersonEntity> officers = s.officers();
            for (int i = 1; i < officers.size(); i++) officers.get(i).moveTo(at.getX() + 12.5, at.getY(), at.getZ() + 12.5, 0, 0);
            TownspersonEntity officer = officers.get(0);
            officer.moveTo(at.getX() + 2.5, at.getY(), at.getZ() + 0.5, 0, 0);
            check(!PoliceResponse.canTouch(s.level, officer, s.thief), "an officer could lay a hand on the suspect through a two-high bush");
            officer.setOnGround(true);   // a navigation plans a path only for a creature that stands on something (GameTest run 122)
            var route = officer.getNavigation().createPath(at, 0);
            check(PoliceResponse.routeReaches(route, at), "a route round a bush in the open was taken not to reach: " + route);
            PoliceResponse.pass(s.level.getServer());
            check(PoliceResponse.arrests == 0, "an officer arrested a suspect through a bush");
            BlockPos beside = officer.errandPlace();
            check(beside != null && beside.distManhattan(at) == 1 && s.level.getBlockState(beside).isAir(), "the officer did not step round the bush to a free cell beside the suspect: going to " + beside);
            officer.moveTo(beside.getX() + 0.5, beside.getY(), beside.getZ() + 0.5, 0, 0);
            PoliceResponse.pass(s.level.getServer());
            check(PoliceResponse.arrests == 1, "the officer beside the suspect did not arrest them: " + PoliceResponse.status());
        }
        helper.succeed();
    }

    /** A wall still stops an arrest: an officer two blocks from a suspect shut in a stone room neither arrests through it nor takes a route that ends outside it for one that reaches. */
    @GameTest(template = "empty")
    public static void anOfficerOutsideAClosedRoomNeitherArrestsThroughItsWallNorCallsItsRouteGood(GameTestHelper helper) {
        try (Scene s = new Scene(helper, "uc_pol_room", ALARMED, true)) {
            arrive(s);
            BlockPos at = s.door.offset(6, 0, 6);
            var stone = net.minecraft.world.level.block.Blocks.STONE.defaultBlockState();
            for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++) for (int dy = 0; dy <= 2; dy++)
                if (dx != 0 || dz != 0 || dy == 2) s.level.setBlock(at.offset(dx, dy, dz), stone, 2);
            s.thief.setPos(at.getX() + 0.5, at.getY(), at.getZ() + 0.5);
            List<TownspersonEntity> officers = s.officers();
            for (int i = 1; i < officers.size(); i++) officers.get(i).moveTo(at.getX() + 12.5, at.getY(), at.getZ() + 12.5, 0, 0);
            TownspersonEntity officer = officers.get(0);
            officer.moveTo(at.getX() + 2.5, at.getY(), at.getZ() + 0.5, 0, 0);
            check(!PoliceResponse.canTouch(s.level, officer, s.thief), "an officer could lay a hand on the suspect through a stone wall");
            check(PoliceResponse.besideFree(s.level, at, officer.blockPosition()) == null, "a free cell was found inside the walls round the suspect");
            officer.setOnGround(true);   // a navigation plans a path only for a creature that stands on something (GameTest run 122)
            var route = officer.getNavigation().createPath(at, 0);
            check(route == null || !PoliceResponse.routeReaches(route, at), "a route that ends outside the room was taken to reach the suspect inside it: ends at " + (route == null ? null : route.getEndNode()));
            PoliceResponse.pass(s.level.getServer());
            check(PoliceResponse.arrests == 0, "an officer arrested a suspect through a wall");
        }
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void anOfficerWhoReachesTheThiefArrestsThemAndTakesOnlyWhatIsStolen(GameTestHelper helper) {
        try (Scene s = new Scene(helper, "uc_pol_f", ALARMED)) {
            arrive(s);
            WorkOrderLedger ledger = WorkOrderLedger.get(s.level.getServer());
            int heatBefore = ledger.heat(s.thief.getUUID(), "valewood");
            ItemStack loot = new ItemStack(Items.GOLD_INGOT, 5);
            StolenGoods.mark(loot, "valewood");
            s.thief.getInventory().setItem(0, loot);
            s.thief.getInventory().setItem(1, new ItemStack(Items.BREAD, 7));
            s.thief.getInventory().setItem(2, new ItemStack(Items.GOLD_INGOT, 3));   // honest gold, same item: the control
            var officer = s.officers().get(0);
            officer.moveTo(s.thief.getX() + 1.0, s.thief.getY(), s.thief.getZ(), 0, 0);
            // still at a lock when the officer arrives: the arrest puts it away (patrol run 3 left the lock screen open over an arrest)
            s.thief.getInventory().setItem(8, new ItemStack(UndergroundCity.item("lockpick"), 2));
            String refusal = LockService.begin(s.thief, s.level, new LockService.Target(LockService.Kind.DOOR, s.door, "deadbolt", "valewood", "test_alarmed", "Test door", 1));
            check(refusal == null && LockService.busy(s.thief.getUUID()), "the lock did not start: " + refusal);
            PoliceResponse.pass(s.level.getServer());
            check(PoliceResponse.dispatches() == 0 && PoliceResponse.arrests == 1, "the officer beside the thief did not arrest them: " + PoliceResponse.status());
            check(!LockService.busy(s.thief.getUUID()), "the thief was still at the lock after the arrest");
            check(s.thief.getInventory().countItem(Items.GOLD_INGOT) == 3, "the honest gold was taken with the stolen gold (or the stolen gold was not taken): " + s.thief.getInventory().countItem(Items.GOLD_INGOT));
            check(s.thief.getInventory().countItem(Items.BREAD) == 7, "honest goods were taken in an arrest");
            check(ledger.heat(s.thief.getUUID(), "valewood") == Math.min(WorkOrderLedger.MAX_HEAT, heatBefore + PoliceResponse.ARREST_HEAT), "an arrest did not raise the city's heat by exactly " + PoliceResponse.ARREST_HEAT);
            check(arrestRecorded(ledger.searches(s.thief.getUUID())), "the arrest was not written into the search history");
            check(s.officers().stream().allMatch(TownspersonEntity::leaving), "officers stayed on the street after the arrest");
        }
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void aThiefWhoGetsAwayOrWhoseTimeRunsOutIsNotCaught(GameTestHelper helper) {
        try (Scene s = new Scene(helper, "uc_pol_g", ALARMED)) {
            arrive(s);
            WorkOrderLedger ledger = WorkOrderLedger.get(s.level.getServer());
            int heatBefore = ledger.heat(s.thief.getUUID(), "valewood");
            ItemStack loot = new ItemStack(Items.GOLD_INGOT, 5);
            StolenGoods.mark(loot, "valewood");
            s.thief.getInventory().setItem(0, loot);
            s.thief.setPos(s.door.getX() + 400.5, s.door.getY(), s.door.getZ() + 0.5);
            PoliceResponse.pass(s.level.getServer());
            check(PoliceResponse.dispatches() == 0 && PoliceResponse.escapes == 1 && PoliceResponse.arrests == 0, "a thief 400 blocks away was not recorded as gone: " + PoliceResponse.status());
            check(s.thief.getInventory().countItem(Items.GOLD_INGOT) == 5 && ledger.heat(s.thief.getUUID(), "valewood") == heatBefore, "a thief who got away was searched or had their heat raised by the police");
        }
        try (Scene s = new Scene(helper, "uc_pol_g2", ALARMED)) {
            arrive(s);
            s.thief.setPos(s.door.getX() + 60.5, s.door.getY(), s.door.getZ() + 60.5);   // out of reach, but not out of the district
            PoliceResponse.dispatchAt(0).windowEnds = 0;
            PoliceResponse.pass(s.level.getServer());
            check(PoliceResponse.dispatches() == 0 && PoliceResponse.escapes == 1 && PoliceResponse.arrests == 0, "when the police gave up the thief was still caught: " + PoliceResponse.status());
        }
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void nobodyIsArrestedWhileAwayAndADeadPlayerIsLeftAlone(GameTestHelper helper) {
        try (Scene s = new Scene(helper, "uc_pol_h", ALARMED)) {
            arrive(s);
            new PoliceResponse().loggedOut(new PlayerEvent.PlayerLoggedOutEvent(s.thief));
            check(PoliceResponse.dispatches() == 0, "a response outlived its thief's session");
            check(s.officers().stream().allMatch(TownspersonEntity::leaving), "officers were left waiting for somebody who had gone");
        }
        try (Scene s = new Scene(helper, "uc_pol_h2", ALARMED)) {
            arrive(s);
            s.thief.setHealth(0.0f);
            PoliceResponse.pass(s.level.getServer());
            check(PoliceResponse.dispatches() == 0 && PoliceResponse.arrests == 0, "the police arrested a dead player");
        }
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void anArrestIsSettledOnceEvenIfTheProcessDiesAfterTheDecisionIsOnDisk(GameTestHelper helper) throws Exception {
        Path path = helper.getLevel().getServer().getWorldPath(net.minecraft.world.level.storage.LevelResource.ROOT).resolve("uc-test-journals/arrest-" + UUID.randomUUID() + ".dat");
        try (Scene s = new Scene(helper, "uc_pol_i", ALARMED)) {
            WorkOrderLedger ledger = new WorkOrderLedger(path);
            ItemStack loot = new ItemStack(Items.GOLD_INGOT, 5);
            StolenGoods.mark(loot, "valewood");
            s.thief.getInventory().setItem(0, loot);
            s.thief.getInventory().setItem(1, new ItemStack(Items.BREAD, 2));
            var citizen = CitizenState.get(s.level.getServer()).find(s.thief.getUUID()).orElseThrow();
            var law = CityLaws.INSTANCE.find("valewood");
            CheckpointService.crashAfterPrepare = true;
            try { CheckpointService.arrest(s.thief, citizen, "police:test", "valewood", law, ledger, 40, 3); throw new AssertionError("the simulated crash did not happen"); }
            catch (CrewService.SimulatedCrash died) { /* the process died with the decision on disk and nothing applied */ }
            CheckpointService.crashAfterPrepare = false;
            check(ledger.player(s.thief.getUUID()).contains("pending"), "the arrest was not journaled before the crash");
            WorkOrderService.recover(s.thief, ledger);
            check(s.thief.getInventory().countItem(Items.GOLD_INGOT) == 0 && s.thief.getInventory().countItem(Items.BREAD) == 2, "recovery did not complete the arrest exactly");
            check(ledger.heat(s.thief.getUUID(), "valewood") == 3 && ledger.player(s.thief.getUUID()).getInt("seizures") == 1, "recovery settled the arrest not exactly once");
            WorkOrderService.recover(s.thief, ledger);
            check(ledger.heat(s.thief.getUUID(), "valewood") == 3 && ledger.searches(s.thief.getUUID()).size() == 1, "a second recovery changed something");
        } finally {
            Files.deleteIfExists(path);
        }
        helper.succeed();
    }

    // ------------------------------------------------------------------------------------------------ the evidence mark

    @GameTest(template = "empty")
    public static void aForcedSafeMarksWhatIsInItAndACheckpointSeizesMarkedGoodsWhateverTheLaw(GameTestHelper helper) throws Exception {
        ServerLevel level = helper.getLevel();
        BlockPos at = helper.absolutePos(new BlockPos(4, 2, 4));
        PropertyPolicy policy = PropertyPolicies.INSTANCE.find("underground_city:apartment_common");
        check(policy != null, "the stock apartment policy is missing");
        ServerPlayer thief = player(helper, "uc_pol_j", "farmer");
        long now = LockService.clock(level.getServer());
        BuildingStash.place(level, at, policy, now);
        var safe = (PropertyCacheBlockEntity) level.getBlockEntity(at);
        check(safe != null && safe.stock() > 0, "the stash has nothing in it to mark");
        for (int i = 0; i < safe.getContainerSize(); i++) check(!StolenGoods.is(safe.getItem(i)), "goods were marked before anybody forced the safe");
        safe.forcedOpenBy(level, thief, now);
        int marked = 0;
        for (int i = 0; i < safe.getContainerSize(); i++) {
            ItemStack stack = safe.getItem(i);
            if (stack.isEmpty()) continue;
            check(StolenGoods.is(stack), "a stack in a forced safe was not marked: " + stack);
            marked++;
        }
        check(marked == safe.stock(), "not every stack was marked");
        Path path = level.getServer().getWorldPath(net.minecraft.world.level.storage.LevelResource.ROOT).resolve("uc-test-journals/stolen-" + UUID.randomUUID() + ".dat");
        try {
            WorkOrderLedger ledger = new WorkOrderLedger(path);
            ItemStack hot = new ItemStack(Items.EMERALD, 4);
            StolenGoods.mark(hot, "valewood");
            check(StolenGoods.is(hot) && StolenGoods.city(hot).equals("valewood"), "marking is not readable");
            ItemStack twice = hot.copy();
            StolenGoods.mark(twice, "ironhaven");
            check(StolenGoods.city(twice).equals("valewood"), "a stack already marked was marked again in another city");
            thief.getInventory().setItem(0, hot);
            thief.getInventory().setItem(1, new ItemStack(Items.EMERALD, 2));   // the same item, honest
            thief.getInventory().setItem(2, new ItemStack(Items.BREAD, 3));
            var gate = new CheckpointCatalog.Checkpoint("test_gate", "Test gate", "valewood", "minecraft:overworld", 0, 0, 48, "inspection");
            var result = CheckpointService.inspect(thief, CitizenState.get(level.getServer()).find(thief.getUUID()).orElseThrow(), gate, CityLaws.INSTANCE.find("valewood"), ledger);
            check(result.outcome() == CheckpointService.Outcome.SEIZED && result.stacksSeized() == 1, "a checkpoint did not seize the marked goods: " + result);
            check(thief.getInventory().countItem(Items.EMERALD) == 2 && thief.getInventory().countItem(Items.BREAD) == 3, "the honest emeralds or the bread were taken");
        } finally {
            Files.deleteIfExists(path);
        }
        level.removeBlock(at, false);
        helper.succeed();
    }
    // ------------------------------------------------------------------------------------------------ police on the beat (D-155)

    /** A patrol of one officer standing at {@code at}, its beat {@code at} and a spot six blocks on, in Valewood (the scene's city), with {@code online} the people on the server. */
    private static PolicePatrols.Patrol patrol(Scene s, BlockPos at, List<ServerPlayer> online) {
        PolicePatrols.resetForTest();
        PolicePatrols.cityAt = (level, pos) -> Optional.of("valewood");
        PolicePatrols.online = server -> online;
        List<BlockPos> beat = List.of(at, at.offset(0, 0, 6));
        java.util.concurrent.atomic.AtomicInteger n = new java.util.concurrent.atomic.AtomicInteger();
        PolicePatrols.spots = (level, chunk, random, players) -> Optional.of(beat.get(n.getAndIncrement() % beat.size()));
        PolicePatrols.Patrol patrol = PolicePatrols.start(s.level, "valewood", new CityLaws.Patrol(1, 120, 120), online, LockService.clock(s.level.getServer()));
        check(patrol != null && patrol.officers.size() == 1, "no officer was put on the beat");
        PolicePatrols.putForTest(patrol);
        return patrol;
    }

    private static void picking(Scene s, ServerPlayer who) {
        who.getInventory().add(new ItemStack(UndergroundCity.item("lockpick"), 5));
        String refusal = LockService.begin(who, s.level, new LockService.Target(LockService.Kind.DOOR, s.door, "deadbolt", "valewood", "test_alarmed", "Test door", 1));
        check(refusal == null && LockService.busy(who.getUUID()), "the lock did not start: " + refusal);
    }

    @GameTest(template = "empty")
    public static void anOfficerOnTheBeatWhoSeesALockPickedComesAtOnceForThatThiefAlone(GameTestHelper helper) {
        try (Scene s = new Scene(helper, "uc_pat_see", new CityBuildings.Response(0.0, 60, 1, 60), true)) {
            ServerPlayer partner = player(helper, "uc_pat_mate", "smith");
            partner.setPos(s.thief.getX() + 1.0, s.thief.getY(), s.thief.getZ());
            try {
                PolicePatrols.Patrol patrol = patrol(s, s.door.offset(10, 0, 1), List.of(s.thief, partner));
                UUID officer = patrol.officers.get(0);
                picking(s, s.thief);
                PolicePatrols.pass(s.level.getServer());
                check(PoliceResponse.dispatches() == 1, "an officer on the beat watched a lock being picked ten blocks away and raised " + PoliceResponse.dispatches() + " responses");
                PoliceResponse.Dispatch d = PoliceResponse.dispatchAt(0);
                check(d.burglar.equals(s.thief.getUUID()), "the response is not for the person at the lock");
                check(d.arrived && d.officers.contains(officer) && d.lastSeen != null && d.why.startsWith("patrol"), "the officer who saw it is not already on the response: " + d.why + ", arrived " + d.arrived);
                check(!LockService.busy(s.thief.getUUID()), "the lock screen was left over the street while an officer came");
                check(patrol.officers.isEmpty(), "the officer is still walking the beat as well");
                PoliceResponse.pass(s.level.getServer());
                check(PoliceResponse.arrests == 0 && PoliceResponse.dispatches() == 1, "the partner, or nobody, was arrested at once");
            } finally {
                LockService.interrupt(s.thief, "");
                PolicePatrols.resetForTest();
            }
        }
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void aWallHidesAThiefFromThePatrolWhoIsToldOfFootstepsOnce(GameTestHelper helper) {
        try (Scene s = new Scene(helper, "uc_pat_wall", new CityBuildings.Response(0.0, 60, 1, 60), true)) {
            var stone = net.minecraft.world.level.block.Blocks.STONE.defaultBlockState();
            for (int dz = -6; dz <= 8; dz++) for (int dy = 0; dy <= 3; dy++) s.level.setBlock(s.door.offset(5, dy, dz), stone, 2);
            try {
                PolicePatrols.Patrol patrol = patrol(s, s.door.offset(10, 0, 1), List.of(s.thief));
                picking(s, s.thief);
                PolicePatrols.pass(s.level.getServer());
                PolicePatrols.pass(s.level.getServer());
                check(PoliceResponse.dispatches() == 0 && LockService.busy(s.thief.getUUID()), "an officer saw through a stone wall");
                check(PolicePatrols.warnings == 1 && patrol.warned.contains(s.thief.getUUID()), "the thief at the lock was told of the footsteps " + PolicePatrols.warnings + " times, not once");
                // the wall comes down: now the officer sees them
                for (int dz = -6; dz <= 8; dz++) for (int dy = 0; dy <= 3; dy++) s.level.removeBlock(s.door.offset(5, dy, dz), false);
                PolicePatrols.pass(s.level.getServer());
                check(PoliceResponse.dispatches() == 1, "with the wall gone the officer still saw nothing");
            } finally {
                LockService.interrupt(s.thief, "");
                PolicePatrols.resetForTest();
            }
        }
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void anOfficerOnTheBeatWhoSeesTheDoorGiveWayIsTheResponse(GameTestHelper helper) {
        try (Scene s = new Scene(helper, "uc_pat_door", new CityBuildings.Response(0.0, 60, 1, 60), true)) {
            try {
                PolicePatrols.Patrol patrol = patrol(s, s.door.offset(8, 0, 3), List.of(s.thief));
                UUID officer = patrol.officers.get(0);
                PoliceResponse.noticed(s.thief, opened(s.thief, s.door, LockService.clock(s.level.getServer())));
                check(PoliceResponse.dispatches() == 1, "a break-in in front of an officer on the beat raised nothing");
                PoliceResponse.Dispatch d = PoliceResponse.dispatchAt(0);
                check(d.arrived && d.officers.contains(officer) && d.why.equals("patrol: breaking in"), "the response did not start with the officer who saw it: " + d.why);
                check(patrol.officers.isEmpty() && PoliceResponse.byPatrol == 1, "the officer stayed on the beat");
            } finally {
                PolicePatrols.resetForTest();
            }
        }
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void patrolsComeAtTheCitysPaceOneAtATimeAndGoWhenNobodyIsThere(GameTestHelper helper) {
        try (Scene s = new Scene(helper, "uc_pat_pace", new CityBuildings.Response(0.0, 60, 1, 60), true)) {
            List<ServerPlayer> online = new java.util.ArrayList<>(List.of(s.thief));
            try {
                PolicePatrols.resetForTest();
                PolicePatrols.cityAt = (level, pos) -> Optional.of("valewood");
                PolicePatrols.online = server -> online;
                List<BlockPos> beat = List.of(s.door.offset(8, 0, 3), s.door.offset(8, 0, 9), s.door.offset(-8, 0, 3));
                java.util.concurrent.atomic.AtomicInteger n = new java.util.concurrent.atomic.AtomicInteger();
                PolicePatrols.spots = (level, chunk, random, players) -> Optional.of(beat.get(n.getAndIncrement() % beat.size()));
                CityLaws.Patrol pace = CityLaws.INSTANCE.find("valewood").patrol();
                check(pace != null && pace.officers() == 1, "Valewood's law has no patrol");
                long now = LockService.clock(s.level.getServer());
                PolicePatrols.pass(s.level.getServer());
                check(PolicePatrols.patrolOf("valewood") == null && PolicePatrols.dueOf("valewood") == now + pace.everySeconds() * 10L,
                    "the first patrol after somebody arrived was not put half the city's pace off: " + PolicePatrols.dueOf("valewood"));
                PolicePatrols.dueForTest("valewood", now);
                PolicePatrols.pass(s.level.getServer());
                PolicePatrols.Patrol first = PolicePatrols.patrolOf("valewood");
                check(first != null && first.officers.size() == pace.officers() && first.beat.size() >= 2, "no patrol came when it was due");
                TownspersonEntity officer = (TownspersonEntity) s.level.getEntity(first.officers.get(0));
                check(officer != null && officer.police() && !officer.shouldBeSaved() && first.beat.contains(officer.errandPlace()), "the officer on the beat is not an unsaved officer walking it");
                PolicePatrols.dueForTest("valewood", now);
                PolicePatrols.pass(s.level.getServer());
                check(PolicePatrols.patrolOf("valewood") == first && s.officers().size() == pace.officers(), "a second patrol of the same city came out: " + s.officers().size() + " officers");
                online.clear();
                PolicePatrols.pass(s.level.getServer());
                check(PolicePatrols.patrolOf("valewood") == null && officer.errandPlace() == null, "the patrol stayed with nobody in the city");
                check(PolicePatrols.dueOf("valewood") == null, "a city nobody is in kept its next patrol");
            } finally {
                PolicePatrols.resetForTest();
            }
        }
        helper.succeed();
    }
}
