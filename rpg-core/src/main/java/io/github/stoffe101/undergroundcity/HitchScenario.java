package io.github.stoffe101.undergroundcity;

import net.minecraft.world.phys.Vec3;

import java.util.Locale;

import static io.github.stoffe101.undergroundcity.JourneyScenario.level;
import static io.github.stoffe101.undergroundcity.JourneyScenario.mc;
import static io.github.stoffe101.undergroundcity.JourneyScenario.server;
import static io.github.stoffe101.undergroundcity.JourneyScenario.until;
import static io.github.stoffe101.undergroundcity.ThiefScenario.scripted;

/**
 * What arriving in a city costs the server (the arrival hitch, docs/technical/population.md), through a real client ({@code -PucPlaytest=hitch -PucPtTicksampler=true}, a new furnished
 * archipelago world). The player is put on the road from Ironhaven about 150 blocks short of Valewood (a teleport, logged), stands still, walks the rest of the road and on to a street near
 * the centre with the movement keys, stays three minutes, then is teleported to Blackwater, to Ironhaven and back to Valewood, staying a while each time. {@link TickSampler} keeps every
 * tick's length under the segment it fell in and logs the server thread's stack through every tick over 100 ms; at the end of each segment the street service's counters are logged too.
 */
final class HitchScenario {
    private HitchScenario() {}

    private static final double[] ROAD = new double[2];   // where the walk starts and ends on the road (its distance along the plan)
    private static String route;

    static void build() {
        ThiefScenario.tag = "HITCH";
        Playtest.queue("survival", () -> Playtest.command("gamemode survival"));
        Playtest.queue("noon, the clock stopped", () -> { Playtest.command("gamerule doDaylightCycle false"); Playtest.command("time set 6000"); scripted("the clock was set to noon and stopped"); });
        segment("spawn, standing");
        Playtest.wait("standing at the spawn (30 s)", 600);
        Playtest.queue("the road to Valewood", () -> {
            CorridorPlan plan = roadTo("valewood");
            if (plan == null) { Playtest.fail("no road plan between Ironhaven and Valewood"); return; }
            double first = -1, last = -1;
            for (double at = 0; at <= plan.length(); at += 1) if (plan.openAt(at)) { if (first < 0) first = at; last = at; }
            boolean fromIronhaven = plan.id().startsWith("ironhaven");
            route = plan.id();
            ROAD[1] = fromIronhaven ? last - 4 : first + 4;
            ROAD[0] = fromIronhaven ? ROAD[1] - 150 : ROAD[1] + 150;
            Playtest.log(String.format(Locale.ROOT, "HITCH road %s is %.0f blocks; the walk goes from %.0f to %.0f along it, Valewood's end", route, plan.length(), ROAD[0], ROAD[1]));
        });
        segment("teleport to the road");
        Playtest.queue("onto the road", () -> {
            scripted(String.format(Locale.ROOT, "a teleport onto the road %s at %.0f, about 150 blocks short of Valewood", route, ROAD[0]));
            Playtest.command("gamemode spectator");
            Playtest.command("uc_dev road " + route + " " + (int) Math.round(ROAD[0]));
        });
        until("on the road with the ground under the player", () -> {
            CorridorPlan plan = roadTo("valewood");
            if (plan == null) return true;
            double[] p = plan.pointAt(ROAD[0]);
            return Math.hypot(p[0] - mc().player.getX(), p[1] - mc().player.getZ()) < 8 && Scenarios.groundBelowPlayer();
        }, 6000);
        Playtest.queue("survival on the road", () -> Playtest.command("gamemode survival"));
        segment("on the road, standing");
        Playtest.wait("standing on the road (30 s)", 600);
        segment("walking the road into Valewood");
        final RoadWalker[] walker = new RoadWalker[1];
        Playtest.queue("walk the road to Valewood's end", () -> walker[0] = new RoadWalker(roadTo("valewood"), false, ROAD[0], ROAD[1]), () -> { walker[0].tick(); return walker[0].done(); }, 6000);
        Playtest.queue("the road walked", () -> Playtest.log("HITCH road walk: " + walker[0].summary()));
        segment("walking into Valewood's streets");
        // from the road's end towards the centre with the movement keys for up to two minutes; wherever the walk gets to is where the stay begins (no teleport: this is the walk in)
        final PathWalker[] inward = new PathWalker[1];
        final int[] inwardTicks = {0};
        final Vec3[] from = new Vec3[1];
        Playtest.queue("walk towards Valewood's centre", () -> {
            from[0] = mc().player.position();
            Vec3 centre = ThiefScenario.centre("valewood");
            inward[0] = new PathWalker("walk towards Valewood's centre", () -> new Vec3(centre.x, mc().player.getY(), centre.z), 12.0);
        }, () -> {
            inward[0].tick();
            if (++inwardTicks[0] > 2400 && !inward[0].done()) inward[0].stop();
            return inward[0].done() || inwardTicks[0] > 2400;
        }, 2500);
        Playtest.queue("the walk in", () -> {
            inward[0].stop();
            Vec3 centre = ThiefScenario.centre("valewood");
            Playtest.log(String.format(Locale.ROOT, "HITCH walked %.0f blocks in from the road's end; %.0f blocks from Valewood's centre now (%s)", mc().player.position().distanceTo(from[0]),
                Math.hypot(centre.x - mc().player.getX(), centre.z - mc().player.getZ()), inward[0].summary()));
        });
        segment("Valewood, standing");
        Playtest.wait("standing in Valewood (3 min)", 3600);
        teleport("blackwater");
        teleport("ironhaven");
        teleport("valewood");
        Playtest.queue("summary", () -> server(() -> {
            for (String name : new String[] {"spawn, standing", "teleport to the road", "on the road, standing", "walking the road into Valewood", "walking into Valewood's streets", "Valewood, standing",
                "teleport to blackwater", "blackwater, standing", "teleport to ironhaven", "ironhaven, standing", "teleport to valewood", "valewood, standing"})
                Playtest.log("HITCH " + TickSampler.summary(name));
            return null;
        }));
        Playtest.queue("done", () -> Playtest.log("HITCH RESULT done"));
    }

    /** A teleport to a street near {@code city}'s centre, its chunks loaded, then two minutes there. */
    private static void teleport(String city) {
        segment("teleport to " + city);
        ThiefScenario.travel("to " + city, () -> ThiefScenario.centre(city), 4);
        final Vec3[] street = new Vec3[1];
        Playtest.queue("a street in " + city, () -> {
            Vec3[] found = server(() -> CrowdScenario.street(level(), city, "commercial"));
            street[0] = found == null ? null : found[0];
            if (street[0] == null) { Playtest.log("HITCH no street found in " + city); return; }
            scripted(String.format(Locale.ROOT, "the player was put on a street of %s at %.0f, %.0f, %.0f", city, street[0].x, street[0].y, street[0].z));
            Playtest.command("gamemode survival");
            Playtest.command(String.format(Locale.ROOT, "tp @s %.2f %.2f %.2f", street[0].x, street[0].y, street[0].z));
        });
        until("the ground in " + city, () -> street[0] == null || Scenarios.groundBelowPlayer(), 2400);
        segment(city + ", standing");
        Playtest.wait("standing in " + city + " (2 min)", 2400);
    }

    /** Ends the previous segment with the street service's counters, then starts {@code name}. */
    private static void segment(String name) {
        Playtest.queue("segment: " + name, () -> server(() -> {
            Playtest.log("HITCH street service before '" + name + "': " + PopulationService.status(mc().getSingleplayerServer()));
            TickSampler.segment(name);
            return null;
        }));
    }

    private static CorridorPlan roadTo(String city) {
        for (CorridorPlan plan : CorridorRoadFeature.plans(level())) if (plan.id().contains("ironhaven") && plan.id().contains(city)) return plan;
        for (CorridorPlan plan : DevCommands.plans(mc().getSingleplayerServer())) if (plan.id().contains("ironhaven") && plan.id().contains(city)) return plan;
        return null;
    }
}
