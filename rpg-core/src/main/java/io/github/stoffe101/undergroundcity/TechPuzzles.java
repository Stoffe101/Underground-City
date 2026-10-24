package io.github.stoffe101.undergroundcity;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.List;
import java.util.Random;

/**
 * The Technician's two puzzles (D-158), pure and seeded so the server holds the answer and a test can pin the rules. Both are fictional electronics.
 *
 * <ul>
 * <li>{@link Trace} (a repair): a circuit of parts from the supply to one or two loads, one part dead. Probing a test point after a part says whether the signal reaches it; the dead part lies
 * between the last live point and the first dead one, so a careful technician halves the circuit with each probe. Replacing a part that was fine wastes a try.</li>
 * <li>{@link Patch} (an install or a network upgrade): a patch grid whose tiles turn a quarter at a click; route the feed from the left edge to every device on the right.</li>
 * </ul>
 */
final class TechPuzzles {
    private TechPuzzles() {}

    // ------------------------------------------------------------------------------------------------ the repair

    /** The kinds of part a circuit is drawn with (only the look differs). */
    enum Part { FUSE, RESISTOR, CAPACITOR, RELAY, CHIP, DIODE }

    /**
     * A circuit: {@code trunk} parts from the supply, then (when {@code split}) branch A and branch B of {@code branch} parts each to load A and load B; part {@code faulty} is dead. Parts are
     * numbered trunk first, then A, then B.
     */
    static final class Trace {
        final int trunk, branch;
        final boolean split;
        final Part[] parts;
        final int faulty;

        Trace(int trunk, int branch, boolean split, Part[] parts, int faulty) {
            this.trunk = trunk; this.branch = branch; this.split = split; this.parts = parts; this.faulty = faulty;
        }

        /** A circuit for a building of {@code tier}: longer and split in two from tier 3. */
        static Trace generate(long seed, int tier) {
            Random r = new Random(seed ^ 0x7ACEL);
            int t = Math.max(1, Math.min(6, tier));
            boolean split = t >= 3;
            int trunk = 3 + t / 2, branch = split ? 2 + t / 3 : 0;
            int n = trunk + (split ? 2 * branch : 0);
            Part[] parts = new Part[n];
            for (int i = 0; i < n; i++) parts[i] = Part.values()[r.nextInt(Part.values().length)];
            return new Trace(trunk, branch, split, parts, r.nextInt(n));
        }

        int size() { return parts.length; }

        /** Which line a part is on: 0 the trunk, 1 branch A, 2 branch B. */
        int line(int part) { return part < trunk ? 0 : part < trunk + branch ? 1 : 2; }

        /** The parts the signal passes through to reach the point just after {@code part}. */
        List<Integer> path(int part) {
            List<Integer> out = new ArrayList<>();
            int line = line(part);
            for (int i = 0; i < Math.min(part + 1, trunk); i++) out.add(i);
            if (line >= 1) for (int i = (line == 1 ? trunk : trunk + branch); i <= part; i++) out.add(i);
            return out;
        }

        /** Whether the signal reaches the test point just after {@code part}. */
        boolean live(int part) { return part >= 0 && part < parts.length && !path(part).contains(faulty); }

        /** Whether load A (or B) is lit: the end of its line is live. Without a split there is one load, after the last trunk part. */
        boolean loadLit(int load) {
            if (!split) return load == 0 && live(trunk - 1);
            return live(load == 0 ? trunk + branch - 1 : trunk + 2 * branch - 1);
        }

        /** What a Diagnostic Scanner tells at the start: the first and last part of a stretch of about half the circuit that holds the dead part. */
        int[] scan() {
            int line = line(faulty);
            int lo = line == 0 ? 0 : line == 1 ? trunk : trunk + branch, hi = line == 0 ? trunk - 1 : lo + branch - 1;
            int mid = (lo + hi) / 2;
            return faulty <= mid ? new int[] {lo, mid} : new int[] {mid + 1, hi};
        }

        /** The fewest probes that always find the part (a binary search over the longest line after the loads were read). */
        int optimalProbes() {
            int longest = split ? Math.max(trunk, branch) : trunk;
            int p = 0;
            while ((1 << p) < longest) p++;
            return p;
        }
    }

    // ------------------------------------------------------------------------------------------------ the install

    /** A tile's connections: north 1, east 2, south 4, west 8. */
    static final int N = 1, E = 2, S = 4, W = 8;

    static int rotate(int mask) { return ((mask << 1) | (mask >> 3)) & 15; }

    /** A patch grid of {@code w} by {@code h}: the feed comes in from the west at row {@code source}; each of {@code outputs} is a device to the east at that row. */
    static final class Patch {
        final int w, h, source;
        final int[] outputs;
        final int[] solution;
        final int[] tiles;

        Patch(int w, int h, int source, int[] outputs, int[] solution, int[] tiles) {
            this.w = w; this.h = h; this.source = source; this.outputs = outputs; this.solution = solution; this.tiles = tiles;
        }

        /** A grid for a job of {@code size} 0 (small, 4x4, two devices) to 2 (large, 6x5, four devices); the tiles start turned at random, none already right. */
        static Patch generate(long seed, int size) {
            Random r = new Random(seed ^ 0xBA7CL);
            int w = 4 + Math.min(2, size), h = 4 + Math.min(1, size), devices = 2 + Math.min(2, size);
            int source = r.nextInt(h);
            // a random spanning tree over the cells, grown from the feed's cell
            int[] mask = new int[w * h];
            boolean[] seen = new boolean[w * h];
            Deque<Integer> stack = new ArrayDeque<>();
            int start = source * w;
            seen[start] = true;
            stack.push(start);
            while (!stack.isEmpty()) {
                int cell = stack.peek(), x = cell % w, y = cell / w;
                List<int[]> next = new ArrayList<>();
                if (y > 0 && !seen[cell - w]) next.add(new int[] {cell - w, N, S});
                if (x < w - 1 && !seen[cell + 1]) next.add(new int[] {cell + 1, E, W});
                if (y < h - 1 && !seen[cell + w]) next.add(new int[] {cell + w, S, N});
                if (x > 0 && !seen[cell - 1]) next.add(new int[] {cell - 1, W, E});
                if (next.isEmpty()) { stack.pop(); continue; }
                int[] pick = next.get(r.nextInt(next.size()));
                mask[cell] |= pick[1];
                mask[pick[0]] |= pick[2];
                seen[pick[0]] = true;
                stack.push(pick[0]);
            }
            // the feed enters the first cell from the west; the devices are on distinct rows of the east edge
            mask[start] |= W;
            List<Integer> rows = new ArrayList<>();
            for (int y = 0; y < h; y++) rows.add(y);
            java.util.Collections.shuffle(rows, r);
            int[] outputs = new int[devices];
            for (int i = 0; i < devices; i++) { outputs[i] = rows.get(i); mask[outputs[i] * w + w - 1] |= E; }
            Arrays.sort(outputs);
            int[] tiles = mask.clone();
            for (int i = 0; i < tiles.length; i++) {
                int turns = 1 + r.nextInt(3);
                for (int k = 0; k < turns; k++) tiles[i] = rotate(tiles[i]);
                // a straight tile turned twice, or a cross, looks solved: give it one more quarter if that changes it
                if (tiles[i] == mask[i] && rotate(tiles[i]) != tiles[i]) tiles[i] = rotate(tiles[i]);
            }
            return new Patch(w, h, source, outputs, mask, tiles);
        }

        void turn(int cell) { if (cell >= 0 && cell < tiles.length) tiles[cell] = rotate(tiles[cell]); }

        /** The cells connected to the feed through matching edges. */
        boolean[] powered() {
            boolean[] on = new boolean[tiles.length];
            int start = source * w;
            if ((tiles[start] & W) == 0) return on;
            Deque<Integer> queue = new ArrayDeque<>();
            on[start] = true;
            queue.add(start);
            while (!queue.isEmpty()) {
                int cell = queue.poll(), x = cell % w, y = cell / w;
                int[][] steps = {{0, -1, N, S}, {1, 0, E, W}, {0, 1, S, N}, {-1, 0, W, E}};
                for (int[] s : steps) {
                    int nx = x + s[0], ny = y + s[1];
                    if (nx < 0 || ny < 0 || nx >= w || ny >= h) continue;
                    int other = ny * w + nx;
                    if (!on[other] && (tiles[cell] & s[2]) != 0 && (tiles[other] & s[3]) != 0) { on[other] = true; queue.add(other); }
                }
            }
            return on;
        }

        /** Whether every device is fed: its east-edge cell is powered and points east. */
        boolean solved() {
            boolean[] on = powered();
            for (int row : outputs) { int cell = row * w + w - 1; if (!on[cell] || (tiles[cell] & E) == 0) return false; }
            return true;
        }
    }
}
