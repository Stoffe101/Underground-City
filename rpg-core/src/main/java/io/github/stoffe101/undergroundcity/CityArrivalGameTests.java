package io.github.stoffe101.undergroundcity;

import com.mojang.authlib.GameProfile;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestAssertException;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraftforge.common.util.FakePlayer;
import net.minecraftforge.common.util.FakePlayerFactory;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** The move of a new citizen to the city they chose (D-187): written once, never repeated, and only ever onto safe open ground. The move through a real client is the arrival scenario. */
@GameTestHolder(UndergroundCity.MOD_ID)
@PrefixGameTestTemplate(false)
public final class CityArrivalGameTests {
    private static void check(boolean condition, String message) { if (!condition) throw new GameTestAssertException(message); }

    @GameTest(template = "empty")
    public static void anArrivalIsWrittenOnceAndNeverForSomebodyAlreadyMoved(GameTestHelper helper) {
        var server = helper.getLevel().getServer();
        UUID fresh = UUID.randomUUID(), moved = UUID.randomUUID();
        CityArrival.Store store = CityArrival.store(server);
        check(CityArrival.scheduleId(server, fresh, "valewood"), "a new citizen's arrival was not written");
        check(!CityArrival.scheduleId(server, fresh, "blackwater"), "a second arrival was written for the same citizen");
        check("valewood".equals(store.pending.get(fresh)), "the pending arrival does not name the city chosen first");
        store.placed.put(moved, new CityArrival.Placed("ironhaven", BlockPos.ZERO, 0, "test"));
        check(!CityArrival.scheduleId(server, moved, "valewood"), "somebody already moved was given a second move");
        check(!CityArrival.scheduleId(server, UUID.randomUUID(), "atlantis"), "an arrival was written for a city that does not exist");
        // the record survives a save and a load as it was
        CityArrival.Store again = CityArrival.Store.load(store.save(new CompoundTag()));
        check("valewood".equals(again.pending.get(fresh)) && again.placed.containsKey(moved), "the arrival record did not survive a save and a load");
        store.pending.remove(fresh);
        store.placed.remove(moved);
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void aFakePlayerRegistrationNeverWritesAMoveAndARefusedOneChangesNothing(GameTestHelper helper) {
        FakePlayer player = FakePlayerFactory.get(helper.getLevel(), new GameProfile(UUID.randomUUID(), "uc_arrival_fake"));
        check(RegistrationService.choose(player, "valewood", "farmer") == RegistrationService.Outcome.REGISTERED, "registration failed");
        check(!CityArrival.pending(player.server, player.getUUID()), "a fake player (a machine, a test) was given a move");
        check(RegistrationService.choose(player, "blackwater", "smith") == RegistrationService.Outcome.ALREADY_REGISTERED, "a second registration went through");
        check(!CityArrival.pending(player.server, player.getUUID()), "a refused registration wrote a move");
        helper.succeed();
    }

    @GameTest(template = "empty", batch = "uc_arrival_ground", timeoutTicks = 100)
    public static void onlyOpenDryGroundUnderTheSkyIsSafeToArriveOn(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos base = helper.absolutePos(new BlockPos(1, 2, 1));
        // a clear patch with nothing above it (other batches build over the test area; this batch runs alone)
        for (int x = -1; x <= 9; x++) for (int z = -1; z <= 5; z++) for (int y = -1; y <= 30; y++) level.setBlock(base.offset(x, y, z), y == -1 ? Blocks.STONE.defaultBlockState() : Blocks.AIR.defaultBlockState(), 2);
        BlockPos open = base;
        check(CityArrival.standable(level, open), "open stone ground under the sky was not standable");
        // water over the floor
        BlockPos wet = base.offset(2, 0, 0);
        level.setBlock(wet, Blocks.WATER.defaultBlockState(), 2);
        check(!CityArrival.standable(level, wet), "a cell full of water was standable");
        // inside a wall
        BlockPos wall = base.offset(4, 0, 0);
        level.setBlock(wall.above(), Blocks.STONE_BRICKS.defaultBlockState(), 2);
        check(!CityArrival.standable(level, wall), "a cell with a wall at head height was standable");
        // under a roof (inside a building)
        BlockPos roofed = base.offset(6, 0, 0);
        level.setBlock(roofed.above(5), Blocks.STONE.defaultBlockState(), 2);
        check(!CityArrival.standable(level, roofed), "a cell under a roof was standable");
        // in the air: no floor
        BlockPos air = base.offset(8, 1, 0);
        check(!CityArrival.standable(level, air), "a cell in the air was standable");
        // on leaves, on lava's neighbour magma, in fire
        BlockPos leaves = base.offset(0, 0, 2);
        level.setBlock(leaves.below(), Blocks.OAK_LEAVES.defaultBlockState(), 2);
        check(!CityArrival.standable(level, leaves), "a cell on leaves was standable");
        BlockPos magma = base.offset(2, 0, 2);
        level.setBlock(magma.below(), Blocks.MAGMA_BLOCK.defaultBlockState(), 2);
        check(!CityArrival.standable(level, magma), "a cell on magma was standable");
        BlockPos fire = base.offset(4, 0, 2);
        level.setBlock(fire, Blocks.FIRE.defaultBlockState(), 2);
        check(!CityArrival.standable(level, fire), "a cell on fire was standable");
        // the search finds the nearest safe cell, never one of the bad ones, and nothing where nothing is safe
        Optional<BlockPos> found = CityArrival.safeStand(level, wall, 1, 2, 2);
        check(found.isPresent() && CityArrival.standable(level, found.get()) && !found.get().equals(wall), "the search did not step off the wall onto safe ground: " + found);
        for (int x = -1; x <= 9; x++) for (int z = -1; z <= 5; z++) level.setBlock(base.offset(x, 0, z), Blocks.WATER.defaultBlockState(), 2);
        check(CityArrival.safeStand(level, base.offset(4, 0, 2), 3, 1, 1).isEmpty(), "a pond was given as a place to arrive");
        for (int x = -1; x <= 9; x++) for (int z = -1; z <= 5; z++) for (int y = -1; y <= 6; y++) level.setBlock(base.offset(x, y, z), Blocks.AIR.defaultBlockState(), 2);
        helper.succeed();
    }

    @GameTest(template = "empty", batch = "uc_arrival_move", timeoutTicks = 100)
    public static void theMoveSetsAnOwnRespawnOnlyWhereThereIsNoneAndNeverTheWorldSpawn(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos spawnBefore = level.getSharedSpawnPos();
        BlockPos base = helper.absolutePos(new BlockPos(1, 2, 1));
        for (int x = -1; x <= 4; x++) for (int z = -1; z <= 4; z++) for (int y = -1; y <= 30; y++) level.setBlock(base.offset(x, y, z), y == -1 ? Blocks.STONE.defaultBlockState() : Blocks.AIR.defaultBlockState(), 2);
        FakePlayer first = FakePlayerFactory.get(level, new GameProfile(UUID.randomUUID(), "uc_arrival_a"));
        first.setRespawnPosition(level.dimension(), null, 0f, false, false);
        CityArrival.move(first, level, new CityArrival.Stand(base, 90f, "test"));
        // (a fake player's connection ignores teleports; where a real citizen lands is checked by the arrival scenario through real clients)
        check(base.equals(first.getRespawnPosition()) && first.isRespawnForced(), "a citizen with no respawn point was not given the arrival as theirs");
        FakePlayer second = FakePlayerFactory.get(level, new GameProfile(UUID.randomUUID(), "uc_arrival_b"));
        BlockPos bed = base.offset(3, 0, 3);
        second.setRespawnPosition(level.dimension(), bed, 0f, false, false);
        CityArrival.move(second, level, new CityArrival.Stand(base, 90f, "test"));
        check(bed.equals(second.getRespawnPosition()), "a respawn point the citizen had chosen was replaced");
        check(level.getSharedSpawnPos().equals(spawnBefore), "the world spawn was moved");
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void everyCityHasAWelcomeAndOnlyIronhavenStartsAtTheRow(GameTestHelper helper) {
        Map<String, CityArrival.Welcome> welcomes = CityArrival.welcomesForTest();
        check(welcomes.keySet().equals(ProgressionRules.CITIES), "a city has no welcome, or a welcome names no city: " + welcomes.keySet());
        check("founders_row".equals(welcomes.get("ironhaven").district()), "Ironhaven's citizens do not start at Founders Row, where the employers are");
        check(welcomes.get("valewood").district().isEmpty() && welcomes.get("blackwater").district().isEmpty(), "Valewood or Blackwater would start at the landing by Ironhaven");
        check(welcomes.get("valewood").buildings().equals(List.of("underground_city:vw_shop")) && welcomes.get("blackwater").buildings().equals(List.of("underground_city:bw_store")),
            "Valewood and Blackwater do not start at their shops with a public terminal");
        boolean refused = false;
        try { CityArrival.parse(com.google.gson.JsonParser.parseString("{\"schema_version\":1,\"city\":\"atlantis\"}").getAsJsonObject()); } catch (IllegalArgumentException expected) { refused = true; }
        check(refused, "a welcome for an unknown city was accepted");
        helper.succeed();
    }
}
