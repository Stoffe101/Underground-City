package io.github.stoffe101.undergroundcity;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Finds level, dry, unreserved ground for a district without loading or generating a single chunk: the caller supplies the terrain height
 * (for a world, the chunk generator's own base height, which ignores trees and buildings). Pure arithmetic, so it is unit-tested without a world.
 */
final class DistrictSites {
    private DistrictSites() {}

    /** The ground under a column: where to stand, whether water covers it, and whether something else (a city) has claimed it. */
    interface Ground {
        int height(int x, int z);
        boolean wet(int x, int z);
        boolean reserved(int x, int z);
    }

    /** A candidate lot: its north-west corner, the standing level (the median ground height) and how uneven the ground is. */
    record Site(int x, int z, int y, int relief, double distance) {}

    static final int STEP = 8, SAMPLE = 4, MARGIN = 3;

    /**
     * The best site within {@code radius} blocks of {@code (aroundX, aroundZ)} for a lot of {@code width} x {@code depth}: dry, unreserved, no
     * more than {@code maxRelief} blocks between the highest and lowest ground, nearest first among equally level candidates. Empty when none fits.
     */
    static java.util.Optional<Site> find(Ground ground, int aroundX, int aroundZ, int width, int depth, int maxRelief, int radius) {
        // A column is asked about by every candidate lot that overlaps it (the lots are a few blocks apart and ninety wide), and a chunk generator's height costs
        // a whole noise column: ask each column once.
        Ground cached = new Cached(ground);
        // Nearest first. A site costs 12 per block of relief plus its distance, so once the best cost found is no more than a candidate's own distance nothing
        // from there on can win: on level ground the search ends at the first lot instead of reading all of them (the exhaustive search took more than nine
        // minutes on the archipelago's flat landing, with the server held back on its first start).
        List<int[]> offsets = new ArrayList<>();
        for (int dx = -radius; dx <= radius; dx += STEP) {
            for (int dz = -radius; dz <= radius; dz += STEP) {
                if (Math.hypot(dx, dz) <= radius) offsets.add(new int[]{dx, dz});
            }
        }
        offsets.sort(Comparator.comparingDouble(o -> Math.hypot(o[0], o[1])));
        Site best = null;
        double bestCost = Double.MAX_VALUE;
        for (int[] o : offsets) {
            double distance = Math.hypot(o[0], o[1]);
            if (distance >= bestCost) break;
            Site site = evaluate(cached, aroundX + o[0] - width / 2, aroundZ + o[1] - depth / 2, width, depth, distance);
            if (site == null || site.relief() > maxRelief) continue;
            double cost = site.relief() * 12.0 + site.distance();
            if (cost < bestCost) { best = site; bestCost = cost; }
        }
        return java.util.Optional.ofNullable(best);
    }

    /** Remembers each column's answers for one search. */
    private static final class Cached implements Ground {
        private final Ground inner;
        private final java.util.Map<Long, Integer> heights = new java.util.HashMap<>();
        private final java.util.Map<Long, Boolean> wet = new java.util.HashMap<>(), reserved = new java.util.HashMap<>();

        Cached(Ground inner) { this.inner = inner; }

        private static long key(int x, int z) { return ((long) x << 32) ^ (z & 0xffffffffL); }

        @Override public int height(int x, int z) { return heights.computeIfAbsent(key(x, z), k -> inner.height(x, z)); }
        @Override public boolean wet(int x, int z) { return wet.computeIfAbsent(key(x, z), k -> inner.wet(x, z)); }
        @Override public boolean reserved(int x, int z) { return reserved.computeIfAbsent(key(x, z), k -> inner.reserved(x, z)); }
    }

    static Site evaluate(Ground ground, int x0, int z0, int width, int depth, double distance) {
        List<Integer> heights = new ArrayList<>();
        for (int x = x0 - MARGIN; x <= x0 + width + MARGIN; x += SAMPLE) {
            for (int z = z0 - MARGIN; z <= z0 + depth + MARGIN; z += SAMPLE) {
                if (ground.reserved(x, z) || ground.wet(x, z)) return null; // reserved first: a column that is not loaded must be refused before anything reads its height
                heights.add(ground.height(x, z));
            }
        }
        // The far corners are sampled too, so a lot cannot hide a ditch between grid points.
        for (int[] corner : new int[][]{{x0, z0}, {x0 + width, z0}, {x0, z0 + depth}, {x0 + width, z0 + depth}}) {
            if (ground.reserved(corner[0], corner[1]) || ground.wet(corner[0], corner[1])) return null;
            heights.add(ground.height(corner[0], corner[1]));
        }
        int min = heights.stream().mapToInt(Integer::intValue).min().orElse(0), max = heights.stream().mapToInt(Integer::intValue).max().orElse(0);
        List<Integer> sorted = heights.stream().sorted().toList();
        return new Site(x0, z0, sorted.get(sorted.size() / 2), max - min, distance);
    }
}
