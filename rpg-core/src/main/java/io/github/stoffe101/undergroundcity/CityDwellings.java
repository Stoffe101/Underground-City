package io.github.stoffe101.undergroundcity;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mojang.logging.LogUtils;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.SimpleJsonResourceReloadListener;
import net.minecraft.util.profiling.ProfilerFiller;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The homes and premises of a multi-chunk building (D-206: a property follows the dwelling, not the chunk). {@code data/<ns>/city_dwellings/<path>.json} describes the multi-chunk building
 * {@code <ns>:<path>}: its size and the part Lost Cities builds in each chunk of it ({@code parts[x][z]}, Lost Cities' own grid), and each dwelling in it with its rooms. A room is an interior
 * box in its part's own coordinates ({@code x}, {@code z} 0 to 15 in the chunk, {@code y} above the street), tagged with the part's place in the grid. A detached house over two chunks is one
 * dwelling with rooms in both; two terraced houses are two dwellings; a block of flats is one dwelling a flat, and its stairs and corridors belong to none.
 *
 * <p>Nothing is guessed. A multi-chunk building without a file has nothing to let, whatever its parts' own plans say; a file whose dwellings reach into each other's shell, or whose rooms lie
 * outside the building, is refused whole and logged; a chunk whose part is not the one the file names for its place lets nothing ({@link CityHomes}).
 */
public final class CityDwellings extends SimpleJsonResourceReloadListener {
    private static final Logger LOGGER = LogUtils.getLogger();
    public static final CityDwellings INSTANCE = new CityDwellings();
    static final int MAX_DWELLINGS = 24, MAX_SIZE = 8;

    /** One interior box of a dwelling, in the part at column {@code x}, row {@code z} of the building. */
    public record Room(int x, int z, int x0, int y0, int z0, int x1, int y1, int z1) {
        int bx0() { return x * 16 + x0; } int bx1() { return x * 16 + x1; } int bz0() { return z * 16 + z0; } int bz1() { return z * 16 + z1; }
    }

    public record Dwelling(String id, String title, List<Room> rooms) {}

    /** {@code parts.get(x).get(z)}: the part Lost Cities builds at column x, row z. */
    public record Building(String multi, int w, int h, List<List<String>> parts, List<Dwelling> dwellings) {
        public String part(int x, int z) { return x >= 0 && x < parts.size() && z >= 0 && z < parts.get(x).size() ? parts.get(x).get(z) : ""; }
    }

    private volatile Map<String, Building> buildings = Map.of();

    private CityDwellings() { super(new Gson(), "city_dwellings"); }

    @Override
    protected void apply(Map<ResourceLocation, JsonElement> data, ResourceManager resources, ProfilerFiller profiler) {
        Map<String, Building> next = new LinkedHashMap<>();
        int refused = 0;
        for (var entry : data.entrySet().stream().sorted(Map.Entry.comparingByKey()).toList()) {
            try {
                Building b = parse(entry.getKey().toString(), entry.getValue().getAsJsonObject());
                next.put(b.multi(), b);
            } catch (RuntimeException error) {
                refused++;
                // the reason names no content: counts, ids and coordinates of the file itself
                LOGGER.warn("[UC] the dwellings of {} are refused and nothing in it is let: {}", entry.getKey(), error.getMessage());
            }
        }
        buildings = Map.copyOf(next);
        LOGGER.info("Loaded the dwellings of {} multi-chunk buildings ({} refused)", buildings.size(), refused);
    }

    static Building parse(String multi, JsonObject json) {
        if (!json.has("schema_version") || json.get("schema_version").getAsInt() != 1) throw new IllegalArgumentException("unsupported schema");
        JsonArray size = json.getAsJsonArray("size");
        int w = size.get(0).getAsInt(), h = size.get(1).getAsInt();
        if (w < 1 || h < 1 || w > MAX_SIZE || h > MAX_SIZE || w * h < 2) throw new IllegalArgumentException("bad size " + w + " x " + h);
        List<List<String>> parts = new ArrayList<>();
        for (JsonElement column : json.getAsJsonArray("parts")) {
            List<String> rows = new ArrayList<>();
            for (JsonElement part : column.getAsJsonArray()) rows.add(part.getAsString());
            if (rows.size() != h) throw new IllegalArgumentException("a column of parts is not " + h + " long");
            parts.add(List.copyOf(rows));
        }
        if (parts.size() != w) throw new IllegalArgumentException("not " + w + " columns of parts");
        List<Dwelling> dwellings = new ArrayList<>();
        for (JsonElement element : json.getAsJsonArray("dwellings")) {
            JsonObject d = element.getAsJsonObject();
            String id = d.get("id").getAsString();
            if (!id.matches("[a-z0-9_]{2,24}")) throw new IllegalArgumentException("bad dwelling id " + id);
            if (dwellings.stream().anyMatch(o -> o.id().equals(id))) throw new IllegalArgumentException("dwelling " + id + " twice");
            List<Room> rooms = new ArrayList<>();
            for (JsonElement r : d.getAsJsonArray("rooms")) {
                JsonObject o = r.getAsJsonObject();
                JsonArray at = o.getAsJsonArray("at"), box = o.getAsJsonArray("box");
                Room room = new Room(at.get(0).getAsInt(), at.get(1).getAsInt(), box.get(0).getAsInt(), box.get(1).getAsInt(), box.get(2).getAsInt(), box.get(3).getAsInt(), box.get(4).getAsInt(), box.get(5).getAsInt());
                if (room.x() < 0 || room.x() >= w || room.z() < 0 || room.z() >= h || room.x0() < 0 || room.x1() > 15 || room.z0() < 0 || room.z1() > 15
                    || room.x0() > room.x1() || room.y0() > room.y1() || room.z0() > room.z1() || room.y0() < 0 || room.y1() > 250) throw new IllegalArgumentException("bad room in " + id);
                rooms.add(room);
            }
            if (rooms.isEmpty() || rooms.size() > CrewLedger.MAX_BOXES) throw new IllegalArgumentException(id + " has " + rooms.size() + " rooms (1 to " + CrewLedger.MAX_BOXES + ")");
            String title = d.has("title") ? d.get("title").getAsString() : id;
            dwellings.add(new Dwelling(id, title.length() > 24 ? title.substring(0, 24) : title, List.copyOf(rooms)));
        }
        if (dwellings.isEmpty() || dwellings.size() > MAX_DWELLINGS) throw new IllegalArgumentException(dwellings.size() + " dwellings (1 to " + MAX_DWELLINGS + ")");
        // as CrewLedger.registerResidence will: no room of one dwelling may reach into the shell of another's
        for (int i = 0; i < dwellings.size(); i++) for (int j = i + 1; j < dwellings.size(); j++)
            for (Room a : dwellings.get(i).rooms()) for (Room b : dwellings.get(j).rooms())
                if (intrudes(a, b) || intrudes(b, a)) throw new IllegalArgumentException(dwellings.get(i).id() + " and " + dwellings.get(j).id() + " have no wall between them");
        return new Building(multi, w, h, List.copyOf(parts), List.copyOf(dwellings));
    }

    /** True when room {@code a}'s interior meets the one-block shell round room {@code b}, in the building's own coordinates. */
    static boolean intrudes(Room a, Room b) {
        return a.bx0() <= b.bx1() + 1 && a.bx1() >= b.bx0() - 1 && a.y0() <= b.y1() + 1 && a.y1() >= b.y0() - 1 && a.bz0() <= b.bz1() + 1 && a.bz1() >= b.bz0() - 1;
    }

    /** The dwellings of a multi-chunk building, or null when it has none (then nothing in it is let). */
    public Building forMulti(String multi) { return multi == null ? null : buildings.get(multi); }
    public int size() { return buildings.size(); }

    /** Test seam: install a set of buildings, returning the ones it replaces. */
    Map<String, Building> replaceForTest(Map<String, Building> replacement) { Map<String, Building> before = buildings; buildings = Map.copyOf(replacement); return before; }
}
