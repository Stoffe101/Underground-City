package io.github.stoffe101.undergroundcity;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.Vec3;

import java.util.Locale;

import static io.github.stoffe101.undergroundcity.JourneyScenario.level;
import static io.github.stoffe101.undergroundcity.JourneyScenario.mc;
import static io.github.stoffe101.undergroundcity.JourneyScenario.me;
import static io.github.stoffe101.undergroundcity.JourneyScenario.server;
import static io.github.stoffe101.undergroundcity.ThiefScenario.pass;
import static io.github.stoffe101.undergroundcity.ThiefScenario.scripted;

/**
 * One building, end to end, through a real client ({@code -PucPlaytest=building -PucPtBuilding=<namespace:building> -PucPtCity=<city>}): any building a world generates, our own or a private
 * pack's (D-192), named at run time so nothing here names a private building. A new citizen travels to the city; the server finds the nearest generated copy of the building and logs what the game
 * makes of it (its security class and tier, whether it is locked, its plan's spots and units, whether it is to let, who lives there); a spectator camera photographs it from four sides and every
 * floor inside it (the frames stay in the run folder; a private building's frames are private); then the citizen tries its street door with an empty hand, and picks it on the real lock screen.
 *
 * <p>Scripted, each logged: the clock, the travel, the camera's positions (a photograph, not play), standing at the door and the lockpicks.
 */
final class BuildingScenario {
    private BuildingScenario() {}

    private static final class Facts {
        volatile CityBuildings.Building building;
        volatile DoorWalk.Spot door;
        volatile BuildingSpots.Plan plan;
        volatile int heardMark;
    }
    private static final Facts F = new Facts();

    static void build() {
        String type = System.getProperty("uc.playtest.building", "");
        String city = System.getProperty("uc.playtest.city", "valewood");
        ThiefScenario.tag = "BUILDING";
        Playtest.keepScreens = true;
        if (type.isBlank() || !type.contains(":")) { Playtest.queue("no building", () -> Playtest.fail("name the building with -PucPtBuilding=<namespace:building>")); return; }
        ThiefScenario.register(0, "the Farmer card", "1 a new citizen (a Farmer) registers");
        Playtest.queue("the clock: 13:00, clear weather", () -> {
            Playtest.command("gamerule doDaylightCycle false");
            Playtest.command("time set 6000");
            Playtest.command("weather clear");
            scripted("the time of day was set to 12:00 and the weather cleared");
        });
        ThiefScenario.travel("to the middle of " + city, () -> ThiefScenario.centre(city), 4);
        Playtest.wait("the streets load", 200);
        Playtest.queue("the nearest " + type + " (on the server)", () -> {
            String report = server(() -> find(level(), type));
            Playtest.log("BUILDING " + report);
            if (F.building == null) Playtest.fail("no generated " + type + " within 12 chunks of " + city + "'s middle");
        });
        ThiefScenario.stopIfFailed("finding the building");
        Playtest.queue("what the game makes of it", () -> {
            CityBuildings.Building b = F.building;
            String what = server(() -> {
                ServerLevel level = level();
                var security = b.security();
                boolean let = CityHomes.anyLet(level.getSeed(), b);
                PopulationCatalog.Def def = PopulationCatalog.INSTANCE.city(b.city());
                int residents = F.plan == null || def == null ? 0 : HouseholdPlan.residents(b.city(), level.getSeed(), b.key(), F.plan, def.names()).size();
                return String.format(Locale.ROOT, "%s at chunk %d,%d street %d: class %s, tier %d, locked %s, plan %s (%d spots, %d units), to let %s, %d people of the household, street door %s",
                    b.type(), b.chunkX(), b.chunkZ(), b.streetY(), security == null ? "none" : security.id(), Thievery.tier(b), b.locked(), F.plan == null ? "none" : F.plan.use(),
                    F.plan == null ? 0 : F.plan.spots().size(), F.plan == null ? 0 : F.plan.units().size(), let, residents, F.door == null ? "none" : F.door.door().toShortString());
            });
            Playtest.log("BUILDING " + what);
            if (b.security() == null || !b.locked()) Playtest.fail("the building has no security class or is not locked: " + what);
            else if (F.plan == null) Playtest.fail("the game has no plan for the building (no city_buildings data): " + what);
            else pass("2 the game knows the building: " + what);
        });
        photographs();
        // the street door, as a passer-by finds it, and its lock
        Playtest.queue("the street door", () -> {
            if (F.door == null) { Playtest.fail("the building has no street door the guard knows"); return; }
            ThiefScenario.useBuilding(F.door, F.building, server(() -> ThiefScenario.furniture(level(), F.building, F.door.door())));
            mc().options.hideGui = false;
            ThiefScenario.standAt(F.door);
            scripted("the player was put on the street at the door");
        });
        ThiefScenario.stopIfFailed("the street door");
        Playtest.wait("settle", 30);
        Playtest.queue("an empty hand and a right click on the door", () -> { F.heardMark = Playtest.heardCount(); Playtest.focusWindow(); Playtest.tap("8"); });
        Playtest.wait("turn", 4);
        Playtest.queue("click", () -> { JourneyScenario.look(Vec3.atCenterOf(F.door.door()).add(0, 0.5, 0)); });
        Playtest.wait("aim", 6);
        Playtest.queue("click it", () -> Playtest.click(3));
        Playtest.wait("the answer", 20);
        ThiefScenario.shot("door");
        Playtest.queue("the door stays shut (or, a shop in its hours, opens)", () -> {
            boolean open = server(() -> DoorWalk.doorOpen(level(), F.door));
            boolean shop = server(() -> BuildingGuard.openToPublic(level(), F.building, null));
            String said = Playtest.heardSince(F.heardMark, "ock");
            String toLet = Playtest.heardSince(F.heardMark, "To let");
            Playtest.log("BUILDING the street door: " + (open ? "OPEN" : "shut") + (shop ? " (open to the public now)" : "") + "; told " + (said != null ? said : toLet));
            if (shop && !open) Playtest.fail("a shop's street door in its hours did not open");
            else if (shop) pass("3 a shop's street door opens to a customer in its hours");
            else if (open) Playtest.fail("the building's street door opened for a stranger");
            else pass("3 the street door stays shut to a stranger (\"" + (said != null ? said : toLet) + "\")");
        });
        // a shop is tried again after closing: the night locks it, and a thief picks it
        Playtest.queue("after closing, for a shop", () -> {
            if (!server(() -> BuildingGuard.openToPublic(level(), F.building, null))) return;
            Playtest.command("time set 18000");
            scripted("the time was set to midnight: the shop is shut");
            server(() -> { var st = level().getBlockState(F.door.door()); if (st.getBlock() instanceof net.minecraft.world.level.block.DoorBlock d && st.getValue(net.minecraft.world.level.block.DoorBlock.OPEN)) d.setOpen(null, level(), st, F.door.door(), false); return null; });
        });
        Playtest.wait("the keeper goes home", 200);
        ThiefScenario.prepareAtDoor();
        ThiefScenario.pickTheDoor();
        Playtest.queue("summary", ThiefScenario::summary);
    }

    /** Server thread: the nearest building of {@code type} in the loaded chunks round the player, its plan and its street door. */
    private static String find(ServerLevel level, String type) {
        int cx = me().getBlockX() >> 4, cz = me().getBlockZ() >> 4;
        int seen = 0;
        for (int r = 0; r <= 12 && F.building == null; r++) for (int dx = -r; dx <= r && F.building == null; dx++) for (int dz = -r; dz <= r && F.building == null; dz++) {
            if (Math.max(Math.abs(dx), Math.abs(dz)) != r || !level.hasChunk(cx + dx, cz + dz)) continue;
            var b = CityBuildings.INSTANCE.ofChunk(level, cx + dx, cz + dz).orElse(null);
            if (b == null) continue;
            seen++;
            if (!b.type().equals(type)) continue;
            F.building = b;
            F.plan = BuildingSpots.INSTANCE.forType(b.type());
            F.door = ThiefScenario.streetDoor(level, b);
        }
        return seen + " buildings looked at; " + (F.building == null ? "none is " + type : "found at chunk " + F.building.chunkX() + "," + F.building.chunkZ());
    }

    /** A spectator camera: the building from its four sides, then every floor from inside, with the interface hidden. */
    private static void photographs() {
        Playtest.queue("a spectator camera with the interface hidden", () -> { Playtest.command("gamemode spectator"); mc().options.hideGui = true; scripted("the photographs are taken by a spectator camera (a photograph, not play)"); });
        String[] sides = {"north", "east", "south", "west"};
        for (int i = 0; i < 4; i++) {
            final int side = i;
            camera("outside-" + sides[i], () -> {
                Vec3 centre = centre(0);
                double angle = Math.PI / 2 * side + Math.PI;
                Vec3 eye = centre.add(Math.sin(angle) * -15, 7, Math.cos(angle) * -15);
                return new Vec3[]{eye, centre.add(0, 1, 0)};
            });
        }
        camera("above", () -> new Vec3[]{centre(0).add(-10, 20, -10), centre(0)});
        for (int floor = 0; floor < 4; floor++) {
            final int f = floor;
            for (int look = 0; look < 2; look++) {
                final int l = look;
                camera("floor" + f + "-" + (l == 0 ? "a" : "b"), () -> {
                    if (F.plan == null || f >= Math.max(1, floors()) + (hasTop() ? 1 : 0)) return null;
                    Vec3 c = centre(f);
                    double[] box = box();
                    Vec3 corner = l == 0 ? new Vec3(box[0] + 0.6, c.y + 1.62, box[2] + 0.6) : new Vec3(box[3] + 0.4, c.y + 1.62, box[5] + 0.4);
                    Vec3 other = l == 0 ? new Vec3(box[3], c.y + 0.6, box[5]) : new Vec3(box[0], c.y + 0.6, box[2]);
                    return new Vec3[]{corner, other};
                });
            }
        }
    }

    private static int floors() { return Math.max(1, F.building.floors()); }
    private static boolean hasTop() { return false; }

    /** The unit box (or the whole chunk) in world coordinates: x0, y0, z0, x1, y1, z1. */
    private static double[] box() {
        CityBuildings.Building b = F.building;
        int ox = b.chunkX() << 4, oz = b.chunkZ() << 4;
        if (F.plan != null && !F.plan.units().isEmpty()) {
            var u = F.plan.units().get(0);
            return new double[]{ox + u.x0(), b.streetY() + u.y0(), oz + u.z0(), ox + u.x1() + 1, b.streetY() + u.y1() + 1, oz + u.z1() + 1};
        }
        return new double[]{ox + 2, b.streetY() + 1, oz + 2, ox + 14, b.streetY() + 6 * floors(), oz + 14};
    }

    /** The middle of floor {@code floor} of the building (its standing level). */
    private static Vec3 centre(int floor) {
        double[] box = box();
        return new Vec3((box[0] + box[3]) / 2, F.building.streetY() + 1 + CityBuildings.FLOOR * floor, (box[2] + box[5]) / 2);
    }

    private static void camera(String label, java.util.function.Supplier<Vec3[]> where) {
        final boolean[] skip = {false};
        Playtest.queue("camera for " + label, () -> {
            Vec3[] at = where.get();
            skip[0] = at == null;
            if (at == null) return;
            Vec3 feet = at[0].subtract(0, 1.62, 0), delta = at[1].subtract(at[0]);
            float yaw = (float) Math.toDegrees(Math.atan2(-delta.x, delta.z));
            float pitch = (float) -Math.toDegrees(Math.atan2(delta.y, Math.hypot(delta.x, delta.z)));
            Playtest.command(String.format(Locale.ROOT, "tp @s %.2f %.2f %.2f %.1f %.1f", feet.x, feet.y, feet.z, yaw, pitch));
        });
        Playtest.wait("the chunks render for " + label, 60);
        Playtest.queue("photograph " + label, () -> { if (!skip[0]) Playtest.screenshotNextFrame("uc-building-" + label); });
        Playtest.wait("after " + label, 6);
    }
}
