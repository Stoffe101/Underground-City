package io.github.stoffe101.undergroundcity;

import java.util.Locale;

/**
 * How busy a piece of a city is at an hour (D-162): pure arithmetic, so a test can pin a street's day. Every chunk of a city is an <b>area</b> (what stands in it: homes, shops, offices,
 * industry, nightlife, a public building, or open street), each area has a <b>day curve</b> (0 to 1 by hour), and each city leans its own way: Ironhaven's works run shifts round the clock,
 * Valewood is calmer and goes to bed early, Blackwater is the busiest by day and its bars and clubs fill after dark. {@link PopulationService} adds the weights of the chunks round a player and puts
 * that many people on the ground, within the density setting's budget.
 */
final class PopulationDensity {
    private PopulationDensity() {}

    enum Area { STREET, RESIDENTIAL, COMMERCIAL, OFFICE, INDUSTRIAL, NIGHTLIFE, PUBLIC }

    /** The density setting (server config {@code population.density}): how many people a busy street holds, and the budget of bodies. */
    enum Preset {
        QUIET(0.5, 24, 64), NORMAL(1.0, 48, 120), BUSY(1.4, 72, 180);

        final double scale;
        final int perPlayer, world;
        Preset(double scale, int perPlayer, int world) { this.scale = scale; this.perPlayer = perPlayer; this.world = world; }

        static Preset parse(String name) {
            try { return valueOf(name.toUpperCase(Locale.ROOT)); } catch (RuntimeException e) { return NORMAL; }
        }
    }

    /** People per chunk of a fully busy area at the normal setting. */
    static final double PER_CHUNK = 0.9;

    // the share of an area's people who are out, by hour (0 is midnight)
    private static final double[] RESIDENTIAL = {.06, .04, .03, .03, .04, .10, .32, .60, .70, .52, .42, .40, .46, .46, .40, .48, .62, .82, .90, .84, .62, .42, .22, .10};
    private static final double[] COMMERCIAL  = {.02, .01, .01, .01, .01, .03, .10, .30, .52, .72, .90, 1.0, 1.0, 1.0, .95, .95, 1.0, 1.0, .90, .70, .46, .26, .10, .05};
    private static final double[] OFFICE      = {.01, .01, .01, .01, .01, .03, .12, .55, .95, .85, .75, .80, 1.0, 1.0, .80, .75, .80, .95, .60, .20, .08, .04, .02, .01};
    private static final double[] INDUSTRIAL  = {.20, .18, .18, .18, .22, .55, .90, 1.0, .90, .85, .85, .85, .95, .90, .85, .90, .95, .80, .55, .40, .35, .32, .30, .24};
    private static final double[] NIGHTLIFE   = {.85, .70, .45, .20, .06, .02, .02, .04, .06, .08, .12, .20, .30, .30, .25, .25, .30, .40, .55, .70, .85, .95, 1.0, .95};
    private static final double[] PUBLIC      = {.02, .01, .01, .01, .01, .03, .10, .35, .70, .85, .90, .90, .85, .85, .85, .85, .80, .60, .35, .18, .08, .04, .02, .02};

    static double curve(Area area, int hour) {
        int h = Math.floorMod(hour, 24);
        return switch (area) {
            case RESIDENTIAL -> RESIDENTIAL[h];
            case COMMERCIAL -> COMMERCIAL[h];
            case OFFICE -> OFFICE[h];
            case INDUSTRIAL -> INDUSTRIAL[h];
            case NIGHTLIFE -> NIGHTLIFE[h];
            case PUBLIC -> PUBLIC[h];
            // open street carries what passes between the buildings round it: the busier of homes and shops, a little less
            case STREET -> 0.8 * Math.max(RESIDENTIAL[h], COMMERCIAL[h]);
        };
    }

    /** How a city leans: a multiplier on an area's curve at an hour. */
    static double lean(String city, Area area, int hour) {
        boolean night = hour >= 21 || hour < 5;
        return switch (city) {
            case "blackwater" -> switch (area) {
                case NIGHTLIFE -> 1.7;
                case STREET -> night ? 1.5 : 1.25;   // people walk between the bars
                case COMMERCIAL, OFFICE -> 1.3;
                default -> 1.1;
            };
            case "ironhaven" -> switch (area) {
                case INDUSTRIAL -> 1.35;
                case NIGHTLIFE -> 0.8;
                default -> 1.0;
            };
            case "valewood" -> switch (area) {
                case NIGHTLIFE -> 0.6;
                case OFFICE, INDUSTRIAL -> 0.7;
                default -> night ? 0.55 : 0.8;
            };
            default -> 0.8;
        };
    }

    /** The weight of one chunk at an hour, in people at the normal setting. */
    static double weight(String city, Area area, int hour) { return PER_CHUNK * curve(area, hour) * lean(city, area, hour); }

    /** What kind of area a building makes its chunk, from its security class's category and its type (a club, a pub or a bar is nightlife). */
    static Area areaOf(String category, String type) {
        String t = type == null ? "" : type.toLowerCase(Locale.ROOT);
        if (t.contains("club") || t.contains("pub") || t.contains("bar") || t.contains("tavern") || t.contains("nightclub")) return Area.NIGHTLIFE;
        return switch (category == null ? "" : category) {
            case "commercial" -> Area.COMMERCIAL;
            case "office" -> Area.OFFICE;
            case "industrial" -> Area.INDUSTRIAL;
            case "public", "special" -> Area.PUBLIC;
            default -> Area.RESIDENTIAL;
        };
    }

    /** The number of people to have round a player, from the summed weight of the chunks round them, at a setting. */
    static int target(double summedWeight, Preset preset) {
        return Math.max(0, Math.min(preset.perPlayer, (int) Math.round(summedWeight * preset.scale)));
    }
}
