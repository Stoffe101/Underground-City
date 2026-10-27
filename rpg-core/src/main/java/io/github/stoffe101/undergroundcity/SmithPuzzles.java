package io.github.stoffe101.undergroundcity;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * The Smith's two hands-on jobs (eighth pass), pure and seeded so the server holds the state and a test can pin the rules. Both are about metal and machines, never electronics.
 *
 * <ul>
 * <li>{@link Turning}: a precision part turned to a drawing at the Machinist's Bench. The bar is in sections, each with the line it must come down to. A pass runs the tool along a stretch of
 * neighbouring sections at one depth; roughing cuts deep and leaves tool marks, finishing cuts one step and leaves a clean surface. Every section must end on its line and clean. A section cut
 * too far is built back up with a sleeve. The grade follows the passes against the shop's standard (the fewest the drawing needs).</li>
 * <li>{@link Drive}: a machine's drive train stripped at a service call. Worn parts are replaced with the matching parts; the gears of the open stages are chosen so the output turns at the
 * machine's plate speed and in its direction (an idler gear turns a stage's direction round); a run tests it.</li>
 * </ul>
 */
final class SmithPuzzles {
    private SmithPuzzles() {}

    // ------------------------------------------------------------------------------------------------ turning to a drawing

    static final class Turning {
        /** The stock's radius (every section starts here), and the thinnest line a drawing asks for. */
        static final int STOCK = 8, MIN_LINE = 2;

        final int[] target, radius;
        final boolean[] marked;
        /** How many steps one roughing pass may take (two at a Vice and Files, three on a Drill Press, four on a Lathe). */
        final int rough;
        /** The shop's standard: the fewest passes the drawing needs with this roughing depth. */
        final int standard;
        int passes, sleeves;

        Turning(int[] target, int rough) {
            this.target = target.clone();
            this.rough = Math.max(1, rough);
            this.radius = new int[target.length];
            this.marked = new boolean[target.length];
            java.util.Arrays.fill(radius, STOCK);
            this.standard = fewestPasses(this.target, this.rough);
        }

        /** A drawing of {@code sections} sections: runs of one to three at a level, neighbouring runs at different levels, and at least one section well below the stock. */
        static Turning generate(long seed, int sections, int rough) {
            Random r = new Random(seed ^ 0x7E2E5A11L);
            int n = Math.max(4, Math.min(10, sections));
            int[] t = new int[n];
            int i = 0, last = -1;
            while (i < n) {
                int len = 1 + r.nextInt(3), level;
                do { level = MIN_LINE + r.nextInt(STOCK - MIN_LINE); } while (level == last);
                for (int k = 0; k < len && i < n; k++) t[i++] = level;
                last = level;
            }
            boolean deep = false;
            for (int v : t) deep |= v <= STOCK - 3;
            if (!deep) t[r.nextInt(n)] = MIN_LINE + r.nextInt(2);
            return new Turning(t, rough);
        }

        int size() { return target.length; }

        /** One pass along sections {@code from} to {@code to} with the tool at {@code line}. Returns why it was refused (nothing changes then), or null. */
        String cut(int from, int to, int line, boolean finish) {
            if (done()) return "The part is finished.";
            int a = Math.max(0, Math.min(from, to)), b = Math.min(size() - 1, Math.max(from, to));
            if (a > b || line < 1 || line >= STOCK) return "Pick a stretch of the bar and a depth first.";
            int high = 0;
            for (int i = a; i <= b; i++) high = Math.max(high, radius[i]);
            if (high <= line) return "The tool would not touch the bar there.";
            int depth = high - line, most = finish ? 1 : rough;
            if (depth > most) return finish ? "A finishing pass takes one step at most." : "Too deep for one roughing pass: " + rough + (rough == 1 ? " step" : " steps") + " at most.";
            for (int i = a; i <= b; i++) if (radius[i] > line) { radius[i] = line; marked[i] = !finish; }
            passes++;
            return null;
        }

        /** True when a section can no longer be finished as it is: cut below its line, or roughed down to it (its tool marks could only be finished away below the line). */
        boolean spoiled(int i) { return radius[i] < target[i] || radius[i] == target[i] && marked[i]; }

        /** Builds a spoiled section back up to one step above its line with a sleeve (one iron nugget, settled when the part is finished). Returns why not, or null. */
        String sleeve(int i) {
            if (i < 0 || i >= size()) return "Pick a section first.";
            if (!spoiled(i)) return "That section can still be finished as it is.";
            radius[i] = Math.min(STOCK, target[i] + 1);
            marked[i] = true;
            sleeves++;
            return null;
        }

        /** Every section on its line and clean. */
        boolean done() {
            for (int i = 0; i < size(); i++) if (radius[i] != target[i] || marked[i]) return false;
            return true;
        }

        /** The grade of a finished part: Reserve at the standard or better, Fine within two passes of it, else Standard; a sleeve caps it at Fine. -1 while unfinished. */
        int grade() {
            if (!done()) return -1;
            int g = passes <= standard ? 2 : passes <= standard + 2 ? 1 : 0;
            return sleeves > 0 ? Math.min(1, g) : g;
        }

        /**
         * The fewest passes a drawing needs from the stock (the shop's standard). A greedy plan that roughs every section to one step above its line and then finishes is not the fewest:
         * a finishing pass is also the one-step move between two stages ({7, 4, 3, 2} at a roughing depth of four takes five passes, not seven; a GameTest found it). This is an exact
         * search over windows of the bar: a window standing at one height is cut by one pass per block of neighbouring sections, and each block's sections still above their lines are
         * worked out the same way from the line it was cut to. Checked against a search of every pass in the tests.
         */
        static int fewestPasses(int[] t, int rough) { return new Planner(t, rough).fewest(0, t.length - 1, STOCK); }

        /** A plan that turns the drawing in {@link #fewestPasses} passes: each pass as {from, to, line, finish (1) or rough (0)}, in order. */
        static List<int[]> plan(int[] t, int rough) {
            Planner p = new Planner(t, rough);
            List<int[]> out = new ArrayList<>();
            p.emit(0, t.length - 1, STOCK, out);
            return out;
        }

        /** The interval search behind the standard: {@code fewest(a, b, h)} for sections a..b all standing at h above their lines, memoised. */
        private static final class Planner {
            private final int[] t;
            private final int rough, n;
            private final int[][][] memoWindow, memoBlock, bestLine;

            Planner(int[] t, int rough) {
                this.t = t; this.rough = Math.max(1, rough); this.n = t.length;
                memoWindow = new int[n][n][STOCK + 1];
                memoBlock = new int[n][n][STOCK + 1];
                bestLine = new int[n][n][STOCK + 1];
            }

            /** The fewest passes for a window whose sections all stand at {@code h}, every line below it: split into blocks, one first pass each. */
            int fewest(int a, int b, int h) {
                if (a > b) return 0;
                if (memoWindow[a][b][h] > 0) return memoWindow[a][b][h] - 1;
                int best = Integer.MAX_VALUE;
                for (int k = a; k <= b; k++) best = Math.min(best, block(a, k, h) + fewest(k + 1, b, h));
                memoWindow[a][b][h] = best + 1;
                return best;
            }

            /** One pass over sections a..k from {@code h} to the best line, then what is left under it. A rough pass onto a section's own line would spoil it, so only a finishing pass may. */
            int block(int a, int k, int h) {
                if (memoBlock[a][k][h] > 0) return memoBlock[a][k][h] - 1;
                int top = 0;
                for (int i = a; i <= k; i++) top = Math.max(top, t[i]);
                int best = Integer.MAX_VALUE, line = h - 1;
                for (int l = Math.max(h - rough, top); l < h; l++) {
                    if (h - l != 1 && onSomeLine(a, k, l)) continue;
                    int cost = 1 + under(a, k, l);
                    if (cost < best) { best = cost; line = l; }
                }
                memoBlock[a][k][h] = best + 1;
                bestLine[a][k][h] = line;
                return best;
            }

            private boolean onSomeLine(int a, int k, int l) {
                for (int i = a; i <= k; i++) if (t[i] == l) return true;
                return false;
            }

            /** The passes for the windows of a..k whose lines lie below {@code l} (a section on {@code l} is finished). */
            private int under(int a, int k, int l) {
                int cost = 0, i = a;
                while (i <= k) {
                    if (t[i] >= l) { i++; continue; }
                    int j = i;
                    while (j <= k && t[j] < l) j++;
                    cost += fewest(i, j - 1, l);
                    i = j;
                }
                return cost;
            }

            void emit(int a, int b, int h, List<int[]> out) {
                if (a > b) return;
                int total = fewest(a, b, h);
                for (int k = a; k <= b; k++) {
                    if (block(a, k, h) + fewest(k + 1, b, h) != total) continue;
                    int l = bestLine[a][k][h];
                    out.add(new int[] {a, k, l, h - l == 1 ? 1 : 0});
                    int i = a;
                    while (i <= k) {
                        if (t[i] >= l) { i++; continue; }
                        int j = i;
                        while (j <= k && t[j] < l) j++;
                        emit(i, j - 1, l, out);
                        i = j;
                    }
                    emit(k + 1, b, h, out);
                    return;
                }
            }
        }
    }

    // ------------------------------------------------------------------------------------------------ a machine's drive train

    /** The parts a drive is drawn with, and the part of the Smith's own making that replaces each. */
    enum Kind {
        COUPLING("Coupling", "steel_bracket"), BEARING("Bearing", "bearing_set"), STAGE("Gear stage", "gear_set"), TENSIONER("Tensioner", "springs");
        final String title, replacement;
        Kind(String title, String replacement) { this.title = title; this.replacement = replacement; }
    }

    /** The gears a Smith can cut for a stage, by teeth. */
    static final int[] TEETH = {12, 18, 24, 36, 48};

    static final class Drive {
        final int motor, stages, lo, hi;
        final boolean clockwise;
        /** The drive from the motor: a coupling, then a bearing and a gear stage for each stage, then a tensioner. */
        final Kind[] parts;
        final boolean[] worn, replaced;
        final int[] driver, driven;
        final boolean[] idler, open;
        int tests;

        private Drive(int motor, int stages, int lo, int hi, boolean clockwise, Kind[] parts, boolean[] worn, int[] driver, int[] driven, boolean[] idler, boolean[] open, int tests) {
            this.motor = motor; this.stages = stages; this.lo = lo; this.hi = hi; this.clockwise = clockwise; this.parts = parts; this.worn = worn; this.replaced = new boolean[parts.length];
            this.driver = driver; this.driven = driven; this.idler = idler; this.open = open; this.tests = tests;
        }

        /**
         * A drive for a machine of building tier {@code tier}: one stage, two from tier III, three from tier V; one worn part, two from tier IV; at least one stage open to new gears (a worn
         * stage, or the stage the owner wants geared again). The plate's band is about five per cent either side of a gearing the Smith can reach, and the drive as found does not meet it.
         */
        static Drive generate(long seed, int tier, int tests) {
            Random r = new Random(seed ^ 0xD21FEL);
            int t = Math.max(1, Math.min(6, tier));
            int stages = t >= 5 ? 3 : t >= 3 ? 2 : 1;
            int motor = r.nextBoolean() ? 1440 : 960;
            Kind[] parts = new Kind[2 + 2 * stages];
            parts[0] = Kind.COUPLING;
            for (int s = 0; s < stages; s++) { parts[1 + 2 * s] = Kind.BEARING; parts[2 + 2 * s] = Kind.STAGE; }
            parts[parts.length - 1] = Kind.TENSIONER;
            boolean[] worn = new boolean[parts.length];
            int wornCount = t >= 4 ? 2 : 1;
            while (count(worn) < wornCount) worn[r.nextInt(parts.length)] = true;
            boolean[] open = new boolean[stages];
            for (int s = 0; s < stages; s++) open[s] = worn[2 + 2 * s];
            if (count(open) == 0) open[r.nextInt(stages)] = true;
            int[] goalDriver = new int[stages], goalDriven = new int[stages];
            boolean[] goalIdler = new boolean[stages];
            for (int guard = 0; guard < 200; guard++) {
                for (int s = 0; s < stages; s++) { goalDriver[s] = r.nextInt(TEETH.length); goalDriven[s] = r.nextInt(TEETH.length); goalIdler[s] = r.nextInt(4) == 0; }
                double out = output(motor, goalDriver, goalDriven);
                if (out >= 20 && out <= 2400) break;
            }
            double goal = output(motor, goalDriver, goalDriven);
            int lo = (int) Math.floor(goal * 0.95), hi = (int) Math.ceil(goal * 1.05);
            boolean clockwise = clockwise(goalIdler);
            int[] driver = goalDriver.clone(), driven = goalDriven.clone();
            boolean[] idler = goalIdler.clone();
            // the drive as found: the open stages geared otherwise, so that it misses the plate
            for (int guard = 0; guard < 200; guard++) {
                for (int s = 0; s < stages; s++) if (open[s]) { driver[s] = r.nextInt(TEETH.length); driven[s] = r.nextInt(TEETH.length); idler[s] = r.nextInt(3) == 0; }
                double out = output(motor, driver, driven);
                if (out < lo || out > hi || clockwise(idler) != clockwise) break;
            }
            return new Drive(motor, stages, lo, hi, clockwise, parts, worn, driver, driven, idler, open, Math.max(1, tests));
        }

        private static int count(boolean[] flags) { int n = 0; for (boolean f : flags) if (f) n++; return n; }

        static double output(int motor, int[] driver, int[] driven) {
            double out = motor;
            for (int s = 0; s < driver.length; s++) out = out * TEETH[driver[s]] / TEETH[driven[s]];
            return out;
        }

        /** Each mesh turns the direction round; an idler gear adds a mesh, so a stage with one keeps the direction it was given. The motor turns clockwise. */
        static boolean clockwise(boolean[] idler) {
            int turns = 0;
            for (boolean i : idler) if (!i) turns++;
            return turns % 2 == 0;
        }

        double output() { return output(motor, driver, driven); }
        boolean clockwise() { return clockwise(idler); }
        int stageOf(int part) { return parts[part] == Kind.STAGE ? (part - 2) / 2 : -1; }
        int partOfStage(int stage) { return 2 + 2 * stage; }

        /** Replaces a worn part (the part is used when the machine runs). Returns why not, or null. */
        String replace(int part) {
            if (part < 0 || part >= parts.length) return "Pick a part first.";
            if (!worn[part]) return "That " + parts[part].title.toLowerCase(java.util.Locale.ROOT) + " is sound.";
            if (replaced[part]) return "That part is new already.";
            replaced[part] = true;
            return null;
        }

        /** Steps the driving or driven gear of an open stage up or down a size. Returns why not, or null. */
        String gear(int stage, boolean driving, int step) {
            if (stage < 0 || stage >= stages) return "Pick a stage first.";
            if (!open[stage]) return "Stage " + (stage + 1) + " is geared right; it is not open.";
            if (worn[partOfStage(stage)] && !replaced[partOfStage(stage)]) return "Take the chipped gears out first: replace stage " + (stage + 1) + ".";
            int[] gears = driving ? driver : driven;
            gears[stage] = Math.floorMod(gears[stage] + (step >= 0 ? 1 : -1), TEETH.length);
            return null;
        }

        /** Fits or takes out an idler gear on an open stage. Returns why not, or null. */
        String idler(int stage) {
            if (stage < 0 || stage >= stages) return "Pick a stage first.";
            if (!open[stage]) return "Stage " + (stage + 1) + " is geared right; it is not open.";
            idler[stage] = !idler[stage];
            return null;
        }

        /** What a test run finds: "" when the machine runs as its plate says, else the first thing wrong, in plain words. Each run uses a test. */
        String run() {
            tests--;
            for (int i = 0; i < parts.length; i++)
                if (worn[i] && !replaced[i]) return "It grinds: the worn " + parts[i].title.toLowerCase(java.util.Locale.ROOT) + (parts[i] == Kind.BEARING || parts[i] == Kind.STAGE ? " at stage " + (stageOfAny(i) + 1) : "") + " is still in.";
            double out = output();
            if (out > hi) return "Too fast: " + Math.round(out) + " rpm against the plate's " + lo + " to " + hi + "; the load would slip.";
            if (out < lo) return "Too slow: " + Math.round(out) + " rpm against the plate's " + lo + " to " + hi + "; the work would stall.";
            if (clockwise() != clockwise) return "It runs backwards: the plate wants it " + (clockwise ? "clockwise" : "anticlockwise") + ".";
            return "";
        }

        private int stageOfAny(int part) { return parts[part] == Kind.STAGE ? (part - 2) / 2 : parts[part] == Kind.BEARING ? (part - 1) / 2 : -1; }

        /** The parts a finished repair uses: each worn part's replacement, and a gear set for each open stage that was not worn (its new gears are cut too). */
        java.util.List<String> uses() {
            java.util.List<String> out = new java.util.ArrayList<>();
            for (int i = 0; i < parts.length; i++) if (worn[i]) out.add(parts[i].replacement);
            for (int s = 0; s < stages; s++) if (open[s] && !worn[partOfStage(s)]) out.add(Kind.STAGE.replacement);
            return out;
        }
    }
}
