package io.github.stoffe101.undergroundcity;

import com.qshop.api.QShopAddonApi;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.fml.ModList;

/** Optional provider boundary. Only this class references Q Shop's public API. */
public final class QShopEconomy {
    private static final ResourceLocation SOURCE = ResourceLocation.fromNamespaceAndPath(
        UndergroundCity.MOD_ID, "work_order");
    private QShopEconomy() {}

    public static boolean available() {
        return ModList.get().isLoaded("qshop") && ModList.get().getModContainerById("qshop")
            .map(mod -> mod.getModInfo().getVersion().toString().equals("1.8.4")).orElse(false);
    }

    public static double balance(ServerPlayer player) {
        if (!available()) throw new IllegalStateException("Q Shop 1.8.4 is required");
        if (!configured(player.server)) throw new IllegalStateException("Install the credits currency template in this world's Q Shop configuration");
        double value = QShopAddonApi.currency().getBalance(player, "credits");
        if (!Double.isFinite(value) || value < 0 || value > 1_000_000_000) {
            throw new IllegalStateException("Invalid Q Shop credits balance");
        }
        return value;
    }

    /**
     * The credits of a player who may be offline, read from the provider's own player-data access, or null when they cannot be read. Read-only:
     * Crew recovery at server start compares this with a recorded before/after and never writes an offline wallet.
     */
    public static Double balanceOffline(net.minecraft.server.MinecraftServer server, java.util.UUID player) {
        if (!available() || !configured(server)) return null;
        try {
            double value = QShopAddonApi.currency().getBalance(server, player, "credits");
            return Double.isFinite(value) && value >= 0 && value <= 1_000_000_000 ? value : null;
        } catch (RuntimeException error) {
            return null;
        }
    }

    /** Absolute target for replaying a prepared transaction; never client supplied. */
    static void applyBalance(ServerPlayer player, double target) {
        if (!available()) throw new IllegalStateException("Q Shop 1.8.4 is required");
        if (!configured(player.server)) throw new IllegalStateException("Q Shop credits configuration is unavailable");
        double result = QShopAddonApi.currency().set(player, "credits", target, SOURCE, null);
        if (Double.compare(result, target) != 0) throw new IllegalStateException("Q Shop wallet unavailable");
    }

    public static boolean configured(net.minecraft.server.MinecraftServer server) {
        // Documented configuration boundary; never manufacture an invisible wallet
        // currency when the selected world uses different currencies.
        java.nio.file.Path path = server.getWorldPath(net.minecraft.world.level.storage.LevelResource.ROOT)
            .resolve("serverconfig/qshop/currencies.json");
        try {
            if (!java.nio.file.Files.isRegularFile(path) || java.nio.file.Files.size(path) > 262144) return false;
            var json = com.google.gson.JsonParser.parseString(java.nio.file.Files.readString(path)).getAsJsonObject();
            for (var entry : json.getAsJsonArray("currencies")) {
                if (entry.getAsJsonObject().get("id").getAsString().equals("credits")) return true;
            }
        } catch (java.io.IOException | RuntimeException ignored) { return false; }
        return false;
    }
}
