package io.github.stoffe101.undergroundcity;

import com.mojang.logging.LogUtils;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.common.util.FakePlayer;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.network.NetworkDirection;
import net.minecraftforge.network.NetworkEvent;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.PacketDistributor;
import net.minecraftforge.network.simple.SimpleChannel;
import org.slf4j.Logger;

import java.util.Optional;

/**
 * Both players' games and the server should run the same pack (eleventh pass). A Gradle launch sets {@code uc.pack.fingerprint}: Core's commit and every mod required on both sides, with its pinned
 * version and hash ({@code rpg-core/build.gradle}). When a player logs in, the server asks the client for its own fingerprint and, when the two differ, tells that player in a notice and writes
 * both to its log. Forge already refuses a missing mod; this catches a game built from another commit or with another set of mods that would still connect. A launch without the property (a
 * production pack) is never warned.
 */
@Mod.EventBusSubscriber(modid = UndergroundCity.MOD_ID)
public final class PackFingerprint {
    private static final Logger LOGGER = LogUtils.getLogger();
    static final String PROPERTY = "uc.pack.fingerprint";
    private static final String VERSION = "1";
    private static final SimpleChannel CHANNEL = NetworkRegistry.newSimpleChannel(
        ResourceLocation.fromNamespaceAndPath(UndergroundCity.MOD_ID, "pack"), () -> VERSION, NetworkRegistry.acceptMissingOr(VERSION), NetworkRegistry.acceptMissingOr(VERSION));

    record Ask() {}
    record Answer(String fingerprint) {}

    private PackFingerprint() {}

    static String local() { return System.getProperty(PROPERTY, "").trim(); }

    public static void register() {
        CHANNEL.registerMessage(0, Ask.class, (m, b) -> {}, b -> new Ask(), (m, supplier) -> {
            NetworkEvent.Context context = supplier.get();
            if (context.getDirection().getReceptionSide().isClient()) context.enqueueWork(() -> CHANNEL.sendToServer(new Answer(local())));
            context.setPacketHandled(true);
        }, Optional.of(NetworkDirection.PLAY_TO_CLIENT));
        CHANNEL.registerMessage(1, Answer.class, (m, b) -> b.writeUtf(m.fingerprint, 64), b -> new Answer(b.readUtf(64)), (m, supplier) -> {
            NetworkEvent.Context context = supplier.get();
            ServerPlayer player = context.getSender();
            if (player != null && context.getDirection().getReceptionSide().isServer()) context.enqueueWork(() -> compare(player, m.fingerprint));
            context.setPacketHandled(true);
        }, Optional.of(NetworkDirection.PLAY_TO_SERVER));
    }

    @SubscribeEvent
    public static void loggedIn(PlayerEvent.PlayerLoggedInEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player) || player instanceof FakePlayer || player.connection == null) return;
        if (local().isEmpty() || player.server.isSingleplayerOwner(player.getGameProfile())) return;
        CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), new Ask());
    }

    /** The server's verdict on a client's answer: nothing when they match or either side has none. */
    static Optional<String> mismatch(String server, String client) {
        if (server == null || client == null || server.isBlank() || client.isBlank() || server.equals(client)) return Optional.empty();
        return Optional.of("Pack " + client + " in your game, " + server + " on the server. Update both PCs to the same commit and start both with the same sets.");
    }

    static void compare(ServerPlayer player, String client) {
        mismatch(local(), client).ifPresent(text -> {
            LOGGER.warn("[UC] {} joined with pack fingerprint {}; the server's is {}", player.getGameProfile().getName(), client, local());
            Notice.send(player, Notice.Kind.WARN, "Your game differs from the server's", text);
        });
    }
}
