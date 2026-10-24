package io.github.stoffe101.undergroundcity;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mojang.logging.LogUtils;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.SimpleJsonResourceReloadListener;
import net.minecraft.util.profiling.ProfilerFiller;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LanternBlock;
import net.minecraft.world.level.block.SlabBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.SlabType;
import net.minecraftforge.common.util.FakePlayer;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Civic projects (D-171): a city asks its people for something it needs, everybody who helps is paid for their part, and when the goal is reached the city changes where
 * everybody can see it. A project ({@code data/underground_city/civic_projects/<id>.json}) is fed by ordinary work orders carrying {@code civic}: each delivery pays its contributor
 * and raises their trust with the project's buyer (the city's council), and the same atomic settlement counts it towards the shared goal, so two players fill one project together and
 * a crash replays a contribution once. Orders carrying {@code requires_civic} open when it is finished.
 *
 * <p>When it is finished, its change is built in the world once, on the server thread, when the city's centre is loaded: market stalls with lanterns on an open street near the
 * centre, placed only into air on public street ground (never inside a building or a home), and remembered in the journal with where they stand, so a restart neither builds them twice
 * nor forgets them. Residents of the city mention it afterwards.
 */
@Mod.EventBusSubscriber(modid = UndergroundCity.MOD_ID)
public final class CivicProjects {
    private static final Logger LOGGER = LogUtils.getLogger();

    public record Project(String id, String title, String city, String summary, int goal, String built, String flavour) {}

    public static final Catalog CATALOG = new Catalog();

    public static final class Catalog extends SimpleJsonResourceReloadListener {
        private Map<String, Project> projects = Map.of();
        private Catalog() { super(new Gson(), "civic_projects"); }

        @Override
        protected void apply(Map<ResourceLocation, JsonElement> data, ResourceManager resources, ProfilerFiller profiler) {
            Map<String, Project> next = new LinkedHashMap<>();
            data.entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(entry -> {
                JsonObject json = entry.getValue().getAsJsonObject();
                if (json.get("schema_version").getAsInt() != 1) throw new IllegalArgumentException("Unsupported civic project schema: " + entry.getKey());
                int goal = json.get("goal").getAsInt();
                String built = json.get("builds").getAsString();
                if (goal < 1 || goal > 100 || !built.equals("market_stalls")) throw new IllegalArgumentException("Invalid civic project " + entry.getKey());
                next.put(entry.getKey().toString(), new Project(entry.getKey().toString(), json.get("title").getAsString(), json.get("city").getAsString(),
                    json.get("summary").getAsString(), goal, built, json.has("flavour_after") ? json.get("flavour_after").getAsString() : ""));
            });
            projects = Map.copyOf(next);
            LOGGER.info("Loaded {} civic projects", projects.size());
        }

        public Project find(String id) { return projects.get(id); }
        public List<Project> all() { return List.copyOf(projects.values()); }
    }

    private CivicProjects() {}

    /** Why an order with these terms may not be taken or delivered now, or null. */
    static String refusal(WorkOrderLedger orders, WorkOrder.Terms terms) {
        if (!terms.civic().isEmpty()) {
            Project p = CATALOG.find(terms.civic());
            if (p == null) return "This civic project is no longer running.";
            if (finished(orders, p.id())) return p.title() + " is done. Thank you.";
        }
        if (!terms.requiresCivic().isEmpty() && !finished(orders, terms.requiresCivic())) {
            Project p = CATALOG.find(terms.requiresCivic());
            return "This opens once " + (p == null ? "a civic project" : p.title()) + " is finished.";
        }
        return null;
    }

    static boolean finished(WorkOrderLedger orders, String project) { return orders.civic(project).getBoolean("finished"); }

    /** The fields a contribution's prepared record carries, so that its settlement counts it. */
    static void deliveryRecord(WorkOrder.Terms terms, CompoundTag extra) {
        Project p = terms.civic().isEmpty() ? null : CATALOG.find(terms.civic());
        if (p == null) return;
        extra.putString("civic", p.id());
        extra.putInt("civic_goal", p.goal());
    }

    /** After a contribution: tells everybody online how it stands, and builds the change when it was the last one. */
    static void afterSettlement(MinecraftServer server, WorkOrderLedger orders, String projectId) {
        Project p = CATALOG.find(projectId);
        if (p == null) return;
        CompoundTag state = orders.civic(projectId);
        boolean done = state.getBoolean("finished");
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            if (player instanceof FakePlayer) continue;
            if (done && !state.getBoolean("built")) Notice.send(player, Notice.Kind.STEP, p.title(), "Done: " + TerminalScreenText.capitalize(p.city()) + " thanks everybody who helped.");
            else if (!done) Notice.send(player, Notice.Kind.INFO, p.title(), state.getInt("count") + " of " + p.goal() + " contributions in.");
        }
        if (done && !state.getBoolean("built")) build(server, orders, p);
    }

    /** One line for a resident of {@code city} to say about a project there that is finished, or empty. */
    static Optional<String> flavour(MinecraftServer server, String city) {
        WorkOrderLedger orders = WorkOrderLedger.get(server);
        for (Project p : CATALOG.all()) if (p.city().equals(city) && !p.flavour().isEmpty() && orders.civic(p.id()).getBoolean("built")) return Optional.of(p.flavour());
        return Optional.empty();
    }

    /** Where a finished project's change stands (for the Places book and navigation), if it is built. */
    static Optional<BlockPos> builtAt(WorkOrderLedger orders, String projectId) {
        CompoundTag where = orders.civic(projectId).getCompound("where");
        return where.contains("anchor") ? Optional.of(NbtUtils.readBlockPos(where.getCompound("anchor"))) : Optional.empty();
    }

    @SubscribeEvent
    public static void tick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END || event.getServer().getTickCount() % 200 != 0) return;
        WorkOrderLedger orders = WorkOrderLedger.get(event.getServer());
        if (orders.faulted()) return;
        for (Project p : CATALOG.all()) {
            CompoundTag state = orders.civic(p.id());
            if (state.getBoolean("finished") && !state.getBoolean("built")) build(event.getServer(), orders, p);   // after a restart, or when the centre was not loaded before
        }
    }

    // ------------------------------------------------------------------------------------------------ the change in the world

    /** Builds a finished project's market stalls on an open street near the city's centre, once; does nothing until such a street is loaded. */
    static boolean build(MinecraftServer server, WorkOrderLedger orders, Project p) {
        ServerLevel level = server.overworld();
        var region = CityRegions.INSTANCE.all().stream().filter(r -> r.id().equals(p.city())).findFirst().orElse(null);
        if (region == null) return false;
        BlockPos at = spot(level, region.centerChunkX(), region.centerChunkZ());
        if (at == null) return false;
        ListTag placed = new ListTag();
        stall(level, at, false, placed);
        stall(level, at.east(4), true, placed);
        CompoundTag where = new CompoundTag();
        where.put("anchor", NbtUtils.writeBlockPos(at));
        where.put("blocks", placed);
        orders.markCivicBuilt(p.id(), where);
        LOGGER.info("[UC] civic project {} finished: market stalls built at {}", p.id(), at.toShortString());
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            if (player instanceof FakePlayer) continue;
            player.sendSystemMessage(Component.literal(p.title() + ": the stalls are up near " + TerminalScreenText.capitalize(p.city()) + "'s centre, at " + at.getX() + ", " + at.getZ() + "."));
        }
        return true;
    }

    /** A spot of seven by three free street ground, nearest the centre, in a loaded open street chunk that no building or home holds; null when none is loaded yet. */
    static BlockPos spot(ServerLevel level, int centreX, int centreZ) {
        CrewLedger homes = CrewService.ledger(level.getServer());
        String dimension = level.dimension().location().toString();
        BlockPos best = null;
        int bestDistance = Integer.MAX_VALUE;
        for (int dx = -6; dx <= 6; dx++) for (int dz = -6; dz <= 6; dz++) {
            int cx = centreX + dx, cz = centreZ + dz, distance = dx * dx + dz * dz;
            if (distance >= bestDistance || level.getChunkSource().getChunkNow(cx, cz) == null) continue;
            if (!CityBuildings.INSTANCE.askable(level, cx, cz) || CityBuildings.INSTANCE.ofChunk(level, cx, cz).isPresent()) continue;
            int street = PopulationService.streetOf(level, new ChunkPos(cx, cz));
            if (street < 0) continue;
            found:
            for (int x = 2; x <= 7; x++) for (int z = 4; z <= 10; z++) {
                BlockPos origin = new BlockPos(cx * 16 + x, street, cz * 16 + z);
                if (free(level, origin, homes, dimension)) { best = origin; bestDistance = distance; break found; }
            }
        }
        return best;
    }

    private static boolean free(ServerLevel level, BlockPos origin, CrewLedger homes, String dimension) {
        for (int x = 0; x < 7; x++) for (int z = 0; z < 3; z++) {
            BlockPos feet = origin.offset(x, 0, z);
            if (!level.getBlockState(feet.below()).isFaceSturdy(level, feet.below(), net.minecraft.core.Direction.UP)) return false;
            for (int y = 0; y < 4; y++) if (!level.getBlockState(feet.above(y)).isAir()) return false;
            if (homes != null && homes.residenceAt(dimension, feet) != null) return false;
            if (!BuildingGuard.exposed(level, feet.above(3))) return false;   // under open sky: a street, not a passage under a building
        }
        return true;
    }

    /** One stall, three wide and two deep: fence posts, a slab roof, a hanging lantern and goods on the counter (no containers: nothing to take or claim). */
    private static void stall(ServerLevel level, BlockPos o, boolean fruit, ListTag placed) {
        BlockState post = Blocks.DARK_OAK_FENCE.defaultBlockState();
        BlockState roof = Blocks.SPRUCE_SLAB.defaultBlockState().setValue(SlabBlock.TYPE, SlabType.BOTTOM);
        for (int[] c : new int[][] {{0, 0}, {2, 0}, {0, 1}, {2, 1}}) {
            put(level, o.offset(c[0], 0, c[1]), post, placed);
            put(level, o.offset(c[0], 1, c[1]), post, placed);
        }
        for (int x = 0; x < 3; x++) for (int z = 0; z < 2; z++) put(level, o.offset(x, 2, z), roof, placed);
        put(level, o.offset(1, 1, 0), Blocks.LANTERN.defaultBlockState().setValue(LanternBlock.HANGING, true), placed);
        put(level, o.offset(1, 0, 1), fruit ? Blocks.MELON.defaultBlockState() : Blocks.HAY_BLOCK.defaultBlockState(), placed);
        put(level, o.offset(1, 0, 0), fruit ? Blocks.PUMPKIN.defaultBlockState() : Blocks.SPRUCE_TRAPDOOR.defaultBlockState(), placed);
    }

    private static void put(ServerLevel level, BlockPos pos, BlockState state, ListTag placed) {
        if (!level.getBlockState(pos).isAir()) return;   // never replaces anything
        level.setBlock(pos, state, 3);
        placed.add(NbtUtils.writeBlockPos(pos));
    }

    /** Test seam: builds at a given spot (the test world has no city plan). */
    static void buildAtForTest(ServerLevel level, WorkOrderLedger orders, Project p, BlockPos at) {
        ListTag placed = new ListTag();
        stall(level, at, false, placed);
        stall(level, at.east(4), true, placed);
        CompoundTag where = new CompoundTag();
        where.put("anchor", NbtUtils.writeBlockPos(at));
        where.put("blocks", placed);
        orders.markCivicBuilt(p.id(), where);
    }

    static List<String> describe(WorkOrderLedger orders) {
        List<String> out = new ArrayList<>();
        for (Project p : CATALOG.all()) {
            CompoundTag s = orders.civic(p.id());
            out.add(p.title() + ": " + s.getInt("count") + "/" + p.goal() + (s.getBoolean("finished") ? ", finished" : "") + (s.getBoolean("built") ? ", built" : ""));
        }
        return out;
    }
}
