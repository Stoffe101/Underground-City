package io.github.stoffe101.undergroundcity;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.util.KeyDispatchDataCodec;
import net.minecraft.world.level.levelgen.DensityFunction;

import java.util.Locale;
import java.util.Optional;

/**
 * One climate or terrain channel of the archipelago world as a density function ({@code underground_city:island}), so the vanilla noise
 * terrain and the vanilla biome source follow the islands without any of Minecraft's own functions being copied. Each channel reads the
 * {@link IslandPlan} (islands, plateaus, climates) and, where it has one, the vanilla function it is modifying as {@code input}:
 *
 * <ul>
 *   <li>{@code continents}: land and sea from the plan's shore, with a little of the vanilla noise ({@code input}) so coasts are not too smooth;</li>
 *   <li>{@code erosion}, {@code ridges}: gentler hills everywhere and dead flat, at a chosen height, under a city or the arrival site (no river, no swamp);</li>
 *   <li>{@code temperature}, {@code humidity}: each island's climate plus a little of the vanilla noise, kept out of the frozen and hot bands.</li>
 * </ul>
 * Everything is a pure function of the block column, which is what the climate sampler and the terrain need.
 */
public final class IslandField implements DensityFunction {
    /** The value the flat ground under a city is given as continentalness, calibrated so the terrain lands on Lost Cities' ground level (see docs/technical/archipelago-world.md). */
    public static final double CITY_CONTINENTS = Double.parseDouble(System.getProperty("uc.island.cityContinents", "0.20"));
    /** Erosion under a city. Minecraft's band 0.55 and above is where it puts swamps and flattens the land to sea level; 0.05 to 0.45 is the gentle middle band (plains, forests) that still lies above the water. */
    public static final double FLAT_EROSION = Double.parseDouble(System.getProperty("uc.island.flatErosion", "0.30"));
    /**
     * The peaks-and-valleys weirdness under a city. Minecraft's height inside the middle erosion bands is decided by it, not by continentalness: 0 is a valley floor (and a river), 1/3 is the
     * middle height, 2/3 is a peak. Holding it constant there is what makes the ground under a city level, and its value is what puts that ground at Lost Cities' ground level 71 (calibrated, see docs/technical/archipelago-world.md).
     */
    public static final double PLATEAU_RIDGES = Double.parseDouble(System.getProperty("uc.island.plateauRidges", "0.51"));

    /**
     * Humidity under a city: the middle of Minecraft's "plains" band (-0.35 to -0.1 at a neutral temperature). A plains biome has next to no trees (one in twenty chunks gets one), and the first
     * survey of the generated cities found tens of thousands of leaf blocks and thousands of logs over streets and roofs wherever the island's own climate was a forest. The countryside keeps its
     * island's forest, birch wood or dark wood; only the ground under and around a city (the plateau) is blended to this value.
     */
    public static final double CITY_HUMIDITY = Double.parseDouble(System.getProperty("uc.island.cityHumidity", "-0.22"));

    /** How far the plains-humidity under a city reaches: the plateau's radius less this many blocks (so about 20 beyond the city circle), easing out over this share of the plateau's own easing. */
    public static final double HUMIDITY_SHRINK = Double.parseDouble(System.getProperty("uc.island.humidityShrink", "20")), HUMIDITY_EASE = Double.parseDouble(System.getProperty("uc.island.humidityEase", "0.5"));

    public enum Channel {
        CONTINENTS(-1.2, 1.0), EROSION(-1.0, 1.0), TEMPERATURE(-0.2, 0.7), HUMIDITY(-1.0, 1.0), RIDGES(-1.0, 1.0);
        final double min, max;
        Channel(double min, double max) { this.min = min; this.max = max; }
        static Channel of(String name) { return valueOf(name.toUpperCase(Locale.ROOT)); }
        String id() { return name().toLowerCase(Locale.ROOT); }
    }

    private static final Codec<Channel> CHANNEL_CODEC = Codec.STRING.xmap(Channel::of, Channel::id);
    private static final Codec<IslandField> DATA_CODEC = RecordCodecBuilder.create(i -> i.group(
        CHANNEL_CODEC.fieldOf("channel").forGetter(f -> f.channel),
        IslandPlan.CODEC.fieldOf("plan").forGetter(f -> f.plan),
        DensityFunction.HOLDER_HELPER_CODEC.optionalFieldOf("input").forGetter(f -> Optional.ofNullable(f.input))
    ).apply(i, (channel, plan, input) -> new IslandField(channel, plan, input.orElse(null))));
    public static final KeyDispatchDataCodec<IslandField> CODEC = KeyDispatchDataCodec.of(DATA_CODEC);

    private final Channel channel;
    private final IslandPlan plan;
    private final DensityFunction input;
    private final int hash;

    public IslandField(Channel channel, IslandPlan plan, DensityFunction input) {
        this.channel = channel;
        this.plan = plan;
        this.input = input;
        this.hash = java.util.Objects.hash(channel, plan, input);
    }

    /**
     * Equal by value, as Minecraft's own density functions (records) are. A noise chunk wraps each distinct function once, keyed by equality, and the terrain functions refer to an island
     * channel from many places (offset, factor, jaggedness, depth, sloped cheese and their splines): compared by identity, every reference kept its own copy of everything above it. A road check
     * in a new archipelago world filled a 3 GB heap with 2.3 million copies held by 2,502 noise chunks (October 24).
     */
    @Override public boolean equals(Object o) {
        return this == o || o instanceof IslandField f && hash == f.hash && channel == f.channel && (plan == f.plan || plan.equals(f.plan)) && java.util.Objects.equals(input, f.input);
    }

    @Override public int hashCode() { return hash; }

    @Override public double compute(FunctionContext context) {
        double base = input == null ? 0.0 : input.compute(context);
        return evaluate(channel, plan, base, context.blockX(), context.blockZ());
    }

    /** The channel's value for a column given the vanilla value it modifies (0 when it has none). Public so the terrain survey can sample the plan without a world. */
    public static double evaluate(Channel channel, IslandPlan plan, double base, double x, double z) {
        return switch (channel) {
            case CONTINENTS -> {
                double land = plan.continents(x, z) + 0.12 * clamp(base, -1, 1);
                double p = plan.plateau(x, z);
                // under a city the shore is far away; blend to the calibrated flat value only where the column really is land
                double shoreD = plan.distanceToShore(x, z);
                double landWeight = p * clamp(shoreD / 40.0, 0, 1);
                yield clamp(land * (1 - landWeight) + CITY_CONTINENTS * landWeight, Channel.CONTINENTS.min, Channel.CONTINENTS.max);
            }
            case EROSION -> {
                double hills = 0.2 * base + 0.22;
                double p = plan.plateau(x, z);
                yield clamp(hills * (1 - p) + FLAT_EROSION * p, Channel.EROSION.min, Channel.EROSION.max);
            }
            case RIDGES -> {
                double p = plan.plateau(x, z);
                yield clamp(base * (1 - p) + PLATEAU_RIDGES * p, Channel.RIDGES.min, Channel.RIDGES.max);
            }
            case TEMPERATURE -> clamp(plan.climate(x, z, true, 0.06) + 0.10 * clamp(base, -1, 1), Channel.TEMPERATURE.min, Channel.TEMPERATURE.max);
            case HUMIDITY -> {
                double p = plan.plateau(x, z, HUMIDITY_SHRINK, HUMIDITY_EASE);
                double island = plan.climate(x, z, false, 0.0) + 0.12 * clamp(base, -1, 1);
                yield clamp(island * (1 - p) + CITY_HUMIDITY * p, Channel.HUMIDITY.min, Channel.HUMIDITY.max);
            }
        };
    }

    private static double clamp(double v, double lo, double hi) { return v < lo ? lo : Math.min(v, hi); }

    @Override public void fillArray(double[] array, ContextProvider provider) { provider.fillAllDirectly(array, this); }

    @Override public DensityFunction mapAll(Visitor visitor) {
        return visitor.apply(new IslandField(channel, plan, input == null ? null : input.mapAll(visitor)));
    }

    @Override public double minValue() { return channel.min; }
    @Override public double maxValue() { return channel.max; }
    @Override public KeyDispatchDataCodec<? extends DensityFunction> codec() { return CODEC; }
}
