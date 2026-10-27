package io.github.stoffe101.undergroundcity;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * A city's good name for a player (tenth pass, D-204): a small, lightweight standing in each of the three cities, from 0 to {@link #MAX}, kept with the citizen. The city's heat stays the measure of
 * trouble (it rises with crime and cools on its own); standing is the other side, and is earned only by what a city sees done for it:
 * <ul>
 *   <li>a legal delivery settled in the city: {@link #DELIVERY};</li>
 *   <li>a civic contribution: {@link #CIVIC} more;</li>
 *   <li>a short shelf filled at one of its shops: {@link #SHORTAGE}.</li>
 * </ul>
 * At most {@link #HOURLY_CAP} a city in an hour of the server's time. An arrest there costs {@link #ARREST}.
 *
 * <p>What it gives, never more: from {@link #WELL_SPOKEN} people in the city say they have heard of you; from {@link #RESPECTED} the city's exchange sells to you {@link #DISCOUNT_PERCENT}%
 * cheaper (with the career skills and Trading, at most the shared 10%). The player is told when a step is reached or lost. Nothing is earned offline.
 */
final class CityStanding {
    static final int MAX = 100, WELL_SPOKEN = 25, RESPECTED = 50;
    static final int DELIVERY = 1, CIVIC = 3, SHORTAGE = 2, ARREST = 15, HOURLY_CAP = 10;
    static final int DISCOUNT_PERCENT = 2;
    static final long HOUR_TICKS = 72_000;
    private static final Map<String, long[]> HOUR = new HashMap<>();   // player|city -> {hour start, earned}

    private CityStanding() {}

    static int of(CitizenState.Citizen citizen, String city) { return citizen == null ? 0 : citizen.standing.getOrDefault(city, 0); }

    /** The exchange discount a city's good name gives there: 0 or {@link #DISCOUNT_PERCENT}%. */
    static double discount(CitizenState.Citizen citizen, String city) { return of(citizen, city) >= RESPECTED ? DISCOUNT_PERCENT / 100.0 : 0; }

    /** Raises a city's good name for good done there, within the hourly cap; tells the player when a step is reached. Returns what was added. */
    static int earn(ServerPlayer player, String city, int amount) {
        if (player == null || amount <= 0 || !ProgressionRules.validCity(city)) return 0;
        CitizenState state = CitizenState.get(player.server);
        CitizenState.Citizen citizen = state.find(player.getUUID()).orElse(null);
        if (citizen == null) return 0;
        long now = player.server.getTickCount();
        long[] hour = HOUR.computeIfAbsent(player.getUUID() + "|" + city, k -> new long[] {now, 0});
        if (now - hour[0] >= HOUR_TICKS) { hour[0] = now; hour[1] = 0; }
        int add = (int) Math.min(amount, HOURLY_CAP - hour[1]);
        if (add <= 0) return 0;
        hour[1] += add;
        int before = of(citizen, city), after = state.changeStanding(player.getUUID(), city, add);
        String name = TerminalScreenText.capitalize(city);
        if (before < WELL_SPOKEN && after >= WELL_SPOKEN) Notice.send(player, Notice.Kind.TRUST, name + " speaks well of you", "People in " + name + " have heard of the work you do for the city.");
        if (before < RESPECTED && after >= RESPECTED) Notice.send(player, Notice.Kind.TRUST, "Respected in " + name, name + "'s exchange sells to you " + DISCOUNT_PERCENT + "% cheaper (within the 10% cap).");
        return after - before;
    }

    /** An arrest in the city: its good name falls; the player is told when a step is lost. */
    static void arrested(MinecraftServer server, ServerPlayer player, String city) {
        if (player == null || !ProgressionRules.validCity(city)) return;
        CitizenState state = CitizenState.get(server);
        CitizenState.Citizen citizen = state.find(player.getUUID()).orElse(null);
        if (citizen == null) return;
        int before = of(citizen, city), after = state.changeStanding(player.getUUID(), city, -ARREST);
        String name = TerminalScreenText.capitalize(city);
        if (before >= RESPECTED && after < RESPECTED) Notice.send(player, Notice.Kind.HEAT, "No longer respected in " + name, "The exchange's discount is gone until the city thinks well of you again.");
        else if (before >= WELL_SPOKEN && after < WELL_SPOKEN) Notice.send(player, Notice.Kind.HEAT, name + " has heard otherwise", "An arrest is talked about.");
    }

    /** What people of the city say first to somebody of good name there, or "". */
    static String greeting(CitizenState.Citizen citizen, String city, String name) {
        int s = of(citizen, city);
        if (s >= RESPECTED) return "You're the one who does so much for " + TerminalScreenText.capitalize(city) + ", aren't you, " + name + "? ";
        if (s >= WELL_SPOKEN) return "I've heard your name about town, " + name + ". ";
        return "";
    }

    /** Test seam. */
    static void clearForTest() { HOUR.clear(); }

    static String key(UUID player, String city) { return player + "|" + city; }
}
