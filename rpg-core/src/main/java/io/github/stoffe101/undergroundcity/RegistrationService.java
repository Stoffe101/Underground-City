package io.github.stoffe101.undergroundcity;

import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

import java.util.Locale;

/**
 * The one place a citizen is created, whether the player used the first-login screen or {@code /uc register}.
 * Server-authoritative: the client only proposes a city and a profession; the identifiers are validated here, a record
 * can be created once, and the welcome pack is granted exactly when the record is created.
 */
public final class RegistrationService {
    public enum Outcome { REGISTERED, ALREADY_REGISTERED, INVALID }

    private RegistrationService() {}

    public static Outcome choose(ServerPlayer player, String city, String career) {
        String cityId = city == null ? "" : city.toLowerCase(Locale.ROOT);
        String careerId = career == null ? "" : career.toLowerCase(Locale.ROOT);
        if (!ProgressionRules.validCity(cityId) || !ProgressionRules.validCareer(careerId)) return Outcome.INVALID;
        CitizenState state = CitizenState.get(player.server);
        if (!state.register(player.getUUID(), cityId, careerId)) return Outcome.ALREADY_REGISTERED;
        int given = StarterKits.INSTANCE.grant(player, careerId);
        player.sendSystemMessage(Component.literal("You are a citizen of " + (cityId.isEmpty() ? "" : Character.toUpperCase(cityId.charAt(0)) + cityId.substring(1)) + ", working as a " + Dialogue.careerTitle(careerId).toLowerCase(Locale.ROOT)
            + ". Welcome pack: " + given + " items, including an Operations Terminal."));
        String blueprint = BlueprintGate.unread(player);
        if (blueprint != null) {
            player.sendSystemMessage(Component.literal("Your pack holds a " + blueprint + ": right-click it to learn the gun. The Gun Smith Table shows only the guns you have learned."));
            Notice.send(player, Notice.Kind.INFO, "Read your blueprint", "Right-click the " + blueprint + " to learn it before you use the Gun Smith Table.");
        }
        return Outcome.REGISTERED;
    }

    public static boolean registered(ServerPlayer player) {
        return CitizenState.get(player.server).find(player.getUUID()).isPresent();
    }

    /** Asks the client to show the registration screen (first login, or /uc start). A registered citizen is never asked. */
    public static boolean prompt(ServerPlayer player) {
        if (registered(player)) return false;
        TerminalNetwork.openRegistration(player);
        return true;
    }

    /** The packet handler: validates, registers and tells the client how it went. */
    static void handleChoice(ServerPlayer player, String city, String career) {
        Outcome outcome = choose(player, city, career);
        String message = switch (outcome) {
            case REGISTERED -> "Welcome to Underground City.";
            case ALREADY_REGISTERED -> "You are already registered. Your identity is unchanged.";
            case INVALID -> "That city or profession is not available.";
        };
        TerminalNetwork.registrationResult(player, outcome != Outcome.INVALID, message);
    }
}
