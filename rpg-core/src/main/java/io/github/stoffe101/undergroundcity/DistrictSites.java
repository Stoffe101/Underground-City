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
        List<Site> fits = new ArrayList<>();
        for (int dx = -radius; dx <= radius; dx += STEP) {
            for (int dz = -radius; dz <= radius; dz += STEP) {
                double distance = Math.hypot(dx, dz);
                if (distance > radius) continue;
                Site site = evaluate(ground, aroundX + dx - width / 2, aroundZ + dz - depth / 2, width, depth, distance);
                if (site != null && site.relief() <= maxRelief) fits.add(site);
            }
        }
        // Level first (each block of relief costs as much as 12 blocks of walking), then near.
        return fits.stream().min(Comparator.comparingDouble(site -> site.relief() * 12.0 + site.distance()));
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
