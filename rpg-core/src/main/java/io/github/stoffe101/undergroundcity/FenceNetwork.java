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

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * The fence's channel (D-125). The server sends what the fence would pay (every price, the budget, the hours); the client may send one thing: "sell this line" (an item id and the city it was taken in,
 * or "*" for everything), with a nonce. The server recomputes everything and decides; a stale or forged line simply sells nothing.
 */
public final class FenceNetwork {
    private static final String VERSION = "1";
    private static final SimpleChannel CHANNEL = NetworkRegistry.newSimpleChannel(
        ResourceLocation.fromNamespaceAndPath(UndergroundCity.MOD_ID, "fence"), () -> VERSION, VERSION::equals, VERSION::equals);
    static final int MAX_ROWS = 36;

    record Open(int entityId, FenceService.Quote quote) {}
    record Sell(int entityId, String item, String fromCity, UUID nonce) {}

    private FenceNetwork() {}

    static void write(FriendlyByteBuf b, FenceService.Quote q) {
        b.writeUtf(q.city(), 32); b.writeUtf(q.title(), 64); b.writeUtf(q.blurb(), 160);
        b.writeVarInt(q.ratePercent()); b.writeVarInt(q.sameCityRatePercent()); b.writeVarInt(q.budget()); b.writeBoolean(q.about());
        List<FenceService.Row> rows = q.rows().size() > MAX_ROWS ? q.rows().subList(0, MAX_ROWS) : q.rows();
        b.writeVarInt(rows.size());
        for (FenceService.Row r : rows) { b.writeUtf(r.item(), 128); b.writeUtf(r.fromCity(), 32); b.writeVarInt(r.count()); b.writeVarInt(r.unit()); }
    }

    static FenceService.Quote read(FriendlyByteBuf b) {
        String city = b.readUtf(32), title = b.readUtf(64), blurb = b.readUtf(160);
        int rate = b.readVarInt(), same = b.readVarInt(), budget = b.readVarInt();
        boolean about = b.readBoolean();
        int n = Math.min(MAX_ROWS, b.readVarInt());
        List<FenceService.Row> rows = new ArrayList<>();
        for (int i = 0; i < n; i++) rows.add(new FenceService.Row(b.readUtf(128), b.readUtf(32), b.readVarInt(), b.readVarInt()));
        return new FenceService.Quote(city, title, blurb, rate, same, budget, about, List.copyOf(rows));
    }

    public static void register() {
        CHANNEL.registerMessage(0, Open.class, (p, b) -> { b.writeVarInt(p.entityId()); write(b, p.quote()); },
            b -> new Open(b.readVarInt(), read(b)),
            (p, supplier) -> {
                NetworkEvent.Context context = supplier.get();
                if (context.getDirection().getReceptionSide().isClient()) context.enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () -> FenceScreen.show(p.entityId(), p.quote())));
                context.setPacketHandled(true);
            }, Optional.of(NetworkDirection.PLAY_TO_CLIENT));
        CHANNEL.registerMessage(1, Sell.class, (p, b) -> { b.writeVarInt(p.entityId()); b.writeUtf(p.item(), 128); b.writeUtf(p.fromCity(), 32); b.writeUUID(p.nonce()); },
            b -> new Sell(b.readVarInt(), b.readUtf(128), b.readUtf(32), b.readUUID()),
            (p, supplier) -> {
                NetworkEvent.Context context = supplier.get();
                context.enqueueWork(() -> { ServerPlayer player = context.getSender(); if (player != null) FenceService.sell(player, p.entityId(), p.item(), p.fromCity(), p.nonce()); });
                context.setPacketHandled(true);
            }, Optional.of(NetworkDirection.PLAY_TO_SERVER));
    }

    static void open(ServerPlayer player, int entityId, FenceService.Quote quote) {
        if (player instanceof FakePlayer || player.connection == null) return;
        CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), new Open(entityId, quote));
    }

    /** Client: asks to sell a line. */
    static void sell(int entityId, String item, String fromCity) { CHANNEL.sendToServer(new Sell(entityId, item, fromCity, UUID.randomUUID())); }
}
