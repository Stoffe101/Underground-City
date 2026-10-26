package io.github.stoffe101.undergroundcity;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mojang.logging.LogUtils;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.SimpleJsonResourceReloadListener;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.TagKey;
import net.minecraft.util.profiling.ProfilerFiller;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.DoublePlantBlock;
import net.minecraft.world.level.block.TrapDoorBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BedPart;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.phys.AABB;
import net.minecraftforge.common.Tags;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.registries.ForgeRegistries;
import org.slf4j.Logger;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Breaking things in a burglary, and the city putting them back (D-190).
 *
 * <p><b>Who may break what.</b> A city building cannot be mined ({@link BuildingGuard}); a thief who has picked one of its locks holds a <i>breach permit</i> for that building alone, for a few
 * minutes of active play, renewed each time they pick a lock of it. With a permit they may break what is inside: furniture and decoration ({@code underground_city:burglary_breakable}, the
 * furniture mods' blocks by namespace), windows, and the light inner walls ({@code underground_city:burglary_breakable_walls}) that are not part of the shell (a block next to the open air
 * outside, a floor or a ceiling). Never a door, a container, a block of Core's own systems ({@code underground_city:burglary_protected}), anything in a home somebody holds, or anything of
 * another building. An open door, or following somebody in, gives no permit.
 *
 * <p><b>What it gives.</b> Nothing: a broken block drops no item (the city puts it back, so a drop would be a copy made from nothing), and anything that stood on it goes with it the same way.
 * The loot of a burglary is what searching gives ({@link BurglarySearch}), marked stolen. Breaking is loud: every break may be heard or trip a wired alarm ({@link CrimeWatch#damaged}).
 *
 * <p><b>Putting it back.</b> The first time a cell is broken its block (and any block entity data, the text of a sign) is written into the world's record ({@value Store#NAME}), which survives
 * a save and a restart. When the building has been left alone for {@link Rules#restoreMinutes} minutes of active play (the server's play clock, which stands still while nobody plays), and
 * its chunk is loaded (nothing is ever loaded or generated for this), and nobody is in it, each recorded cell that is still empty is put back as it was, supports first. A cell somebody has
 * filled since is left as it is; a cell now inside a home somebody holds is left to its owner. A building nobody leaves for long is put back anyway after twice the wait, round anybody standing in it.
 */
public final class BurglaryDamage {
    private static final Logger LOGGER = LogUtils.getLogger();
    public static final TagKey<Block> BREAKABLE = TagKey.create(Registries.BLOCK, ResourceLocation.fromNamespaceAndPath(UndergroundCity.MOD_ID, "burglary_breakable"));
    public static final TagKey<Block> BREAKABLE_WALLS = TagKey.create(Registries.BLOCK, ResourceLocation.fromNamespaceAndPath(UndergroundCity.MOD_ID, "burglary_breakable_walls"));
    public static final TagKey<Block> PROTECTED = TagKey.create(Registries.BLOCK, ResourceLocation.fromNamespaceAndPath(UndergroundCity.MOD_ID, "burglary_protected"));

    /** The numbers, as data ({@code data/underground_city/burglary_damage/rules.json}). */
    public record Rules(int permitMinutes, int restoreMinutes, int maxCellsPerBuilding, int maxBuildings, Set<String> furnitureNamespaces) {
        public Rules {
            if (permitMinutes < 1 || permitMinutes > 60 || restoreMinutes < 1 || restoreMinutes > 24 * 60 || maxCellsPerBuilding < 16 || maxCellsPerBuilding > 4096 || maxBuildings < 1 || maxBuildings > 4096)
                throw new IllegalArgumentException("Invalid burglary damage rules");
            furnitureNamespaces = Set.copyOf(furnitureNamespaces);
        }
        static final Rules DEFAULT = new Rules(5, 30, 512, 256, Set.of());
    }
    static volatile Rules RULES = Rules.DEFAULT;

    public static final class Loader extends SimpleJsonResourceReloadListener {
        public static final Loader INSTANCE = new Loader();
        private Loader() { super(new Gson(), "burglary_damage"); }
        @Override
        protected void apply(Map<ResourceLocation, JsonElement> data, ResourceManager resources, ProfilerFiller profiler) {
            JsonElement element = data.get(ResourceLocation.fromNamespaceAndPath(UndergroundCity.MOD_ID, "rules"));
            RULES = element == null ? Rules.DEFAULT : parse(element.getAsJsonObject());
            LOGGER.info("Burglary damage: a permit lasts {} active minutes, a building is put back after {}", RULES.permitMinutes(), RULES.restoreMinutes());
        }
    }

    static Rules parse(JsonObject json) {
        if (!json.has("schema_version") || json.get("schema_version").getAsInt() != 1) throw new IllegalArgumentException("Unsupported burglary damage schema");
        java.util.Set<String> namespaces = new java.util.TreeSet<>();
        if (json.has("furniture_namespaces")) for (JsonElement e : json.getAsJsonArray("furniture_namespaces")) {
            String ns = e.getAsString();
            if (!ns.matches("[a-z0-9_.-]{2,64}") || ns.equals("minecraft") || ns.equals(UndergroundCity.MOD_ID)) throw new IllegalArgumentException("Bad furniture namespace " + ns);
            namespaces.add(ns);
        }
        // a server operator may shorten or lengthen the wait without a data pack: -Duc.burglary.restoreMinutes=N
        int restore = Integer.getInteger("uc.burglary.restoreMinutes", Integer.getInteger("uc.playtest.restoreminutes", json.get("restore_active_minutes").getAsInt()));
        return new Rules(json.get("permit_active_minutes").getAsInt(), restore, json.get("max_cells_per_building").getAsInt(), json.get("max_buildings").getAsInt(), namespaces);
    }

    // ------------------------------------------------------------------------------------------------ permits (memory only: a restart ends them)

    private record Permit(long buildingKey, ResourceKey<Level> dimension, long until) {}
    private static final Map<UUID, List<Permit>> PERMITS = new HashMap<>();

    /** A lock of this building was picked by this player (D-190): they may break things inside it for a while. */
    static void grant(ServerPlayer player, CityBuildings.Building building, long now) {
        List<Permit> list = PERMITS.computeIfAbsent(player.getUUID(), k -> new ArrayList<>());
        list.removeIf(p -> p.buildingKey() == building.key() || p.until() <= now);
        list.add(new Permit(building.key(), player.level().dimension(), now + RULES.permitMinutes() * 60L * 20L));
    }

    /** True when this player holds a live permit for this building. */
    static boolean permitted(ServerPlayer player, CityBuildings.Building building, long now) {
        List<Permit> list = PERMITS.get(player.getUUID());
        if (list == null) return false;
        for (Permit p : list) if (p.buildingKey() == building.key() && p.dimension() == player.level().dimension() && p.until() > now) return true;
        return false;
    }

    /** Test seam. */
    static void grantForTest(ServerPlayer player, CityBuildings.Building building, long now) { grant(player, building, now); }
    static void resetForTest() { PERMITS.clear(); FIXED_NOW = null; }
    private static volatile Long FIXED_NOW;

    // ------------------------------------------------------------------------------------------------ what may be broken

    public enum Verdict { ALLOWED, NO_PERMIT, PROTECTED, SHELL, NOT_BREAKABLE, HOME, FULL }

    /** Whether this player may break the block at {@code pos} of this building now, and if not, why. */
    static Verdict judge(ServerPlayer player, ServerLevel level, CityBuildings.Building b, BlockPos pos, long now) {
        if (!permitted(player, b, now)) return Verdict.NO_PERMIT;
        if (!b.contains(pos)) return Verdict.NO_PERMIT;
        CrewLedger ledger = CrewService.ledger(level.getServer());
        if (ledger != null && !ledger.faulted() && ledger.residenceAt(level.dimension().location().toString(), pos) != null) return Verdict.HOME;
        BlockState state = level.getBlockState(pos);
        if (state.isAir()) return Verdict.NOT_BREAKABLE;
        if (protectedBlock(state, level.getBlockEntity(pos))) return Verdict.PROTECTED;
        if (glass(state)) return within(level, b, pos) ? Verdict.ALLOWED : Verdict.FULL;
        if (state.is(BREAKABLE) || furniture(state)) return within(level, b, pos) ? Verdict.ALLOWED : Verdict.FULL;
        if (state.is(BREAKABLE_WALLS)) {
            if (shell(level, b, pos)) return Verdict.SHELL;
            return within(level, b, pos) ? Verdict.ALLOWED : Verdict.FULL;
        }
        return Verdict.NOT_BREAKABLE;
    }

    /** A door, a container, a block of Core's systems, anything that holds items, anything tagged protected, or anything unbreakable: never broken in a burglary. */
    static boolean protectedBlock(BlockState state, BlockEntity entity) {
        if (state.is(PROTECTED) || state.getBlock() instanceof DoorBlock || state.getBlock() instanceof TrapDoorBlock || state.is(BlockTags.FENCE_GATES)) return true;
        if (state.getDestroySpeed(null, BlockPos.ZERO) < 0) return true;
        if (entity != null && (ResidenceGuard.isContainer(entity) || entity instanceof PropertyCacheBlockEntity)) return true;
        ResourceLocation id = ForgeRegistries.BLOCKS.getKey(state.getBlock());
        return id != null && id.getNamespace().equals(UndergroundCity.MOD_ID) && !state.is(BREAKABLE);
    }

    static boolean glass(BlockState state) { return state.is(Tags.Blocks.GLASS) || state.is(Tags.Blocks.GLASS_PANES) || state.is(BlockTags.IMPERMEABLE); }

    /** A block of one of the furniture mods the rules name (a chair, a table, a lamp of that mod). */
    static boolean furniture(BlockState state) {
        ResourceLocation id = ForgeRegistries.BLOCKS.getKey(state.getBlock());
        return id != null && RULES.furnitureNamespaces().contains(id.getNamespace());
    }

    /** Part of the building's shell: next to the open air outside (a cell with the sky over it), or on the level of a floor or a ceiling (every six blocks from the street). */
    static boolean shell(ServerLevel level, CityBuildings.Building b, BlockPos pos) {
        if (Math.floorMod(pos.getY() - b.streetY(), CityBuildings.FLOOR) == 0) return true;
        for (Direction side : Direction.values()) {
            BlockPos n = pos.relative(side);
            if (level.getBlockState(n).isAir() && BuildingGuard.exposed(level, n)) return true;
        }
        return false;
    }

    private static boolean within(ServerLevel level, CityBuildings.Building b, BlockPos pos) {
        Record r = store(level.getServer()).records.get(b.key());
        return r == null || r.cells.size() < RULES.maxCellsPerBuilding() || r.cells.containsKey(pos.asLong());
    }

    // ------------------------------------------------------------------------------------------------ breaking

    static long breaks, dependents, restoredCells, restoredBuildings, skippedFilled, skippedHomes;

    /**
     * Breaks the block (and the other half of a bed, a door or a tall plant, and whatever stood on it and cannot stand without it), records each first-time cell, drops nothing. Returns how many
     * cells went. The caller has judged it {@link Verdict#ALLOWED}.
     */
    static int breakFor(ServerPlayer player, ServerLevel level, CityBuildings.Building b, BlockPos pos, long now) {
        Store store = store(level.getServer());
        Record record = store.records.computeIfAbsent(b.key(), k -> new Record(b.key(), level.dimension(), b.city(), b.chunkX(), b.chunkZ(), now));
        if (store.records.size() > RULES.maxBuildings()) forgetOldest(store, b.key());
        BlockState state = level.getBlockState(pos);
        List<BlockPos> group = new ArrayList<>();
        group.add(pos);
        BlockPos partner = partner(pos, state);
        if (partner != null && level.getBlockState(partner).getBlock() == state.getBlock()) group.add(partner);
        int removed = 0;
        for (BlockPos p : group) { record.capture(level, p); level.setBlock(p, Blocks.AIR.defaultBlockState(), Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE); removed++; }
        level.levelEvent(2001, pos, Block.getId(state));   // the break's particles and sound, as a hand would make
        // what stood on it, or hung from it, goes too (and is put back with it): a carpet, a torch, a picture lamp
        Deque<BlockPos> look = new ArrayDeque<>();
        for (BlockPos p : group) for (Direction side : Direction.values()) look.add(p.relative(side));
        int budget = 16;
        while (!look.isEmpty() && budget > 0) {
            BlockPos n = look.poll();
            BlockState s = level.getBlockState(n);
            if (s.isAir() || !b.contains(n) || s.canSurvive(level, n) || protectedBlock(s, level.getBlockEntity(n))) continue;
            budget--;
            record.capture(level, n);
            BlockPos other = partner(n, s);
            level.setBlock(n, Blocks.AIR.defaultBlockState(), Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE);
            if (other != null && level.getBlockState(other).getBlock() == s.getBlock()) { record.capture(level, other); level.setBlock(other, Blocks.AIR.defaultBlockState(), Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE); }
            removed++; dependents++;
            for (Direction side : Direction.values()) look.add(n.relative(side));
        }
        record.last = now;
        store.setDirty();
        breaks++;
        return removed;
    }

    /** The other half of a two-block thing (a bed, a door, a tall plant), or null. */
    static BlockPos partner(BlockPos pos, BlockState state) {
        if (state.getBlock() instanceof BedBlock && state.hasProperty(BedBlock.PART) && state.hasProperty(BedBlock.FACING)) {
            Direction facing = state.getValue(BedBlock.FACING);
            return state.getValue(BedBlock.PART) == BedPart.FOOT ? pos.relative(facing) : pos.relative(facing.getOpposite());
        }
        if (state.hasProperty(BlockStateProperties.DOUBLE_BLOCK_HALF) && (state.getBlock() instanceof DoublePlantBlock || state.getBlock() instanceof DoorBlock))
            return state.getValue(BlockStateProperties.DOUBLE_BLOCK_HALF) == DoubleBlockHalf.LOWER ? pos.above() : pos.below();
        return null;
    }

    // ------------------------------------------------------------------------------------------------ the record

    /** One building's broken cells: what each held before anybody broke it. */
    static final class Record {
        final long buildingKey;
        final ResourceKey<Level> dimension;
        final String city;
        final int chunkX, chunkZ;
        final long first;
        long last;
        final Map<Long, CompoundTag> cells = new LinkedHashMap<>();

        Record(long buildingKey, ResourceKey<Level> dimension, String city, int chunkX, int chunkZ, long first) {
            this.buildingKey = buildingKey; this.dimension = dimension; this.city = city; this.chunkX = chunkX; this.chunkZ = chunkZ; this.first = first; this.last = first;
        }

        /** Writes down what the cell holds now, the first time only (a cell broken twice keeps what it held before the first break). */
        void capture(ServerLevel level, BlockPos pos) {
            if (cells.containsKey(pos.asLong())) return;
            CompoundTag cell = new CompoundTag();
            cell.put("state", NbtUtils.writeBlockState(level.getBlockState(pos)));
            BlockEntity entity = level.getBlockEntity(pos);
            if (entity != null) cell.put("entity", entity.saveWithFullMetadata());
            cells.put(pos.asLong(), cell);
        }

        CompoundTag save() {
            CompoundTag tag = new CompoundTag();
            tag.putLong("building", buildingKey); tag.putString("dimension", dimension.location().toString()); tag.putString("city", city);
            tag.putInt("cx", chunkX); tag.putInt("cz", chunkZ); tag.putLong("first", first); tag.putLong("last", last);
            ListTag list = new ListTag();
            cells.forEach((pos, cell) -> { CompoundTag c = cell.copy(); c.putLong("pos", pos); list.add(c); });
            tag.put("cells", list);
            return tag;
        }

        static Record load(CompoundTag tag) {
            Record r = new Record(tag.getLong("building"), ResourceKey.create(Registries.DIMENSION, ResourceLocation.parse(tag.getString("dimension"))), tag.getString("city"),
                tag.getInt("cx"), tag.getInt("cz"), tag.getLong("first"));
            r.last = tag.getLong("last");
            ListTag list = tag.getList("cells", Tag.TAG_COMPOUND);
            for (int i = 0; i < list.size(); i++) {
                CompoundTag c = list.getCompound(i);
                long pos = c.getLong("pos");
                c.remove("pos");
                r.cells.put(pos, c);
            }
            return r;
        }
    }

    static final class Store extends SavedData {
        static final String NAME = "underground_city_burglary_damage";
        final Map<Long, Record> records = new LinkedHashMap<>();

        static Store load(CompoundTag tag) {
            Store s = new Store();
            ListTag list = tag.getList("records", Tag.TAG_COMPOUND);
            for (int i = 0; i < list.size(); i++) {
                try { Record r = Record.load(list.getCompound(i)); s.records.put(r.buildingKey, r); }
                catch (RuntimeException error) { LOGGER.warn("[UC] a burglary damage record could not be read and is skipped: {}", error.toString()); }
            }
            return s;
        }

        @Override
        public CompoundTag save(CompoundTag tag) {
            tag.putInt("schema_version", 1);
            ListTag list = new ListTag();
            records.values().forEach(r -> list.add(r.save()));
            tag.put("records", list);
            return tag;
        }
    }

    static Store store(MinecraftServer server) { return server.overworld().getDataStorage().computeIfAbsent(Store::load, Store::new, Store.NAME); }

    /** More buildings damaged than the record keeps: the oldest is put back at once when it can be, else forgotten (its blocks stay as they are). */
    private static void forgetOldest(Store store, long keep) {
        Record oldest = store.records.values().stream().filter(r -> r.buildingKey != keep).min(Comparator.comparingLong(r -> r.last)).orElse(null);
        if (oldest == null) return;
        store.records.remove(oldest.buildingKey);
        LOGGER.warn("[UC] burglary damage: more than {} buildings damaged at once; the oldest record ({} cells in {}) is forgotten", RULES.maxBuildings(), oldest.cells.size(), oldest.city);
    }

    // ------------------------------------------------------------------------------------------------ putting it back

    @SubscribeEvent
    public void tick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END || event.getServer().getTickCount() % 100 != 37) return;
        try { restoreDue(event.getServer()); }
        catch (RuntimeException error) { LOGGER.error("Putting burglary damage back failed", error); }
    }

    /** One look at every record: those whose wait is over, whose chunk is loaded and that nobody is in are put back. Returns how many buildings were put back. Visible for the tests. */
    static int restoreDue(MinecraftServer server) {
        Store store = store(server);
        if (store.records.isEmpty()) return 0;
        long now = FIXED_NOW != null ? FIXED_NOW : WorkOrderLedger.get(server).clock();
        long wait = RULES.restoreMinutes() * 60L * 20L;
        int done = 0;
        for (var it = store.records.values().iterator(); it.hasNext(); ) {
            Record r = it.next();
            if (now - r.last < wait) continue;
            ServerLevel level = server.getLevel(r.dimension);
            if (level == null || level.getChunkSource().getChunkNow(r.chunkX, r.chunkZ) == null) continue;   // never loaded for this; put back when somebody is near
            boolean overdue = now - r.last >= 2 * wait;
            if (!overdue && occupied(level, r)) continue;
            restore(level, r);
            it.remove();
            store.setDirty();
            done++;
        }
        return done;
    }

    /** True when a player is in or at the building (its chunk, from below its cellar to above its roof, and two blocks round). */
    static boolean occupied(ServerLevel level, Record r) {
        Optional<CityBuildings.Building> b = CityBuildings.INSTANCE.ofChunk(level, r.chunkX, r.chunkZ);
        int minY = b.map(CityBuildings.Building::minY).orElse(level.getMinBuildHeight()), maxY = b.map(CityBuildings.Building::maxY).orElse(level.getMaxBuildHeight());
        AABB box = new AABB(r.chunkX * 16 - 2, minY - 2, r.chunkZ * 16 - 2, r.chunkX * 16 + 18, maxY + 2, r.chunkZ * 16 + 18);
        return !level.getEntitiesOfClass(net.minecraft.world.entity.player.Player.class, box, p -> !p.isSpectator()).isEmpty();
    }

    /** Puts a building's recorded cells back: lowest first, only where the cell is still empty, never inside a home somebody holds, never where somebody stands. */
    static void restore(ServerLevel level, Record r) {
        CrewLedger ledger = CrewService.ledger(level.getServer());
        String dim = level.dimension().location().toString();
        List<Long> order = new ArrayList<>(r.cells.keySet());
        order.sort(Comparator.comparingInt(p -> BlockPos.of(p).getY()));
        int put = 0;
        for (long key : order) {
            BlockPos pos = BlockPos.of(key);
            CompoundTag cell = r.cells.get(key);
            BlockState current = level.getBlockState(pos);
            if (!current.isAir() && !current.canBeReplaced()) { skippedFilled++; continue; }
            if (ledger != null && !ledger.faulted()) {
                CrewLedger.Residence home = ledger.residenceAt(dim, pos);
                if (home != null && home.owned()) { skippedHomes++; continue; }
            }
            BlockState state = NbtUtils.readBlockState(level.holderLookup(Registries.BLOCK), cell.getCompound("state"));
            if (state.isAir()) continue;
            if (!level.getEntitiesOfClass(Entity.class, new AABB(pos), e -> e instanceof net.minecraft.world.entity.LivingEntity).isEmpty() && state.blocksMotion()) { skippedFilled++; continue; }
            level.setBlock(pos, state, Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE);
            if (cell.contains("entity")) {
                BlockEntity entity = level.getBlockEntity(pos);
                if (entity != null) { entity.load(cell.getCompound("entity")); entity.setChanged(); level.sendBlockUpdated(pos, state, state, Block.UPDATE_CLIENTS); }
            }
            put++;
        }
        restoredCells += put;
        restoredBuildings++;
        LOGGER.info("[UC-BURGLARY] put back {} of {} broken cells in a building of {} at chunk {} {}", put, r.cells.size(), r.city, r.chunkX, r.chunkZ);
    }

    /** Test seam: the restore pass sees this clock instead of the play clock (null for the real one). */
    static void fixClockForTest(Long now) { FIXED_NOW = now; }

    static String status(MinecraftServer server) {
        Store store = store(server);
        int cells = 0;
        for (Record r : store.records.values()) cells += r.cells.size();
        return "burglary damage: " + store.records.size() + " building(s) waiting with " + cells + " cell(s); broken " + breaks + " (" + dependents + " that stood on them); put back " + restoredCells + " cell(s) in "
            + restoredBuildings + " building(s), skipped " + skippedFilled + " filled and " + skippedHomes + " in homes";
    }
}
