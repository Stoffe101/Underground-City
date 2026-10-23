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

import java.util.Optional;

/**
 * The burglar's channel (D-144): the server tells the client what a casing found (a report of words and small numbers) and that a search started or ended (for the bar under the crosshair). The
 * client sends nothing on it: a casing and a search both start from an ordinary right click, which the server judges.
 */
public final class BurglaryNetwork {
    private static final String VERSION = "1";
    private static final SimpleChannel CHANNEL = NetworkRegistry.newSimpleChannel(
        ResourceLocation.fromNamespaceAndPath(UndergroundCity.MOD_ID, "burglary"), () -> VERSION, VERSION::equals, VERSION::equals);

    private record CasingMsg(Casing.Report report) {}
    private record SearchMsg(String label, int ticks, int state) {}

    private BurglaryNetwork() {}

    public static void register() {
        CHANNEL.registerMessage(0, CasingMsg.class, (m, b) -> write(m.report, b), b -> new CasingMsg(read(b)),
            (m, supplier) -> {
                NetworkEvent.Context context = supplier.get();
                if (context.getDirection().getReceptionSide().isClient()) context.enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () -> ClientBurglary.casing(m.report)));
                context.setPacketHandled(true);
            }, Optional.of(NetworkDirection.PLAY_TO_CLIENT));
        CHANNEL.registerMessage(1, SearchMsg.class, (m, b) -> { b.writeUtf(m.label, 64); b.writeVarInt(m.ticks); b.writeVarInt(m.state); },
            b -> new SearchMsg(b.readUtf(64), b.readVarInt(), b.readVarInt()),
            (m, supplier) -> {
                NetworkEvent.Context context = supplier.get();
                if (context.getDirection().getReceptionSide().isClient()) context.enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () -> ClientBurglary.search(m.label, m.ticks, m.state)));
                context.setPacketHandled(true);
            }, Optional.of(NetworkDirection.PLAY_TO_CLIENT));
    }

    private static void write(Casing.Report r, FriendlyByteBuf b) {
        b.writeUtf(r.title(), 48); b.writeUtf(r.city(), 24); b.writeVarInt(r.tier()); b.writeUtf(r.lock(), 64); b.writeUtf(r.lockNote(), 160); b.writeBoolean(r.canTry());
        b.writeVarInt(r.alarm() + 1); b.writeVarInt(r.cameras() + 1); b.writeVarInt(r.inside() + 1); b.writeUtf(r.empty(), 64); b.writeVarInt(r.safe() + 1); b.writeVarInt(r.recent() + 1);
        b.writeBoolean(r.beenIn()); b.writeVarInt(r.skill());
    }

    private static Casing.Report read(FriendlyByteBuf b) {
        return new Casing.Report(b.readUtf(48), b.readUtf(24), Math.max(0, Math.min(6, b.readVarInt())), b.readUtf(64), b.readUtf(160), b.readBoolean(),
            b.readVarInt() - 1, b.readVarInt() - 1, b.readVarInt() - 1, b.readUtf(64), b.readVarInt() - 1, b.readVarInt() - 1, b.readBoolean(), b.readVarInt());
    }

    private static boolean reachable(ServerPlayer player) { return player != null && !(player instanceof FakePlayer) && player.connection != null; }

    static void casing(ServerPlayer player, Casing.Report report) { if (reachable(player)) CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), new CasingMsg(report)); }

    /** {@code state}: 0 started (with its length in ticks), 1 finished, 2 stopped. */
    static void searchStart(ServerPlayer player, String label, int ticks) { if (reachable(player)) CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), new SearchMsg(label, ticks, 0)); }
    static void searchEnd(ServerPlayer player, boolean done) { if (reachable(player)) CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), new SearchMsg("", 0, done ? 1 : 2)); }
}
