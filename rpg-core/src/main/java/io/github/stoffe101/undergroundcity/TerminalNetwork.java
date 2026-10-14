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

import java.util.function.Supplier;

/**
 * Server-validated terminal bridge. Opening the initial civilian QShop requires
 * a physical nearby Operations Terminal and a registered citizen.
 * This is NOT a remote city supplier, contraband order, or raid service.
 */
public final class TerminalNetwork {
    private static final String VERSION = "2";
    private static final String STARTER_SHOP_ID = "uc_starter_supply";
    private static final double MAX_TERMINAL_DISTANCE_SQUARED = 64.0;
    private static final SimpleChannel CHANNEL = NetworkRegistry.newSimpleChannel(
        new ResourceLocation(UndergroundCity.MOD_ID, "terminal"),
        () -> VERSION, VERSION::equals, VERSION::equals
    );

    private TerminalNetwork() {}

    public static void register() {
        CHANNEL.registerMessage(0, OpenTerminal.class,
            (packet, buffer) -> buffer.writeBlockPos(packet.pos),
            buffer -> new OpenTerminal(buffer.readBlockPos()),
            TerminalNetwork::receiveOpen);
        CHANNEL.registerMessage(1, OpenStarterShop.class,
            (packet, buffer) -> buffer.writeBlockPos(packet.pos),
            buffer -> new OpenStarterShop(buffer.readBlockPos()),
            TerminalNetwork::receiveShop);
    }

    private static void receiveOpen(OpenTerminal packet, Supplier<NetworkEvent.Context> supplier) {
        NetworkEvent.Context context = supplier.get();
        if (context.getDirection().getReceptionSide().isClient()) {
            context.enqueueWork(() ->
                DistExecutor.unsafeRunWhenOn(Dist.CLIENT,
                    () -> () -> ClientTerminal.open(packet.pos)));
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
        CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), new OpenTerminal(pos.immutable()));
    }

    /** Caller must be a click on the real PC screen, never a free-typed shop ID. */
    public static void requestStarterShop(BlockPos pos) {
        CHANNEL.sendToServer(new OpenStarterShop(pos.immutable()));
    }

    static boolean authorizedForStarterShop(ServerPlayer player, BlockPos pos) {
        if (player == null || pos == null || !player.serverLevel().hasChunkAt(pos)) return false;
        if (!player.serverLevel().getBlockState(pos).is(UndergroundCity.TERMINAL.get())) return false;
        if (player.distanceToSqr(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5)
                > MAX_TERMINAL_DISTANCE_SQUARED) return false;
        return CitizenState.get(player.server).find(player.getUUID()).isPresent();
    }

    private static void openVerifiedStarterShop(ServerPlayer player, BlockPos pos) {
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
        // Fixed server-authored ID, never arbitrary strings from a client packet.
        // QShop owns currency, inventory checks, shop stock and actual trades.
        // Only common civilian items are configured in this starter shop.
        player.server.getCommands().performPrefixedCommand(
            player.createCommandSourceStack(), "qshop open " + STARTER_SHOP_ID);
    }

    public static final class OpenTerminal {
        final BlockPos pos;
        OpenTerminal(BlockPos pos) { this.pos = pos; }
    }

    public static final class OpenStarterShop {
        final BlockPos pos;
        OpenStarterShop(BlockPos pos) { this.pos = pos; }
    }
}
