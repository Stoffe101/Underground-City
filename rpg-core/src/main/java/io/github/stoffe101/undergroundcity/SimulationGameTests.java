package io.github.stoffe101.undergroundcity;

import io.github.stoffe101.undergroundcity.SimulationWorld.Profile;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Difficulty;
import net.minecraft.world.DifficultyInstance;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LightningBolt;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.entity.animal.Pig;
import net.minecraft.world.entity.monster.Zombie;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.level.NaturalSpawner;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.SpawnerBlockEntity;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.phys.AABB;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.common.util.FakePlayerFactory;
import net.minecraftforge.event.entity.living.MobSpawnEvent;
import net.minecraftforge.eventbus.api.Event;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

import static io.github.stoffe101.undergroundcity.CrewGameTests.check;
import static io.github.stoffe101.undergroundcity.CrewGameTests.player;

/**
 * The monster-free simulation world (D-094), tested through the game's own paths: the same events the natural spawner, spawner blocks, structure generation, raids and
 * conversions post, and the natural spawner itself ({@code NaturalSpawner.spawnCategoryForPosition}) in a dark room, in the profile a player gets by default and, as the control
 * that proves the harness can see monsters at all, in the CLASSIC profile.
 *
 * <p>What this is not: it is not a night played by a person in a generated world. It shows that the rules refuse what they should and allow what they should, with the base game's
 * own spawner as the witness; the combined journey and the hosted pack job run them on the full pack (every mod's hostile entity types are listed by
 * {@link #everyHostileKindOfThePackIsRefusedForEveryAutomaticReason}).
 */
@GameTestHolder(UndergroundCity.MOD_ID)
@PrefixGameTestTemplate(false)
public final class SimulationGameTests {
    private SimulationGameTests() {}

    private static final List<MobSpawnType> AUTOMATIC = List.of(MobSpawnType.NATURAL, MobSpawnType.CHUNK_GENERATION, MobSpawnType.STRUCTURE, MobSpawnType.SPAWNER, MobSpawnType.TRIGGERED,
        MobSpawnType.REINFORCEMENT, MobSpawnType.PATROL, MobSpawnType.JOCKEY, MobSpawnType.EVENT, MobSpawnType.MOB_SUMMONED, MobSpawnType.CONVERSION);

    private static final List<String> REQUIRED_HOSTILE = List.of("zombie", "husk", "drowned", "zombie_villager", "zombified_piglin", "skeleton", "stray", "wither_skeleton", "creeper", "spider", "cave_spider",
        "enderman", "endermite", "silverfish", "witch", "phantom", "slime", "magma_cube", "blaze", "ghast", "guardian", "elder_guardian", "shulker", "vex", "vindicator", "evoker", "pillager", "ravager",
        "piglin", "piglin_brute", "hoglin", "zoglin", "warden", "wither", "ender_dragon");

    private static final List<String> REQUIRED_NOT_HOSTILE = List.of("pig", "cow", "sheep", "chicken", "horse", "donkey", "rabbit", "villager", "wandering_trader", "iron_golem", "snow_golem", "wolf", "cat",
        "ocelot", "fox", "bee", "bat", "cod", "salmon", "squid", "axolotl", "frog", "turtle", "parrot", "llama", "goat", "dolphin", "polar_bear", "panda", "camel", "sniffer", "allay", "mooshroom", "strider");

    private static EntityType<?> type(String path) { return ForgeRegistries.ENTITY_TYPES.getValue(ResourceLocation.withDefaultNamespace(path)); }

    private static Mob create(ServerLevel level, EntityType<?> type, BlockPos at) {
        Entity entity = type.create(level);
        if (!(entity instanceof Mob mob)) return null;
        mob.moveTo(at.getX() + 0.5, at.getY(), at.getZ() + 0.5);
        return mob;
    }

    private static boolean positionDenied(ServerLevel level, Mob mob, MobSpawnType reason) {
        MobSpawnEvent.PositionCheck check = new MobSpawnEvent.PositionCheck(mob, level, reason, null);
        MinecraftForge.EVENT_BUS.post(check);
        return check.getResult() == Event.Result.DENY;
    }

    private static boolean finalizeCancelled(ServerLevel level, Mob mob, MobSpawnType reason) {
        DifficultyInstance difficulty = level.getCurrentDifficultyAt(mob.blockPosition());
        MobSpawnEvent.FinalizeSpawn finalize = new MobSpawnEvent.FinalizeSpawn(mob, level, mob.getX(), mob.getY(), mob.getZ(), difficulty, reason, null, null, null);
        MinecraftForge.EVENT_BUS.post(finalize);
        return finalize.isSpawnCancelled();
    }

    // ------------------------------------------------------------------------------------------------------------ the defaults

    @GameTest(template = "empty")
    public static void theWorldIsASimulationByDefaultAndNothingElseIsSwitchedOff(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        check(SimulationWorld.profile() == Profile.SIMULATION, "the default profile is not SIMULATION: " + SimulationWorld.profile());
        check(SimulationWorld.SPEC.getValues().get("world.hostileMobs") != null, "the world configuration does not declare hostileMobs");
        // not peaceful and not a game-rule switch: difficulty is the server's own, hunger still bites at that difficulty
        check(level.getDifficulty() != Difficulty.PEACEFUL, "the simulation must not be Peaceful: " + level.getDifficulty());
        ServerPlayer p = player(helper, "uc_sim_hungry", "farmer");
        p.getFoodData().setFoodLevel(20);
        p.getFoodData().setSaturation(0f);
        p.causeFoodExhaustion(40f);
        for (int i = 0; i < 3; i++) p.getFoodData().tick(p);
        check(p.getFoodData().getFoodLevel() < 20, "hunger did not drain at " + level.getDifficulty() + ": " + p.getFoodData().getFoodLevel());
        // the rule exists and says what it should
        System.out.println("[UC-SIMULATION-AUDIT] doMobSpawning in this test world: " + level.getGameRules().getBoolean(net.minecraft.world.level.GameRules.RULE_DOMOBSPAWNING) + "; difficulty " + level.getDifficulty());
        helper.succeed();
    }

    // ------------------------------------------------------------------------------------------------------------ every hostile kind, every automatic reason

    @GameTest(template = "empty", timeoutTicks = 400)
    public static void everyHostileKindOfThePackIsRefusedForEveryAutomaticReason(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos at = helper.absolutePos(new BlockPos(2, 2, 2));
        List<String> hostile = new ArrayList<>(), harmless = new ArrayList<>(), uncreatable = new ArrayList<>();
        Map<String, List<String>> byNamespace = new TreeMap<>();
        for (EntityType<?> type : ForgeRegistries.ENTITY_TYPES) {
            ResourceLocation key = ForgeRegistries.ENTITY_TYPES.getKey(type);
            Mob mob;
            try {
                mob = create(level, type, at);
            } catch (Exception | LinkageError e) {
                uncreatable.add(key + " (" + e.getClass().getSimpleName() + ")");
                continue;
            }
            if (mob == null) continue;   // not a mob (a projectile, a vehicle, an item)
            if (SimulationWorld.hostile(mob)) {
                hostile.add(key.toString());
                byNamespace.computeIfAbsent(key.getNamespace(), n -> new ArrayList<>()).add(key.getPath());
                for (MobSpawnType reason : AUTOMATIC) {
                    check(positionDenied(level, mob, reason), key + " was not refused at the position check for " + reason);
                    Mob again = create(level, type, at);
                    check(finalizeCancelled(level, again, reason), key + " was not refused at the finalise step for " + reason);
                }
                Mob joiner = create(level, type, at);
                check(!level.addFreshEntity(joiner) && level.getEntity(joiner.getUUID()) == null, key + " joined the level");
            } else {
                harmless.add(key.toString());
                for (MobSpawnType reason : AUTOMATIC) {
                    check(!positionDenied(level, mob, reason), key + " (not hostile) was refused at the position check for " + reason);
                    check(!finalizeCancelled(level, create(level, type, at), reason), key + " (not hostile) was refused at the finalise step for " + reason);
                }
            }
        }
        for (String path : REQUIRED_HOSTILE) check(hostile.contains("minecraft:" + path), "minecraft:" + path + " is not classified as hostile (hostile found: " + hostile.size() + ")");
        for (String path : REQUIRED_NOT_HOSTILE) check(harmless.contains("minecraft:" + path), "minecraft:" + path + " is classified as hostile or does not exist");
        // the record for docs/technical/simulation-world.md: every hostile entity type of every loaded mod
        System.out.println("[UC-SIMULATION-AUDIT] hostile entity types refused (" + hostile.size() + "), by mod: " + byNamespace);
        System.out.println("[UC-SIMULATION-AUDIT] not hostile (" + harmless.size() + "): " + harmless);
        if (!uncreatable.isEmpty()) System.out.println("[UC-SIMULATION-AUDIT] could not be created for the audit: " + uncreatable);
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void livestockVillagersPetsAndWildlifeSpawnBreedAndPersist(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos at = helper.absolutePos(new BlockPos(3, 2, 3));
        for (String path : List.of("pig", "cow", "sheep", "chicken", "villager", "wolf", "cat", "fox", "bee", "bat", "cod")) {
            Mob mob = create(level, type(path), at);
            boolean water = path.equals("cod");
            if (water) continue;   // needs water to exist; the event rules above cover it
            check(level.addFreshEntity(mob), "a " + path + " could not join the level");
            // saved and loaded again, as a chunk does
            CompoundTag saved = mob.saveWithoutId(new CompoundTag());
            saved.putString("id", ForgeRegistries.ENTITY_TYPES.getKey(mob.getType()).toString());
            mob.discard();
            Entity loaded = EntityType.loadEntityRecursive(saved, level, e -> e);
            check(loaded != null && level.addFreshEntity(loaded), "a saved " + path + " did not load back into the level");
            loaded.discard();
        }
        // breeding: a child is created through the game's own breeding path and joins
        Pig mother = (Pig) create(level, EntityType.PIG, at), father = (Pig) create(level, EntityType.PIG, at.east());
        level.addFreshEntity(mother);
        level.addFreshEntity(father);
        mother.setAge(0);
        father.setAge(0);
        mother.setInLove(null);
        father.setInLove(null);
        Pig child = (Pig) mother.getBreedOffspring(level, father);
        check(child != null, "pigs could not produce offspring");
        child.setBaby(true);
        child.moveTo(mother.position());
        check(level.addFreshEntity(child), "a piglet could not join the level");
        // villagers are people, not monsters: a village is a village
        Villager villager = (Villager) create(level, EntityType.VILLAGER, at.north());
        check(level.addFreshEntity(villager) && !SimulationWorld.hostile(villager), "a villager was treated as hostile");
        mother.discard();
        father.discard();
        child.discard();
        villager.discard();
        helper.succeed();
    }

    // ------------------------------------------------------------------------------------------------------------ scripted and deliberate hostile creatures

    @GameTest(template = "empty")
    public static void scriptedEnemiesStillExistAndSurviveASaveAndALoad(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos at = helper.absolutePos(new BlockPos(3, 2, 3));
        // an armed criminal, a guard dog, a scripted raider: tagged before they join
        for (String path : List.of("pillager", "vindicator", "zombie", "skeleton")) {
            Mob raider = SimulationWorld.permit(create(level, type(path), at));
            check(level.addFreshEntity(raider), "a scripted " + path + " was refused");
            check(raider.isAlive() && level.getEntity(raider.getUUID()) != null, "a scripted " + path + " is not in the level");
            // it persists: saved, then loaded again (the tag is saved with it)
            CompoundTag saved = raider.saveWithoutId(new CompoundTag());
            saved.putString("id", "minecraft:" + path);
            raider.discard();
            Entity back = EntityType.loadEntityRecursive(saved, level, e -> e);
            check(back != null && level.addFreshEntity(back) && SimulationWorld.permitted(back), "a scripted " + path + " did not survive a save and a load");
            back.discard();
        }
        // deliberate spawns by a person: tagged, so that they also survive the next restart
        for (MobSpawnType reason : List.of(MobSpawnType.COMMAND, MobSpawnType.SPAWN_EGG, MobSpawnType.DISPENSER)) {
            Mob chosen = create(level, EntityType.CREEPER, at);
            check(!positionDenied(level, chosen, reason), "a creeper placed on purpose (" + reason + ") was refused at the position check");
            check(!finalizeCancelled(level, chosen, reason) && SimulationWorld.permitted(chosen), "a creeper placed on purpose (" + reason + ") was refused or not tagged");
            check(level.addFreshEntity(chosen), "a creeper placed on purpose (" + reason + ") did not join");
            chosen.discard();
        }
        // and the untagged one that an operator summoned with its own NBT (no reason is given) is refused, with the tag the way through
        Mob plain = create(level, EntityType.ZOMBIE, at);
        check(!level.addFreshEntity(plain), "an untagged zombie joined the simulation world");
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void hostileCreaturesThatAlreadyExistAreRemovedWhenTheWorldBecomesASimulation(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos at = helper.absolutePos(new BlockPos(3, 2, 3));
        Zombie old = SimulationWorld.withProfile(Profile.CLASSIC, () -> {
            Zombie zombie = (Zombie) create(level, EntityType.ZOMBIE, at);
            check(level.addFreshEntity(zombie), "control: CLASSIC did not let a zombie in");
            return zombie;
        });
        Zombie guard = (Zombie) SimulationWorld.permit(create(level, EntityType.ZOMBIE, at.east()));
        check(level.addFreshEntity(guard), "the scripted zombie was refused");
        int removed = SimulationWorld.purge(level.getServer());
        check(removed >= 1, "nothing was removed");
        check(!old.isAlive() && old.isRemoved(), "the untagged hostile creature that already existed is still alive");
        check(guard.isAlive(), "the scripted creature was removed by the purge");
        guard.discard();
        helper.succeed();
    }

    // ------------------------------------------------------------------------------------------------------------ the classic profile (control)

    @GameTest(template = "empty")
    public static void theClassicProfileWorksWhenAnOperatorChoosesIt(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos at = helper.absolutePos(new BlockPos(3, 2, 3));
        SimulationWorld.withProfile(Profile.CLASSIC, () -> {
            for (EntityType<?> type : List.of(EntityType.ZOMBIE, EntityType.SKELETON, EntityType.CREEPER, EntityType.SPIDER, EntityType.ENDERMAN, EntityType.WITCH, EntityType.SLIME)) {
                Mob mob = create(level, type, at);
                check(!positionDenied(level, mob, MobSpawnType.NATURAL), type + " was refused at the position check in CLASSIC");
                check(!finalizeCancelled(level, create(level, type, at), MobSpawnType.NATURAL), type + " was refused at the finalise step in CLASSIC");
                check(level.addFreshEntity(mob), type + " could not join the level in CLASSIC");
                mob.discard();
            }
        });
        check(SimulationWorld.profile() == Profile.SIMULATION, "the profile was not restored");
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void lightningMakesWitchesAndZombifiedPiglinsOnlyInTheClassicProfile(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        if (level.getDifficulty() == Difficulty.PEACEFUL) level.getServer().setDifficulty(Difficulty.NORMAL, true);
        BlockPos at = helper.absolutePos(new BlockPos(3, 2, 3));
        // simulation: the pig and the villager stay what they are
        Pig pig = (Pig) create(level, EntityType.PIG, at);
        Villager villager = (Villager) create(level, EntityType.VILLAGER, at.east(2));
        level.addFreshEntity(pig);
        level.addFreshEntity(villager);
        LightningBolt bolt = EntityType.LIGHTNING_BOLT.create(level);
        pig.thunderHit(level, bolt);
        villager.thunderHit(level, bolt);
        check(pig.isAlive() && !pig.isRemoved(), "lightning turned a pig into a monster in the simulation world");
        check(villager.isAlive() && !villager.isRemoved(), "lightning turned a villager into a witch in the simulation world");
        pig.discard();
        villager.discard();
        // control: the base game's behaviour is real and visible to this test
        SimulationWorld.withProfile(Profile.CLASSIC, () -> {
            Pig second = (Pig) create(level, EntityType.PIG, at);
            level.addFreshEntity(second);
            second.thunderHit(level, bolt);
            check(second.isRemoved(), "control: lightning did not convert a pig in CLASSIC, so the check above proves nothing");
            for (Entity e : level.getEntitiesOfClass(Entity.class, new AABB(at).inflate(3))) if (SimulationWorld.hostile(e)) e.discard();
        });
        helper.succeed();
    }

    // ------------------------------------------------------------------------------------------------------------ the base game's own spawner, in the dark

    /** A sealed, dark room (a cave) and a roofed, unlit hall with a doorway (a building) at the given corner; walls, floor and ceiling of stone. Returns the floor-level centre. */
    private static BlockPos darkRoom(ServerLevel level, BlockPos corner, int size, boolean doorway) {
        for (int x = 0; x <= size + 1; x++) for (int z = 0; z <= size + 1; z++) for (int y = 0; y <= 5; y++) {
            boolean shell = x == 0 || z == 0 || x == size + 1 || z == size + 1 || y == 0 || y == 5;
            level.setBlock(corner.offset(x, y, z), shell ? Blocks.STONE.defaultBlockState() : Blocks.AIR.defaultBlockState(), 2);
        }
        if (doorway) for (int y = 1; y <= 2; y++) level.setBlock(corner.offset(0, y, size / 2), Blocks.AIR.defaultBlockState(), 2);
        return corner.offset(size / 2 + 1, 1, size / 2 + 1);
    }

    private static int spawnMonstersInto(ServerLevel level, BlockPos centre, int attempts, MobCategory category) {
        LevelChunk chunk = level.getChunkAt(centre);
        int[] spawned = {0};
        for (int i = 0; i < attempts; i++) {
            NaturalSpawner.spawnCategoryForPosition(category, level, chunk, centre, (type, pos, c) -> true, (mob, c) -> spawned[0]++);
        }
        return spawned[0];
    }

    private static void clearHostiles(ServerLevel level, BlockPos centre) {
        for (Entity e : level.getEntitiesOfClass(Entity.class, new AABB(centre).inflate(20))) if (SimulationWorld.hostile(e)) e.discard();
    }

    /** Runs after the light engine has seen the rooms that were just built (it works on its own thread), and reports a failure as the test's failure. */
    private static void later(GameTestHelper helper, int ticks, Body body) {
        helper.runAfterDelay(ticks, () -> {
            try {
                body.run();
                helper.succeed();
            } catch (Throwable failure) {
                helper.fail(String.valueOf(failure.getMessage()));
            }
        });
    }

    private interface Body { void run() throws Exception; }

    /**
     * The natural spawner only works near a player of the level ("getNearestPlayer" with no limit, and then at least 24 blocks from that player). A scripted fake player is not part of
     * the level's player list unless it is added, so this adds one well away from the rooms and the caller removes it again.
     */
    private static ServerPlayer distantPlayer(ServerLevel level, BlockPos near) {
        ServerPlayer player = FakePlayerFactory.get(level, new com.mojang.authlib.GameProfile(UUID.randomUUID(), "uc_sim_far"));
        player.moveTo(near.getX() + 0.5, near.getY(), near.getZ() + 0.5 + 70);
        level.addNewPlayer(player);
        return player;
    }

    /**
     * A place for a test arena that no other test uses (the tests are packed closely and the template is a single block): far east of the test area, high above any terrain, at least 24
     * blocks from the world's spawn point as the natural spawner insists, with its chunks held loaded. {@code slot} separates the arenas of tests that run at the same time.
     */
    private static BlockPos arena(ServerLevel level, int slot) {
        BlockPos pos = new BlockPos(3000 + slot * 160, 290, 3000);
        net.minecraft.world.level.ChunkPos centre = new net.minecraft.world.level.ChunkPos(pos);
        for (int dx = -3; dx <= 8; dx++) for (int dz = -2; dz <= 9; dz++) {
            net.minecraft.world.level.ChunkPos chunk = new net.minecraft.world.level.ChunkPos(centre.x + dx, centre.z + dz);
            level.getChunkSource().addRegionTicket(net.minecraft.server.level.TicketType.FORCED, chunk, 2, chunk);
            level.getChunk(chunk.x, chunk.z);
        }
        return pos;
    }

    private static void releaseArena(ServerLevel level, int slot) {
        net.minecraft.world.level.ChunkPos centre = new net.minecraft.world.level.ChunkPos(new BlockPos(3000 + slot * 160, 290, 3000));
        for (int dx = -3; dx <= 8; dx++) for (int dz = -2; dz <= 9; dz++) {
            net.minecraft.world.level.ChunkPos chunk = new net.minecraft.world.level.ChunkPos(centre.x + dx, centre.z + dz);
            level.getChunkSource().removeRegionTicket(net.minecraft.server.level.TicketType.FORCED, chunk, 2, chunk);
        }
    }

    @GameTest(template = "empty", timeoutTicks = 600, batch = "uc_simulation_spawner")
    public static void theNaturalSpawnerPutsNoMonstersInACaveOrABuildingAtNightButDoesInTheClassicProfile(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        if (level.getDifficulty() == Difficulty.PEACEFUL) level.getServer().setDifficulty(Difficulty.NORMAL, true);
        long time = level.getDayTime();
        level.setDayTime(18000);   // midnight
        BlockPos origin = arena(level, 0);
        BlockPos cave = darkRoom(level, origin, 9, false), hall = darkRoom(level, origin.offset(14, 0, 0), 9, true);
        ServerPlayer far = distantPlayer(level, origin);
        later(helper, 60, () -> {
            try {
                // the default profile: not one monster in 400 attempts per room
                int caveMonsters = spawnMonstersInto(level, cave, 400, MobCategory.MONSTER), hallMonsters = spawnMonstersInto(level, hall, 400, MobCategory.MONSTER);
                clearHostiles(level, cave);
                check(caveMonsters == 0 && hallMonsters == 0, "the natural spawner still produced monsters in the simulation world: cave " + caveMonsters + ", building " + hallMonsters);
                // the control: the same rooms, the same spawner, the CLASSIC profile
                int[] control = SimulationWorld.withProfile(Profile.CLASSIC, () -> new int[] {spawnMonstersInto(level, cave, 400, MobCategory.MONSTER), spawnMonstersInto(level, hall, 400, MobCategory.MONSTER)});
                clearHostiles(level, cave);
                System.out.println("[UC-SIMULATION-AUDIT] natural spawner, 400 attempts per room at midnight: simulation cave " + caveMonsters + " / building " + hallMonsters + "; classic cave " + control[0] + " / building " + control[1]
                    + "; doMobSpawning=" + level.getGameRules().getBoolean(net.minecraft.world.level.GameRules.RULE_DOMOBSPAWNING));
                check(control[0] > 0 && control[1] > 0, "control: the CLASSIC profile produced no monsters in the dark rooms (cave " + control[0] + ", building " + control[1] + "), so the zero above proves nothing about the rule");
            } finally {
                level.setDayTime(time);
                level.removePlayerImmediately(far, Entity.RemovalReason.DISCARDED);
                releaseArena(level, 0);
            }
        });
    }

    @GameTest(template = "empty", timeoutTicks = 600, batch = "uc_simulation_spawner")
    public static void aMonsterSpawnerBlockSpawnsNothingInTheSimulationButSomethingInTheClassicProfile(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        if (level.getDifficulty() == Difficulty.PEACEFUL) level.getServer().setDifficulty(Difficulty.NORMAL, true);
        BlockPos origin = arena(level, 1);
        BlockPos room = darkRoom(level, origin, 9, false);
        BlockPos block = room.above();
        level.setBlock(block, Blocks.SPAWNER.defaultBlockState(), 3);
        SpawnerBlockEntity spawner = (SpawnerBlockEntity) level.getBlockEntity(block);
        spawner.setEntityId(EntityType.ZOMBIE, level.getRandom());
        // a player the spawner can notice: a fake player that is also part of the level
        ServerPlayer near = FakePlayerFactory.get(level, new com.mojang.authlib.GameProfile(UUID.randomUUID(), "uc_sim_near"));
        near.moveTo(block.getX() + 0.5, block.getY(), block.getZ() + 4.5);
        level.addNewPlayer(near);
        later(helper, 60, () -> {
            try {
                for (int i = 0; i < 1500; i++) spawner.getSpawner().serverTick(level, block);
                long inSimulation = level.getEntitiesOfClass(Zombie.class, new AABB(block).inflate(10)).size();
                check(inSimulation == 0, "a monster spawner block spawned " + inSimulation + " zombie(s) in the simulation world");
                long control = SimulationWorld.withProfile(Profile.CLASSIC, () -> {
                    for (int i = 0; i < 1500; i++) spawner.getSpawner().serverTick(level, block);
                    return (long) level.getEntitiesOfClass(Zombie.class, new AABB(block).inflate(10)).size();
                });
                clearHostiles(level, room);
                check(control > 0, "control: the spawner block spawned nothing in CLASSIC either, so the zero above proves nothing about the rule");
            } finally {
                level.removePlayerImmediately(near, Entity.RemovalReason.DISCARDED);
                releaseArena(level, 1);
            }
        });
    }

    /** Open-air spots (found by asking the world's biome source, which generates nothing) whose biome has livestock, at least 100 blocks apart. */
    private static List<BlockPos> livestockSpots(ServerLevel level, int wanted) {
        List<BlockPos> found = new ArrayList<>();
        for (int i = 0; i < 400 && found.size() < wanted; i++) {
            BlockPos spot = new BlockPos(5000 + (i % 20) * 160, 290, 5000 + (i / 20) * 160);
            var biome = level.getBiome(spot).value();
            boolean livestock = !biome.getMobSettings().getMobs(MobCategory.CREATURE).isEmpty() && biome.getBaseTemperature() > 0.15f && biome.getBaseTemperature() < 1.5f;
            String name = level.getBiome(spot).unwrapKey().map(k -> k.location().getPath()).orElse("");
            if (livestock && !name.contains("ocean") && !name.contains("river") && !name.contains("beach") && !name.contains("desert") && !name.contains("badlands")) found.add(spot);
        }
        return found;
    }

    @GameTest(template = "empty", timeoutTicks = 900, batch = "uc_simulation_spawner")
    public static void creaturesStillSpawnNaturallyInTheSimulation(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        long time = level.getDayTime();
        level.setDayTime(6000);
        List<BlockPos> spots = livestockSpots(level, 3);
        check(!spots.isEmpty(), "the biome source offers no spot with livestock within 3000 blocks, so the test world cannot show it");
        String tried = "";
        try {
            for (BlockPos origin : spots) {
                // a lit grass meadow under the open sky, used in this very tick while its chunks are held, with a player of the level 60 blocks off (the natural spawner needs one and keeps 24 blocks from it)
                BlockPos playerAt = origin.offset(0, 0, 60);
                List<net.minecraft.world.level.ChunkPos> held = new ArrayList<>();
                for (BlockPos around : List.of(origin, origin.offset(19, 0, 19), playerAt)) {
                    net.minecraft.world.level.ChunkPos chunk = new net.minecraft.world.level.ChunkPos(around);
                    level.getChunkSource().addRegionTicket(net.minecraft.server.level.TicketType.FORCED, chunk, 2, chunk);
                    level.getChunk(chunk.x, chunk.z);
                    held.add(chunk);
                }
                ServerPlayer far = FakePlayerFactory.get(level, new com.mojang.authlib.GameProfile(UUID.randomUUID(), "uc_sim_far"));
                far.moveTo(playerAt.getX() + 0.5, playerAt.getY(), playerAt.getZ() + 0.5);
                level.addNewPlayer(far);
                try {
                    for (int x = 0; x < 20; x++) for (int z = 0; z < 20; z++) {
                        level.setBlock(origin.offset(x, 0, z), Blocks.GRASS_BLOCK.defaultBlockState(), 2);
                        for (int y = 1; y <= 4; y++) level.setBlock(origin.offset(x, y, z), Blocks.AIR.defaultBlockState(), 2);
                    }
                    BlockPos centre = origin.offset(10, 1, 10);
                    int classic = SimulationWorld.withProfile(Profile.CLASSIC, () -> spawnMonstersInto(level, centre, 150, MobCategory.CREATURE));
                    for (Entity e : level.getEntitiesOfClass(Animal.class, new AABB(centre).inflate(30))) e.discard();
                    String biome = level.getBiome(centre).unwrapKey().map(k -> k.location().getPath()).orElse("?");
                    tried += biome + "=" + classic + " ";
                    if (classic == 0) continue;
                    int spawned = spawnMonstersInto(level, centre, 150, MobCategory.CREATURE);
                    for (Entity e : level.getEntitiesOfClass(Animal.class, new AABB(centre).inflate(30))) e.discard();
                    System.out.println("[UC-SIMULATION-AUDIT] natural spawner on a meadow in " + biome + ", 150 attempts: creatures in classic " + classic + ", in the simulation " + spawned);
                    check(spawned > 0, "the simulation world stopped livestock from spawning in " + biome + ": " + spawned + " against " + classic + " in CLASSIC");
                    helper.succeed();
                    return;
                } finally {
                    level.removePlayerImmediately(far, Entity.RemovalReason.DISCARDED);
                    for (net.minecraft.world.level.ChunkPos chunk : held) level.getChunkSource().removeRegionTicket(net.minecraft.server.level.TicketType.FORCED, chunk, 2, chunk);
                }
            }
            check(false, "the base game's own spawner found no livestock on any of " + spots.size() + " meadows, so the test world cannot show it (tried: " + tried + ")");
        } finally {
            level.setDayTime(time);
        }
    }
}
