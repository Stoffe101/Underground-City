package io.github.stoffe101.undergroundcity;

import com.google.gson.JsonObject;
import com.mojang.logging.LogUtils;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.slf4j.Logger;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.Map;
import java.util.UUID;

/**
 * What the economy really did in a world (twelfth pass): one JSON line per payout, purchase, sale, fine and session, so a playtest can be read against the economy model
 * ({@code scripts/economy_report.py}). The model's hands-on share, its minutes per order and its affordability timelines are assumptions; this records what happened.
 *
 * <p>Local and bounded: the lines go to {@code underground_city_telemetry.jsonl} in the world folder (a server's or a single-player save's), never anywhere else; the file is rolled to
 * {@code .1} at {@link #MAX_BYTES}. A player is a pseudonym (a hash of the world's seed and the player's id), never a name; nothing else about a person is written. It never takes part
 * in a settlement: it is written after one, and a failure to write is logged once and ignored. Off with {@code -Duc.economyTelemetry=false}.
 *
 * <p>Every line has {@code t} (the shared active clock in ticks), {@code wall} (epoch seconds), {@code p} (the pseudonym), {@code career}, {@code level}, {@code wallet} (the Q Shop balance
 * after the event, left out when the wallet cannot be read) and {@code kind}: login, logout, city (entered a city, or {@code ""} for open country), accepted, order, service, purchase, supply, fence, encounter, business, arrest,
 * search.
 */
@Mod.EventBusSubscriber(modid = UndergroundCity.MOD_ID)
final class EconomyTelemetry {
    private static final Logger LOGGER = LogUtils.getLogger();
    static final String FILE = "underground_city_telemetry.jsonl";
    static final long MAX_BYTES = 4_000_000L;
    /** How often a player's city is read (ticks): entering and leaving cities gives the travel times. */
    static final int CITY_EVERY = 100;
    static volatile boolean on = !"false".equalsIgnoreCase(System.getProperty("uc.economyTelemetry", "true"));

    private static final Map<String, Long> ACCEPTED = new HashMap<>();
    private static final Map<UUID, String> CITY = new HashMap<>();
    private static boolean warned;
    static long written;

    private EconomyTelemetry() {}

    /** A pseudonym for a player in this world: the same in every line of the world, different between worlds, never the name. */
    static String pseudonym(MinecraftServer server, UUID player) {
        try {
            MessageDigest sha = MessageDigest.getInstance("SHA-256");
            sha.update(Long.toString(server.overworld().getSeed()).getBytes(StandardCharsets.UTF_8));
            sha.update(player.toString().getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(sha.digest()).substring(0, 10);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    /** One event for {@code player}: {@code fields} are name, value pairs (numbers, strings or booleans). */
    static void record(ServerPlayer player, String kind, Object... fields) {
        if (!on || player == null) return;
        try {
            MinecraftServer server = player.server;
            JsonObject line = new JsonObject();
            line.addProperty("t", LockService.clock(server));
            line.addProperty("wall", System.currentTimeMillis() / 1000L);
            line.addProperty("p", pseudonym(server, player.getUUID()));
            CitizenState.get(server).find(player.getUUID()).ifPresent(c -> { line.addProperty("career", c.career); line.addProperty("level", c.careerLevel()); });
            // the wallet is read when it can be: a world without Q Shop's credits template (CI's smoke servers had none) still gets the line, without the field
            try { line.addProperty("wallet", Math.round(QShopEconomy.balance(player))); } catch (RuntimeException noWallet) { /* the line is still written */ }
            line.addProperty("kind", kind);
            for (int i = 0; i + 1 < fields.length; i += 2) {
                String key = String.valueOf(fields[i]);
                Object value = fields[i + 1];
                if (value instanceof Number n) line.addProperty(key, n);
                else if (value instanceof Boolean b) line.addProperty(key, b);
                else line.addProperty(key, value == null ? "" : value.toString());
            }
            write(server, line.toString());
        } catch (RuntimeException | IOException error) {
            if (!warned) { warned = true; LOGGER.warn("Economy telemetry could not be written (it is never part of a settlement): {}", error.toString()); }
        }
    }

    private static synchronized void write(MinecraftServer server, String line) throws IOException {
        Path file = server.getWorldPath(LevelResource.ROOT).resolve(FILE);
        if (Files.exists(file) && Files.size(file) > MAX_BYTES) Files.move(file, file.resolveSibling(FILE + ".1"), StandardCopyOption.REPLACE_EXISTING);
        Files.writeString(file, line + "\n", StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        written++;
    }

    // ------------------------------------------------------------------------------------------------ an order's time on the clock

    static void accepted(ServerPlayer player, String order) {
        if (!on || player == null) return;
        synchronized (ACCEPTED) { ACCEPTED.put(player.getUUID() + "|" + order, LockService.clock(player.server)); }
        record(player, "accepted", "order", order);
    }

    /** Active minutes since the player accepted {@code order} in this run of the server, or -1 when that is not known (a restart in between). */
    static double minutesSinceAccepted(ServerPlayer player, String order) {
        Long at;
        synchronized (ACCEPTED) { at = ACCEPTED.remove(player.getUUID() + "|" + order); }
        return at == null ? -1 : Math.round((LockService.clock(player.server) - at) / 120.0) / 10.0;
    }

    // ------------------------------------------------------------------------------------------------ sessions and travel

    @SubscribeEvent
    public static void login(PlayerEvent.PlayerLoggedInEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) record(player, "login");
    }

    @SubscribeEvent
    public static void logout(PlayerEvent.PlayerLoggedOutEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            record(player, "logout");
            CITY.remove(player.getUUID());
        }
    }

    @SubscribeEvent
    public static void tick(TickEvent.ServerTickEvent event) {
        if (!on || event.phase != TickEvent.Phase.END) return;
        MinecraftServer server = event.getServer();
        if (server.getTickCount() % CITY_EVERY != 0) return;
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            if (player.isSpectator() || !Chunks.ready(player.serverLevel(), player.blockPosition())) continue;
            String city = CityRegions.INSTANCE.cityAt(player.level(), player.blockPosition()).orElse("");
            String before = CITY.put(player.getUUID(), city);
            if (!city.equals(before)) record(player, "city", "city", city, "from", before == null ? "?" : before);
        }
    }
}
