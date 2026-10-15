package io.github.stoffe101.undergroundcity;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.world.level.levelgen.feature.configurations.FeatureConfiguration;

import java.util.ArrayList;
import java.util.List;

/**
 * Datapack configuration of the corridor road feature. It is generated from the blueprint JSON so there are no
 * hardcoded world coordinates in Java: routes are block-coordinate waypoint lists and cities are the circles that
 * Lost Cities owns (the road stops at their edge).
 */
public record CorridorRoadConfig(int width, List<Route> routes, List<City> cities) implements FeatureConfiguration {
    public record Route(String id, List<List<Integer>> waypoints) {
        public static final Codec<Route> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            Codec.STRING.fieldOf("id").forGetter(Route::id),
            Codec.INT.listOf().listOf().fieldOf("waypoints").forGetter(Route::waypoints)
        ).apply(instance, Route::new));
    }

    public record City(String id, double x, double z, double radius) {
        public static final Codec<City> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            Codec.STRING.fieldOf("id").forGetter(City::id),
            Codec.DOUBLE.fieldOf("x").forGetter(City::x),
            Codec.DOUBLE.fieldOf("z").forGetter(City::z),
            Codec.DOUBLE.fieldOf("radius").forGetter(City::radius)
        ).apply(instance, City::new));
    }

    public static final Codec<CorridorRoadConfig> CODEC = RecordCodecBuilder.create(instance -> instance.group(
        Codec.intRange(3, 15).fieldOf("width").forGetter(CorridorRoadConfig::width),
        Route.CODEC.listOf().fieldOf("routes").forGetter(CorridorRoadConfig::routes),
        City.CODEC.listOf().fieldOf("cities").forGetter(CorridorRoadConfig::cities)
    ).apply(instance, CorridorRoadConfig::new));

    /** Unbuilt plans (the terrain profile is added on first use, from the live chunk generator). */
    public List<CorridorPlan> plans() {
        List<CorridorPlan.City> circles = new ArrayList<>();
        for (City city : cities) circles.add(new CorridorPlan.City(city.x, city.z, city.radius));
        List<CorridorPlan> out = new ArrayList<>();
        for (Route route : routes) {
            double[] xs = new double[route.waypoints.size()], zs = new double[route.waypoints.size()];
            for (int i = 0; i < xs.length; i++) {
                List<Integer> point = route.waypoints.get(i);
                if (point.size() != 2) throw new IllegalArgumentException("Waypoint must be [x, z]: " + route.id);
                xs[i] = point.get(0);
                zs[i] = point.get(1);
            }
            out.add(new CorridorPlan(route.id, xs, zs, width, circles));
        }
        return out;
    }
}
