package io.github.stoffe101.undergroundcity;

import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.common.util.FakePlayer;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Keeps each player's on-screen status current (D-109): once every two seconds the server builds the player's {@link HudState} and sends it when it differs from what was last sent, so a
 * player standing still costs nothing on the wire and a few ledger reads on the server. Players are staggered across the two seconds, so a full server does not do all of them in one tick.
 */
public final class HudService {
    private static final int PERIOD = 40;
    private static final Map<UUID, HudState> SENT = new HashMap<>();
    /** Counters for {@code /uc_population status}-style checks and the tests: snapshots built and snapshots sent. */
    static long built, sent;

    @SubscribeEvent
    public void tick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        int tick = event.getServer().getTickCount();
        for (ServerPlayer player : event.getServer().getPlayerList().getPlayers()) {
            if (player instanceof FakePlayer) continue;
            if (Math.floorMod(tick + player.getId(), PERIOD) != 0) continue;
            refresh(player, false);
        }
    }

    /** Builds the player's snapshot and sends it if it changed (or {@code force}). */
    static boolean refresh(ServerPlayer player, boolean force) {
        HudState now = HudState.of(player);
        built++;
        if (!force && now.equals(SENT.get(player.getUUID()))) return false;
        SENT.put(player.getUUID(), now);
        sent++;
        TerminalNetwork.sendHud(player, now);
        return true;
    }

    @SubscribeEvent public void loggedIn(PlayerEvent.PlayerLoggedInEvent event) { SENT.remove(event.getEntity().getUUID()); }
    @SubscribeEvent public void loggedOut(PlayerEvent.PlayerLoggedOutEvent event) { SENT.remove(event.getEntity().getUUID()); }
    @SubscribeEvent public void respawned(PlayerEvent.PlayerRespawnEvent event) { SENT.remove(event.getEntity().getUUID()); }
    @SubscribeEvent public void changedDimension(PlayerEvent.PlayerChangedDimensionEvent event) { SENT.remove(event.getEntity().getUUID()); }
}
