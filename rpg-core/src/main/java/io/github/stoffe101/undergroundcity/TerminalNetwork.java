package io.github.stoffe101.undergroundcity;

import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.network.NetworkEvent;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.PacketDistributor;
import net.minecraftforge.network.simple.SimpleChannel;
import net.minecraftforge.network.NetworkDirection;
import java.util.Optional;
import java.util.UUID;

import java.util.function.Supplier;

/**
 * Server-validated terminal bridge. Opening the initial civilian QShop requires
 * a physical nearby Operations Terminal and a registered citizen.
 * This is NOT a remote city supplier, contraband order, or raid service.
 */
public final class TerminalNetwork {
    private static final String VERSION = "4";
    private static final String STARTER_SHOP_ID = "uc_starter_supply";
    private static final double MAX_TERMINAL_DISTANCE_SQUARED = 64.0;
    private static final java.util.Map<ServerPlayer, Integer> REQUEST_TIMES = new java.util.WeakHashMap<>();
    private static final SimpleChannel CHANNEL = NetworkRegistry.newSimpleChannel(
        ResourceLocation.fromNamespaceAndPath(UndergroundCity.MOD_ID, "terminal"),
        () -> VERSION, VERSION::equals, VERSION::equals
    );

    private TerminalNetwork() {}

    public static void register() {
        CHANNEL.registerMessage(0, OpenTerminal.class,
            (packet, buffer) -> { buffer.writeBlockPos(packet.pos); packet.data.write(buffer); },
            buffer -> new OpenTerminal(buffer.readBlockPos(), TerminalData.read(buffer)),
            TerminalNetwork::receiveOpen, Optional.of(NetworkDirection.PLAY_TO_CLIENT));
        CHANNEL.registerMessage(1, OpenStarterShop.class,
            (packet, buffer) -> buffer.writeBlockPos(packet.pos),
            buffer -> new OpenStarterShop(buffer.readBlockPos()),
            TerminalNetwork::receiveShop, Optional.of(NetworkDirection.PLAY_TO_SERVER));
        CHANNEL.registerMessage(2, WorkAction.class,
            (packet, buffer) -> {
                buffer.writeBlockPos(packet.pos); buffer.writeVarInt(packet.action);
                buffer.writeUtf(packet.id, 128); buffer.writeUUID(packet.nonce);
            }, buffer -> new WorkAction(buffer.readBlockPos(), buffer.readVarInt(), buffer.readUtf(128), buffer.readUUID()),
            (packet, supplier) -> {
                NetworkEvent.Context context = supplier.get();
                context.enqueueWork(() -> {
                    ServerPlayer player = context.getSender();
                    if (player != null) WorkOrderService.action(player, packet.pos, packet.action, packet.id, packet.nonce);
                });
                context.setPacketHandled(true);
            }, Optional.of(NetworkDirection.PLAY_TO_SERVER));
        CHANNEL.registerMessage(3, RegionalBuy.class,
            (packet, buffer) -> {
                buffer.writeBlockPos(packet.pos); buffer.writeUtf(packet.commodity, 128);
                buffer.writeVarInt(packet.quantity); buffer.writeUUID(packet.nonce);
            }, buffer -> new RegionalBuy(buffer.readBlockPos(), buffer.readUtf(128), buffer.readVarInt(), buffer.readUUID()),
            (packet, supplier) -> {
                NetworkEvent.Context context = supplier.get();
                context.enqueueWork(() -> {
                    ServerPlayer player = context.getSender();
                    if (player != null) RegionalMarket.buy(player, packet.pos, packet.commodity, packet.quantity, packet.nonce);
                });
                context.setPacketHandled(true);
            }, Optional.of(NetworkDirection.PLAY_TO_SERVER));
    }

    private static void receiveOpen(OpenTerminal packet, Supplier<NetworkEvent.Context> supplier) {
        NetworkEvent.Context context = supplier.get();
        if (context.getDirection().getReceptionSide().isClient()) {
            context.enqueueWork(() ->
                DistExecutor.unsafeRunWhenOn(Dist.CLIENT,
                    () -> () -> ClientTerminal.open(packet.pos, packet.data)));
        }
        context.setPacketHandled(true);
    }

    private static void receiveShop(OpenStarterShop packet, Supplier<NetworkEvent.Context> supplier) {
        NetworkEvent.Context context = supplier.get();
        if (context.getDirection().getReceptionSide().isServer()) {
            context.enqueueWork(() -> {
                ServerPlayer player = context.getSender();
                if (player != null) openVerifiedStarterShop(player, packet.pos);
            });
        }
        context.setPacketHandled(true);
    }

    /** Called after physical right-click, not by a remote client action. */
    public static void open(ServerPlayer player, BlockPos pos) {
        CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), new OpenTerminal(pos.immutable(), TerminalData.snapshot(player, pos)));
    }

    /** Client click on a regional offer; quantity is validated and the price is server-owned. */
    public static void requestRegionalBuy(BlockPos pos, String commodity, int quantity) {
        CHANNEL.sendToServer(new RegionalBuy(pos.immutable(), commodity, quantity, UUID.randomUUID()));
    }

    public static void requestWork(BlockPos pos, int action, String id, UUID nonce) {
        CHANNEL.sendToServer(new WorkAction(pos.immutable(), action, id, nonce == null ? new UUID(0, 0) : nonce));
    }

    static boolean requestSlot(ServerPlayer player) {
        int now = player.server.getTickCount();
        Integer previous = REQUEST_TIMES.get(player);
        if (previous != null && now - previous < 5) return false;
        REQUEST_TIMES.put(player, now);
        return true;
    }

    /** Caller must be a click on the real PC screen, never a free-typed shop ID. */
    public static void requestStarterShop(BlockPos pos) {
        CHANNEL.sendToServer(new OpenStarterShop(pos.immutable()));
    }

    static boolean authorizedForStarterShop(ServerPlayer player, BlockPos pos) {
        if (player == null || !player.isAlive() || player.isSpectator() || pos == null || !player.serverLevel().hasChunkAt(pos)) return false;
        if (!player.serverLevel().getBlockState(pos).is(UndergroundCity.TERMINAL.get())) return false;
        if (player.distanceToSqr(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5)
                > MAX_TERMINAL_DISTANCE_SQUARED) return false;
        return CitizenState.get(player.server).find(player.getUUID()).isPresent();
    }

    private static void openVerifiedStarterShop(ServerPlayer player, BlockPos pos) {
        if (!requestSlot(player)) return;
        if (!authorizedForStarterShop(player, pos)) {
            player.sendSystemMessage(Component.literal(
                "Use a nearby Operations Terminal after registering citizenship."));
            return;
        }
        if (!ModList.get().isLoaded("qshop")
                || player.server.getCommands().getDispatcher().getRoot().getChild("qshop") == null) {
            player.sendSystemMessage(Component.literal(
                "Q Shop is not installed. The Imports screen is a preview."));
            return;
        }
        if (WorkOrderLedger.get(player.server).faulted()
                || WorkOrderLedger.get(player.server).player(player.getUUID()).contains("pending")) {
            player.sendSystemMessage(Component.literal("Finish work-order recovery before opening shops."));
            return;
        }
        // Fixed server-authored ID, never arbitrary strings from a client packet.
        // QShop owns currency, inventory checks, shop stock and actual trades.
        // Only common civilian items are configured in this starter shop.
        player.server.getCommands().performPrefixedCommand(
            player.createCommandSourceStack(), "qshop open " + STARTER_SHOP_ID);
    }

    public static final class OpenTerminal {
        final BlockPos pos;
        final TerminalData data;
        OpenTerminal(BlockPos pos, TerminalData data) { this.pos = pos; this.data = data; }
    }

    public static final class OpenStarterShop {
        final BlockPos pos;
        OpenStarterShop(BlockPos pos) { this.pos = pos; }
    }

    private record WorkAction(BlockPos pos, int action, String id, UUID nonce) {}
    private record RegionalBuy(BlockPos pos, String commodity, int quantity, UUID nonce) {}
}
