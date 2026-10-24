package io.github.stoffe101.undergroundcity;

import net.minecraft.client.Minecraft;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

/**
 * What this player is tracking, as the server last said (D-161): drawn on the status display's next-step card, and handed to Xaero's Minimap as a temporary waypoint when that mod is installed
 * and works ({@link XaeroWaypoints}). Display only: the server owns the choice.
 */
@OnlyIn(Dist.CLIENT)
public final class ClientNavigation {
    private static volatile Navigation.Destination current;

    private ClientNavigation() {}

    static void update(Navigation.Destination next) {
        current = next;
        XaeroWaypoints.want(next);
    }

    static Navigation.Destination current() { return current; }

    /** True while {@code kind} and {@code ref} name what is tracked now. */
    static boolean tracking(String kind, String ref) {
        Navigation.Destination d = current;
        return d != null && d.kind().equals(kind) && d.ref().equals(ref);
    }

    /** The status display's next-step card: the tracked destination while there is one in this dimension, else the journey's own next step. */
    static Journey.Objective objectiveOr(Journey.Objective journey) {
        Navigation.Destination d = current;
        Minecraft mc = Minecraft.getInstance();
        if (d == null || mc.level == null || !mc.level.dimension().location().toString().equals(d.dimension())) return journey;
        String heading = switch (d.kind()) { case "tech" -> "Service call"; case "place" -> "Tracking a place"; case "supply" -> "Delivery"; default -> "Tracking"; };
        String stop = d.kind().equals("tech") ? "Stop or change it on your computer." : "Stop or change it in the Places book (J).";
        return new Journey.Objective(heading, d.title() + ". " + d.detail() + ". " + stop, "", d.pos(), d.title());
    }

    /**
     * Every client tick (called from {@link ClientPlaces}' clock, which runs every tick): clears the client's copy when the player leaves a world (the server sends it again on joining) and keeps
     * Xaero's waypoint in step.
     */
    static void clientTick(Minecraft mc) {
        if (mc.player == null || mc.level == null) {
            if (current != null) { current = null; XaeroWaypoints.want(null); }
            return;
        }
        XaeroWaypoints.tick(mc);
    }
}
