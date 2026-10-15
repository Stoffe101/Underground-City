package io.github.stoffe101.undergroundcity;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mojang.logging.LogUtils;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.SimpleJsonResourceReloadListener;
import net.minecraft.util.profiling.ProfilerFiller;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Operator-built districts (D-055), read from {@code data/underground_city/districts}. A district is a list of
 * operations relative to an origin the operator chooses; no world coordinate lives in Java or in the data.
 */
public final class DistrictCatalog extends SimpleJsonResourceReloadListener {
    private static final Logger LOGGER = LogUtils.getLogger();
    public static final DistrictCatalog INSTANCE = new DistrictCatalog();
    static final Set<String> OPS = Set.of("fill", "block", "terminal", "safe", "secure_terminal", "clerk");
    /** Hard bound so a bad datapack cannot ask the server to rewrite an unbounded volume. */
    static final int MAX_EXTENT = 64, MAX_VOLUME = 40_000, MAX_OPS = 400;

    /** {@code a} and {@code b} are relative corners (min/max already ordered); {@code text} is a block state, policy or terminal ref. */
    public record Op(String kind, BlockPos a, BlockPos b, String text, String facing, BlockPos reference) {}
    public record District(String id, String title, List<Op> ops, BlockPos min, BlockPos max) {
        public int volume() { return (max.getX() - min.getX() + 1) * (max.getY() - min.getY() + 1) * (max.getZ() - min.getZ() + 1); }
    }

    private volatile Map<String, District> districts = Map.of();
    private DistrictCatalog() { super(new Gson(), "districts"); }

    public District find(String id) { return districts.get(id); }
    public List<String> ids() { return districts.keySet().stream().sorted().toList(); }

    @Override
    protected void apply(Map<ResourceLocation, JsonElement> data, ResourceManager resources, ProfilerFiller profiler) {
        Map<String, District> next = new LinkedHashMap<>();
        for (var entry : data.entrySet().stream().sorted(Map.Entry.comparingByKey()).toList()) {
            District district = parse(entry.getKey().getPath(), entry.getValue().getAsJsonObject());
            next.put(district.id(), district);
        }
        districts = Map.copyOf(next);
        LOGGER.info("Loaded {} Underground City districts", districts.size());
    }

    static District parse(String fileName, JsonObject json) {
        if (!json.has("schema_version") || json.get("schema_version").getAsInt() != 1) throw new IllegalArgumentException("Unsupported district schema: " + fileName);
        String id = json.get("id").getAsString();
        if (!id.equals(fileName)) throw new IllegalArgumentException("District file must be named after its id: " + fileName);
        JsonArray raw = json.getAsJsonArray("ops");
        if (raw.size() == 0 || raw.size() > MAX_OPS) throw new IllegalArgumentException("District " + id + " has an unreasonable number of operations");
        List<Op> ops = new ArrayList<>();
        int[] lo = {Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MAX_VALUE}, hi = {Integer.MIN_VALUE, Integer.MIN_VALUE, Integer.MIN_VALUE};
        for (JsonElement element : raw) {
            JsonObject op = element.getAsJsonObject();
            String kind = op.get("op").getAsString();
            if (!OPS.contains(kind)) throw new IllegalArgumentException("Unknown district operation " + kind);
            BlockPos a, b;
            if (kind.equals("fill")) {
                BlockPos from = pos(op.getAsJsonArray("from")), to = pos(op.getAsJsonArray("to"));
                a = new BlockPos(Math.min(from.getX(), to.getX()), Math.min(from.getY(), to.getY()), Math.min(from.getZ(), to.getZ()));
                b = new BlockPos(Math.max(from.getX(), to.getX()), Math.max(from.getY(), to.getY()), Math.max(from.getZ(), to.getZ()));
            } else { a = b = pos(op.getAsJsonArray("at")); }
            String text = switch (kind) {
                case "fill", "block" -> op.get("block").getAsString();
                case "safe" -> op.get("policy").getAsString();
                default -> "";
            };
            String facing = op.has("facing") ? op.get("facing").getAsString() : "north";
            if (!Set.of("north", "south", "east", "west").contains(facing)) throw new IllegalArgumentException("Bad facing " + facing);
            BlockPos reference = kind.equals("clerk") ? pos(op.getAsJsonArray("terminal")) : null;
            ops.add(new Op(kind, a, b, text, facing, reference));
            for (BlockPos corner : reference == null ? new BlockPos[]{a, b} : new BlockPos[]{a, b, reference}) {
                lo[0] = Math.min(lo[0], corner.getX()); lo[1] = Math.min(lo[1], corner.getY()); lo[2] = Math.min(lo[2], corner.getZ());
                hi[0] = Math.max(hi[0], corner.getX()); hi[1] = Math.max(hi[1], corner.getY()); hi[2] = Math.max(hi[2], corner.getZ());
            }
        }
        BlockPos min = new BlockPos(lo[0], lo[1], lo[2]), max = new BlockPos(hi[0], hi[1], hi[2]);
        District district = new District(id, json.has("title") ? json.get("title").getAsString() : id, List.copyOf(ops), min, max);
        if (max.getX() - min.getX() >= MAX_EXTENT || max.getY() - min.getY() >= MAX_EXTENT || max.getZ() - min.getZ() >= MAX_EXTENT || district.volume() > MAX_VOLUME) {
            throw new IllegalArgumentException("District " + id + " is larger than the safe build limit");
        }
        return district;
    }

    private static BlockPos pos(JsonArray array) {
        if (array.size() != 3) throw new IllegalArgumentException("A position needs three numbers");
        return new BlockPos(array.get(0).getAsInt(), array.get(1).getAsInt(), array.get(2).getAsInt());
    }
}
