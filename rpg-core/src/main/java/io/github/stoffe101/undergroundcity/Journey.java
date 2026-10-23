package io.github.stoffe101.undergroundcity;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.phys.AABB;
import net.minecraftforge.common.util.FakePlayer;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * The guided first half hour: an ordered list of milestones the server derives from what a player has really done (a registered citizen,
 * a conversation, an accepted and a delivered order, a skill, a purchase, a visit to a rival city) and the one thing to do next, shown on
 * the HUD with a direction when it is a place. Nothing here is compulsory, awards anything or blocks anything: it is a signpost, and an
 * experienced player can ignore it. Progress is kept on the citizen record (see {@link CitizenState#markJourney}).
 */
public final class Journey {
    /** One milestone: its stable id, the title and hint shown to the player and the terminal page that helps with it (or empty). */
    public record Def(String id, String title, String hint, String page) {}
    /** What the HUD shows next: the step, an optional place and its name. {@code target} is null when the step is not a place. */
    public record Objective(String title, String hint, String page, BlockPos target, String targetName) {}

    static final List<Def> STEPS = List.of(
        new Def("registered", "Register as a citizen", "Speak to the registrar by the gate of Founders Row and choose your city and trade.", ""),
        new Def("employer", "Meet your employer", "Employers work at their desks in Founders Row. Walk up and speak to yours.", ""),
        new Def("accept", "Take your first order", "Open WORK on an Operations Terminal, pick an order and accept it.", "CONTRACTS"),
        new Def("deliver", "Deliver it", "Gather the goods, then use DELIVER on a terminal in the right city. You are paid in credits and experience.", "CONTRACTS"),
        new Def("trade", "Buy something", "On TRADE, open the civilian shop for seeds and rations, or buy goods on the exchange. A purchase counts, a look does not.", "IMPORTS"),
        new Def("home", "Find a place to sleep", "Walk into the Row Flats or the Motel beside Founders Row, press K and open Home to rent a flat. The motel's first hours are free.", ""),
        new Def("skill", "Learn a skill", "Spend your first skill point on the SKILLS tab. Legal and underground branches are both open.", "SKILLS"),
        new Def("travel", "Travel to a rival city", "Use a terminal inside another city. A road and a bridge link all three, about four minutes each way.", "BLACK_MARKET"),
        new Def("second", "Finish a second order", "A second delivery shows how employer demand refills as you play.", "CONTRACTS"));

    private static final Map<UUID, Integer> SENT = new HashMap<>();
    private static final Map<UUID, String> LAST_TITLE = new HashMap<>();

    public Journey() {}

    static boolean done(CitizenState.Citizen citizen, WorkOrderLedger ledger, UUID uuid, String id) {
        return switch (id) {
            case "registered" -> true;
            case "employer" -> citizen.journey("employer") > 0;
            case "accept" -> citizen.journey("accepted") > 0 || ledger.player(uuid).hasUUID("order");
            case "deliver" -> citizen.journey("deliveries") > 0 || citizen.careerXp() > 0;
            case "skill" -> !citizen.skills().isEmpty();
            case "trade" -> citizen.journey("bought") > 0;
            case "home" -> citizen.journey("home") > 0;
            case "travel" -> ledger.foreignVisits(uuid, citizen.city) >= 1;
            case "second" -> citizen.journey("deliveries") >= 2;
            default -> false;
        };
    }

    /** The checklist for the terminal's GUIDE page. */
    static List<TerminalData.Step> steps(CitizenState.Citizen citizen, WorkOrderLedger ledger, UUID uuid) {
        List<TerminalData.Step> out = new ArrayList<>();
        for (Def def : STEPS) out.add(new TerminalData.Step(def.id(), def.title(), def.hint(), done(citizen, ledger, uuid, def.id()), def.page()));
        return out;
    }

    /** The first milestone not yet reached, or null when the journey is complete; for an unregistered player, the registrar. */
    static Objective next(ServerPlayer player) {
        var citizens = CitizenState.get(player.server);
        var citizen = citizens.find(player.getUUID());
        ServerLevel level = player.serverLevel();
        if (citizen.isEmpty()) {
            Villager registrar = find(level, "registrar", null);
            return new Objective(STEPS.get(0).title(), STEPS.get(0).hint(), "", registrar == null ? null : registrar.blockPosition(), registrar == null ? "" : registrar.getName().getString());
        }
        WorkOrderLedger ledger = WorkOrderLedger.get(player.server);
        if (ledger.faulted()) return null;
        backfillHome(player, citizens, citizen.get());
        Objective fence = fenceObjective(player, citizen.get());
        if (fence != null) return fence;
        for (Def def : STEPS) {
            if (def.id().equals("registered") || done(citizen.get(), ledger, player.getUUID(), def.id())) continue;
            BlockPos target = null;
            String name = "";
            if (def.id().equals("employer") || (def.id().equals("accept") && citizen.get().journey("employer") == 0)) {
                Villager employer = find(level, "employer", citizen.get().career);
                if (employer != null) { target = employer.blockPosition(); name = employer.getName().getString(); }
            }
            if (def.id().equals("travel")) {
                String here = CityRegions.INSTANCE.cityAt(level, player.blockPosition()).orElse("");
                RoadOut out = roadOut(roads(level), here, citizen.get().city, player.getX(), player.getZ());
                if (out != null) { target = BlockPos.containing(out.x(), player.getY(), out.z()); name = "Road to " + capitalize(out.to()); }
            }
            return new Objective(def.title(), def.hint(), def.page(), target, name);
        }
        return goal(player, citizen.get());
    }

    /**
     * A thief with Fence Contacts who carries stolen goods (D-143): the side display points at the nearest fence anybody has been near, and says whether they are dealing now. Without the skill, or
     * with nothing to sell, nothing changes.
     */
    static Objective fenceObjective(ServerPlayer player, CitizenState.Citizen citizen) {
        if (!citizen.skills().contains("thief.fence_contacts") || !FenceService.carriesStolen(player)) return null;
        var known = FenceService.Directory.get(player.server).nearest(player.blockPosition());
        if (known == null) return new Objective("Find a fence", "Fences deal at night behind Blackwater's clubs and in Ironhaven's warehouses. Walk past one and you will know it.", "", null, "");
        FenceCatalog.Fence fence = FenceCatalog.INSTANCE.forCity(known.getValue()).orElse(null);
        int hour = PopulationPlan.hourOf(HouseholdService.dayTime(player.serverLevel()));
        String when = fence == null ? "" : fence.about(hour) ? " They are dealing now." : " They deal from " + String.format(java.util.Locale.ROOT, "%02d:00", fence.fromHour()) + ".";
        return new Objective("Sell what you took", (fence == null ? "A fence" : fence.title()) + " in " + capitalize(known.getValue()) + "." + when, "", known.getKey(), fence == null ? "Fence" : fence.title());
    }

    /** Where the road out begins: the end of an open road, and the city it leads to. */
    record RoadOut(double x, double z, String to) {}

    private static final Map<net.minecraft.server.MinecraftServer, List<CorridorPlan>> CONFIGURED_ROADS = new java.util.WeakHashMap<>();

    /** The roads of the world: the built plans, or (before any road chunk has been generated, as in a new world) the configured ones, per server; only their geometry is used here. */
    private static List<CorridorPlan> roads(ServerLevel level) {
        List<CorridorPlan> built = CorridorRoadFeature.plans(level);
        if (!built.isEmpty()) return built;
        synchronized (CONFIGURED_ROADS) { return CONFIGURED_ROADS.computeIfAbsent(level.getServer(), DevCommands::plans); }
    }

    /**
     * The way to a rival city for the travel step (a scripted journey walked about 720 blocks from the Row to the nearest road's end with nothing on the screen saying where it was): in a city,
     * the nearest end of a road that leaves it for another city; outside every city, the nearest end where a road enters a city not the player's own; in a rival city already, nothing (the
     * step is a terminal there). A road's id names its two cities in order, and its ends are listed in the same order ({@link CorridorPlan#ends}).
     */
    static RoadOut roadOut(List<CorridorPlan> plans, String here, String home, double x, double z) {
        if (!here.isEmpty() && !here.equals(home)) return null;
        RoadOut best = null;
        double bestD = Double.MAX_VALUE;
        for (CorridorPlan plan : plans) {
            String[] cities = plan.id().split("_", 2);
            CorridorPlan.End[] ends = plan.ends();
            if (cities.length != 2 || ends.length != 2) continue;
            for (int i = 0; i < 2; i++) {
                String at = cities[i], other = cities[1 - i];
                // in the home city: where its roads leave it; outside: where a road enters a rival city (on the road already, that is the end ahead)
                if (here.isEmpty() ? at.equals(home) : !at.equals(here)) continue;
                double d = Math.hypot(ends[i].x() - x, ends[i].z() - z);
                if (d < bestD) { bestD = d; best = new RoadOut(ends[i].x(), ends[i].z(), here.isEmpty() ? at : other); }
            }
        }
        return best;
    }

    private static String capitalize(String id) { return id == null || id.isEmpty() ? "" : Character.toUpperCase(id.charAt(0)) + id.substring(1); }

    /** A household that held a home before the home milestone existed (or got one by an operator's hand) has done that step. */
    private static void backfillHome(ServerPlayer player, CitizenState citizens, CitizenState.Citizen citizen) {
        if (citizen.journey("home") > 0) return;
        CrewLedger crews = CrewService.ledger(player.server);
        if (crews == null || crews.faulted()) return;
        CrewLedger.Crew crew = crews.crewOf(player.getUUID());
        if (!crews.residencesOf(CrewLedger.OwnerType.PLAYER, player.getUUID()).isEmpty() || (crew != null && !crews.residencesOf(CrewLedger.OwnerType.CREW, crew.id).isEmpty())) citizens.markJourney(player.getUUID(), "home");
    }

    /**
     * What to do once the checklist is done: not a task, a direction, chosen from the state of the citizen (the same words in the guide). It ends by itself at career level 5; it is the one thing on the
     * screen that says "and then what", for a player who has found their feet and has not yet found the next thing.
     */
    static Objective goal(ServerPlayer player, CitizenState.Citizen citizen) {
        int level = citizen.careerLevel();
        if (level >= 5) return null;
        if (level < 3) return new Objective("Reach level " + (level + 1), "Keep taking apprentice orders. Level 3 opens the regional orders, which need a trip to another city.", "CONTRACTS", null, "");
        CrewLedger crews = CrewService.ledger(player.server);
        boolean crew = crews != null && !crews.faulted() && crews.crewOf(player.getUUID()) != null;
        if (!crew) return new Objective("Share a home with a friend", "Press K to start a Crew: a shared bank and home. Everything also works alone.", "", null, "");
        return new Objective("Grow your trade", "Learn the next skill, rent a better home, or look at the underground branch of your trade. The LAW page shows what it risks.", "SKILLS", null, "");
    }

    /** A citizen of the given role (and career, for an employer) near the first district that has one, or null. */
    static Villager find(ServerLevel level, String role, String career) {
        for (String id : DistrictCatalog.INSTANCE.ids()) {
            BlockPos origin = DistrictBuilder.originOf(level, id);
            DistrictCatalog.District district = DistrictCatalog.INSTANCE.find(id);
            if (origin == null || district == null) continue;
            AABB box = new AABB(origin.offset(district.min()).offset(-4, -4, -4), origin.offset(district.max()).offset(5, 5, 5));
            for (Villager villager : level.getEntitiesOfClass(Villager.class, box)) {
                var npc = CityNpcs.definition(villager);
                if (npc.isPresent() && npc.get().role().equals(role) && (career == null || career.equals(npc.get().career()))) return villager;
            }
        }
        return null;
    }

    @SubscribeEvent
    public void tick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END || event.getServer().getTickCount() % 20 != 13) return;
        for (ServerPlayer player : event.getServer().getPlayerList().getPlayers()) {
            if (player instanceof FakePlayer) continue;
            Objective now = next(player);
            int hash = Objects.hash(now == null ? null : now.title(), now == null || now.target() == null ? 0L : now.target().asLong() / 8);
            if (Integer.valueOf(hash).equals(SENT.get(player.getUUID()))) continue;
            boolean known = SENT.containsKey(player.getUUID());
            SENT.put(player.getUUID(), hash);
            // A milestone just behind us (the next one changed since the last look): mark it, once per step.
            String last = LAST_TITLE.put(player.getUUID(), now == null ? "" : now.title());
            if (known && last != null && !last.isEmpty() && (now == null || !now.title().equals(last)) && indexOf(last) >= 0 && (now == null || indexOf(now.title()) > indexOf(last))) {
                Notice.send(player, Notice.Kind.STEP, "Step done", last + (now == null ? ". You have found your feet." : ". Next: " + now.title() + "."));
            }
            TerminalNetwork.sendObjective(player, now);
        }
    }

    /** The step's place in the checklist; a goal (what comes after it) is past the end of it. */
    private static int indexOf(String title) {
        for (int i = 0; i < STEPS.size(); i++) if (STEPS.get(i).title().equals(title)) return i;
        return STEPS.size();
    }

    @SubscribeEvent public void loggedIn(net.minecraftforge.event.entity.player.PlayerEvent.PlayerLoggedInEvent event) { SENT.remove(event.getEntity().getUUID()); LAST_TITLE.remove(event.getEntity().getUUID()); }
    @SubscribeEvent public void loggedOut(net.minecraftforge.event.entity.player.PlayerEvent.PlayerLoggedOutEvent event) { SENT.remove(event.getEntity().getUUID()); LAST_TITLE.remove(event.getEntity().getUUID()); }
}
