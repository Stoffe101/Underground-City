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

    /**
     * {@code city} is a city id; {@code outlet} is {@link RegionalStock#CITY} or {@link RegionalStock#WAYSIDE}, or a shop's outlet. {@code roadside} for a shop of a roadside place (D-138): its stock is
     * booked to a city, but it stands on the road, in no city.
     */
    record Exchange(String city, String outlet, String title, boolean roadside) {
        Exchange(String city, String outlet) { this(city, outlet, "", false); }
        Exchange(String city, String outlet, String title) { this(city, outlet, title, false); }
        boolean wayside() { return outlet.equals(RegionalStock.WAYSIDE); }
        /** A shopkeeper's counter (D-126): a shop and nothing else, like a wayside desk. */
        boolean counter() { return RegionalStock.isShop(outlet); }
        /** Only a shop: no orders, contacts or law (a wayside desk or a counter). */
        boolean shopOnly() { return wayside() || counter(); }
        /**
         * Who a purchase names as the seller: a city's shop "in" its city, a roadside place's shop by its name alone (the Crossroads Diner's cook and its clerk were "in Valewood" and "in Ironhaven"
         * for one diner on the open road), a desk or an exchange by its city.
         */
        String seller() {
            String name = TerminalScreenText.capitalize(city);
            return counter() ? (roadside ? title : title + " in " + name) : wayside() ? "the " + name + " wayside desk" : "the " + name + " exchange";
        }
    }

    /** The exchange of a shopkeeper's counter: its outlet of its city's stock, and whether it is a roadside place's. */
    static Exchange of(ShopCounters.Shop shop) { return new Exchange(shop.city(), shop.outlet(), shop.title(), shop.building().startsWith(RoadsidePlaces.SHOP_PREFIX)); }

    static Optional<Exchange> at(Level level, BlockPos pos) {
        // an open shop counter (D-126) sells its own outlet of its city's stock, whatever else stands there
        if (level instanceof ServerLevel server) {
            var counter = ShopCounters.at(server, pos);
            if (counter.isPresent()) return Optional.of(of(counter.get().shop()));
        }
        Optional<String> city = CityRegions.INSTANCE.cityAt(level, pos);
        if (city.isPresent()) return Optional.of(new Exchange(city.get(), RegionalStock.CITY));
        if (level instanceof ServerLevel server && WaysideShelters.shelterAt(server, pos).isPresent()) {
            return CityRegions.INSTANCE.nearest(level, pos).map(nearest -> new Exchange(nearest, RegionalStock.WAYSIDE));
        }
        return Optional.empty();
    }
}
