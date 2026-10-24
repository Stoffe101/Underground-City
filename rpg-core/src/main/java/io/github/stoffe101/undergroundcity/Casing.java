package io.github.stoffe101.undergroundcity;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

import java.util.List;
import java.util.Locale;

/**
 * Casing a building (D-144, layered by D-157): before committing, a thief reads the place from its street door (crouched with an empty hand, or the Inspect key while looking at the door).
 *
 * <p>What anybody can tell from the street: what the place is and its address, how well off it is (its tier), what holds the door and whether they could open it with what they carry. Then, in
 * layers by Thief skill: with <b>Casing</b> whether the alarm is armed, how many cameras watch the inside, how many people are in now, and the risk of a break-in <i>now</i> in a word; with
 * <b>Know Their Routines</b> when it will next be empty, whether there is a safe, whether it was broken into lately, and every reason behind the risk; an <b>expert</b> (Routines, Camera Sense
 * and Thief level {@link #EXPERT_LEVEL}) reads the risk as a number, how long the police would take, how often officers walk the city and how much in it is worth searching.
 *
 * <p>Everything is computed on the server from what the game already knows (the building's class and grade, the household's clock, the alarm's hash, the devices in its rooms, the people in the
 * street); the client is sent words and numbers to show, never anything it could use beyond what the report says. A casing is remembered in the thief's book ({@link PropertyIntel.Book}).
 */
public final class Casing {
    /** What the card shows. Numbers are -1 when the thief cannot tell. {@code alarm}: 0 none, 1 fitted but not armed, 2 armed, 3 armed with a panel by the door. {@code safe}: 0 none, 1 household, 2 wall, 3 vault. */
    public record Report(String title, String city, int tier, String lock, String lockNote, boolean canTry, int alarm, int cameras, int inside, String empty, int safe, int recent,
                         boolean beenIn, int skill, String category, String address, String risk, int riskPercent, String reasons, int policeSeconds, int patrolMinutes, int searchable,
                         boolean playerHomes) {
        /** The report before D-157's layers (tests and older call sites). */
        public Report(String title, String city, int tier, String lock, String lockNote, boolean canTry, int alarm, int cameras, int inside, String empty, int safe, int recent, boolean beenIn, int skill) {
            this(title, city, tier, lock, lockNote, canTry, alarm, cameras, inside, empty, safe, recent, beenIn, skill, "", "", "", -1, "", -1, -1, -1, false);
        }
    }

    /** The Thief level from which a thief with Know Their Routines and Camera Sense reads a place as an expert. */
    static final int EXPERT_LEVEL = 10;

    /** How long after a break-in a household stays watchful, in active ticks (three hours). */
    static final long WATCHFUL_TICKS = 3 * 72_000L;

    private Casing() {}

    /** True when this click is a casing: crouched, empty main hand. */
    static boolean wants(ServerPlayer player) { return player.isCrouching() && player.getMainHandItem().isEmpty(); }

    /** Reads a building for a thief at its door. */
    static Report read(ServerPlayer player, ServerLevel level, CityBuildings.Building building, BlockPos door) {
        CityBuildings.SecurityClass security = building.security();
        int tier = Thievery.tier(building);
        boolean casing = Thievery.has(player, "thief.casing"), routines = casing && Thievery.has(player, "thief.routines");
        boolean expert = routines && Thievery.has(player, "thief.camera_sense") && thiefLevel(player) >= EXPERT_LEVEL;
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
        String category = PropertyIntel.categoryWord(CityBuildings.INSTANCE.category(security.id()));
        String address = CityHomes.address(level.getSeed(), building);
        String risk = "", reasons = "";
        int riskPercent = -1, police = -1, patrol = -1, searchable = -1;
        if (casing) {
            HouseholdService.Mem mem = HouseholdService.memory(level).byBuilding.get(building.key());
            boolean watchful = mem != null && LockService.clock(level.getServer()) - mem.tick() < WATCHFUL_TICKS;
            PropertyIntel.Risk estimate = PropertyIntel.risk(player, level, building, door, Math.max(0, inside), Math.max(0, cameras), watchful);
            risk = estimate.word();
            List<String> shown = routines ? estimate.reasons() : estimate.reasons().subList(0, Math.min(1, estimate.reasons().size()));
            reasons = String.join("; ", shown);
            if (reasons.length() > 300) reasons = reasons.substring(0, 300);
            if (expert) {
                riskPercent = (int) Math.round(estimate.chance() * 100);
                CityBuildings.Response response = security.response();
                police = response != null ? response.dispatchSeconds() : PoliceResponse.NEIGHBOUR.dispatchSeconds();
                CityLaws.Law law = CityLaws.INSTANCE.find(building.city());
                patrol = law == null || law.patrol() == null ? -1 : Math.max(1, Math.round(law.patrol().everySeconds() / 60f));
                searchable = PropertyIntel.searchable(level, building);
            }
        }
        return new Report(title, capitalise(building.city()), tier, lock, note, canTry, alarm, cameras, inside, empty, safe, recent, beenIn, expert ? 3 : routines ? 2 : casing ? 1 : 0,
            category, address, risk, riskPercent, reasons, police, patrol, searchable, CityHomes.heldUnits(level.getServer(), building) > 0);
    }

    static int thiefLevel(ServerPlayer player) {
        return CitizenState.get(player.server).find(player.getUUID()).map(CitizenState.Citizen::thiefLevel).orElse(1);
    }

    /** A casing from the street, by a click or the Inspect key: reads the place, remembers it in the thief's book and sends the card. */
    static void caseDoor(ServerPlayer player, ServerLevel level, CityBuildings.Building building, BlockPos door) {
        Report report = read(player, level, building, door);
        PropertyIntel.casings++;
        PropertyIntel.book(level.getServer()).cased(player.getUUID(), building, door, report.address(), CityBuildings.INSTANCE.category(building.security().id()),
            building.security().label(), LockService.clock(level.getServer()), report.skill(), report.recent() == 1, report.lock(), report.alarm(), report.cameras(), report.risk());
        BurglaryNetwork.casing(player, report);
    }

    /**
     * The Inspect key (D-157): the same casing as a crouched click with an empty hand, for the door the player looks at, from its street side and within reach. Returns why it cannot be cased,
     * or null when the card was sent.
     */
    static String inspect(ServerPlayer player, ServerLevel level, BlockPos pos) {
        var state = level.getBlockState(pos);
        if (!(state.getBlock() instanceof net.minecraft.world.level.block.DoorBlock)) return "Look at a building's street door to case it.";
        BlockPos lower = BuildingGuard.lowerOf(pos, state);
        var lowerState = level.getBlockState(lower);
        if (player.position().distanceTo(net.minecraft.world.phys.Vec3.atCenterOf(lower)) > 5.5) return "Come closer to the door to case it.";
        var found = BuildingGuard.protectedAt(level, lower);
        if (found.isEmpty() || !found.get().locked() || Thievery.tier(found.get()) <= 0) return "Nothing to case here.";
        if (!BuildingGuard.exterior(level, lower, lowerState)) return "Case a building from its street door.";
        if (!BuildingGuard.outsideSide(level, lower, lowerState, player.position())) return "Case a place from the street side of its door.";
        if (CityHomes.holdsKey(player, found.get())) return "You live here.";
        caseDoor(player, level, found.get(), lower);
        return null;
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
        if (!r.risk().isEmpty()) out.add("Risk now: " + r.risk() + (r.riskPercent() >= 0 ? " (about " + r.riskPercent() + "%)" : "") + (r.reasons().isEmpty() ? "" : ": " + r.reasons()));
        if (r.policeSeconds() >= 0) out.add("Police would take about " + r.policeSeconds() + " s");
        if (r.searchable() >= 0) out.add("Worth searching: " + r.searchable() + " places");
        return out;
    }
}
