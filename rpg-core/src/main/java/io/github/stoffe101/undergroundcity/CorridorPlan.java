package io.github.stoffe101.undergroundcity;

import java.util.List;

/**
 * Pure geometry for one data-defined inter-city road (no Minecraft types, so it is unit-tested on a plain JVM).
 *
 * A route is a polyline of block coordinates. The deck profile is sampled every {@link #STEP} blocks from the natural
 * terrain, smoothed so a walker or vehicle never meets a slope steeper than {@link #MAX_RISE_HALF} half-blocks per
 * sample, and lifted to {@link #BRIDGE_CLEARANCE} blocks above any water so a boat can pass underneath. Everything is
 * a deterministic function of the seed and the route data, which keeps every chunk of the road consistent whenever it
 * is generated. City circles are excluded: inside a city, Lost Cities owns the street grid.
 */
public final class CorridorPlan {
    public static final int STEP = 4;
    /** Deck height is measured in half blocks so slabs can ramp half a block at a time. */
    public static final int MAX_RISE_HALF = 2;
    /** Free blocks of air between the water surface and the underside of the bridge beam. */
    public static final int BRIDGE_CLEARANCE = 4;
    /** The deck may be this many blocks below natural ground (a shallow cutting) before it is raised instead. */
    public static final int MAX_CUT = 8;
    private static final int AVERAGE_WINDOW = 8;

    public record City(double x, double z, double radius) {}
    public record Hit(double s, double u, double distance) {}
    /** Natural terrain, queried per column. {@code surface} is the first free block above water or ground. */
    public interface Terrain {
        int surface(int x, int z);
        int floor(int x, int z);
    }

    private final String id;
    private final double[] xs, zs, arc;
    private final double length, deckHalfWidth;
    private final List<City> cities;
    private int[] deckHalf;
    private boolean[] bridge, open;
    private int sampleCount;

    public CorridorPlan(String id, double[] xs, double[] zs, int width, List<City> cities) {
        if (xs.length != zs.length || xs.length < 2) throw new IllegalArgumentException("Route needs at least two waypoints: " + id);
        if (width < 3 || width > 15) throw new IllegalArgumentException("Road width out of range: " + id);
        this.id = id;
        this.xs = xs.clone();
        this.zs = zs.clone();
        this.cities = List.copyOf(cities);
        this.deckHalfWidth = width / 2.0;
        this.arc = new double[xs.length];
        for (int i = 1; i < xs.length; i++) arc[i] = arc[i - 1] + Math.hypot(xs[i] - xs[i - 1], zs[i] - zs[i - 1]);
        this.length = arc[arc.length - 1];
        this.sampleCount = (int) Math.ceil(length / STEP) + 1;
    }

    public String id() { return id; }
    public double length() { return length; }
    public int samples() { return sampleCount; }
    public double deckHalfWidth() { return deckHalfWidth; }
    /** Distance from the centreline beyond which a column is never touched (deck, parapet and clearing band). */
    public double reach() { return deckHalfWidth + 1.6; }
    public boolean built() { return deckHalf != null; }

    public double[] pointAt(double s) {
        double clamped = Math.max(0, Math.min(length, s));
        int i = 1;
        while (i < arc.length - 1 && arc[i] < clamped) i++;
        double span = arc[i] - arc[i - 1];
        double t = span <= 0 ? 0 : (clamped - arc[i - 1]) / span;
        return new double[]{xs[i - 1] + (xs[i] - xs[i - 1]) * t, zs[i - 1] + (zs[i] - zs[i - 1]) * t};
    }

    /** True where the centreline is clear of every city circle. */
    public boolean openAt(double s) {
        double[] point = pointAt(s);
        for (City city : cities) if (Math.hypot(point[0] - city.x, point[1] - city.z) <= city.radius) return false;
        return true;
    }

    /** Nearest open point on the polyline to a column centre, or null if it is further than {@link #reach()}. */
    public Hit nearest(double x, double z) {
        Hit best = null;
        for (int i = 1; i < xs.length; i++) {
            double dx = xs[i] - xs[i - 1], dz = zs[i] - zs[i - 1];
            double span = dx * dx + dz * dz;
            double t = span == 0 ? 0 : Math.max(0, Math.min(1, ((x - xs[i - 1]) * dx + (z - zs[i - 1]) * dz) / span));
            double px = xs[i - 1] + dx * t, pz = zs[i - 1] + dz * t;
            double distance = Math.hypot(x - px, z - pz);
            if (distance > reach() || (best != null && distance >= best.distance)) continue;
            double s = arc[i - 1] + Math.sqrt(span) * t;
            if (!openAt(s)) continue;
            double side = Math.signum(dx * (z - pz) - dz * (x - px));
            best = new Hit(s, side * distance, distance);
        }
        return best;
    }

    /** Cheap rejection test used for whole chunks. */
    public boolean nearChunk(int minX, int minZ) { return nearChunk(minX, minZ, 0); }

    /** As {@link #nearChunk(int, int)} with a margin in blocks, e.g. 16 to include the neighbouring chunks. */
    public boolean nearChunk(int minX, int minZ, double margin) {
        double cx = minX + 8, cz = minZ + 8, radius = 11.4 + reach() + margin;
        for (int i = 1; i < xs.length; i++) {
            double dx = xs[i] - xs[i - 1], dz = zs[i] - zs[i - 1];
            double span = dx * dx + dz * dz;
            double t = span == 0 ? 0 : Math.max(0, Math.min(1, ((cx - xs[i - 1]) * dx + (cz - zs[i - 1]) * dz) / span));
            if (Math.hypot(cx - (xs[i - 1] + dx * t), cz - (zs[i - 1] + dz * t)) <= radius) return true;
        }
        return false;
    }

    // ------------------------------------------------------------------------------------------ profile
    /** Samples the natural terrain once and fixes the deck profile. Safe to call again; later calls replace it. */
    public void build(Terrain terrain) {
        int count = sampleCount;
        int[] target = new int[count], minimum = new int[count];
        boolean[] water = new boolean[count], isOpen = new boolean[count];
        int[] floor = new int[count];
        for (int i = 0; i < count; i++) {
            double s = Math.min(length, (double) i * STEP);
            isOpen[i] = openAt(s);
            if (!isOpen[i]) continue;
            double[] point = pointAt(s);
            int x = (int) Math.floor(point[0]), z = (int) Math.floor(point[1]);
            int surface = terrain.surface(x, z);
            floor[i] = terrain.floor(x, z);
            water[i] = surface > floor[i];
            // Deck top = water surface + clearance + the beam under the deck (one block) + the deck block itself.
            target[i] = 2 * (water[i] ? surface + BRIDGE_CLEARANCE + 2 : surface);
            minimum[i] = water[i] ? target[i] : target[i] - 2 * MAX_CUT;
        }
        int[] profile = smooth(target, minimum, isOpen);
        boolean[] spans = new boolean[count];
        for (int i = 0; i < count; i++) {
            if (!isOpen[i]) continue;
            int deckY = Math.floorDiv(profile[i] - 1, 2);
            spans[i] = water[i] || deckY - 1 - floor[i] > 3;
        }
        this.deckHalf = profile;
        this.open = isOpen;
        this.bridge = spans;
    }

    /**
     * Moving average, then lower bounds (bridge clearance, maximum cut), then a forward and a backward pass that only
     * raise, so every pair of neighbours differs by at most {@link #MAX_RISE_HALF}. Runs are independent: samples
     * inside a city are skipped.
     */
    static int[] smooth(int[] target, int[] minimum, boolean[] open) {
        int count = target.length;
        int[] out = new int[count];
        int i = 0;
        while (i < count) {
            if (!open[i]) { i++; continue; }
            int end = i;
            while (end + 1 < count && open[end + 1]) end++;
            for (int k = i; k <= end; k++) {
                int from = Math.max(i, k - AVERAGE_WINDOW), to = Math.min(end, k + AVERAGE_WINDOW);
                long sum = 0;
                for (int j = from; j <= to; j++) sum += target[j];
                out[k] = Math.max((int) Math.round((double) sum / (to - from + 1)), minimum[k]);
            }
            for (int k = i + 1; k <= end; k++) out[k] = Math.max(out[k], out[k - 1] - MAX_RISE_HALF);
            for (int k = end - 1; k >= i; k--) out[k] = Math.max(out[k], out[k + 1] - MAX_RISE_HALF);
            i = end + 1;
        }
        return out;
    }

    /** Deck top in half blocks at an arc position (linear between samples). */
    public int deckHalfAt(double s) {
        if (deckHalf == null) throw new IllegalStateException("Profile not built: " + id);
        double position = Math.max(0, Math.min(length, s)) / STEP;
        int lower = (int) Math.floor(position), upper = Math.min(lower + 1, sampleCount - 1);
        double fraction = position - lower;
        // Samples inside a city circle carry no profile: never interpolate towards them.
        if (!open[lower] && !open[upper]) return deckHalf[lower];
        if (!open[upper]) return deckHalf[lower];
        if (!open[lower]) return deckHalf[upper];
        return (int) Math.round(deckHalf[lower] + (deckHalf[upper] - deckHalf[lower]) * fraction);
    }

    /** True where the deck stands well above water or ground and needs a parapet and supports. */
    public boolean bridgeAt(double s) {
        int centre = (int) Math.round(Math.max(0, Math.min(length, s)) / STEP);
        for (int i = Math.max(0, centre - 1); i <= Math.min(sampleCount - 1, centre + 1); i++) if (bridge[i]) return true;
        return false;
    }

    public boolean pierAt(double s, double u) {
        return Math.abs(u) <= 1.6 && Math.floorMod((long) Math.floor(s), 24L) < 3;
    }

    /** Top block of the deck for a half-block height; odd heights are bottom slabs. */
    public static int deckBlockY(int half) { return Math.floorDiv(half - 1, 2); }
    public static boolean isSlab(int half) { return (half & 1) != 0; }

    /** Largest difference, in half blocks, between neighbouring profile samples inside open runs. */
    public int maxProfileStep() {
        int max = 0;
        for (int i = 1; i < sampleCount; i++) if (open[i] && open[i - 1]) max = Math.max(max, Math.abs(deckHalf[i] - deckHalf[i - 1]));
        return max;
    }

    public int bridgeSamples() {
        int n = 0;
        for (int i = 0; i < sampleCount; i++) if (open[i] && bridge[i]) n++;
        return n;
    }

    public boolean sampleOpen(int index) { return open[index]; }
    public boolean sampleBridge(int index) { return bridge[index]; }
}
