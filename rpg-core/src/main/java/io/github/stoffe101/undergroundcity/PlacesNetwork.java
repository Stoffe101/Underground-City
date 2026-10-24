package io.github.stoffe101.undergroundcity;

import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.common.util.FakePlayer;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkDirection;
import net.minecraftforge.network.NetworkEvent;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.PacketDistributor;
import net.minecraftforge.network.simple.SimpleChannel;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.BiConsumer;
import java.util.function.Function;

/**
 * The places channel (D-157). The client asks about the door under its crosshair (a glance), asks to case the door it looks at (the Inspect key), asks for its book and marks or forgets a place;
 * the server answers a glance and sends the book. Every question is judged on the server: a glance is answered only for a door within reach, at most a few times a second.
 */
public final class PlacesNetwork {
    private static final String VERSION = "1";
    private static final SimpleChannel CHANNEL = NetworkRegistry.newSimpleChannel(
        ResourceLocation.fromNamespaceAndPath(UndergroundCity.MOD_ID, "places"), () -> VERSION, VERSION::equals, VERSION::equals);

    record PeekAsk(BlockPos pos) {}
    /** {@code peek} is null for a door that is nothing to glance at (the client then shows nothing and does not ask again for a while). */
    record PeekAnswer(BlockPos pos, PropertyIntel.Peek peek) {}
    record BookAsk() {}
    record BookAnswer(List<PropertyIntel.Row> rows, int thiefLevel, boolean casing, long clock) {}
    record Mark(long key, boolean on) {}
    record Inspect(BlockPos pos) {}
    record Forget(long key) {}

    /** Server ticks between two glances one player may ask for. */
    static final int PEEK_EVERY = 3;
    private static final Map<UUID, Integer> LAST_PEEK = new HashMap<>();

    private PlacesNetwork() {}

    public static void register() {
        toServer(0, PeekAsk.class, (m, b) -> b.writeBlockPos(m.pos), b -> new PeekAsk(b.readBlockPos()), (m, player) -> {
            int now = player.server.getTickCount();
            Integer last = LAST_PEEK.get(player.getUUID());
            if (last != null && now - last < PEEK_EVERY && now >= last) return;
            LAST_PEEK.put(player.getUUID(), now);
            PropertyIntel.peeks++;
            Optional<PropertyIntel.Peek> peek = PropertyIntel.peek(player, player.serverLevel(), m.pos);
            send(player, new PeekAnswer(m.pos, peek.orElse(null)));
        });
        toClient(1, PeekAnswer.class, PlacesNetwork::writeAnswer, PlacesNetwork::readAnswer, m -> ClientPlaces.answer(m.pos, m.peek));
        toServer(2, BookAsk.class, (m, b) -> {}, b -> new BookAsk(), (m, player) -> sendBook(player));
        toClient(3, BookAnswer.class, PlacesNetwork::writeBook, PlacesNetwork::readBook, m -> ClientPlaces.book(m.rows, m.thiefLevel, m.casing, m.clock));
        toServer(4, Mark.class, (m, b) -> { b.writeLong(m.key); b.writeBoolean(m.on); }, b -> new Mark(b.readLong(), b.readBoolean()), (m, player) -> {
            PropertyIntel.book(player.server).mark(player.getUUID(), m.key, m.on);
            sendBook(player);
        });
        toServer(5, Inspect.class, (m, b) -> b.writeBlockPos(m.pos), b -> new Inspect(b.readBlockPos()), (m, player) -> {
            String refusal = Casing.inspect(player, player.serverLevel(), m.pos);
            if (refusal != null) player.displayClientMessage(net.minecraft.network.chat.Component.literal(refusal), true);
        });
        toServer(6, Forget.class, (m, b) -> b.writeLong(m.key), b -> new Forget(b.readLong()), (m, player) -> {
            PropertyIntel.book(player.server).forget(player.getUUID(), m.key);
            sendBook(player);
        });
    }

    private static <T> void toServer(int id, Class<T> type, BiConsumer<T, FriendlyByteBuf> write, Function<FriendlyByteBuf, T> read, BiConsumer<T, ServerPlayer> handle) {
        CHANNEL.registerMessage(id, type, write, read, (m, supplier) -> {
            NetworkEvent.Context context = supplier.get();
            ServerPlayer player = context.getSender();
            if (player != null && context.getDirection().getReceptionSide().isServer()) context.enqueueWork(() -> handle.accept(m, player));
            context.setPacketHandled(true);
        }, Optional.of(NetworkDirection.PLAY_TO_SERVER));
    }

    private static <T> void toClient(int id, Class<T> type, BiConsumer<T, FriendlyByteBuf> write, Function<FriendlyByteBuf, T> read, java.util.function.Consumer<T> handle) {
        CHANNEL.registerMessage(id, type, write, read, (m, supplier) -> {
            NetworkEvent.Context context = supplier.get();
            if (context.getDirection().getReceptionSide().isClient()) context.enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () -> handle.accept(m)));
            context.setPacketHandled(true);
        }, Optional.of(NetworkDirection.PLAY_TO_CLIENT));
    }

    // ------------------------------------------------------------------------------------------------ wire formats

    private static void writePeek(PropertyIntel.Peek p, FriendlyByteBuf b) {
        b.writeBlockPos(p.door()); b.writeEnum(p.status()); b.writeUtf(p.category(), 24); b.writeUtf(p.label(), 40); b.writeUtf(p.address(), 48); b.writeUtf(p.city(), 24);
        b.writeVarInt(p.tier()); b.writeVarInt(p.access()); b.writeVarInt(p.layer() + 1); b.writeBoolean(p.beenIn()); b.writeBoolean(p.playerHomes());
    }

    private static PropertyIntel.Peek readPeek(FriendlyByteBuf b) {
        return new PropertyIntel.Peek(b.readBlockPos(), b.readEnum(PropertyIntel.Status.class), b.readUtf(24), b.readUtf(40), b.readUtf(48), b.readUtf(24),
            Math.max(0, Math.min(6, b.readVarInt())), b.readVarInt(), b.readVarInt() - 1, b.readBoolean(), b.readBoolean());
    }

    private static void writeAnswer(PeekAnswer m, FriendlyByteBuf b) {
        b.writeBlockPos(m.pos);
        b.writeBoolean(m.peek != null);
        if (m.peek != null) writePeek(m.peek, b);
    }

    private static PeekAnswer readAnswer(FriendlyByteBuf b) {
        BlockPos pos = b.readBlockPos();
        return new PeekAnswer(pos, b.readBoolean() ? readPeek(b) : null);
    }

    static void writeEntry(PropertyIntel.Entry e, FriendlyByteBuf b) {
        b.writeLong(e.key()); b.writeUtf(e.city(), 24); b.writeBlockPos(e.door()); b.writeUtf(e.address(), 48); b.writeUtf(e.category(), 24); b.writeUtf(e.label(), 40);
        b.writeVarInt(e.tier()); b.writeLong(e.firstSeen()); b.writeLong(e.lastSeen()); b.writeVarInt(e.layer() + 1); b.writeLong(e.casedAt()); b.writeBoolean(e.marked());
        b.writeBoolean(e.watchful()); b.writeUtf(e.lock(), 64); b.writeVarInt(e.alarm() + 1); b.writeVarInt(e.cameras() + 1); b.writeUtf(e.risk(), 24);
    }

    static PropertyIntel.Entry readEntry(FriendlyByteBuf b) {
        return new PropertyIntel.Entry(b.readLong(), b.readUtf(24), b.readBlockPos(), b.readUtf(48), b.readUtf(24), b.readUtf(40), b.readVarInt(), b.readLong(), b.readLong(),
            b.readVarInt() - 1, b.readLong(), b.readBoolean(), b.readBoolean(), b.readUtf(64), b.readVarInt() - 1, b.readVarInt() - 1, b.readUtf(24));
    }

    private static void writeBook(BookAnswer m, FriendlyByteBuf b) {
        b.writeVarInt(m.rows.size());
        for (PropertyIntel.Row row : m.rows) { writeEntry(row.entry(), b); b.writeBoolean(row.suitable()); b.writeBoolean(row.beenIn()); }
        b.writeVarInt(m.thiefLevel);
        b.writeBoolean(m.casing);
        b.writeLong(m.clock);
    }

    private static BookAnswer readBook(FriendlyByteBuf b) {
        int n = Math.min(PropertyIntel.MAX_PLACES, b.readVarInt());
        List<PropertyIntel.Row> rows = new ArrayList<>(n);
        for (int i = 0; i < n; i++) rows.add(new PropertyIntel.Row(readEntry(b), b.readBoolean(), b.readBoolean()));
        return new BookAnswer(rows, b.readVarInt(), b.readBoolean(), b.readLong());
    }

    // ------------------------------------------------------------------------------------------------ sending

    private static boolean reachable(ServerPlayer player) { return player != null && !(player instanceof FakePlayer) && player.connection != null; }

    static void send(ServerPlayer player, Object message) { if (reachable(player)) CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), message); }

    static void sendBook(ServerPlayer player) {
        send(player, new BookAnswer(PropertyIntel.rows(player), Casing.thiefLevel(player), Thievery.has(player, "thief.casing"), LockService.clock(player.server)));
    }

    // client to server
    static void askPeek(BlockPos pos) { CHANNEL.sendToServer(new PeekAsk(pos)); }
    static void askBook() { CHANNEL.sendToServer(new BookAsk()); }
    static void mark(long key, boolean on) { CHANNEL.sendToServer(new Mark(key, on)); }
    static void inspect(BlockPos pos) { CHANNEL.sendToServer(new Inspect(pos)); }
    static void forget(long key) { CHANNEL.sendToServer(new Forget(key)); }
}
