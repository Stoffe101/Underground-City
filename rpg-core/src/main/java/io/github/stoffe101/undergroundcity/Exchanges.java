package io.github.stoffe101.undergroundcity;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;

import java.util.Optional;

/**
 * Which exchange a placed Operations Terminal belongs to (D-091): inside a city it is that city's exchange; at the provisions desk of a built wayside shelter
 * it is that shelter's outlet of the city nearest to it; at an open shopkeeper's counter (D-126) it is that shop's outlet of its city. All draw on the same finite shared stock of the city and are paid through the same ledger, wallet and
 * Crew rules; the wayside outlet sells fewer things and is only a shop (orders, contacts and the law are the cities'). Anywhere else a terminal has no exchange.
 */
final class Exchanges {
    private Exchanges() {}

    /** {@code city} is a city id; {@code outlet} is {@link RegionalStock#CITY} or {@link RegionalStock#WAYSIDE}. */
    record Exchange(String city, String outlet, String title) {
        Exchange(String city, String outlet) { this(city, outlet, ""); }
        boolean wayside() { return outlet.equals(RegionalStock.WAYSIDE); }
        /** A shopkeeper's counter (D-126): a shop and nothing else, like a wayside desk. */
        boolean counter() { return RegionalStock.isShop(outlet); }
        /** Only a shop: no orders, contacts or law (a wayside desk or a counter). */
        boolean shopOnly() { return wayside() || counter(); }
    }

    static Optional<Exchange> at(Level level, BlockPos pos) {
        // an open shop counter (D-126) sells its own outlet of its city's stock, whatever else stands there
        if (level instanceof ServerLevel server) {
            var counter = ShopCounters.at(server, pos);
            if (counter.isPresent()) return Optional.of(new Exchange(counter.get().shop().city(), counter.get().shop().outlet(), counter.get().shop().title()));
        }
        Optional<String> city = CityRegions.INSTANCE.cityAt(level, pos);
        if (city.isPresent()) return Optional.of(new Exchange(city.get(), RegionalStock.CITY));
        if (level instanceof ServerLevel server && WaysideShelters.shelterAt(server, pos).isPresent()) {
            return CityRegions.INSTANCE.nearest(level, pos).map(nearest -> new Exchange(nearest, RegionalStock.WAYSIDE));
        }
        return Optional.empty();
    }
}
