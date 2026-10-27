package io.github.stoffe101.undergroundcity;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mojang.logging.LogUtils;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.SimpleJsonResourceReloadListener;
import net.minecraft.util.profiling.ProfilerFiller;
import net.minecraftforge.common.util.FakePlayer;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Crew contracts (D-170, expansion plan WP1): one order that several members fill together, each with what their own career makes. A contract
 * ({@code data/underground_city/crew_contracts/<id>.json}) names its parts, and each part is an ordinary work order carrying {@code crew_part}: a member takes the part on the
 * terminal and delivers it through the work-order journal's single settlement, which pays that member's own credits and career XP and, in the same atomic write, records the
 * part against the member's Crew. Nobody holds a part for anybody: a member who takes a part and goes away blocks nothing, and a part another member has already delivered is
 * refused before anything is taken.
 *
 * <p>When the last part lands, the contract's bonus is split equally between the members who delivered parts (stated on the board) and owed to each of them in the same write;
 * each share is then paid into that member's own wallet by a settlement of its own, at once if they are online, else when they next are. A part no member can make can be filled
 * by an NPC supplier at its stated price, paid from the asking member's wallet (also one settlement); a filled part earns nobody a share or XP. So every credit moves through a Q Shop
 * wallet, the Crew bank is not touched, a crash replays a settlement once and never twice, and nothing is paid or charged while a member is away. A Crew of one may take a
 * contract and fill the parts of other careers. Cycle: a completed contract opens again after its cooldown of active play.
 */
@Mod.EventBusSubscriber(modid = UndergroundCity.MOD_ID)
public final class CrewContracts {
    private static final Logger LOGGER = LogUtils.getLogger();

    public record Part(String id, String order, int fillPrice) {}
    public record Contract(String id, String title, String summary, String city, int bonus, long cooldownTicks, List<Part> parts) {
        public Part part(String partId) { for (Part p : parts) if (p.id().equals(partId)) return p; return null; }
    }

    public static final Catalog CATALOG = new Catalog();

    public static final class Catalog extends SimpleJsonResourceReloadListener {
        private Map<String, Contract> contracts = Map.of();
        private Catalog() { super(new Gson(), "crew_contracts"); }

        @Override
        protected void apply(Map<ResourceLocation, JsonElement> data, ResourceManager resources, ProfilerFiller profiler) {
            Map<String, Contract> next = new LinkedHashMap<>();
            data.entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(entry -> {
                JsonObject json = entry.getValue().getAsJsonObject();
                if (json.get("schema_version").getAsInt() != 1) throw new IllegalArgumentException("Unsupported Crew contract schema: " + entry.getKey());
                List<Part> parts = new ArrayList<>();
                for (JsonElement element : json.getAsJsonArray("parts")) {
                    JsonObject p = element.getAsJsonObject();
                    String id = p.get("id").getAsString();
                    if (!id.matches("[a-z_]{2,32}")) throw new IllegalArgumentException("Bad part id in " + entry.getKey() + ": " + id);
                    parts.add(new Part(id, p.get("order").getAsString(), p.get("fill_price").getAsInt()));
                }
                int bonus = json.get("bonus").getAsInt();
                if (parts.size() < 2 || parts.size() > 6 || bonus < 0 || bonus > 10_000 || parts.stream().anyMatch(p -> p.fillPrice() < 0 || p.fillPrice() > 10_000)
                    || parts.stream().map(Part::id).distinct().count() != parts.size()) throw new IllegalArgumentException("Invalid Crew contract " + entry.getKey());
                Contract contract = new Contract(entry.getKey().toString(), json.get("title").getAsString(), json.has("summary") ? json.get("summary").getAsString() : "",
                    json.has("city") ? json.get("city").getAsString() : "", bonus, json.has("cooldown_ticks") ? json.get("cooldown_ticks").getAsLong() : 24000L, List.copyOf(parts));
                next.put(contract.id(), contract);
            });
            contracts = Map.copyOf(next);
            LOGGER.info("Loaded {} Crew contracts", contracts.size());
        }

        public Contract find(String id) { return contracts.get(id); }
        public List<Contract> all() { return contracts.values().stream().sorted(java.util.Comparator.comparing(Contract::id)).toList(); }

        /** A contract every part of which has its work order loaded (a part for a mod that is not installed takes the contract off the board). */
        public boolean available(Contract contract) {
            for (Part p : contract.parts()) {
                WorkOrder order = WorkOrderCatalog.INSTANCE.find(p.order());
                if (order == null || !(contract.id() + "/" + p.id()).equals(order.terms().crewPart())) return false;
            }
            return true;
        }

        void replaceForTest(Map<String, Contract> next) { contracts = Map.copyOf(next); }
    }

    private CrewContracts() {}

    /** "contract/part" into its two halves; null when it is not a Crew part. */
    static String[] split(String crewPart) {
        if (crewPart == null || crewPart.isEmpty()) return null;
        int slash = crewPart.lastIndexOf('/');
        return slash <= 0 ? null : new String[] {crewPart.substring(0, slash), crewPart.substring(slash + 1)};
    }

    /** Why this player may not take (or deliver) this Crew part now, or null. */
    static String refusal(MinecraftServer server, WorkOrderLedger orders, UUID player, String crewPart) {
        String[] ref = split(crewPart);
        if (ref == null) return null;
        Contract contract = CATALOG.find(ref[0]);
        if (contract == null || contract.part(ref[1]) == null || !CATALOG.available(contract)) return "This Crew contract is no longer offered.";
        CrewLedger crews = CrewService.ledger(server);
        CrewLedger.Crew crew = crews == null ? null : crews.crewOf(player);
        if (crew == null) return "A Crew contract: found or join a Crew first (a Crew of one may take it and have an NPC supplier fill the other parts).";
        if (orders.crewOpensAt(crew.id, contract.id()) > orders.clock()) return "Your Crew finished this contract recently; it opens again after more active play.";
        String by = orders.crewPartBy(crew.id, contract.id(), ref[1]);
        if (by != null) return "Your Crew has this part already (" + (by.equals(WorkOrderLedger.NPC_FILL) ? "filled by a supplier" : name(server, by)) + ").";
        return null;
    }

    /** The extra fields the delivery's prepared record carries so that its settlement records the part against the Crew. */
    static CompoundTag deliveryRecord(MinecraftServer server, UUID player, String crewPart) {
        CompoundTag extra = new CompoundTag();
        String[] ref = split(crewPart);
        if (ref == null) return extra;
        CrewLedger.Crew crew = CrewService.ledger(server).crewOf(player);
        Contract contract = CATALOG.find(ref[0]);
        extra.putUUID("crew", crew.id);
        extra.putString("crew_part", crewPart);
        extra.putInt("crew_bonus", contract.bonus());
        extra.putLong("crew_cooldown", contract.cooldownTicks());
        ListTag parts = new ListTag();
        for (Part p : contract.parts()) parts.add(net.minecraft.nbt.StringTag.valueOf(p.id()));
        extra.put("crew_parts", parts);
        return extra;
    }

    /**
     * Has an NPC supplier fill a part nobody in the Crew will make: the price leaves the asking member's wallet through one settlement, which records the part as filled. Members
     * with the Crew's SPEND permission may ask (the founder always). Returns the price paid.
     */
    static int fill(ServerPlayer player, WorkOrderLedger orders, String contractId, String partId) {
        Contract contract = CATALOG.find(contractId);
        Part part = contract == null ? null : contract.part(partId);
        if (part == null) throw new IllegalStateException("No such Crew contract part.");
        String why = refusal(player.server, orders, player.getUUID(), contractId + "/" + partId);
        if (why != null) throw new IllegalStateException(why);
        CrewLedger.Crew crew = CrewService.ledger(player.server).crewOf(player.getUUID());
        if (!crew.can(player.getUUID(), CrewRules.Perm.SPEND)) throw new IllegalStateException("Your role in the Crew may not spend on its behalf.");
        double wallet = QShopEconomy.balance(player);
        if (wallet < part.fillPrice()) throw new IllegalStateException("A supplier fills this part for " + part.fillPrice() + " credits; you have " + (long) wallet + ".");
        CompoundTag extra = deliveryRecord(player.server, player.getUUID(), contractId + "/" + partId);
        int xpNow = CitizenState.get(player.server).find(player.getUUID()).map(CitizenState.Citizen::careerXp).orElse(0);
        orders.prepareCrewFill(player.getUUID(), player.getInventory().save(new ListTag()), wallet - part.fillPrice(), xpNow, part.fillPrice(), extra);
        WorkOrderService.recover(player, orders);
        LOGGER.info("[UC] {} had a supplier fill {} of {} for {} credits", player.getGameProfile().getName(), partId, contractId, part.fillPrice());
        player.sendSystemMessage(Component.literal("A supplier fills the " + partId.replace('_', ' ') + " part of " + contract.title() + ": -" + part.fillPrice() + " credits."));
        afterSettlement(player.server, orders, crew.id, contractId);
        return part.fillPrice();
    }

    /** The supplier fill asked from the terminal's order row of a part ({@code orderId} is the part's work order). */
    static int fillOrder(ServerPlayer player, WorkOrderLedger orders, String orderId) {
        WorkOrder order = WorkOrderCatalog.INSTANCE.find(orderId);
        String[] ref = order == null ? null : split(order.terms().crewPart());
        if (ref == null) throw new IllegalStateException("That is not a Crew contract part.");
        return fill(player, orders, ref[0], ref[1]);
    }

    /**
     * The line the terminal shows under a part's order, at most 80 characters (the terminal's limit for a line): the Crew's progress, the bonus and the supplier's price; null for
     * an ordinary order. It starts with {@link #ROW_PREFIX}, which the screen uses to show the supplier's button.
     */
    static String rowLine(MinecraftServer server, WorkOrderLedger orders, UUID player, String crewPart) {
        String[] ref = split(crewPart);
        if (ref == null) return null;
        Contract contract = CATALOG.find(ref[0]);
        Part part = contract == null ? null : contract.part(ref[1]);
        if (part == null) return null;
        CrewLedger crews = CrewService.ledger(server);
        CrewLedger.Crew crew = crews == null ? null : crews.crewOf(player);
        if (crew == null) return ROW_PREFIX + " found or join a Crew to take a part (bonus " + contract.bonus() + ")";
        int done = 0;
        for (Part p : contract.parts()) if (orders.crewPartBy(crew.id, contract.id(), p.id()) != null) done++;
        String by = orders.crewPartBy(crew.id, contract.id(), part.id());
        // the card draws the line without its "; supplier N", which the Supplier button shows (1280x720 cut it off); the button reads the price from here
        String line = ROW_PREFIX + " " + done + "/" + contract.parts().size() + " parts in, bonus " + contract.bonus() + " shared; "
            + (by != null ? "this part is in" : "supplier " + part.fillPrice());
        return line.length() <= 80 ? line : line.substring(0, 80);
    }

    static final String ROW_PREFIX = "Crew:";

    /** After a part was recorded: tells the Crew's members online how the contract stands, and pays any share owed to a member online now. */
    static void afterSettlement(MinecraftServer server, WorkOrderLedger orders, UUID crewId, String contractId) {
        Contract contract = CATALOG.find(contractId);
        if (contract == null) return;
        CrewLedger.Crew crew = CrewService.ledger(server).crew(crewId);
        int done = 0;
        for (Part p : contract.parts()) if (orders.crewPartBy(crewId, contractId, p.id()) != null) done++;
        boolean completed = done == 0 && orders.crewOpensAt(crewId, contractId) > orders.clock();   // the settlement that completed it reset the parts for the next cycle
        if (crew == null) return;
        for (UUID member : crew.members.keySet()) {
            ServerPlayer online = server.getPlayerList().getPlayer(member);
            if (online == null) continue;
            if (completed) Notice.send(online, Notice.Kind.DELIVERED, "Crew contract done", contract.title() + ": the bonus is shared among those who delivered a part.");
            else Notice.send(online, Notice.Kind.ORDER, "Crew contract", contract.title() + ": " + done + " of " + contract.parts().size() + " parts in.");
            payOwed(online, orders);
        }
    }

    /** Pays this player's owed Crew bonus shares into their wallet through one settlement (nothing when none is owed or another settlement is pending). Returns the credits paid. */
    static long payOwed(ServerPlayer player, WorkOrderLedger orders) {
        if (orders.faulted()) return 0;
        long owed = orders.crewBonusOwed(player.getUUID());
        if (owed <= 0 || orders.player(player.getUUID()).contains("pending")) return 0;
        if (CitizenState.get(player.server).find(player.getUUID()).isEmpty()) return 0;
        double wallet = QShopEconomy.balance(player);
        if (wallet + owed > 1_000_000_000) return 0;
        int xpNow = CitizenState.get(player.server).find(player.getUUID()).get().careerXp();
        orders.prepareCrewBonus(player.getUUID(), player.getInventory().save(new ListTag()), wallet + owed, xpNow, owed);
        WorkOrderService.recover(player, orders);
        player.sendSystemMessage(Component.literal("Crew contract bonus: +" + owed + " credits, your equal share for the parts you delivered."));
        Notice.send(player, Notice.Kind.DELIVERED, "Crew bonus", "+" + owed + " credits, your share of a finished Crew contract.");
        return owed;
    }

    /** One line per contract for the terminal: the parts, who brought each, and what a supplier would charge. */
    static List<String> board(MinecraftServer server, WorkOrderLedger orders, UUID player) {
        List<String> lines = new ArrayList<>();
        CrewLedger.Crew crew = CrewService.ledger(server) == null ? null : CrewService.ledger(server).crewOf(player);
        for (Contract contract : CATALOG.all()) {
            if (!CATALOG.available(contract)) continue;
            StringBuilder line = new StringBuilder(contract.title()).append(" (bonus ").append(contract.bonus()).append(", shared by those who deliver): ");
            List<String> parts = new ArrayList<>();
            for (Part p : contract.parts()) {
                WorkOrder order = WorkOrderCatalog.INSTANCE.find(p.order());
                String by = crew == null ? null : orders.crewPartBy(crew.id, contract.id(), p.id());
                parts.add(p.id().replace('_', ' ') + " [" + TerminalScreenText.capitalize(order.career()) + "]" + (by == null ? " open, supplier " + p.fillPrice() : by.equals(WorkOrderLedger.NPC_FILL) ? " filled" : " in from " + name(server, by)));
            }
            lines.add(line.append(String.join("; ", parts)).toString());
        }
        return lines;
    }

    private static String name(MinecraftServer server, String uuid) {
        try {
            var profile = server.getProfileCache() == null ? java.util.Optional.<com.mojang.authlib.GameProfile>empty() : server.getProfileCache().get(UUID.fromString(uuid));
            return profile.map(com.mojang.authlib.GameProfile::getName).orElse("a member");
        } catch (IllegalArgumentException error) {
            return "a member";
        }
    }

    @SubscribeEvent
    public static void login(PlayerEvent.PlayerLoggedInEvent event) {
        if (event.getEntity() instanceof ServerPlayer player && !(player instanceof FakePlayer)) {
            try {
                payOwed(player, WorkOrderLedger.get(player.server));
            } catch (RuntimeException error) {
                LOGGER.error("[UC] could not pay {}'s owed Crew bonus at login; it stays owed", player.getGameProfile().getName(), error);
            }
        }
    }

    @SubscribeEvent
    public static void tick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END || event.getServer().getTickCount() % 200 != 0) return;
        WorkOrderLedger orders = WorkOrderLedger.get(event.getServer());
        for (ServerPlayer player : event.getServer().getPlayerList().getPlayers()) {
            if (player instanceof FakePlayer || orders.crewBonusOwed(player.getUUID()) <= 0) continue;
            try {
                payOwed(player, orders);
            } catch (RuntimeException error) {
                LOGGER.error("[UC] could not pay {}'s owed Crew bonus; it stays owed", player.getGameProfile().getName(), error);
            }
        }
    }
}
