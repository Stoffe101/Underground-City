package io.github.stoffe101.undergroundcity;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import static io.github.stoffe101.undergroundcity.JourneyScenario.level;
import static io.github.stoffe101.undergroundcity.JourneyScenario.mc;
import static io.github.stoffe101.undergroundcity.JourneyScenario.me;
import static io.github.stoffe101.undergroundcity.JourneyScenario.server;
import static io.github.stoffe101.undergroundcity.JourneyScenario.until;
import static io.github.stoffe101.undergroundcity.ThiefScenario.scripted;
import static io.github.stoffe101.undergroundcity.ThiefScenario.shot;

/**
 * How full the streets look (D-162), through a real client ({@code -PucPlaytest=crowd}, a new furnished archipelago world): the player stands in a busy street and a quiet one of each city by day,
 * in Blackwater's bars and clubs at night and in the small hours elsewhere, and at each stop counts, every ten seconds for a minute, the people within 64 blocks, the people actually on screen (in
 * the view and not behind a wall, in each of four directions), the server's tick time, the client's frames a second and what the street service's passes cost; four screenshots look down the street;
 * then the player walks forty blocks along it with the movement keys and counts again.
 *
 * <p>It uses only what the street service had before D-162 too (its counters, its status and the city plan), so the same scenario measured the old service for the "before" numbers. Scripted, each
 * logged: the clock (set per stop, the daylight cycle stopped), the travel between cities and stops (teleports), and the camera turned to each direction for the counts and the pictures.
 */
final class CrowdScenario {
    private CrowdScenario() {}

    record Sample(String stop, String clock, int within64, int inSightServer, int onScreen, int within256, double mspt, int fps, double passMeanUs, long passWorstUs) {}

    private static final List<Sample> SAMPLES = new ArrayList<>();
    private static Vec3 stand, walkTo;

    static void build() {
        ThiefScenario.tag = "CROWD";
        Playtest.queue("survival", () -> Playtest.command("gamemode survival"));
        Playtest.queue("the clock stopped", () -> { Playtest.command("gamerule doDaylightCycle false"); scripted("the daylight cycle was stopped; each stop sets its own hour"); });
        stop("ironhaven", "commercial", 6000, "12:00");
        stop("ironhaven", "residential", 9000, "15:00");
        stop("valewood", "commercial", 5000, "11:00");
        stop("valewood", "residential", 11000, "17:00");
        stop("blackwater", "commercial", 7000, "13:00");
        stop("blackwater", "nightlife", 16000, "22:00");
        stop("ironhaven", "industrial", 21000, "03:00");
        stop("valewood", "residential", 17000, "23:00");
        Playtest.queue("summary", CrowdScenario::summary);
    }

    /** One stop: the place, its hour, a minute of counts, four pictures, a walk along the street and two more counts. */
    private static void stop(String city, String kind, long dayTime, String clock) {
        String label = city + " " + kind + " at " + clock;
        ThiefScenario.travel("to " + city, () -> ThiefScenario.centre(city), 4);
        Playtest.queue("a " + kind + " street in " + city, () -> {
            Vec3[] found = server(() -> street(level(), city, kind));
            if (found == null) { Playtest.log("CROWD no " + kind + " street found in " + city); stand = null; return; }
            stand = found[0]; walkTo = found[1];
            scripted(String.format(Locale.ROOT, "the player was put on a %s street of %s at %.0f, %.0f, %.0f, and the time set to %s", kind, city, stand.x, stand.y, stand.z, clock));
            Playtest.command("time set " + dayTime);
            Playtest.command("gamemode survival");
            Playtest.command(String.format(Locale.ROOT, "tp @s %.2f %.2f %.2f", stand.x, stand.y, stand.z));
        });
        until("the ground at the stop", () -> stand == null || Scenarios.groundBelowPlayer(), 2400);
        Playtest.wait("the street fills (20 s)", 400);
        for (int i = 0; i < 6; i++) {
            sample(label);
            Playtest.wait("ten seconds", 200);
        }
        for (int d = 0; d < 4; d++) {
            final int dir = d;
            Playtest.queue("look " + dir, () -> { if (stand != null) { var p = mc().player; p.setYRot(dir * 90f); p.yHeadRot = p.getYRot(); p.setXRot(4f); } });
            Playtest.wait("draw", 30);
            shot(city + "-" + kind + "-" + clock.replace(":", "") + "-" + "nesw".charAt(dir));
        }
        ThiefScenario.walkOrTeleport("walk forty blocks along the street", () -> stand == null ? mc().player.position() : walkTo, 1.5, 900);
        sample(label + " (after the walk)");
        Playtest.wait("ten seconds", 200);
        sample(label + " (after the walk)");
    }

    /**
     * The counts now. On screen: client-side people within 64 blocks whose eyes are inside the camera's view (the vertical field of view widened by the window's aspect) and in line of sight,
     * the best of the four directions; in sight on the server: the server's own line-of-sight test from the player to each person within 64 blocks, all round.
     */
    private static void sample(String label) {
        Playtest.queue("count: " + label, () -> {
            if (stand == null) return;
            var mc = mc();
            var p = mc.player;
            double vFov = Math.toRadians(mc.options.fov().get());
            double aspect = mc.getWindow().getWidth() / (double) Math.max(1, mc.getWindow().getHeight());
            double hHalf = Math.atan(Math.tan(vFov / 2) * aspect);
            int best = 0;
            List<TownspersonEntity> near = new ArrayList<>();
            for (Entity e : mc.level.entitiesForRendering()) if (e instanceof TownspersonEntity t && t.distanceTo(p) <= 64) near.add(t);
            for (int dir = 0; dir < 4; dir++) {
                double yaw = Math.toRadians(dir * 90.0);
                Vec3 look = new Vec3(-Math.sin(yaw), 0, Math.cos(yaw));
                int seen = 0;
                for (TownspersonEntity t : near) {
                    Vec3 to = t.getEyePosition().subtract(p.getEyePosition());
                    Vec3 flat = new Vec3(to.x, 0, to.z).normalize();
                    double angle = Math.acos(Math.max(-1, Math.min(1, flat.dot(look))));
                    if (angle <= hHalf && p.hasLineOfSight(t)) seen++;
                }
                best = Math.max(best, seen);
            }
            int[] srv = server(() -> {
                var me = me();
                var all = level().getEntitiesOfClass(TownspersonEntity.class, me.getBoundingBox().inflate(64), t -> t.isAlive() && !t.police() && t.distanceTo(me) <= 64);
                int sight = 0;
                for (TownspersonEntity t : all) if (me.hasLineOfSight(t)) sight++;
                int wide = level().getEntitiesOfClass(TownspersonEntity.class, me.getBoundingBox().inflate(256), TownspersonEntity::isAlive).size();
                return new int[] {all.size(), sight, wide};
            });
            double mspt = mc.getSingleplayerServer().getAverageTickTime();
            long passes = PopulationService.passes;
            Sample s = new Sample(label, label.substring(label.lastIndexOf(" at ") + 4).replace(" (after the walk)", ""), srv[0], srv[1], best, srv[2], mspt, mc.getFps(),
                passes == 0 ? 0 : PopulationService.nanosTotal / 1000.0 / passes, PopulationService.nanosWorst / 1000);
            SAMPLES.add(s);
            Playtest.log(String.format(Locale.ROOT, "CROWD %s: %d within 64 blocks, %d in the server's sight, %d on screen (best of four directions), %d within 256; server %.1f ms a tick, %d fps; street pass mean %.0f us, worst %d us",
                label, s.within64(), s.inSightServer(), s.onScreen(), s.within256(), s.mspt(), s.fps(), s.passMeanUs(), s.passWorstUs()));
        });
    }

    /**
     * A street chunk of the kind wanted near the city's centre: open street (no building) whose neighbours within a chunk are mostly that kind (shops and offices for "commercial", homes for
     * "residential", bars and clubs for "nightlife", works for "industrial"), and a second street spot about forty blocks away to walk to.
     */
    static Vec3[] street(ServerLevel level, String city, String kind) {
        var region = CityRegions.INSTANCE.all().stream().filter(r -> r.id().equals(city)).findFirst().orElse(null);
        if (region == null) return null;
        int cx = region.centerChunkX(), cz = region.centerChunkZ();
        ChunkPos best = null;
        int bestScore = 0;
        for (int dx = -7; dx <= 7; dx++) for (int dz = -7; dz <= 7; dz++) {
            int x = cx + dx, z = cz + dz;
            if (!level.hasChunk(x, z) || CityBuildings.INSTANCE.ofChunk(level, x, z).isPresent() || PopulationService.streetOf(level, new ChunkPos(x, z)) < 0) continue;
            int score = 0;
            for (int ax = -1; ax <= 1; ax++) for (int az = -1; az <= 1; az++) {
                var b = CityBuildings.INSTANCE.ofChunk(level, x + ax, z + az).orElse(null);
                if (b != null && fits(b, kind)) score++;
            }
            if (score > bestScore) { bestScore = score; best = new ChunkPos(x, z); }
        }
        if (best == null) return null;
        BlockPos at = spotIn(level, best);
        if (at == null) return null;
        // somewhere to walk to: a street spot two or three chunks off, the farthest found
        BlockPos to = null;
        for (int dx = -3; dx <= 3; dx++) for (int dz = -3; dz <= 3; dz++) {
            if (Math.max(Math.abs(dx), Math.abs(dz)) < 2) continue;
            ChunkPos c = new ChunkPos(best.x + dx, best.z + dz);
            if (!level.hasChunk(c.x, c.z) || CityBuildings.INSTANCE.ofChunk(level, c.x, c.z).isPresent() || PopulationService.streetOf(level, c) < 0) continue;
            BlockPos s = spotIn(level, c);
            if (s != null && (to == null || Math.abs(s.distSqr(at) - 1600) < Math.abs(to.distSqr(at) - 1600))) to = s;
        }
        return new Vec3[] {Vec3.atBottomCenterOf(at), Vec3.atBottomCenterOf(to == null ? at : to)};
    }

    private static boolean fits(CityBuildings.Building b, String kind) {
        String category = b.security() == null ? "public" : CityBuildings.INSTANCE.category(b.security().id());
        String type = b.type().toLowerCase(Locale.ROOT);
        boolean nightlife = type.contains("club") || type.contains("pub") || type.contains("bar");
        return switch (kind) {
            case "commercial" -> category.equals("commercial") || category.equals("office");
            case "nightlife" -> nightlife || category.equals("commercial");
            case "industrial" -> category.equals("industrial");
            default -> category.equals("residential");
        };
    }

    private static BlockPos spotIn(ServerLevel level, ChunkPos chunk) {
        int street = PopulationService.streetOf(level, chunk);
        if (street < 0) return null;
        for (int x = 4; x < 12; x++) for (int z = 4; z < 12; z++) for (int y = street + 2; y >= street; y--) {
            BlockPos feet = new BlockPos(chunk.getMinBlockX() + x, y, chunk.getMinBlockZ() + z);
            if (PopulationService.standable(level, feet) && BuildingGuard.exposed(level, feet.above(2))) return feet;
        }
        return null;
    }

    private static void summary() {
        Playtest.log("CROWD summary (stop | within 64 | server sight | on screen | within 256 | ms a tick | fps | pass mean us | pass worst us):");
        for (Sample s : SAMPLES)
            Playtest.log(String.format(Locale.ROOT, "CROWD | %s | %d | %d | %d | %d | %.1f | %d | %.0f | %d", s.stop(), s.within64(), s.inSightServer(), s.onScreen(), s.within256(), s.mspt(), s.fps(), s.passMeanUs(), s.passWorstUs()));
        Playtest.log("CROWD status: " + server(() -> PopulationService.status(level().getServer())));
        Playtest.log("CROWD households: " + HouseholdService.status());
        ThiefScenario.summary();
    }
}
