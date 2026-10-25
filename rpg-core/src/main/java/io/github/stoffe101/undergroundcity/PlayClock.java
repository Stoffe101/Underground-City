package io.github.stoffe101.undergroundcity;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraftforge.common.util.FakePlayer;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Each player's own play time, and the few things to tell a player the next time they log in (eighth pass, D-184). The world's active clock runs while anybody plays; this one runs
 * only while that player does, so a hold measured on it never lapses while its player is away. Saved as {@code underground_city_play_clock}.
 */
final class PlayClock extends SavedData {
    static final String KEY = "underground_city_play_clock";
    /** How many notes wait for one player at most (the oldest go first). */
    static final int MAX_NOTES = 8;

    final Map<UUID, Long> ticks = new HashMap<>();
    final Map<UUID, List<String>> notes = new HashMap<>();

    static PlayClock get(MinecraftServer server) { return server.overworld().getDataStorage().computeIfAbsent(PlayClock::load, PlayClock::new, KEY); }

    /** How long {@code player} has played (server ticks), 0 for somebody never seen. */
    static long of(MinecraftServer server, UUID player) { return get(server).ticks.getOrDefault(player, 0L); }

    /** Tells {@code player} now if they are on, else when they next log in. */
    static void note(MinecraftServer server, UUID player, String title, String text) {
        ServerPlayer online = server.getPlayerList().getPlayer(player);
        if (online != null) { Notice.send(online, Notice.Kind.ORDER, title, text); return; }
        PlayClock clock = get(server);
        List<String> list = clock.notes.computeIfAbsent(player, k -> new ArrayList<>());
        list.add(title + "\u0000" + text);
        while (list.size() > MAX_NOTES) list.remove(0);
        clock.setDirty();
    }

    /** Test seam: adds play time to a player as if they had played it. */
    static void addForTest(MinecraftServer server, UUID player, long played) { get(server).ticks.merge(player, played, Long::sum); get(server).setDirty(); }

    static PlayClock load(CompoundTag tag) {
        PlayClock c = new PlayClock();
        CompoundTag t = tag.getCompound("ticks");
        for (String k : t.getAllKeys()) try { c.ticks.put(UUID.fromString(k), Math.max(0, t.getLong(k))); } catch (IllegalArgumentException ignored) { }
        CompoundTag n = tag.getCompound("notes");
        for (String k : n.getAllKeys()) try {
            List<String> list = new ArrayList<>();
            for (Tag s : n.getList(k, Tag.TAG_STRING)) list.add(s.getAsString());
            if (!list.isEmpty()) c.notes.put(UUID.fromString(k), list);
        } catch (IllegalArgumentException ignored) { }
        return c;
    }

    @Override
    public CompoundTag save(CompoundTag tag) {
        CompoundTag t = new CompoundTag();
        ticks.forEach((k, v) -> t.putLong(k.toString(), v));
        tag.put("ticks", t);
        CompoundTag n = new CompoundTag();
        notes.forEach((k, v) -> { ListTag list = new ListTag(); for (String s : v) list.add(StringTag.valueOf(s)); n.put(k.toString(), list); });
        tag.put("notes", n);
        return tag;
    }

    @Mod.EventBusSubscriber(modid = UndergroundCity.MOD_ID)
    static final class Events {
        private Events() {}

        /** Once a second: a second for every player on (not a spectator, not a fake player); saved with the world. */
        @SubscribeEvent
        public static void tick(TickEvent.ServerTickEvent event) {
            if (event.phase != TickEvent.Phase.END || event.getServer().getTickCount() % 20 != 7) return;
            PlayClock clock = null;
            for (ServerPlayer p : event.getServer().getPlayerList().getPlayers()) {
                if (p instanceof FakePlayer || p.isSpectator()) continue;
                if (clock == null) clock = get(event.getServer());
                clock.ticks.merge(p.getUUID(), 20L, Long::sum);
            }
            if (clock != null && event.getServer().getTickCount() % 1200 == 7) clock.setDirty();
        }

        @SubscribeEvent
        public static void login(PlayerEvent.PlayerLoggedInEvent event) {
            if (!(event.getEntity() instanceof ServerPlayer player) || player instanceof FakePlayer) return;
            PlayClock clock = get(player.server);
            List<String> waiting = clock.notes.remove(player.getUUID());
            if (waiting == null) return;
            clock.setDirty();
            for (String s : waiting) {
                int cut = s.indexOf('\u0000');
                Notice.send(player, Notice.Kind.ORDER, cut < 0 ? "While you were away" : s.substring(0, cut), cut < 0 ? s : s.substring(cut + 1));
            }
        }
    }
}
