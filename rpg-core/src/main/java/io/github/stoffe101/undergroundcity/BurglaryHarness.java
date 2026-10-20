package io.github.stoffe101.undergroundcity;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.authlib.GameProfile;
import com.mojang.logging.LogUtils;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.common.util.FakePlayer;
import net.minecraftforge.common.util.FakePlayerFactory;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.server.ServerStartedEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import org.slf4j.Logger;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Opt-in proof, on a generated Lost Cities world, of what the building guard and the lock do to ONE real apartment block (D-105): {@code -Duc.burglary=<report.json>} (disposable dedicated server only).
 * It is not the GameTests (those build a house of their own and answer Lost Cities' plan through a seam): here the building is the one the generator made, the walls and doors are its own
 * blocks, and the plan comes from Lost Cities. Phase {@code a} (a fresh world) finds a building of the representative class that has a ground-floor exterior door, then, as a survival player,
 * tries to break its wall, its window and a furnished block, tries its door by hand and with picks, picks it with the honest solver, walks in, finds the stash it was furnished with, picks that,
 * takes one item, and saves the world. Phase {@code b} starts the same world again and checks that the door and the stash are what phase {@code a} left (and that the door locks again when its
 * window lapses). It reports facts; a failing fact is a line in the report, never a silent pass.
 */
public final class BurglaryHarness {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final String OUTPUT = System.getProperty("uc.burglary");
    private static final String PHASE = System.getProperty("uc.burglary.phase", "a");
    private static final String STATE = System.getProperty("uc.burglary.state", "");
    private static final long BUDGET_NANOS = 20L * 60 * 1_000_000_000L;

    private final JsonObject report = new JsonObject();
    private final JsonArray facts = new JsonArray();
    private final List<int[]> queue = new ArrayList<>();
    private long started;
    private boolean prepared, finished;
    private int failures;

    private void fact(String name, boolean ok, String detail) {
        JsonObject o = new JsonObject();
        o.addProperty("fact", name); o.addProperty("ok", ok); o.addProperty("detail", detail);
        facts.add(o);
        if (!ok) failures++;
        LOGGER.info("[UC-BURGLARY] {} {} {}", ok ? "PASS" : "FAIL", name, detail);
    }

    @SubscribeEvent
    public void started(ServerStartedEvent event) { if (OUTPUT != null && !OUTPUT.isBlank()) started = System.nanoTime(); }

    @SubscribeEvent
    public void tick(TickEvent.ServerTickEvent event) {
        if (OUTPUT == null || OUTPUT.isBlank() || finished || started == 0 || event.phase != TickEvent.Phase.END) return;
        MinecraftServer server = event.getServer();
        if (!LostCitiesLink.ready()) return;
        ServerLevel level = server.overworld();
        try {
            if (PHASE.equals("a")) phaseA(server, level); else phaseB(server, level);
        } catch (RuntimeException | AssertionError error) {
            fact("harness", false, error.toString());
            finish(server);
        }
        if (!finished && System.nanoTime() - started > BUDGET_NANOS) { fact("budget", false, "the harness did not finish in twenty minutes"); finish(server); }
    }

    private void finish(MinecraftServer server) {
        finished = true;
        report.addProperty("phase", PHASE);
        report.addProperty("failures", failures);
        report.add("facts", facts);
        try { Files.writeString(Path.of(OUTPUT), report + "\n", StandardCharsets.UTF_8); }
        catch (IOException error) { throw new IllegalStateException("Cannot write the burglary report", error); }
        LOGGER.info("[UC-BURGLARY] phase {} finished with {} failed facts; report {}", PHASE, failures, OUTPUT);
        server.halt(false);
    }

    // ------------------------------------------------------------------------------------------------ phase a

    private static ServerPlayer visitor(ServerLevel level, String name) {
        FakePlayer player = FakePlayerFactory.get(level, new GameProfile(UUID.nameUUIDFromBytes(("uc-burglary-" + name).getBytes(StandardCharsets.UTF_8)), name));
        player.getInventory().clearContent();
        CitizenState.get(level.getServer()).register(player.getUUID(), "ironhaven", "farmer");
        player.setGameMode(GameType.SURVIVAL);
        return player;
    }

    private static BlockHitResult click(BlockPos against, Direction face) { return new BlockHitResult(Vec3.atCenterOf(against).add(Vec3.atLowerCornerOf(face.getNormal()).scale(0.5)), face, against, false); }

    /** A ground-floor exterior door of a building of a lockable class: where a visitor would knock. */
    private record Candidate(CityBuildings.Building building, BlockPos door) {}

    private Optional<Candidate> find(ServerLevel level) {
        var region = CityRegions.INSTANCE.all().stream().filter(r -> r.id().equals("ironhaven")).findFirst().orElseThrow();
        List<Candidate> found = new ArrayList<>();
        for (int radius = 1; radius <= 9 && found.isEmpty(); radius++) {
            for (int dx = -radius; dx <= radius; dx++) for (int dz = -radius; dz <= radius; dz++) {
                if (Math.max(Math.abs(dx), Math.abs(dz)) != radius) continue;
                int cx = region.centerChunkX() + dx, cz = region.centerChunkZ() + dz;
                Optional<CityBuildings.Building> building = CityBuildings.INSTANCE.ofChunk(level, cx, cz);
                if (building.isEmpty() || !building.get().locked() || building.get().security().stashPolicy().isEmpty()) continue;
                LevelChunk chunk = level.getChunk(cx, cz);
                for (int x = 0; x < 16; x++) for (int z = 0; z < 16; z++) {
                    BlockPos pos = new BlockPos(cx * 16 + x, building.get().streetY() + 1, cz * 16 + z);
                    BlockState state = chunk.getBlockState(pos);
                    if (state.getBlock() instanceof DoorBlock && state.getValue(DoorBlock.HALF) == net.minecraft.world.level.block.state.properties.DoubleBlockHalf.LOWER
                        && BuildingGuard.exterior(level, pos, state)) found.add(new Candidate(building.get(), pos));
                }
            }
        }
        return found.stream().findFirst();
    }

    private void phaseA(MinecraftServer server, ServerLevel level) {
        Optional<Candidate> candidate = find(level);
        if (candidate.isEmpty()) { fact("representative building", false, "no lockable building with an exterior ground-floor door within nine chunks of Ironhaven's centre"); finish(server); return; }
        CityBuildings.Building building = candidate.get().building();
        BlockPos door = candidate.get().door();
        JsonObject target = new JsonObject();
        target.addProperty("city", building.city()); target.addProperty("type", building.type()); target.addProperty("floors", building.floors()); target.addProperty("cellars", building.cellars());
        target.addProperty("class", building.security().id()); target.addProperty("lock_profile", building.security().lockProfile());
        target.addProperty("chunk", building.chunkX() + "," + building.chunkZ()); target.addProperty("door", door.toShortString()); target.addProperty("street_y", building.streetY());
        report.add("representative_building", target);
        fact("representative building", true, building.type() + " of " + building.city() + ", " + building.floors() + " floors, class " + building.security().id() + ", door at " + door.toShortString());

        ServerPlayer thief = visitor(level, "UC_Burglar");
        BlockState doorState = level.getBlockState(door);
        Direction facing = doorState.getValue(DoorBlock.FACING);
        // stand on the sky side
        BlockPos outside = BuildingGuard.exposed(level, door.relative(facing).above()) ? door.relative(facing) : door.relative(facing.getOpposite());
        thief.moveTo(outside.getX() + 0.5, outside.getY(), outside.getZ() + 0.5);
        fact("the door is locked from the street", BuildingGuard.exterior(level, door, doorState) && BuildingGuard.outsideSide(level, door, doorState, thief.position()), "exterior door, visitor on the sky side");

        // protection: break the wall beside the door, the glass, anything solid in the volume; control: a block two chunks away
        BlockPos wall = door.relative(facing.getClockWise());
        int broken = 0, tried = 0;
        for (BlockPos pos : new BlockPos[] {wall, wall.above(), door.relative(facing.getClockWise(), 2).above(), door.above(3)}) {
            if (level.getBlockState(pos).isAir()) continue;
            tried++;
            thief.gameMode.destroyBlock(pos);
            if (level.getBlockState(pos).isAir()) broken++;
        }
        fact("a visitor cannot break the building's walls", tried > 0 && broken == 0, "tried " + tried + " blocks around the door, " + broken + " broke");
        // the control: the same player, in survival, on a lot that is not a building, breaks a block he has just put there
        BlockPos free = null;
        search:
        for (int ring = 2; ring <= 9 && free == null; ring++) {
            for (int dx = -ring; dx <= ring && free == null; dx++) {
                for (int dz = -ring; dz <= ring; dz++) {
                    if (Math.max(Math.abs(dx), Math.abs(dz)) != ring) continue;
                    BlockPos spot = new BlockPos((building.chunkX() + dx) * 16 + 8, building.streetY() + 1, (building.chunkZ() + dz) * 16 + 8);
                    if (CityBuildings.INSTANCE.at(level, spot).isEmpty()) { free = spot; continue search; }
                }
            }
        }
        if (free == null) {
            fact("outside the volume the same player is not stopped", false, "no lot without a building within nine chunks");
        } else {
            level.getChunk(free.getX() >> 4, free.getZ() >> 4);
            level.setBlock(free, Blocks.DIRT.defaultBlockState(), 3);
            thief.gameMode.destroyBlock(free);
            fact("outside the volume the same player is not stopped", level.getBlockState(free).isAir(), "the control block at " + free.toShortString() + " belongs to no building and broke");
        }

        // the door by hand, without a pick
        thief.getInventory().clearContent();
        thief.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
        thief.gameMode.useItemOn(thief, level, ItemStack.EMPTY, InteractionHand.MAIN_HAND, click(door, facing));
        fact("the door does not open by hand", !level.getBlockState(door).getValue(DoorBlock.OPEN) && !LockService.busy(thief.getUUID()), "no pick, no lock session, door closed");

        // with picks the click starts the lock
        thief.getInventory().add(new ItemStack(UndergroundCity.item("lockpick"), 6));
        thief.gameMode.useItemOn(thief, level, ItemStack.EMPTY, InteractionHand.MAIN_HAND, click(door, facing));
        LockService.Active active = LockService.activeFor(thief.getUUID());
        fact("a lockpick starts the lock", active != null && !level.getBlockState(door).getValue(DoorBlock.OPEN), active == null ? "no session" : active.target.profile() + " lock, " + active.session.profile().pins() + " pins");
        if (active == null) { finish(server); return; }
        LockpickSession.State end = LockpickSessionGameTests.solve(active.session, false, (position, pressing) -> {
            active.position = position; active.pressing = pressing;
            LockService.tickOne(server, thief, active);
        });
        fact("an honest player opens the lock", end == LockpickSession.State.OPENED, "ending " + end + ", " + LockService.picks(thief) + " of 6 lockpicks left");
        PropertyLocks locks = PropertyLocks.get(server);
        long now = WorkOrderLedger.get(server).clock();
        fact("the door stands open for its window", level.getBlockState(door).getValue(DoorBlock.OPEN) && locks.breached(door, now), "breached until " + locks.door(door));
        fact("the break-in raised heat", WorkOrderLedger.get(server).heat(thief.getUUID(), "ironhaven") > 0, "heat " + WorkOrderLedger.get(server).heat(thief.getUUID(), "ironhaven"));
        fact("the building was considered for a stash", locks.furnished(building.key()), "furnished " + locks.furnishedCount());

        // the stash: a safe inside the building, if its class gave it one (a chance, not a certainty)
        BlockPos safePos = null;
        for (int y = building.minY(); y < building.maxY() && safePos == null; y++) for (int x = 0; x < 16 && safePos == null; x++) for (int z = 0; z < 16 && safePos == null; z++) {
            BlockPos pos = new BlockPos(building.chunkX() * 16 + x, y, building.chunkZ() * 16 + z);
            if (level.getBlockState(pos).is(UndergroundCity.PROPERTY_CACHE.get())) safePos = pos;
        }
        JsonObject state = new JsonObject();
        state.addProperty("door", door.asLong()); state.addProperty("buildingKey", building.key());
        if (safePos == null) {
            fact("a stash was placed", true, "this building's class rolled no stash (chance " + building.security().stashChance() + "); the stash path is covered by the GameTests and a second building is needed to see one here");
        } else {
            PropertyCacheBlockEntity safe = (PropertyCacheBlockEntity) level.getBlockEntity(safePos);
            fact("a stash was placed inside the building", safe != null && safe.registered() && building.contains(safePos), "safe at " + safePos.toShortString() + " policy " + (safe == null ? "?" : safe.policyId()) + " stock " + (safe == null ? -1 : safe.stock()));
            thief.moveTo(safePos.getX() + 0.5, safePos.getY(), safePos.getZ() + 1.5);
            var entry = safe.entry(level, thief, now);
            fact("the safe asks for a pick", entry.kind() == PropertyCacheBlockEntity.Entry.Kind.PICK, entry.kind() + " profile " + entry.profile());
            String refusal = LockService.begin(thief, level, new LockService.Target(LockService.Kind.SAFE, safePos, entry.profile(), "ironhaven", "safe", "Household safe", entry.picksNeeded()));
            LockService.Active safeActive = LockService.activeFor(thief.getUUID());
            if (refusal != null || safeActive == null) {
                fact("the safe's lock starts", false, String.valueOf(refusal));
            } else {
                LockpickSession.State safeEnd = LockpickSessionGameTests.solve(safeActive.session, false, (position, pressing) -> {
                    safeActive.position = position; safeActive.pressing = pressing;
                    LockService.tickOne(server, thief, safeActive);
                });
                fact("an honest player opens the safe", safeEnd == LockpickSession.State.OPENED, "ending " + safeEnd);
                int before = safe.stock();
                ItemStack taken = ItemStack.EMPTY;
                for (int slot = 0; slot < safe.getContainerSize() && taken.isEmpty(); slot++) if (!safe.getItem(slot).isEmpty()) taken = safe.removeItem(slot, 64);
                fact("an item can be taken, once", !taken.isEmpty() && safe.stock() == before - 1, "took " + taken + ", stock " + before + " -> " + safe.stock());
                state.addProperty("safe", safePos.asLong()); state.addProperty("stockAfter", safe.stock()); state.addProperty("breakins", safe.breakins()); state.addProperty("policy", safe.policyId());
                safe.setChanged();
            }
        }
        state.addProperty("breachedUntil", locks.door(door) == null ? -1 : locks.door(door).breachedUntil());
        state.addProperty("clock", WorkOrderLedger.get(server).clock());
        report.add("state_for_phase_b", state);
        if (!STATE.isBlank()) try { Files.writeString(Path.of(STATE), state + "\n", StandardCharsets.UTF_8); } catch (IOException error) { throw new IllegalStateException(error); }
        locks.setDirty();
        server.saveAllChunks(true, true, true);
        fact("the world was saved", true, "saveAllChunks");
        finish(server);
    }

    // ------------------------------------------------------------------------------------------------ phase b

    private void phaseB(MinecraftServer server, ServerLevel level) {
        if (STATE.isBlank() || !Files.isRegularFile(Path.of(STATE))) { fact("state file", false, "phase a left no state file"); finish(server); return; }
        JsonObject state;
        try { state = JsonParser.parseString(Files.readString(Path.of(STATE))).getAsJsonObject(); } catch (IOException error) { throw new IllegalStateException(error); }
        BlockPos door = BlockPos.of(state.get("door").getAsLong());
        level.getChunk(door.getX() >> 4, door.getZ() >> 4);
        PropertyLocks locks = PropertyLocks.get(server);
        long now = WorkOrderLedger.get(server).clock();
        fact("the world's clock survived the restart", now >= state.get("clock").getAsLong(), "before " + state.get("clock").getAsLong() + ", now " + now);
        fact("the door's state survived the restart", locks.door(door) != null && locks.door(door).breachedUntil() == state.get("breachedUntil").getAsLong(), "saved " + locks.door(door));
        fact("the furnished building survived the restart", locks.furnished(state.get("buildingKey").getAsLong()), "furnished " + locks.furnishedCount());
        if (state.has("safe")) {
            BlockPos safePos = BlockPos.of(state.get("safe").getAsLong());
            level.getChunk(safePos.getX() >> 4, safePos.getZ() >> 4);
            PropertyCacheBlockEntity safe = level.getBlockEntity(safePos) instanceof PropertyCacheBlockEntity s ? s : null;
            fact("the safe survived the restart", safe != null && safe.registered(), String.valueOf(safe));
            if (safe != null) {
                fact("the safe holds what the thief left", safe.stock() == state.get("stockAfter").getAsInt(), "stock " + safe.stock() + ", expected " + state.get("stockAfter").getAsInt());
                fact("the break-in is remembered", safe.breakins() == state.get("breakins").getAsInt() && safe.vigilance() >= 1, "break-ins " + safe.breakins() + ", vigilance " + safe.vigilance());
            }
        }
        // the window lapses (the shared clock of this disposable world is moved on): the sweep shuts the door and it is locked again
        WorkOrderLedger.get(server).advance(LockService.BREACH_TICKS + 10);
        LockService.sweep(server);
        BlockState after = level.getBlockState(door);
        fact("the lapsed door is shut again", after.getBlock() instanceof DoorBlock && !after.getValue(DoorBlock.OPEN), "open=" + (after.getBlock() instanceof DoorBlock ? after.getValue(DoorBlock.OPEN) : "n/a"));
        ServerPlayer stranger = visitor(level, "UC_Stranger");
        Direction facing = after.getValue(DoorBlock.FACING);
        BlockPos outside = BuildingGuard.exposed(level, door.relative(facing).above()) ? door.relative(facing) : door.relative(facing.getOpposite());
        stranger.moveTo(outside.getX() + 0.5, outside.getY(), outside.getZ() + 0.5);
        stranger.gameMode.useItemOn(stranger, level, ItemStack.EMPTY, InteractionHand.MAIN_HAND, click(door, facing));
        fact("a stranger cannot open it by hand after the restart", !level.getBlockState(door).getValue(DoorBlock.OPEN), "closed");
        finish(server);
    }
}
