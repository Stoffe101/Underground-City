package io.github.stoffe101.undergroundcity;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Where a player is going (D-161): one tracked destination each, chosen on purpose (Track on the computer's service board, in the Places book, on a delivery), kept on the server and saved, so it
 * survives a restart and is the same on every screen. The client shows it on the status display's next-step card and, when Xaero's Minimap is installed, as a temporary waypoint on the minimap and
 * the world map ({@code XaeroWaypoints}); the server never depends on that mod.
 *
 * <ul>
 * <li>A destination is built on the server from the source's own record (a service call's panel, a place's street door), never from coordinates the client sends, and points at the spot the
 * activity needs: a utility panel, a street door, a counter.</li>
 * <li>It ends when its job is done, cancelled or expired, when the player tracks something else or stops, or when its record is gone (checked every few seconds); reaching it does not end it, since
 * the work is still to do there. Every player tracks their own; another player's choice never touches it.</li>
 * </ul>
 */
public final class Navigation {
    /** Registered on the Forge bus for its clock and logins. */
    Navigation() {}

    static final String DATA_KEY = "underground_city_navigation";
    /** How many players' destinations are kept (the oldest choice is forgotten first), and how often the open ones are checked against their records. */
    static final int MAX_PLAYERS = 1024, CHECK_EVERY = 100;

    /** A tracked destination: {@code kind} and {@code ref} name the record it came from ("tech" and a service call's id, "place" and a building's key), {@code pos} is where to go. */
    public record Destination(String kind, String ref, String title, String detail, String dimension, BlockPos pos, String city, long since) {
        String id() { return kind + ":" + ref; }

        CompoundTag save() {
            CompoundTag t = new CompoundTag();
            t.putString("kind", kind); t.putString("ref", ref); t.putString("title", title); t.putString("detail", detail); t.putString("dim", dimension);
            t.putLong("pos", pos.asLong()); t.putString("city", city); t.putLong("since", since);
            return t;
        }

        static Destination load(CompoundTag t) {
            return new Destination(t.getString("kind"), t.getString("ref"), t.getString("title"), t.getString("detail"), t.getString("dim"), BlockPos.of(t.getLong("pos")), t.getString("city"), t.getLong("since"));
        }

        void write(FriendlyByteBuf b) {
            b.writeUtf(kind, 32); b.writeUtf(ref, 64); b.writeUtf(title, 96); b.writeUtf(detail, 128); b.writeUtf(dimension, 128); b.writeBlockPos(pos); b.writeUtf(city, 32); b.writeLong(since);
        }

        static Destination read(FriendlyByteBuf b) {
            return new Destination(b.readUtf(32), b.readUtf(64), b.readUtf(96), b.readUtf(128), b.readUtf(128), b.readBlockPos(), b.readUtf(32), b.readLong());
        }
    }

    /** Every player's tracked destination. Saved. */
    static final class Store extends SavedData {
        final Map<UUID, Destination> byPlayer = new LinkedHashMap<>();

        static Store load(CompoundTag root) {
            Store s = new Store();
            CompoundTag players = root.getCompound("players");
            for (String key : players.getAllKeys()) {
                try { s.byPlayer.put(UUID.fromString(key), Destination.load(players.getCompound(key))); }
                catch (IllegalArgumentException ignored) {}
            }
            return s;
        }

        @Override public CompoundTag save(CompoundTag root) {
            CompoundTag players = new CompoundTag();
            byPlayer.forEach((player, d) -> players.put(player.toString(), d.save()));
            root.put("players", players);
            return root;
        }

        void put(UUID player, Destination d) {
            byPlayer.remove(player);
            byPlayer.put(player, d);
            while (byPlayer.size() > MAX_PLAYERS) byPlayer.remove(byPlayer.keySet().iterator().next());
            setDirty();
        }

        Destination remove(UUID player) {
            Destination d = byPlayer.remove(player);
            if (d != null) setDirty();
            return d;
        }
    }

    static Store store(MinecraftServer server) { return server.overworld().getDataStorage().computeIfAbsent(Store::load, Store::new, DATA_KEY); }

    static Optional<Destination> of(MinecraftServer server, UUID player) { return Optional.ofNullable(store(server).byPlayer.get(player)); }

    // ------------------------------------------------------------------------------------------------ choosing and ending

    /** A player asks to track something: built from the source's own record, or refused with a reason the client shows. */
    static void request(ServerPlayer player, String kind, String ref) {
        Optional<Destination> built;
        try { built = build(player, kind, ref); }
        catch (RuntimeException error) { built = Optional.empty(); }
        if (built.isEmpty()) { player.displayClientMessage(Component.literal("That can no longer be tracked."), true); return; }
        track(player, built.get());
    }

    /** Tracks {@code d} for {@code player}, replacing whatever they tracked before. */
    static void track(ServerPlayer player, Destination d) {
        store(player.server).put(player.getUUID(), d);
        NavigationNetwork.send(player, d);
        player.displayClientMessage(Component.literal("Tracking: " + d.title()), true);
    }

    /** The player stops tracking (or is told why it stopped, when {@code reason} is not null). */
    static void clear(ServerPlayer player, String reason) {
        Destination gone = store(player.server).remove(player.getUUID());
        NavigationNetwork.send(player, null);
        if (gone != null && reason != null) player.displayClientMessage(Component.literal(reason), true);
    }

    /**
     * The record behind a destination has ended (a service call put right, a job done): everybody tracking it stops. {@code by} is the player who finished it, if any; they are told {@code done}, the
     * others {@code others}.
     */
    static void finished(MinecraftServer server, String kind, String ref, UUID by, String done, String others) {
        Store s = store(server);
        List<UUID> tracking = new ArrayList<>();
        s.byPlayer.forEach((player, d) -> { if (d.kind().equals(kind) && d.ref().equals(ref)) tracking.add(player); });
        for (UUID player : tracking) {
            s.remove(player);
            ServerPlayer online = server.getPlayerList().getPlayer(player);
            if (online == null) continue;
            NavigationNetwork.send(online, null);
            String why = player.equals(by) ? done : others;
            if (why != null) online.displayClientMessage(Component.literal(why), true);
        }
    }

    // ------------------------------------------------------------------------------------------------ the sources

    static Optional<Destination> build(ServerPlayer player, String kind, String ref) {
        MinecraftServer server = player.server;
        long now = LockService.clock(server);
        String overworld = server.overworld().dimension().location().toString();
        switch (kind) {
            case "tech" -> {
                UUID id = UUID.fromString(ref);
                for (TechWork.Call call : TechWork.board(player)) {
                    if (!call.id().equals(id)) continue;
                    String what = switch (call.kind()) { case "repair_shop" -> "Till repair"; case "repair_security" -> "Alarm repair"; case "install" -> "Alarm install"; case "quiet" -> "Back channel"; default -> "Network upgrade"; };
                    return Optional.of(new Destination(kind, ref, what + " · " + call.address(), call.credits() + " credits · the utility panel by the street door", overworld, call.access(), call.city(), now));
                }
                return Optional.empty();
            }
            case "place" -> {
                long key = Long.parseLong(ref);
                PropertyIntel.Entry e = PropertyIntel.book(server).get(player.getUUID(), key);
                if (e == null) return Optional.empty();
                return Optional.of(new Destination(kind, ref, e.label() + " · " + e.address(), Thievery.tierWord(e.tier()) + " · its street door", overworld, e.door(), e.city(), now));
            }
            case "order" -> { return orderDestination(player, ref, WorkOrderLedger.get(server)); }
            default -> { return Optional.empty(); }
        }
    }

    /**
     * The destination of the player's accepted order {@code ref} (D-173): during the bonded store job, the store's door until the shipment is taken; else, for an order with a buyer, the
     * buyer's city, whose terminals take the delivery (a terminal anywhere inside it will do). Empty for an order with nowhere in particular to go.
     */
    static Optional<Destination> orderDestination(ServerPlayer player, String ref, WorkOrderLedger orders) {
        MinecraftServer server = player.server;
        long now = LockService.clock(server);
        String overworld = server.overworld().dimension().location().toString();
        CompoundTag record = orders.player(player.getUUID());
        if (!record.hasUUID("order")) return Optional.empty();
        WorkOrder order = WorkOrder.load(record.getCompound("job"));
        if (!order.id().equals(ref)) return Optional.empty();
        Optional<CompoundTag> raid = Raids.open(player, orders);
        if (raid.isPresent() && raid.get().contains("door") && !raid.get().getBoolean("taken"))
            return Optional.of(new Destination("order", ref, "The bonded store, Ironhaven", "The seized shipment is in its back room", overworld, BlockPos.of(raid.get().getLong("door")), "ironhaven", now));
        BuyerCatalog.Buyer buyer = order.terms().buyer().isEmpty() ? null : BuyerCatalog.INSTANCE.find(order.terms().buyer());
        if (buyer == null) return Optional.empty();
        var region = CityRegions.INSTANCE.all().stream().filter(r -> r.id().equals(buyer.city())).findFirst().orElse(null);
        if (region == null) return Optional.empty();
        BlockPos centre = new BlockPos(region.centerChunkX() * 16 + 8, server.overworld().getSeaLevel(), region.centerChunkZ() * 16 + 8);
        return Optional.of(new Destination("order", ref, "Deliver to " + buyer.title(), "A terminal anywhere inside " + TerminalScreenText.capitalize(buyer.city()) + " takes it", overworld, centre, buyer.city(), now));
    }

    /** Builds the player's tracked destination of {@code kind} again (its target moved, as when the bonded store's shipment is taken), or stops it when there is none any more. */
    static void retrack(ServerPlayer player, String kind) {
        Destination current = store(player.server).byPlayer.get(player.getUUID());
        if (current == null || !current.kind().equals(kind)) return;
        Optional<Destination> next = build(player, kind, current.ref());
        if (next.isPresent()) track(player, next.get()); else clear(player, "That order has nothing more to find.");
    }

    /** Why a destination has ended, or null while its record still stands. */
    static String ended(MinecraftServer server, UUID player, Destination d) {
        switch (d.kind()) {
            case "tech" -> {
                UUID id;
                try { id = UUID.fromString(d.ref()); } catch (IllegalArgumentException e) { return "That service call is gone."; }
                TechService.Systems s = TechService.systems(server);
                for (TechService.Fault f : s.faults.values()) if (f.id().equals(id)) return null;
                if (s.jobs.containsKey(id)) return null;
                return "That service call has been dealt with.";
            }
            case "place" -> {
                try { return PropertyIntel.book(server).get(player, Long.parseLong(d.ref())) == null ? "That place is no longer in your book." : null; }
                catch (NumberFormatException e) { return "That place is gone."; }
            }
            case "order" -> {
                CompoundTag record = WorkOrderLedger.get(server).player(player);
                if (!record.hasUUID("order")) return "That order is done.";
                try { return WorkOrder.load(record.getCompound("job")).id().equals(d.ref()) ? null : "That order is done."; }
                catch (IllegalArgumentException e) { return "That order is gone."; }
            }
            default -> { return "That destination is gone."; }
        }
    }

    // ------------------------------------------------------------------------------------------------ the clock

    @SubscribeEvent
    public void tick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END || event.getServer().getTickCount() % CHECK_EVERY != 41) return;
        MinecraftServer server = event.getServer();
        Store s = store(server);
        if (s.byPlayer.isEmpty()) return;
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            Destination d = s.byPlayer.get(player.getUUID());
            if (d == null) continue;
            String why;
            try { why = ended(server, player.getUUID(), d); }
            catch (RuntimeException error) { why = null; }
            if (why != null) clear(player, why);
        }
    }

    @SubscribeEvent
    public void loggedIn(PlayerEvent.PlayerLoggedInEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        Destination d = store(player.server).byPlayer.get(player.getUUID());
        if (d != null && ended(player.server, player.getUUID(), d) != null) { store(player.server).remove(player.getUUID()); d = null; }
        NavigationNetwork.send(player, d);
    }
}
