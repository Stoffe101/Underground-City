package io.github.stoffe101.undergroundcity;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

import java.util.List;
import java.util.Locale;

/**
 * Casing a building (D-144): before committing, a thief crouches at a street door with an empty hand and reads the place. What anybody can tell from the street is how well off it looks (its tier)
 * and what holds the door, and whether they could open it with what they carry. A thief with <b>Casing</b> also reads whether the alarm is armed, how many cameras watch the inside and how many
 * people are in now; with <b>Know Their Routines</b>, when it will next be empty, whether there is a safe, and whether it was broken into lately (the household is watchful).
 *
 * <p>Everything is computed on the server from what the game already knows (the building's class, the household's clock, the alarm's hash, the devices in its rooms); the client is sent words and
 * numbers to show, never anything it could use beyond what the report says.
 */
public final class Casing {
    /** What the card shows. Numbers are -1 when the thief cannot tell. {@code alarm}: 0 none, 1 fitted but not armed, 2 armed, 3 armed with a panel by the door. {@code safe}: 0 none, 1 household, 2 wall, 3 vault. */
    public record Report(String title, String city, int tier, String lock, String lockNote, boolean canTry, int alarm, int cameras, int inside, String empty, int safe, int recent,
                         boolean beenIn, int skill) {}

    /** How long after a break-in a household stays watchful, in active ticks (three hours). */
    static final long WATCHFUL_TICKS = 3 * 72_000L;

    private Casing() {}

    /** True when this click is a casing: crouched, empty main hand. */
    static boolean wants(ServerPlayer player) { return player.isCrouching() && player.getMainHandItem().isEmpty(); }

    /** Reads a building for a thief at its door. */
    static Report read(ServerPlayer player, ServerLevel level, CityBuildings.Building building, BlockPos door) {
        CityBuildings.SecurityClass security = building.security();
        int tier = Thievery.tier(building);
        boolean casing = Thievery.has(player, "thief.casing"), routines = Thievery.has(player, "thief.routines");
        boolean keypad = SecurityCraftTrial.isKeypadDoor(level.getBlockState(door));
        LockpickSession.Profile profile = LockProfiles.INSTANCE.find(security.lockProfile());
        String lock = keypad ? "a keypad" : "a " + LockService.describe(profile);
        String requirement = LockService.requirement(player, profile, new LockService.Target(LockService.Kind.DOOR, door, profile.id(), building.city(), security.id(), "", 1));
        boolean pro = LockService.worksProSet(player);
        String note = requirement != null ? requirement
            : !pro && LockService.picks(player) < 1 ? "You carry no lockpick."
            : pro ? "Your Pro Pick Set will do." : profile.spools() > 0 ? "A lockpick will do, with care: it has " + profile.spools() + (profile.spools() == 1 ? " spool pin." : " spool pins.") : "A lockpick will do.";
        boolean canTry = requirement == null && (pro || LockService.picks(player) >= 1);
        int alarm = -1, cameras = -1, inside = -1, safe = -1, recent = -1;
        String empty = "";
        if (casing) {
            CityBuildings.Response response = security.response();
            alarm = response == null ? 0 : !PoliceResponse.armed(level, building, response) || AlarmPanels.jammed(level, building) ? 1 : AlarmPanels.panelOf(building).isPresent() ? 3 : 2;
            cameras = SecurityDevices.cameras(level, building).size();
            inside = peopleIn(level, building, HouseholdService.dayTime(level));
        }
        if (routines) {
            empty = nextEmpty(level, building);
            safe = security.stashPolicy().isEmpty() || BuildingStash.roll(level, building, 1) >= security.stashChance() ? 0
                : "vault_safe".equals(security.safeProfile()) ? 3 : "wall_safe".equals(security.safeProfile()) ? 2 : 1;
            HouseholdService.Mem mem = HouseholdService.memory(level).byBuilding.get(building.key());
            recent = mem != null && LockService.clock(level.getServer()) - mem.tick() < WATCHFUL_TICKS ? 1 : 0;
        }
        boolean beenIn = Thievery.records(level.getServer()).seen(player.getUUID(), "entered", building.key());
        String title = capitalise(security.label().isEmpty() ? "building" : security.label());
        return new Report(title, capitalise(building.city()), tier, lock, note, canTry, alarm, cameras, inside, empty, safe, recent, beenIn, routines ? 2 : casing ? 1 : 0);
    }

    /** How many of the building's household are in at this world time (0 for a building with nobody of its own). */
    static int peopleIn(ServerLevel level, CityBuildings.Building building, long time) {
        BuildingSpots.Plan plan = BuildingSpots.INSTANCE.forType(building.type());
        PopulationCatalog.Def def = PopulationCatalog.INSTANCE.city(building.city());
        if (plan == null || def == null) return 0;
        int in = 0;
        for (HouseholdPlan.Resident who : HouseholdPlan.residents(building.city(), level.getSeed(), building.key(), plan, def.names())) {
            if (CityHomes.spotLet(level.getSeed(), building, plan, plan.spots().get(who.spot()))) continue;
            if (HouseholdService.whereNow(who, plan, level.getSeed(), time) != HouseholdPlan.AWAY) in++;
        }
        return in;
    }

    /** When the building is next empty, in a thief's words: now, from an hour today, or never in the next day. */
    static String nextEmpty(ServerLevel level, CityBuildings.Building building) {
        long now = HouseholdService.dayTime(level);
        if (peopleIn(level, building, now) == 0) return "Empty now";
        long hourStart = now - Math.floorMod(now, 1000L);
        for (int h = 1; h <= 24; h++) {
            long at = hourStart + h * 1000L + 700L;   // well into the hour, after everybody's own minute of coming and going
            if (peopleIn(level, building, at) == 0) {
                int hour = PopulationPlan.hourOf(at);
                // the card's label already says "Empty", and the column is narrow at GUI scale 2 ("Empty from about 09:00 (in 3 ho..." on a 1280x720 capture)
                return "About " + String.format(Locale.ROOT, "%02d:00", hour) + (h == 1 ? " (soon)" : " (in " + h + " h)");
            }
        }
        return "Somebody is always in";
    }

    static String capitalise(String text) { return text == null || text.isEmpty() ? "" : Character.toUpperCase(text.charAt(0)) + text.substring(1); }

    /** The words of a report for a chat line or a log (the card shows the same). */
    static List<String> lines(Report r) {
        List<String> out = new java.util.ArrayList<>();
        out.add(r.title() + " in " + r.city() + ": " + Thievery.tierWord(r.tier()) + " (tier " + r.tier() + ")");
        out.add("Door: " + r.lock() + ". " + r.lockNote());
        if (r.alarm() >= 0) out.add("Alarm: " + switch (r.alarm()) { case 0 -> "none"; case 1 -> "fitted, not armed"; case 2 -> "armed"; default -> "armed, panel by the door"; });
        if (r.cameras() >= 0) out.add("Cameras: " + r.cameras());
        if (r.inside() >= 0) out.add("Inside now: " + r.inside());
        if (!r.empty().isEmpty()) out.add(r.empty());
        if (r.safe() >= 0) out.add("Safe: " + switch (r.safe()) { case 0 -> "none"; case 1 -> "a household safe"; case 2 -> "a wall safe"; default -> "a vault safe"; });
        if (r.recent() == 1) out.add("Broken into lately: they are watchful");
        return out;
    }
}
