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
        new Def("deliver", "Deliver it", "Gather the goods, then use DELIVER on a terminal in the right city.", "CONTRACTS"),
        new Def("skill", "Learn a skill", "Spend your first skill point on the SKILLS tab. Legal and underground branches are both open.", "SKILLS"),
        new Def("trade", "Visit the exchange", "TRADE sells city goods and tools. Buy something that helps your next order.", "IMPORTS"),
        new Def("travel", "Travel to a rival city", "Use a terminal inside another city. A road links all three, about four minutes each way.", "BLACK_MARKET"),
        new Def("second", "Finish a second order", "A second delivery shows how employer demand refills as you play.", "CONTRACTS"));

    private static final Map<UUID, Integer> SENT = new HashMap<>();

    public Journey() {}

    static boolean done(CitizenState.Citizen citizen, WorkOrderLedger ledger, UUID uuid, String id) {
        return switch (id) {
            case "registered" -> true;
            case "employer" -> citizen.journey("employer") > 0;
            case "accept" -> citizen.journey("accepted") > 0 || ledger.player(uuid).hasUUID("order");
            case "deliver" -> citizen.journey("deliveries") > 0 || citizen.careerXp() > 0;
            case "skill" -> !citizen.skills().isEmpty();
            case "trade" -> citizen.journey("bought") > 0;
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
        for (Def def : STEPS) {
            if (def.id().equals("registered") || done(citizen.get(), ledger, player.getUUID(), def.id())) continue;
            BlockPos target = null;
            String name = "";
            if (def.id().equals("employer") || (def.id().equals("accept") && citizen.get().journey("employer") == 0)) {
                Villager employer = find(level, "employer", citizen.get().career);
                if (employer != null) { target = employer.blockPosition(); name = employer.getName().getString(); }
            }
            return new Objective(def.title(), def.hint(), def.page(), target, name);
        }
        return null;
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
            SENT.put(player.getUUID(), hash);
            TerminalNetwork.sendObjective(player, now);
        }
    }

    @SubscribeEvent public void loggedIn(net.minecraftforge.event.entity.player.PlayerEvent.PlayerLoggedInEvent event) { SENT.remove(event.getEntity().getUUID()); }
    @SubscribeEvent public void loggedOut(net.minecraftforge.event.entity.player.PlayerEvent.PlayerLoggedOutEvent event) { SENT.remove(event.getEntity().getUUID()); }
}
