package io.github.stoffe101.undergroundcity;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * The people of a city as arithmetic (D-108): who they are, where they live, and where they are at a given hour. Pure functions of the city, the world seed and the clock, with no Minecraft types,
 * so a test can prove that the same person is the same person every time, that a street is busy by day and quiet at night, and that nothing needs saving.
 *
 * <p>A city does not hold a thousand entities. It holds a <b>roster</b> of a few dozen named people (identity, face, trade, a district to live in) that costs nothing while nobody is near, and
 * a <b>schedule</b> that says, for any hour, whether each of them is out in the street, standing at a post, or indoors. {@link PopulationService} turns the roster into a handful of real
 * entities around each player and removes them again; nobody is simulated that nobody can see.
 */
final class PopulationPlan {
    private PopulationPlan() {}

    /** One person. {@code district} is a chunk offset from the city centre, in chunks, where they spend the day; {@code skin} indexes the generated citizen skins. */
    record Person(long id, String name, int skin, String role, String city, int districtX, int districtZ) {
        /** A stable number for this person in this world, for hashes that vary their day. */
        long seed(long worldSeed) { return mix(id ^ worldSeed); }
    }

    /** Where a person is at a given hour: out in the street, standing at a post, or indoors (not an entity at all). */
    enum Place { STREET, POST, HOME }

    /** Roles and their share of a city (parts of 100, summing to 100); guards stand at posts, the rest walk. */
    record Mix(int resident, int worker, int courier, int guard) {
        Mix {
            if (resident < 0 || worker < 0 || courier < 0 || guard < 0 || resident + worker + courier + guard != 100) throw new IllegalArgumentException("role shares must sum to 100");
        }
    }

    /** The names a city's people draw from. */
    record Names(List<String> first, List<String> last) {
        Names {
            if (first.size() < 8 || last.size() < 8) throw new IllegalArgumentException("a name pool needs at least eight first and eight last names");
        }
    }

    /** Outfit indexes in the generated skins ({@code tools/art/gen_citizens.py}: skin {@code i} wears outfit {@code i % 12}). */
    static final int OUTFITS = 12, SKINS = 48;

    /** The outfits that say each role (dockhand 0, foundry 1, office 2, shopkeeper 3, farmer 4, vintner 5, technician 6, resident 7 and 8, courier 9, guard 10, mechanic 11). */
    static int[] outfitsOf(String role, String city) {
        return switch (role) {
            case "guard" -> new int[] {10};
            case "courier" -> new int[] {9};
            case "worker" -> switch (city) {
                case "ironhaven" -> new int[] {0, 1, 11, 6};
                case "valewood" -> new int[] {4, 5, 3};
                default -> new int[] {2, 6, 3};
            };
            default -> new int[] {7, 8};
        };
    }

    static long mix(long value) {
        long z = value * 0x9E3779B97F4A7C15L + 0x94D049BB133111EBL;
        z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
        z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
        return z ^ (z >>> 31);
    }

    static long cityKey(String city) {
        long h = 1125899906842597L;
        for (int i = 0; i < city.length(); i++) h = 31 * h + city.charAt(i);
        return h;
    }

    /**
     * The roster of a city: {@code count} people, the same every time for the same city and world seed. Roles follow the mix; each person has a name that is unique within the city, a skin whose
     * outfit says their role, and a district within {@code reachChunks} of the centre.
     */
    static List<Person> roster(String city, long worldSeed, int count, Mix mix, Names names, int reachChunks) {
        if (count < 0 || count > 400) throw new IllegalArgumentException("a roster holds at most 400 people");
        Random random = new Random(mix(worldSeed ^ cityKey(city)));
        List<Person> people = new ArrayList<>();
        java.util.Set<String> used = new java.util.HashSet<>();
        int guards = Math.round(count * mix.guard() / 100f), couriers = Math.round(count * mix.courier() / 100f), workers = Math.round(count * mix.worker() / 100f);
        for (int i = 0; i < count; i++) {
            String role = i < guards ? "guard" : i < guards + couriers ? "courier" : i < guards + couriers + workers ? "worker" : "resident";
            String name;
            int guard = 0;
            do { name = names.first().get(random.nextInt(names.first().size())) + " " + names.last().get(random.nextInt(names.last().size())); } while (!used.add(name) && ++guard < 200);
            int[] outfits = outfitsOf(role, city);
            int skin = outfits[random.nextInt(outfits.length)] + OUTFITS * random.nextInt(SKINS / OUTFITS);
            // a district: anywhere in a square of chunks around the centre, with guards kept near the middle and the rest spread out
            int reach = role.equals("guard") ? Math.max(1, reachChunks / 2) : reachChunks;
            long id = mix(cityKey(city) * 31 + i + 1);
            people.add(new Person(id, name, skin, role, city, random.nextInt(2 * reach + 1) - reach, random.nextInt(2 * reach + 1) - reach));
        }
        return List.copyOf(people);
    }

    /** The hour of the day (0 to 23) for a world clock; the game's day starts at 6 in the morning. */
    static int hourOf(long dayTime) { return (int) (((Math.floorMod(dayTime, 24000L) / 1000L) + 6) % 24); }

    /** The day count for a world clock, for hashes that change a person's day from one day to the next. */
    static long dayOf(long dayTime) { return Math.floorDiv(dayTime, 24000L); }

    /**
     * Where a person is at {@code hour}. Residents are out for a morning and an afternoon walk, whose start shifts by a person-specific one to two hours; workers are out from early morning to early
     * evening; couriers for most of the day; guards stand at posts round the clock, thinner at night. Nobody is out between ten at night and six in the morning except the guards.
     */
    static Place place(Person person, long worldSeed, int hour, long day) {
        long h = mix(person.seed(worldSeed) + day * 31);
        int shift = (int) (h & 1) + (int) ((h >>> 1) & 1);   // 0 to 2 hours
        boolean out = switch (person.role()) {
            case "guard" -> true;
            case "worker" -> hour >= 7 + (shift & 1) && hour < 18 + (shift >> 1);
            case "courier" -> hour >= 8 && hour < 19 + (shift & 1);
            default -> (hour >= 8 + shift && hour < 12) || (hour >= 15 && hour < 19 + (shift & 1));
        };
        if (!out) return Place.HOME;
        if (person.role().equals("guard")) return (hour >= 22 || hour < 6) && (h & 3) != 0 ? Place.HOME : Place.POST;
        return Place.STREET;
    }

    /** How many of a roster are out at {@code hour} (an average over a few days), for tests and the status command. */
    static double averageOut(List<Person> roster, long worldSeed, int hour) {
        if (roster.isEmpty()) return 0;
        int out = 0, total = 0;
        for (long day = 0; day < 5; day++) for (Person person : roster) { total++; if (place(person, worldSeed, hour, day) != Place.HOME) out++; }
        return out / (double) total;
    }
}
