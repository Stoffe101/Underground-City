package io.github.stoffe101.undergroundcity;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mojang.logging.LogUtils;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.SimpleJsonResourceReloadListener;
import net.minecraft.util.profiling.ProfilerFiller;
import org.slf4j.Logger;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Shopkeepers who sell (D-126). A furnished building of a kind listed in {@code data/<ns>/shop_counters} has a counter (or a desk) where its worker stands during working hours; speaking to that
 * worker opens the city's stock lines for that kind of shop ({@code shop:<kind>} outlets of {@link RegionalStock}) on the terminal's Trade page. A counter is an outlet of the city's exchange,
 * not a second shop system: the stock is the same finite, shared, active-time stock, the purchase is the same journalled purchase ({@link RegionalMarket}), the wallet is Q Shop's.
 *
 * <p>A counter exists only while its shopkeeper is at it: speaking to them opens a counter at the place they stand, for a few minutes, and a purchase is accepted there only while the shopkeeper is
 * still alive and within a few blocks of it and the buyer is in reach. At night the shop is shut because nobody is behind the counter, and nothing in the client decides otherwise.
 */
public final class ShopCounters extends SimpleJsonResourceReloadListener {
    private static final Logger LOGGER = LogUtils.getLogger();
    public static final ShopCounters INSTANCE = new ShopCounters();
    /** How long a counter stays open after the shopkeeper was spoken to, in server ticks (the buyer keeps the screen up; speaking again renews it). */
    static final int OPEN_TICKS = 6000;
    static final double REACH_SQUARED = 64.0, KEEPER_SQUARED = 9.0;

    /** One kind of shop: which building, which spot the shopkeeper serves from, which outlet of which city they sell, and the shop's name. */
    public record Shop(String outlet, String building, String spot, String city, String title) {}
    /** An open counter: the shop, the shopkeeper's body and when it closes. */
    record Open(Shop shop, UUID keeper, int closesAt) {}

    private volatile Map<String, Shop> byBuilding = Map.of();
    private static final Map<Long, Open> OPEN = new ConcurrentHashMap<>();

    private ShopCounters() { super(new Gson(), "shop_counters"); }

    @Override
    protected void apply(Map<ResourceLocation, JsonElement> data, ResourceManager resources, ProfilerFiller profiler) {
        Map<String, Shop> next = new LinkedHashMap<>();
        try {
            for (var entry : data.entrySet()) {
                JsonObject json = entry.getValue().getAsJsonObject();
                if (json.get("schema_version").getAsInt() != 1) throw new IllegalArgumentException(entry.getKey() + ": schema_version must be 1");
                Shop shop = new Shop(json.get("outlet").getAsString(), json.get("building").getAsString(), json.get("spot").getAsString(), json.get("city").getAsString(), json.get("title").getAsString());
                if (!shop.outlet().matches("shop:[a-z_]{2,24}") || !ProgressionRules.validCity(shop.city()) || !(shop.spot().equals("counter") || shop.spot().equals("desk"))) {
                    throw new IllegalArgumentException(entry.getKey() + ": a shop needs a shop:<kind> outlet, a city and a counter or desk spot");
                }
                next.put(shop.building(), shop);
            }
            byBuilding = Map.copyOf(next);
            LOGGER.info("Loaded {} Underground City shop counters", byBuilding.size());
        } catch (RuntimeException error) {
            LOGGER.error("Shop counter data is invalid; keeping the previous counters", error);
        }
    }

    public Optional<Shop> forBuilding(String buildingType) { return Optional.ofNullable(byBuilding.get(buildingType)); }
    public Map<String, Shop> all() { return byBuilding; }

    /** The shop a household body keeps, if they are its shopkeeper: a worker of a building that has a shop, standing for the spot the shop serves from. */
    static Optional<Shop> shopOf(TownspersonEntity body) {
        if (body == null || !body.isAlive() || !body.role().equals("worker") || body.homeKey() == 0) return Optional.empty();
        String type = HouseholdService.typeOf(body);
        if (type == null) return Optional.empty();
        Optional<Shop> shop = INSTANCE.forBuilding(type);
        if (shop.isEmpty() || !shop.get().city().equals(body.city())) return Optional.empty();
        return HouseholdService.servesAt(body, shop.get().spot()) ? shop : Optional.empty();
    }

    /**
     * Opens the counter that a district's shopkeeper keeps (a roadside place's cook, foreman or clerk, D-138): the shop named by {@code shopKey} (a {@code shop_counters} entry's
     * {@code building}), at the place the keeper stands, under the same rules as a household worker's counter. Returns false when no shop has that key.
     */
    static boolean openKept(ServerPlayer player, net.minecraft.world.entity.LivingEntity keeper, String shopKey) {
        Optional<Shop> shop = INSTANCE.forBuilding(shopKey);
        if (shop.isEmpty() || keeper == null || !keeper.isAlive()) return false;
        BlockPos at = keeper.blockPosition();
        OPEN.put(at.asLong(), new Open(shop.get(), keeper.getUUID(), player.server.getTickCount() + OPEN_TICKS));
        OPEN.entrySet().removeIf(e -> e.getValue().closesAt() < player.server.getTickCount());
        TerminalNetwork.open(player, at);
        return true;
    }

    /** Opens the counter of the shopkeeper {@code body} for {@code player} (the terminal screen's Trade page, with this shop's lines only). */
    static void open(ServerPlayer player, TownspersonEntity body) {
        Optional<Shop> shop = shopOf(body);
        if (shop.isEmpty()) return;
        BlockPos at = body.blockPosition();
        OPEN.put(at.asLong(), new Open(shop.get(), body.getUUID(), player.server.getTickCount() + OPEN_TICKS));
        OPEN.entrySet().removeIf(e -> e.getValue().closesAt() < player.server.getTickCount());
        TerminalNetwork.open(player, at);
    }

    /** The open counter at a position, while its shopkeeper is still there; empty for any other position. */
    static Optional<Open> at(ServerLevel level, BlockPos pos) {
        Open open = OPEN.get(pos.asLong());
        if (open == null) return Optional.empty();
        if (open.closesAt() < level.getServer().getTickCount()) { OPEN.remove(pos.asLong()); return Optional.empty(); }
        if (!(level.getEntity(open.keeper()) instanceof net.minecraft.world.entity.LivingEntity keeper) || !keeper.isAlive() || keeper.distanceToSqr(pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5) > KEEPER_SQUARED) {
            OPEN.remove(pos.asLong());
            return Optional.empty();
        }
        return Optional.of(open);
    }

    /** True when the player may trade at the counter at {@code pos}: registered, alive, not a spectator, in reach of an open counter whose shopkeeper is at it. */
    static boolean authorized(ServerPlayer player, BlockPos pos) {
        if (player == null || !player.isAlive() || player.isSpectator() || pos == null) return false;
        if (player.distanceToSqr(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5) > REACH_SQUARED) return false;
        if (at(player.serverLevel(), pos).isEmpty()) return false;
        return CitizenState.get(player.server).find(player.getUUID()).isPresent();
    }

    /** Test seam: opens a counter as speaking to its shopkeeper would, for a body the test placed itself. */
    static void openForTest(BlockPos pos, Shop shop, UUID keeper, int closesAt) { OPEN.put(pos.asLong(), new Open(shop, keeper, closesAt)); }
    /** Test seam. */
    static void clearForTest() { OPEN.clear(); }
    static int openCount() { return OPEN.size(); }
}
