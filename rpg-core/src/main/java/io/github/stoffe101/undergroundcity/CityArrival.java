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
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.TicketType;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.SimpleJsonResourceReloadListener;
import net.minecraft.util.profiling.ProfilerFiller;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BaseFireBlock;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CampfireBlock;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.MagmaBlock;
import net.minecraft.world.level.block.SweetBerryBushBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.common.util.FakePlayer;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.server.ServerStartedEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Where a new citizen starts (D-187). Before registration everybody arrives at the world's landing (Founders Row in a new archipelago world, the neutral arrival); once a citizen has chosen a
 * home city and a trade, the server moves them, once, to that city's welcome: Founders Row's gate for Ironhaven (its arrival quarter, inside its civic border, where the employers keep their
 * desks), the street outside the shop with a public Operations Terminal for the other cities (the General Store in Valewood, the Corner Store in Blackwater), or a street near the city's middle
 * where that building does not exist. The welcome of each city is data ({@code data/underground_city/arrivals/<city>.json}).
 *
 * <p>The move is server-authoritative and happens exactly once per registration: registering writes a pending arrival into the world's record ({@value Store#NAME}) before anything else, the
 * move clears it and writes where the citizen was put, and a pending arrival survives a crash or a logout and is completed at the next login. A citizen registered before this existed has no
 * pending arrival and is never moved; a reconnect never moves anybody. The target chunks are loaded with a ticket, never generated on the server thread in one go; the standing place is
 * open ground under the sky (never in a wall, in water, in the air or inside a building), checked on the blocks actually there. The citizen's own respawn point is set there only when they
 * have none (a bed or an operator's choice is never replaced), and the world spawn is never touched.
 */
public final class CityArrival {
    private static final Logger LOGGER = LogUtils.getLogger();
    static final TicketType<ChunkPos> TICKET = TicketType.create("underground_city_arrival", Comparator.comparingLong(ChunkPos::toLong), 20 * 60);
    /** How long to wait for the welcome's chunks to load before moving the citizen anyway (the chunks are then loaded synchronously). */
    static final int LOAD_PATIENCE_TICKS = 20 * 20;
    /** A registration closes the screen first: the move waits this long so the citizen sees the welcome message after the screen, not under it. */
    static final int SETTLE_TICKS = 20;

    /** One city's welcome as data: a district to arrive at (its gate), or buildings to arrive in front of (Lost Cities building types, in order of preference), and the words for it. */
    public record Welcome(String city, String district, List<String> buildings, String label, String hint, int searchChunks) {
        public Welcome {
            buildings = List.copyOf(buildings);
            if (searchChunks < 1 || searchChunks > 40) throw new IllegalArgumentException("search_chunks must be 1 to 40 for " + city);
        }
    }

    /** Where the welcome of a city was found in this world (kept so it is looked for once): the chunk, what stands there, and (once seen) the door or gate to stand in front of. */
    record Site(String city, int chunkX, int chunkZ, String kind, String label, BlockPos door) {
        Site withDoor(BlockPos at) { return new Site(city, chunkX, chunkZ, kind, label, at); }
    }

    /** Where a citizen was put: the position, the city, the game time and whether it was the welcome itself or a fallback. */
    record Placed(String city, BlockPos pos, long gameTime, String how) {}

    private static volatile Map<String, Welcome> WELCOMES = Map.of();

    public static final class Loader extends SimpleJsonResourceReloadListener {
        public static final Loader INSTANCE = new Loader();
        private Loader() { super(new Gson(), "arrivals"); }

        @Override
        protected void apply(Map<ResourceLocation, JsonElement> data, ResourceManager resources, ProfilerFiller profiler) {
            Map<String, Welcome> next = new LinkedHashMap<>();
            for (var entry : data.entrySet().stream().sorted(Map.Entry.comparingByKey()).toList()) {
                Welcome welcome = parse(entry.getValue().getAsJsonObject());
                next.put(welcome.city(), welcome);
            }
            WELCOMES = Map.copyOf(next);
            LOGGER.info("Loaded the arrival welcomes of {} cities", next.size());
        }
    }

    static Welcome parse(JsonObject json) {
        if (!json.has("schema_version") || json.get("schema_version").getAsInt() != 1) throw new IllegalArgumentException("Unsupported arrival schema");
        String city = json.get("city").getAsString();
        if (!ProgressionRules.validCity(city)) throw new IllegalArgumentException("An arrival names the unknown city " + city);
        List<String> buildings = new ArrayList<>();
        if (json.has("buildings")) for (JsonElement b : json.getAsJsonArray("buildings")) {
            String id = b.getAsString();
            ResourceLocation.parse(id);
            buildings.add(id);
        }
        if (buildings.size() > 8) throw new IllegalArgumentException("At most 8 arrival buildings for " + city);
        String district = json.has("district") ? json.get("district").getAsString() : "";
        return new Welcome(city, district, buildings, json.has("label") ? json.get("label").getAsString() : "the street", json.has("hint") ? json.get("hint").getAsString() : "",
            json.has("search_chunks") ? json.get("search_chunks").getAsInt() : 16);
    }

    static Optional<Welcome> welcome(String city) { return Optional.ofNullable(WELCOMES.get(city)); }

    /** Test seam. */
    static Map<String, Welcome> welcomesForTest() { return WELCOMES; }
    static void replaceWelcomesForTest(Map<String, Welcome> replacement) { WELCOMES = Map.copyOf(replacement); }

    // ------------------------------------------------------------------------------------------------ the record

    static final class Store extends SavedData {
        static final String NAME = "underground_city_arrivals";
        /** Citizens whose move is still to happen, and the city they chose. */
        final Map<UUID, String> pending = new LinkedHashMap<>();
        /** Citizens who were moved, and where (kept for the audit and so a move is never repeated). */
        final Map<UUID, Placed> placed = new LinkedHashMap<>();
        /** The welcome of each city found in this world. */
        final Map<String, Site> sites = new LinkedHashMap<>();

        static Store load(CompoundTag tag) {
            Store s = new Store();
            ListTag pending = tag.getList("pending", Tag.TAG_COMPOUND);
            for (int i = 0; i < pending.size(); i++) {
                CompoundTag p = pending.getCompound(i);
                if (p.hasUUID("player") && ProgressionRules.validCity(p.getString("city"))) s.pending.put(p.getUUID("player"), p.getString("city"));
            }
            ListTag placed = tag.getList("placed", Tag.TAG_COMPOUND);
            for (int i = 0; i < placed.size(); i++) {
                CompoundTag p = placed.getCompound(i);
                if (p.hasUUID("player")) s.placed.put(p.getUUID("player"), new Placed(p.getString("city"), BlockPos.of(p.getLong("pos")), p.getLong("time"), p.getString("how")));
            }
            ListTag sites = tag.getList("sites", Tag.TAG_COMPOUND);
            for (int i = 0; i < sites.size(); i++) {
                CompoundTag p = sites.getCompound(i);
                String city = p.getString("city");
                if (!ProgressionRules.validCity(city)) continue;
                s.sites.put(city, new Site(city, p.getInt("cx"), p.getInt("cz"), p.getString("kind"), p.getString("label"), p.contains("door") ? BlockPos.of(p.getLong("door")) : null));
            }
            return s;
        }

        @Override
        public CompoundTag save(CompoundTag tag) {
            tag.putInt("schema_version", 1);
            ListTag pending = new ListTag();
            this.pending.forEach((id, city) -> { CompoundTag p = new CompoundTag(); p.putUUID("player", id); p.putString("city", city); pending.add(p); });
            tag.put("pending", pending);
            ListTag placed = new ListTag();
            this.placed.forEach((id, at) -> {
                CompoundTag p = new CompoundTag();
                p.putUUID("player", id); p.putString("city", at.city()); p.putLong("pos", at.pos().asLong()); p.putLong("time", at.gameTime()); p.putString("how", at.how());
                placed.add(p);
            });
            tag.put("placed", placed);
            ListTag sites = new ListTag();
            this.sites.values().forEach(site -> {
                CompoundTag p = new CompoundTag();
                p.putString("city", site.city()); p.putInt("cx", site.chunkX()); p.putInt("cz", site.chunkZ()); p.putString("kind", site.kind()); p.putString("label", site.label());
                if (site.door() != null) p.putLong("door", site.door().asLong());
                sites.add(p);
            });
            tag.put("sites", sites);
            return tag;
        }
    }

    static Store store(MinecraftServer server) { return server.overworld().getDataStorage().computeIfAbsent(Store::load, Store::new, Store.NAME); }

    // ------------------------------------------------------------------------------------------------ the registration hook

    /** Called once a citizen record has been created: the move is written down before anything else, so it happens even after a crash. A fake player is never moved. */
    static void schedule(ServerPlayer player, String city) {
        if (player instanceof FakePlayer) return;
        if (scheduleId(player.server, player.getUUID(), city)) WAITING.put(player.getUUID(), new Attempt(player.server.getTickCount() + SETTLE_TICKS));
    }

    /** Writes a pending arrival for an account that has never been moved; false (and nothing written) when it has been moved before or one is already pending. */
    static boolean scheduleId(MinecraftServer server, UUID id, String city) {
        Store s = store(server);
        if (s.placed.containsKey(id) || s.pending.containsKey(id) || !ProgressionRules.validCity(city)) return false;
        s.pending.put(id, city);
        s.setDirty();
        return true;
    }

    /** True when this citizen still has a move to come (for the status line and the tests). */
    static boolean pending(MinecraftServer server, UUID id) { return store(server).pending.containsKey(id); }

    /** Where this citizen was put, if the server moved them. */
    static Optional<Placed> placed(MinecraftServer server, UUID id) { return Optional.ofNullable(store(server).placed.get(id)); }

    /** The welcome of a city in this world, if it has been found (the Journey points a new citizen at its door). */
    static Optional<Site> site(MinecraftServer server, String city) { return Optional.ofNullable(store(server).sites.get(city)); }

    // ------------------------------------------------------------------------------------------------ the move

    /** A move in progress for an online citizen: when to look again, the chunks asked for and since when. */
    private static final class Attempt {
        long notBefore;
        ChunkPos asked;
        long askedAt;
        Attempt(long notBefore) { this.notBefore = notBefore; }
    }
    private static final Map<UUID, Attempt> WAITING = new HashMap<>();

    /** A place to stand, which way to face, how it was chosen and the door or gate it is in front of (null when none). */
    record Stand(BlockPos pos, float yaw, String how, BlockPos door) {
        Stand(BlockPos pos, float yaw, String how) { this(pos, yaw, how, null); }
    }

    @SubscribeEvent
    public void tick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END || event.getServer().getTickCount() % 5 != 0) return;
        MinecraftServer server = event.getServer();
        Store s = store(server);
        if (s.pending.isEmpty()) { WAITING.clear(); return; }
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            String city = s.pending.get(player.getUUID());
            if (city == null) continue;
            Attempt attempt = WAITING.computeIfAbsent(player.getUUID(), id -> new Attempt(server.getTickCount() + SETTLE_TICKS));
            if (server.getTickCount() < attempt.notBefore) continue;
            try {
                step(server, s, player, city, attempt);
            } catch (RuntimeException error) {
                LOGGER.error("[UC-ARRIVAL] moving {} to {} failed; they stay where they are", player.getGameProfile().getName(), city, error);
                finish(s, player, city, player.blockPosition(), "failed: " + error.getClass().getSimpleName());
                player.sendSystemMessage(Component.literal("The way to " + title(city) + " could not be found just now; you stay where you are. An operator can move you with /uc admin relocate."));
            }
        }
        WAITING.keySet().removeIf(id -> !s.pending.containsKey(id) || server.getPlayerList().getPlayer(id) == null);
    }

    private static void step(MinecraftServer server, Store s, ServerPlayer player, String city, Attempt attempt) {
        CityRegions.Region region = CityRegions.INSTANCE.region(city).orElse(null);
        if (region == null) {
            // no geography for this city in this world (a test world, a world without the city package): nowhere to go, and nothing to repeat
            finish(s, player, city, player.blockPosition(), "no region");
            return;
        }
        ServerLevel level = server.getLevel(ResourceKey.create(Registries.DIMENSION, ResourceLocation.parse(region.dimension())));
        if (level == null) { finish(s, player, city, player.blockPosition(), "no level"); return; }
        Site site = s.sites.get(city);
        if (site == null) {
            site = find(level, city, region);
            s.sites.put(city, site);
            s.setDirty();
            LOGGER.info("[UC-ARRIVAL] the welcome of {} is {} at chunk {} {}", city, site.kind(), site.chunkX(), site.chunkZ());
        }
        if (alreadyThere(level, player, region, site)) {
            // registered at the welcome itself (the Row's registrar) or already in the streets of the city chosen: nothing to move, and nothing to repeat; their own respawn point is
            // still the welcome (when its ground is loaded and they have none), so a death brings them back to it rather than to the world's spawn
            if (player.getRespawnPosition() == null && level.getChunkSource().getChunkNow(site.chunkX(), site.chunkZ()) != null)
                locate(level, site).ifPresent(st -> player.setRespawnPosition(level.dimension(), st.pos(), st.yaw(), true, false));
            finish(s, player, city, player.blockPosition(), "already there");
            return;
        }
        ChunkPos chunk = new ChunkPos(site.chunkX(), site.chunkZ());
        if (attempt.asked == null) {
            level.getChunkSource().addRegionTicket(TICKET, chunk, 2, chunk);
            attempt.asked = chunk;
            attempt.askedAt = server.getTickCount();
        }
        boolean loaded = true;
        for (int dx = -1; dx <= 1 && loaded; dx++) for (int dz = -1; dz <= 1 && loaded; dz++) loaded = level.getChunkSource().getChunkNow(chunk.x + dx, chunk.z + dz) != null;
        if (!loaded && server.getTickCount() - attempt.askedAt < LOAD_PATIENCE_TICKS) return;
        if (!loaded) for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++) level.getChunk(chunk.x + dx, chunk.z + dz);
        Optional<Stand> stand = locate(level, site);
        if (stand.isPresent() && site.door() == null && stand.get().door() != null) {
            site = site.withDoor(stand.get().door());
            s.sites.put(city, site);
            s.setDirty();
        }
        if (stand.isEmpty()) {
            // the welcome's ground is not standable (rubble, water, a building filling the chunk): any open street ground in its city, near its middle
            stand = streetNear(level, region.centerChunkX(), region.centerChunkZ(), 6);
        }
        if (stand.isEmpty()) {
            LOGGER.warn("[UC-ARRIVAL] no safe ground found for {} in {}; they stay at {}", player.getGameProfile().getName(), city, player.blockPosition().toShortString());
            finish(s, player, city, player.blockPosition(), "no safe ground");
            player.sendSystemMessage(Component.literal("No safe ground was found in " + title(city) + " just now; you stay where you are. An operator can move you with /uc admin relocate."));
            return;
        }
        move(player, level, stand.get());
        finish(s, player, city, stand.get().pos(), stand.get().how());
        level.getChunkSource().removeRegionTicket(TICKET, chunk, 2, chunk);
        greet(player, city, site);
    }

    /**
     * True when the citizen already stands where the move would take them: inside the welcome district (or within {@value #NEAR_WELCOME} blocks of its gate), or inside the chosen city's
     * own circle (its streets), in its dimension.
     */
    static boolean alreadyThere(ServerLevel level, ServerPlayer player, CityRegions.Region region, Site site) {
        if (player.serverLevel() != level) return false;
        if (site.kind().startsWith("district:")) {
            String id = site.kind().substring("district:".length());
            BlockPos origin = DistrictBuilder.originOf(level, id);
            DistrictCatalog.District district = DistrictCatalog.INSTANCE.find(id);
            if (origin != null && district != null) {
                var box = new net.minecraft.world.phys.AABB(origin.offset(district.min()).offset(-8, -8, -8), origin.offset(district.max()).offset(9, 9, 9));
                if (box.contains(player.position())) return true;
            }
            return site.door() != null && site.door().distSqr(player.blockPosition()) <= (double) NEAR_WELCOME * NEAR_WELCOME;
        }
        return region.distanceSquared(player.blockPosition()) <= (double) region.cityRadius() * region.cityRadius();
    }
    static final int NEAR_WELCOME = 32;

    /** Puts the player on the place, safely: off any vehicle, no fall carried over, facing the way given; sets their own respawn point there only when they have none. */
    static void move(ServerPlayer player, ServerLevel level, Stand stand) {
        player.stopRiding();
        player.teleportTo(level, stand.pos().getX() + 0.5, stand.pos().getY(), stand.pos().getZ() + 0.5, stand.yaw(), 0f);
        player.setDeltaMovement(Vec3.ZERO);
        player.fallDistance = 0;
        if (player.getRespawnPosition() == null) player.setRespawnPosition(level.dimension(), stand.pos(), stand.yaw(), true, false);
    }

    private static void finish(Store s, ServerPlayer player, String city, BlockPos at, String how) {
        s.pending.remove(player.getUUID());
        s.placed.put(player.getUUID(), new Placed(city, at, player.serverLevel().getGameTime(), how));
        s.setDirty();
        WAITING.remove(player.getUUID());
        LOGGER.info("[UC-ARRIVAL] {} arrived for {} at {} ({})", player.getGameProfile().getName(), city, at.toShortString(), how);
    }

    private static void greet(ServerPlayer player, String city, Site site) {
        Welcome welcome = WELCOMES.get(city);
        String where = site.kind().startsWith("district:") || site.kind().startsWith("building:") ? site.label() : "a street near the middle of " + title(city);
        String hint = welcome == null || welcome.hint().isEmpty() ? "Your welcome pack holds an Operations Terminal: set it down and open WORK for your first order." : welcome.hint();
        player.sendSystemMessage(Component.literal("Welcome to " + title(city) + ". You are at " + where + ". " + hint));
        Notice.send(player, Notice.Kind.INFO, "Welcome to " + title(city), "You are at " + where + ".");
    }

    // ------------------------------------------------------------------------------------------------ finding the welcome

    /** The welcome of a city in this world: its district, the nearest preferred building to its middle, or the nearest street chunk to its middle. Asks only Lost Cities' plan (nothing is generated). */
    static Site find(ServerLevel level, String city, CityRegions.Region region) {
        Welcome welcome = WELCOMES.get(city);
        if (welcome != null && !welcome.district().isEmpty()) {
            BlockPos origin = DistrictBuilder.originOf(level, welcome.district());
            DistrictCatalog.District district = DistrictCatalog.INSTANCE.find(welcome.district());
            if (origin != null && district != null) {
                int width = district.max().getX() - district.min().getX() + 1, depth = district.max().getZ() - district.min().getZ() + 1;
                BlockPos gate = origin.offset(district.min().getX() + width / 2, 0, depth + 2);
                return new Site(city, gate.getX() >> 4, gate.getZ() >> 4, "district:" + welcome.district(), welcome.label(), gate);
            }
        }
        int radius = welcome == null ? 16 : welcome.searchChunks();
        int cityChunks = Math.max(1, region.cityRadius() / 16 - 1);
        radius = Math.min(radius, cityChunks);
        if (welcome != null && !welcome.buildings().isEmpty() && LostCitiesLink.ready()) {
            for (String type : welcome.buildings()) {
                for (int r = 0; r <= radius; r++) {
                    for (int dx = -r; dx <= r; dx++) for (int dz = -r; dz <= r; dz++) {
                        if (Math.max(Math.abs(dx), Math.abs(dz)) != r) continue;
                        int cx = region.centerChunkX() + dx, cz = region.centerChunkZ() + dz;
                        Optional<LostCitiesLink.ChunkSample> sample = LostCitiesLink.chunk(level, cx, cz);
                        if (sample.isPresent() && sample.get().city() && type.equals(sample.get().building())) return new Site(city, cx, cz, "building:" + type, welcome.label(), null);
                    }
                }
            }
        }
        if (LostCitiesLink.ready()) {
            for (int r = 0; r <= radius; r++) {
                for (int dx = -r; dx <= r; dx++) for (int dz = -r; dz <= r; dz++) {
                    if (Math.max(Math.abs(dx), Math.abs(dz)) != r) continue;
                    int cx = region.centerChunkX() + dx, cz = region.centerChunkZ() + dz;
                    Optional<LostCitiesLink.ChunkSample> sample = LostCitiesLink.chunk(level, cx, cz);
                    if (sample.isPresent() && street(sample.get())) return new Site(city, cx, cz, "street", "a street near the middle of " + title(city), null);
                }
            }
        }
        return new Site(city, region.centerChunkX(), region.centerChunkZ(), "centre", "the middle of " + title(city), null);
    }

    /** A city chunk with no building and no highway over it: a street, a square or a park. */
    static boolean street(LostCitiesLink.ChunkSample sample) {
        return sample.city() && (sample.building() == null || sample.building().isEmpty()) && sample.buildingId().isEmpty() && sample.highwayLevel() < 0;
    }

    /** Where to stand at a site, on the blocks that are there now (the site's chunks must be loaded). */
    static Optional<Stand> locate(ServerLevel level, Site site) {
        if (site.kind().startsWith("district:") && site.door() != null) return safeStand(level, site.door(), 6, 4, 4).map(p -> new Stand(p, 180f, "at the district's gate", site.door()));
        if (site.kind().startsWith("building:")) {
            Optional<Stand> front = inFrontOfDoor(level, site);
            if (front.isPresent()) return front;
        }
        return streetNear(level, site.chunkX(), site.chunkZ(), 1);
    }

    /**
     * Outside the street door of the welcome building: the ground-floor exterior door whose open side is the sky, then the safe ground nearest a point four blocks out from it, facing the
     * door. The door is read from the generated blocks, so a building whose door is elsewhere than its plan's still works.
     */
    static Optional<Stand> inFrontOfDoor(ServerLevel level, Site site) {
        int street = streetHeight(level, site.chunkX(), site.chunkZ());
        List<BlockPos> doors = new ArrayList<>();
        int x0 = site.chunkX() << 4, z0 = site.chunkZ() << 4;
        for (int y = street - 1; y <= street + 3; y++) for (int x = 0; x < 16; x++) for (int z = 0; z < 16; z++) {
            BlockPos pos = new BlockPos(x0 + x, y, z0 + z);
            BlockState state = level.getBlockState(pos);
            if (!(state.getBlock() instanceof DoorBlock) || state.getValue(DoorBlock.HALF) != DoubleBlockHalf.LOWER) continue;
            if (BuildingGuard.exterior(level, pos, state)) doors.add(pos);
        }
        BlockPos preferred = site.door();
        doors.sort(Comparator.comparingDouble(d -> preferred == null ? 0 : d.distSqr(preferred)));
        for (BlockPos door : doors) {
            BlockState state = level.getBlockState(door);
            Direction facing = state.getValue(DoorBlock.FACING);
            boolean front = BuildingGuard.leadsOut(level, door, facing), back = BuildingGuard.leadsOut(level, door, facing.getOpposite());
            if (front == back) continue;
            Direction out = front ? facing : facing.getOpposite();
            BlockPos aim = door.relative(out, 4);
            Optional<BlockPos> stand = safeStand(level, aim, 4, 2, 3);
            if (stand.isEmpty()) continue;
            Direction back2 = out.getOpposite();
            float yaw = (float) Math.toDegrees(Math.atan2(-back2.getStepX(), back2.getStepZ()));
            return Optional.of(new Stand(stand.get(), yaw, "in front of the " + out.getName() + " door", door));
        }
        return Optional.empty();
    }

    /** Open ground under the sky in a street chunk near the given chunk (the chunks looked at must be loaded; nothing is generated here). */
    static Optional<Stand> streetNear(ServerLevel level, int chunkX, int chunkZ, int radius) {
        for (int r = 0; r <= radius; r++) {
            for (int dx = -r; dx <= r; dx++) for (int dz = -r; dz <= r; dz++) {
                if (Math.max(Math.abs(dx), Math.abs(dz)) != r) continue;
                int cx = chunkX + dx, cz = chunkZ + dz;
                if (level.getChunkSource().getChunkNow(cx, cz) == null) continue;
                Optional<LostCitiesLink.ChunkSample> sample = LostCitiesLink.ready() ? LostCitiesLink.chunk(level, cx, cz) : Optional.empty();
                if (sample.isPresent() && sample.get().city() && !street(sample.get())) continue;
                int y = sample.isPresent() ? streetHeight(level, cx, cz) : level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, (cx << 4) + 8, (cz << 4) + 8);
                Optional<BlockPos> stand = safeStand(level, new BlockPos((cx << 4) + 8, y, (cz << 4) + 8), 7, 3, 3);
                if (stand.isPresent()) return Optional.of(new Stand(stand.get(), 0f, "street " + cx + " " + cz));
            }
        }
        return Optional.empty();
    }

    /** The street height Lost Cities gives the chunk's city level; the terrain's own height outside a city or without Lost Cities. */
    static int streetHeight(ServerLevel level, int cx, int cz) {
        if (LostCitiesLink.ready()) {
            Optional<LostCitiesLink.ChunkSample> sample = LostCitiesLink.chunk(level, cx, cz);
            if (sample.isPresent() && sample.get().city()) {
                int h = LostCitiesLink.realHeight(level, sample.get().cityLevel());
                if (h > level.getMinBuildHeight()) return h;
            }
        }
        return level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, (cx << 4) + 8, (cz << 4) + 8);
    }

    /**
     * The safe place to stand nearest {@code near}: within {@code radius} blocks across and from {@code below} under to {@code above} over it, a cell whose floor is solid and not a hazard,
     * whose feet and head cells are open and dry, and that is under the open sky (so never inside a building or a wall). Only loaded chunks are read. Nearest across first, then nearest in height.
     */
    static Optional<BlockPos> safeStand(ServerLevel level, BlockPos near, int radius, int below, int above) {
        List<int[]> offsets = new ArrayList<>();
        for (int dx = -radius; dx <= radius; dx++) for (int dz = -radius; dz <= radius; dz++) offsets.add(new int[]{dx, dz});
        offsets.sort(Comparator.comparingInt(o -> o[0] * o[0] + o[1] * o[1]));
        for (int[] o : offsets) {
            for (int k = 0; k <= Math.max(below, above); k++) {
                for (int sign : new int[]{1, -1}) {
                    int dy = k * sign;
                    if (dy > above || -dy > below || (k == 0 && sign < 0)) continue;
                    BlockPos pos = near.offset(o[0], dy, o[1]);
                    if (standable(level, pos)) return Optional.of(pos);
                }
            }
        }
        return Optional.empty();
    }

    /** One cell a person can stand in: a solid, safe floor, open dry feet and head cells, the sky above. Unloaded chunks are never standable. */
    static boolean standable(ServerLevel level, BlockPos feet) {
        if (level.getChunkSource().getChunkNow(feet.getX() >> 4, feet.getZ() >> 4) == null) return false;
        if (feet.getY() <= level.getMinBuildHeight() + 1 || feet.getY() >= level.getMaxBuildHeight() - 2) return false;
        BlockPos floorPos = feet.below();
        BlockState floor = level.getBlockState(floorPos);
        if (!floor.isFaceSturdy(level, floorPos, Direction.UP) || !floor.getFluidState().isEmpty()) return false;
        if (floor.getBlock() instanceof LeavesBlock || floor.getBlock() instanceof MagmaBlock || floor.getBlock() instanceof CampfireBlock) return false;
        for (BlockPos cell : new BlockPos[]{feet, feet.above()}) {
            BlockState state = level.getBlockState(cell);
            if (!state.getCollisionShape(level, cell).isEmpty() || !state.getFluidState().isEmpty()) return false;
            if (state.getBlock() instanceof BaseFireBlock || state.getBlock() instanceof SweetBerryBushBlock || state.is(Blocks.POWDER_SNOW) || state.is(Blocks.COBWEB)) return false;
        }
        // open sky: nothing that blocks movement above the head (the heightmap is kept with every block change; sky light is worked out later, off the server thread), or nothing within
        // SKY_REACH blocks over it (a roof is lower than that; something far above, a bridge deck or a test's neighbour, is not a building round the person)
        if (feet.getY() + 2 >= level.getHeight(Heightmap.Types.MOTION_BLOCKING, feet.getX(), feet.getZ())) return true;
        BlockPos.MutableBlockPos above = new BlockPos.MutableBlockPos();
        for (int dy = 2; dy <= SKY_REACH; dy++) {
            above.set(feet.getX(), feet.getY() + dy, feet.getZ());
            BlockState state = level.getBlockState(above);
            if (state.blocksMotion() || !state.getFluidState().isEmpty()) return false;
        }
        return true;
    }
    static final int SKY_REACH = 24;

    /** An operator's move of a registered citizen to their city's welcome (or another city's), for debugging: the same search and safety checks, no pending record, never a second registration. */
    static Optional<Stand> relocate(ServerPlayer player, String city) {
        CityRegions.Region region = CityRegions.INSTANCE.region(city).orElse(null);
        if (region == null) return Optional.empty();
        ServerLevel level = player.server.getLevel(ResourceKey.create(Registries.DIMENSION, ResourceLocation.parse(region.dimension())));
        if (level == null) return Optional.empty();
        Store s = store(player.server);
        Site site = s.sites.get(city);
        if (site == null) { site = find(level, city, region); s.sites.put(city, site); s.setDirty(); }
        for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++) level.getChunk(site.chunkX() + dx, site.chunkZ() + dz);
        Optional<Stand> stand = locate(level, site);
        if (stand.isEmpty()) {
            for (int dx = -2; dx <= 2; dx++) for (int dz = -2; dz <= 2; dz++) level.getChunk(region.centerChunkX() + dx, region.centerChunkZ() + dz);
            stand = streetNear(level, region.centerChunkX(), region.centerChunkZ(), 2);
        }
        stand.ifPresent(st -> {
            player.stopRiding();
            player.teleportTo(level, st.pos().getX() + 0.5, st.pos().getY(), st.pos().getZ() + 0.5, st.yaw(), 0f);
            player.setDeltaMovement(Vec3.ZERO);
            player.fallDistance = 0;
        });
        return stand;
    }

    @SubscribeEvent
    public void started(ServerStartedEvent event) {
        WAITING.clear();
        // the welcomes are looked for before anybody joins, while Lost Cities is not generating (a question asked while it generates waits on its workers, D-167)
        MinecraftServer server = event.getServer();
        Store s = store(server);
        if (!LostCitiesLink.ready()) return;
        long start = System.nanoTime();
        int found = 0;
        for (CityRegions.Region region : CityRegions.INSTANCE.all()) {
            Welcome welcome = WELCOMES.get(region.id());
            // a district's gate is read from the district's record when it is needed (on a new world's first start the district is built by another listener of this event)
            if (s.sites.containsKey(region.id()) || welcome == null || !welcome.district().isEmpty()) continue;
            ServerLevel level = server.getLevel(ResourceKey.create(Registries.DIMENSION, ResourceLocation.parse(region.dimension())));
            if (level == null || level.dimension() != Level.OVERWORLD) continue;
            Site site = find(level, region.id(), region);
            s.sites.put(region.id(), site);
            found++;
        }
        if (found > 0) {
            s.setDirty();
            LOGGER.info("[UC-ARRIVAL] found the welcomes of {} cities in {} ms: {}", found, (System.nanoTime() - start) / 1_000_000L, s.sites.values());
        }
    }

    static String title(String city) { return city == null || city.isEmpty() ? "" : Character.toUpperCase(city.charAt(0)) + city.substring(1); }
}
