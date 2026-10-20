package io.github.stoffe101.undergroundcity;

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

import java.util.Optional;

/**
 * The lockpicking screen's own channel. The server tells the client that a lock is in the player's hands (its name and how many pins it has), then how the pick feels, how many pins are set,
 * how strained the pick is and how much time is left, and finally how it ended. The client may send exactly two things: where the pick is and whether the button is down, and "I stopped".
 * Nothing the client sends decides any outcome; the pins are never sent.
 */
public final class LockNetwork {
    private static final String VERSION = "1";
    private static final SimpleChannel CHANNEL = NetworkRegistry.newSimpleChannel(
        ResourceLocation.fromNamespaceAndPath(UndergroundCity.MOD_ID, "lock"), () -> VERSION, VERSION::equals, VERSION::equals);

    private record Open(String label, int pins, int timeLimit, int holdTicks) {}
    private record Progress(int feel, int pinsSet, int strain, int timeLeft) {}
    private record Finished(int state, String note) {}
    private record Input(float position, boolean pressing) {}
    private record Abort() {}

    private LockNetwork() {}

    public static void register() {
        CHANNEL.registerMessage(0, Open.class, (p, b) -> { b.writeUtf(p.label, 48); b.writeVarInt(p.pins); b.writeVarInt(p.timeLimit); b.writeVarInt(p.holdTicks); },
            b -> new Open(b.readUtf(48), b.readVarInt(), b.readVarInt(), b.readVarInt()),
            (p, supplier) -> {
                NetworkEvent.Context context = supplier.get();
                if (context.getDirection().getReceptionSide().isClient()) context.enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () -> ClientLock.open(p.label, p.pins, p.timeLimit, p.holdTicks)));
                context.setPacketHandled(true);
            }, Optional.of(NetworkDirection.PLAY_TO_CLIENT));
        CHANNEL.registerMessage(1, Progress.class, (p, b) -> { b.writeVarInt(p.feel); b.writeVarInt(p.pinsSet); b.writeVarInt(p.strain); b.writeVarInt(p.timeLeft); },
            b -> new Progress(b.readVarInt(), b.readVarInt(), b.readVarInt(), b.readVarInt()),
            (p, supplier) -> {
                NetworkEvent.Context context = supplier.get();
                if (context.getDirection().getReceptionSide().isClient()) context.enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () -> ClientLock.progress(p.feel, p.pinsSet, p.strain, p.timeLeft)));
                context.setPacketHandled(true);
            }, Optional.of(NetworkDirection.PLAY_TO_CLIENT));
        CHANNEL.registerMessage(2, Finished.class, (p, b) -> { b.writeVarInt(p.state); b.writeUtf(p.note, 120); },
            b -> new Finished(b.readVarInt(), b.readUtf(120)),
            (p, supplier) -> {
                NetworkEvent.Context context = supplier.get();
                if (context.getDirection().getReceptionSide().isClient()) context.enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () -> ClientLock.finished(p.state, p.note)));
                context.setPacketHandled(true);
            }, Optional.of(NetworkDirection.PLAY_TO_CLIENT));
        CHANNEL.registerMessage(3, Input.class, (p, b) -> { b.writeFloat(p.position); b.writeBoolean(p.pressing); },
            b -> new Input(b.readFloat(), b.readBoolean()),
            (p, supplier) -> {
                NetworkEvent.Context context = supplier.get();
                context.enqueueWork(() -> { ServerPlayer player = context.getSender(); if (player != null) LockService.input(player, p.position, p.pressing); });
                context.setPacketHandled(true);
            }, Optional.of(NetworkDirection.PLAY_TO_SERVER));
        CHANNEL.registerMessage(4, Abort.class, (p, b) -> { }, b -> new Abort(),
            (p, supplier) -> {
                NetworkEvent.Context context = supplier.get();
                context.enqueueWork(() -> { ServerPlayer player = context.getSender(); if (player != null) LockService.abort(player); });
                context.setPacketHandled(true);
            }, Optional.of(NetworkDirection.PLAY_TO_SERVER));
    }

    private static boolean reachable(ServerPlayer player) { return player != null && !(player instanceof FakePlayer) && player.connection != null; }

    static void open(ServerPlayer player, String label, LockpickSession.Profile profile) {
        if (reachable(player)) CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), new Open(label, profile.pins(), profile.timeLimitTicks(), profile.holdTicks()));
    }

    static void progress(ServerPlayer player, LockpickSession session) {
        if (reachable(player)) CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), new Progress(session.feel(), session.pinsSet(), session.strainPercent(), session.timeLeft()));
    }

    /** {@code state} is the ordinal of {@link LockpickSession.State}. */
    static void finished(ServerPlayer player, LockpickSession.State state, String note) {
        if (reachable(player)) CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), new Finished(state.ordinal(), note));
    }

    static void close(ServerPlayer player, String note) { finished(player, LockpickSession.State.ABORTED, note); }

    // client to server (used by the screen)

    static void sendInput(float position, boolean pressing) { CHANNEL.sendToServer(new Input(position, pressing)); }
    static void sendAbort() { CHANNEL.sendToServer(new Abort()); }
}
