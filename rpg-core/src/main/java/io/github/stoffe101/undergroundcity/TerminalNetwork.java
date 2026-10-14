package io.github.stoffe101.undergroundcity;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.PacketDistributor;
import net.minecraftforge.network.simple.SimpleChannel;

import java.util.function.Supplier;

/** One-way request to open a READ-ONLY terminal. No unsafe client purchases. */
public final class TerminalNetwork {
    private static final String VERSION = "1";
    private static final SimpleChannel CHANNEL = NetworkRegistry.newSimpleChannel(
        new ResourceLocation(UndergroundCity.MOD_ID, "terminal"),
        () -> VERSION, VERSION::equals, VERSION::equals
    );

    private TerminalNetwork() {}

    public static void register() {
        CHANNEL.registerMessage(0, OpenTerminal.class, (packet, buffer) -> {},
            buffer -> new OpenTerminal(), TerminalNetwork::receiveOpen);
    }

    private static void receiveOpen(OpenTerminal packet, Supplier<NetworkEvent.Context> supplier) {
        NetworkEvent.Context context = supplier.get();
        // This packet is only meaningful from the real server to the player.
        if (context.getDirection().getReceptionSide().isClient()) {
            context.enqueueWork(() ->
                DistExecutor.unsafeRunWhenOn(Dist.CLIENT,
                    () -> ClientTerminal::open));
        }
        context.setPacketHandled(true);
    }

    public static void open(ServerPlayer player) {
        CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), new OpenTerminal());
    }

    public static final class OpenTerminal {
        public OpenTerminal() {}
    }
}
