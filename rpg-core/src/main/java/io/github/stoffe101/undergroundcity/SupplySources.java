package io.github.stoffe101.undergroundcity;

import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;

/**
 * Where a thing can be had, in one short line for a screen (D-175): the cheapest places that sell it (a city's exchange, a shopkeeper's counter in a city or at a roadside place), or else the
 * workshop station that makes it. The first-hour audit found that no screen, station note or employer ever said where a Gunsmith's springs or a Vintner's hoops were sold, so a station's
 * missing parts and an order's missing goods now say it. Read from the same supplier lines the purchase itself uses; nothing here is a second price list.
 */
final class SupplySources {
    private SupplySources() {}

    static final int MAX_PLACES = 2;
    /** The wire carries at most 120 characters of it (TerminalData, WorkshopNetwork); a longer shop title in the data must shorten the hint, never break the view (D-178 review). */
    static final int MAX_LENGTH = 110;

    static String fit(String text) { return text.length() <= MAX_LENGTH ? text : text.substring(0, MAX_LENGTH - 3) + "..."; }

    /** "Sold at Builders' Yard, Ironhaven 12; Ironhaven exchange 18", "Made at a Machinist's Bench", or "" when the world itself supplies it (or nothing does). */
    static String where(String item) {
        if (item == null || item.isEmpty()) return "";
        Set<String> places = new LinkedHashSet<>();
        RegionalCatalog.INSTANCE.all().stream().filter(stock -> stock.item().toString().equals(item)).sorted(Comparator.comparingInt(RegionalStock::unitPrice)).forEach(stock -> {
            for (String outlet : stock.outlets()) {
                if (places.size() >= MAX_PLACES) return;
                String place = place(outlet, stock.city());
                if (!place.isEmpty()) places.add(place + " " + stock.unitPrice());
            }
        });
        if (!places.isEmpty()) return fit("Sold at " + String.join("; ", places));
        for (Workshops.Station station : Workshops.INSTANCE.all().values())
            for (Workshops.Recipe recipe : station.recipes())
                if (recipe.output().toString().equals(item)) return fit("Made at a " + station.title());
        return "";
    }

    /** A supplier line's outlet as a place a person can find: "Ironhaven exchange", "Builders' Yard, Ironhaven", "Harrow Farm Stand" (a roadside place names itself). */
    static String place(String outlet, String city) {
        String town = city.isEmpty() ? "" : city.substring(0, 1).toUpperCase(Locale.ROOT) + city.substring(1);
        if (RegionalStock.CITY.equals(outlet)) return town + " exchange";
        if (!RegionalStock.isShop(outlet)) return "";   // a wayside provisions desk sells survival goods only; it is not where anybody goes for parts
        for (ShopCounters.Shop shop : ShopCounters.INSTANCE.all().values())
            if (shop.outlet().equals(outlet) && shop.city().equals(city)) return shop.building().startsWith("roadside:") ? shop.title() : shop.title() + ", " + town;
        return "";
    }
}
