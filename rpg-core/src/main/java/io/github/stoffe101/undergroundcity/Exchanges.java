package io.github.stoffe101.undergroundcity;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;

import java.util.Optional;

/**
 * Which exchange a placed Operations Terminal belongs to (D-091): inside a city it is that city's exchange; at the provisions desk of a built wayside shelter
 * it is that shelter's outlet of the city nearest to it. Both draw on the same finite shared stock of the city and are paid through the same ledger, wallet and
 * Crew rules; the wayside outlet sells fewer things and is only a shop (orders, contacts and the law are the cities'). Anywhere else a terminal has no exchange.
 */
final class Exchanges {
    private Exchanges() {}

    /** {@code city} is a city id; {@code outlet} is {@link RegionalStock#CITY} or {@link RegionalStock#WAYSIDE}. */
    record Exchange(String city, String outlet) {
        boolean wayside() { return outlet.equals(RegionalStock.WAYSIDE); }
    }

    static Optional<Exchange> at(Level level, BlockPos pos) {
        Optional<String> city = CityRegions.INSTANCE.cityAt(level, pos);
        if (city.isPresent()) return Optional.of(new Exchange(city.get(), RegionalStock.CITY));
        if (level instanceof ServerLevel server && WaysideShelters.shelterAt(server, pos).isPresent()) {
            return CityRegions.INSTANCE.nearest(level, pos).map(nearest -> new Exchange(nearest, RegionalStock.WAYSIDE));
        }
        return Optional.empty();
    }
}
