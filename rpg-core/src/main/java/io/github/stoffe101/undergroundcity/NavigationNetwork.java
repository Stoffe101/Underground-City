package io.github.stoffe101.undergroundcity;

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

import java.util.function.BiConsumer;
import java.util.function.Function;

/**
 * The navigation channel (D-161): the server tells a player what they are tracking (or that they track nothing); the client asks to track something by its record (a service call's id, a place's
 * key) or to stop. Coordinates only ever travel from the server to the client.
 */
public final class NavigationNetwork {
    private static final String VERSION = "1";
    private static final SimpleChannel CHANNEL = NetworkRegistry.newSimpleChannel(
        ResourceLocation.fromNamespaceAndPath(UndergroundCity.MOD_ID, "navigation"), () -> VERSION, VERSION::equals, VERSION::equals);

    /** {@code destination} is null for "tracking nothing". */
    record Update(Navigation.Destination destination) {}
    record Track(String kind, String ref) {}
    record Stop() {}

    private NavigationNetwork() {}

    public static void register() {
        toClient(0, Update.class, (m, b) -> { b.writeBoolean(m.destination != null); if (m.destination != null) m.destination.write(b); },
            b -> new Update(b.readBoolean() ? Navigation.Destination.read(b) : null), m -> ClientNavigation.update(m.destination));
        toServer(1, Track.class, (m, b) -> { b.writeUtf(m.kind, 32); b.writeUtf(m.ref, 64); }, b -> new Track(b.readUtf(32), b.readUtf(64)),
            (m, player) -> Navigation.request(player, m.kind, m.ref));
        toServer(2, Stop.class, (m, b) -> {}, b -> new Stop(), (m, player) -> Navigation.clear(player, "Stopped tracking."));
    }

    private static <T> void toServer(int id, Class<T> type, BiConsumer<T, FriendlyByteBuf> write, Function<FriendlyByteBuf, T> read, BiConsumer<T, ServerPlayer> handle) {
        CHANNEL.registerMessage(id, type, write, read, (m, supplier) -> {
            NetworkEvent.Context context = supplier.get();
            ServerPlayer player = context.getSender();
            if (player != null && context.getDirection().getReceptionSide().isServer()) context.enqueueWork(() -> handle.accept(m, player));
            context.setPacketHandled(true);
        }, java.util.Optional.of(NetworkDirection.PLAY_TO_SERVER));
    }

    private static <T> void toClient(int id, Class<T> type, BiConsumer<T, FriendlyByteBuf> write, Function<FriendlyByteBuf, T> read, java.util.function.Consumer<T> handle) {
        CHANNEL.registerMessage(id, type, write, read, (m, supplier) -> {
            NetworkEvent.Context context = supplier.get();
            if (context.getDirection().getReceptionSide().isClient()) context.enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () -> handle.accept(m)));
            context.setPacketHandled(true);
        }, java.util.Optional.of(NetworkDirection.PLAY_TO_CLIENT));
    }

    static void send(ServerPlayer player, Navigation.Destination destination) {
        if (player instanceof FakePlayer || player.connection == null) return;
        CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), new Update(destination));
    }

    /** Client side: track a record, or stop. */
    static void track(String kind, String ref) { CHANNEL.sendToServer(new Track(kind, ref)); }
    static void stop() { CHANNEL.sendToServer(new Stop()); }
}
