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
    static final Set<String> OPS = Set.of("fill", "block", "terminal", "safe", "secure_terminal", "ledger", "sign", "clerk", "npc", "residence");
    static final Set<String> ROLES = Set.of("registrar", "employer", "resident", "guard", "vendor", "instructor", "warden");
    static final int MAX_NPCS = 20, MAX_LINES = 8, MAX_LINE = 180;
    /** Hard bound so a bad datapack cannot ask the server to rewrite an unbounded volume. */
    static final int MAX_EXTENT = 64, MAX_VOLUME = 64_000, MAX_OPS = 1_000;

    /** {@code a} and {@code b} are relative corners (min/max already ordered); {@code text} is a block state, policy or terminal ref. */
    public record Op(String kind, BlockPos a, BlockPos b, String text, String facing, BlockPos reference, Npc npc, String requiresMod, Sign sign, Home home) {
        public Op(String kind, BlockPos a, BlockPos b, String text, String facing, BlockPos reference) { this(kind, a, b, text, facing, reference, null, "", null, null); }
        /** True when this operation applies in the current mod set (an operation naming an absent mod is skipped, not an error). */
        public boolean applies() { return requiresMod.isEmpty() || net.minecraftforge.fml.ModList.get().isLoaded(requiresMod); }
    }
    /** A leasable home: its interior is the operation's box (corners a and b, relative to the district origin); id is unique within the district. */
    public record Home(String id, String title, String kind, int price, int starterPrice) {}
    /** The words on a sign: up to four short lines, a dye colour and whether the text glows. */
    public record Sign(List<String> lines, String color, boolean glow) {}
    /** A named citizen of the district: role, villager profession (look and workstation), the career an employer represents and the lines they say. */
    public record Npc(String role, String name, String profession, String career, List<String> lines) {}
    public record District(String id, String title, List<Op> ops, BlockPos min, BlockPos max, boolean repeatable) {
        public int volume() { return (max.getX() - min.getX() + 1) * (max.getY() - min.getY() + 1) * (max.getZ() - min.getZ() + 1); }
        /** The citizens in file order. A citizen's index here is how a placed villager remembers which entry is theirs. */
        public List<Op> npcOps() { return ops.stream().filter(op -> op.npc() != null).toList(); }
        /** The leasable homes, in file order. */
        public List<Op> homeOps() { return ops.stream().filter(op -> op.home() != null).toList(); }
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
            if (kind.equals("fill") || kind.equals("residence")) {
                BlockPos from = pos(op.getAsJsonArray("from")), to = pos(op.getAsJsonArray("to"));
                a = new BlockPos(Math.min(from.getX(), to.getX()), Math.min(from.getY(), to.getY()), Math.min(from.getZ(), to.getZ()));
                b = new BlockPos(Math.max(from.getX(), to.getX()), Math.max(from.getY(), to.getY()), Math.max(from.getZ(), to.getZ()));
            } else { a = b = pos(op.getAsJsonArray("at")); }
            String text = switch (kind) {
                case "fill", "block", "sign" -> op.get("block").getAsString();
                case "safe" -> op.get("policy").getAsString();
                default -> "";
            };
            String facing = op.has("facing") ? op.get("facing").getAsString() : "north";
            if (!Set.of("north", "south", "east", "west").contains(facing)) throw new IllegalArgumentException("Bad facing " + facing);
            BlockPos reference = kind.equals("clerk") || (kind.equals("npc") && op.has("terminal")) ? pos(op.getAsJsonArray("terminal")) : null;
            Npc npc = kind.equals("npc") ? parseNpc(id, op) : null;
            Sign sign = kind.equals("sign") ? parseSign(id, op) : null;
            Home home = kind.equals("residence") ? parseHome(id, op) : null;
            ops.add(new Op(kind, a, b, text, facing, reference, npc, op.has("requires_mod") ? op.get("requires_mod").getAsString() : "", sign, home));
            for (BlockPos corner : reference == null ? new BlockPos[]{a, b} : new BlockPos[]{a, b, reference}) {
                lo[0] = Math.min(lo[0], corner.getX()); lo[1] = Math.min(lo[1], corner.getY()); lo[2] = Math.min(lo[2], corner.getZ());
                hi[0] = Math.max(hi[0], corner.getX()); hi[1] = Math.max(hi[1], corner.getY()); hi[2] = Math.max(hi[2], corner.getZ());
            }
        }
        long npcs = ops.stream().filter(op -> op.kind().equals("npc")).count();
        if (npcs > MAX_NPCS) throw new IllegalArgumentException("District " + id + " has more than " + MAX_NPCS + " NPCs");
        BlockPos min = new BlockPos(lo[0], lo[1], lo[2]), max = new BlockPos(hi[0], hi[1], hi[2]);
        boolean repeatable = json.has("repeatable") && json.get("repeatable").getAsBoolean();
        if (repeatable && ops.stream().anyMatch(op -> op.home() != null)) throw new IllegalArgumentException("District " + id + ": a repeatable district cannot hold leasable homes");
        java.util.Set<String> homeIds = new java.util.HashSet<>();
        for (Op op : ops) if (op.home() != null && !homeIds.add(op.home().id())) throw new IllegalArgumentException("District " + id + ": duplicate home id " + op.home().id());
        District district = new District(id, json.has("title") ? json.get("title").getAsString() : id, List.copyOf(ops), min, max,
            json.has("repeatable") && json.get("repeatable").getAsBoolean());
        if (max.getX() - min.getX() >= MAX_EXTENT || max.getY() - min.getY() >= MAX_EXTENT || max.getZ() - min.getZ() >= MAX_EXTENT || district.volume() > MAX_VOLUME) {
            throw new IllegalArgumentException("District " + id + " is larger than the safe build limit");
        }
        return district;
    }

    private static Home parseHome(String district, JsonObject op) {
        String id = op.get("id").getAsString();
        if (!id.matches("[a-z0-9_]{3,32}")) throw new IllegalArgumentException("District " + district + ": bad home id " + id);
        String title = op.get("title").getAsString();
        if (title.isBlank() || title.length() > 40) throw new IllegalArgumentException("District " + district + ": bad home title");
        String kind = op.has("kind") ? op.get("kind").getAsString() : "flat";
        if (!Set.of("motel", "flat", "house").contains(kind)) throw new IllegalArgumentException("District " + district + ": unknown home kind " + kind);
        int price = op.get("price").getAsInt(), starter = op.has("starter_price") ? op.get("starter_price").getAsInt() : -1;
        if (price < 1 || price > 1_000_000 || starter > price) throw new IllegalArgumentException("District " + district + ": bad home price for " + id);
        return new Home(id, title, kind, price, starter);
    }

    private static Sign parseSign(String district, JsonObject op) {
        List<String> lines = new ArrayList<>();
        for (JsonElement line : op.getAsJsonArray("lines")) {
            String text = line.getAsString();
            if (text.length() > 15) throw new IllegalArgumentException("District " + district + ": a sign line is longer than 15 characters: " + text);
            lines.add(text);
        }
        if (lines.isEmpty() || lines.size() > 4) throw new IllegalArgumentException("District " + district + ": a sign has one to four lines");
        String color = op.has("color") ? op.get("color").getAsString() : "black";
        if (net.minecraft.world.item.DyeColor.byName(color, null) == null) throw new IllegalArgumentException("District " + district + ": unknown sign colour " + color);
        return new Sign(List.copyOf(lines), color, op.has("glow") && op.get("glow").getAsBoolean());
    }

    private static Npc parseNpc(String district, JsonObject op) {
        String role = op.get("role").getAsString();
        if (!ROLES.contains(role)) throw new IllegalArgumentException("District " + district + ": unknown NPC role " + role);
        String name = op.get("name").getAsString();
        if (name.isBlank() || name.length() > 40) throw new IllegalArgumentException("District " + district + ": bad NPC name");
        String career = op.has("career") ? op.get("career").getAsString() : "";
        if (role.equals("employer") && (!ProgressionRules.validCareer(career) || !op.has("terminal"))) throw new IllegalArgumentException("District " + district + ": an employer needs a career and a terminal");
        List<String> lines = new ArrayList<>();
        if (op.has("lines")) for (JsonElement line : op.getAsJsonArray("lines")) {
            String text = line.getAsString();
            if (text.isBlank() || text.length() > MAX_LINE) throw new IllegalArgumentException("District " + district + ": bad dialogue line for " + name);
            lines.add(text);
        }
        if (lines.size() > MAX_LINES) throw new IllegalArgumentException("District " + district + ": too many lines for " + name);
        return new Npc(role, name, op.has("profession") ? op.get("profession").getAsString() : "none", career, List.copyOf(lines));
    }

    private static BlockPos pos(JsonArray array) {
        if (array.size() != 3) throw new IllegalArgumentException("A position needs three numbers");
        return new BlockPos(array.get(0).getAsInt(), array.get(1).getAsInt(), array.get(2).getAsInt());
    }
}
