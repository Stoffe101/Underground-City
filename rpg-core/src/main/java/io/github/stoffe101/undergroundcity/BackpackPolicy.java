package io.github.stoffe101.undergroundcity;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraftforge.event.server.ServerStartedEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.registries.ForgeRegistries;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static io.github.stoffe101.undergroundcity.ModReflect.callOn;
import static io.github.stoffe101.undergroundcity.ModReflect.cls;
import static io.github.stoffe101.undergroundcity.ModReflect.configValue;
import static io.github.stoffe101.undergroundcity.ModReflect.field;

/**
 * The backpack policy of Underground City (D-092): which Sophisticated Backpacks tiers and upgrades exist and how big they are. The policy file
 * (content/gameplay/backpack-policy.json, copied into Core's data by scripts/generate_backpack_config.py) is the one source; this class compares the
 * configuration the mods are ACTUALLY running with against it, so that a world created before the policy existed, or a hand-edited file, is noticed and reported
 * instead of silently changing the economy. It reads the mods' configuration through reflection (they are optional, all-rights-reserved runtime mods) and never changes it.
 *
 * <p>It does not enforce: the configuration files do (Core's enabledItems switches recipes off, the world's serverconfig sizes the packs). A drift is logged as a warning at
 * server start and is what the backpack GameTests assert on.
 */
public final class BackpackPolicy {
    private static final Logger LOG = LoggerFactory.getLogger("UndergroundCity/BackpackPolicy");
    private static final String POLICY = "/data/underground_city/backpack_policy/policy.json";
    private static final String BACKPACKS_CONFIG = "net.p3pp3rf1y.sophisticatedbackpacks.Config";
    private static final String CORE_CONFIG = "net.p3pp3rf1y.sophisticatedcore.Config";
    private static final String[] TIER_FIELDS = {"leatherBackpack", "copperBackpack", "ironBackpack", "goldBackpack", "diamondBackpack", "netheriteBackpack"};

    /** The policy shipped inside Core. */
    static JsonObject load() {
        try (Reader reader = new InputStreamReader(BackpackPolicy.class.getResourceAsStream(POLICY), StandardCharsets.UTF_8)) {
            return new Gson().fromJson(reader, JsonObject.class);
        } catch (Exception e) {
            throw new IllegalStateException("backpack policy missing from Core: " + POLICY, e);
        }
    }

    static List<String> strings(JsonArray array) {
        List<String> out = new ArrayList<>();
        for (JsonElement e : array) out.add(e.getAsString());
        return out;
    }

    /** Every difference between the running configuration and the policy; empty when they agree. Needs both mods loaded and the server configuration read. */
    static List<String> audit(JsonObject policy) {
        List<String> drift = new ArrayList<>();
        try {
            String provider = policy.getAsJsonObject("provider").get("backpacks").getAsString();
            String anyItem = policy.getAsJsonObject("tiers").getAsJsonObject("leatherBackpack").get("item").getAsString();
            Class<?> backpacksConfig = cls(anyItem, BACKPACKS_CONFIG);
            Class<?> coreConfig = cls(anyItem, CORE_CONFIG);
            Object server = field(backpacksConfig, null, "SERVER");
            Object nerfs = field(server, "nerfsConfig");
            JsonObject expectedNerfs = policy.getAsJsonObject("nerfs");
            same(drift, "nerfs.maxNumberOfBackpacks", expectedNerfs.get("maxNumberOfBackpacks").getAsInt(), configValue(nerfs, "maxNumberOfBackpacks"));
            same(drift, "nerfs.tooManyBackpacksSlowness", expectedNerfs.get("tooManyBackpacksSlowness").getAsBoolean(), configValue(nerfs, "tooManyBackpacksSlowness"));
            same(drift, "nerfs.onlyWornBackpackTriggersUpgrades", expectedNerfs.get("onlyWornBackpackTriggersUpgrades").getAsBoolean(), configValue(nerfs, "onlyWornBackpackTriggersUpgrades"));
            JsonObject expectedServer = policy.getAsJsonObject("server");
            same(drift, "allowOpeningOtherPlayerBackpacks", expectedServer.get("allowOpeningOtherPlayerBackpacks").getAsBoolean(), configValue(server, "allowOpeningOtherPlayerBackpacks"));
            same(drift, "itemFluidHandlerEnabled", expectedServer.get("itemFluidHandlerEnabled").getAsBoolean(), configValue(server, "itemFluidHandlerEnabled"));
            same(drift, "tickDedupeLogicDisabled", expectedServer.get("tickDedupeLogicDisabled").getAsBoolean(), configValue(server, "tickDedupeLogicDisabled"));
            same(drift, "magnetUpgrade.magnetRange", policy.get("magnet_range").getAsInt(), configValue(field(server, "magnetUpgrade"), "magnetRange"));
            same(drift, "canBePlacedInContainerItems", expectedServer.get("canBePlacedInContainerItems").getAsBoolean(), configValue(server, "canBePlacedInContainerItems"));
            same(drift, "entityBackpackAdditions.chance", policy.get("mob_backpack_chance").getAsDouble(), configValue(field(server, "entityBackpackAdditions"), "chance"));
            same(drift, "chestLootEnabled", policy.get("chest_loot").getAsBoolean(), configValue(field(backpacksConfig, null, "COMMON"), "chestLootEnabled"));
            Object limits = field(server, "maxUpgradesPerStorage");
            Object limitList = callOn(field(limits, "maxUpgradesPerStorageList"), "get");
            List<String> expectedLimits = strings(expectedServer.getAsJsonArray("maxUpgradesPerStorage"));
            same(drift, "maxUpgradesPerStorage", new HashSet<>(expectedLimits), new HashSet<Object>((java.util.Collection<?>) limitList));

            JsonObject tiers = policy.getAsJsonObject("tiers");
            for (int i = 0; i < TIER_FIELDS.length; i++) {
                JsonObject tier = tiers.getAsJsonObject(TIER_FIELDS[i]);
                Item item = ForgeRegistries.ITEMS.getValue(ResourceLocation.parse(tier.get("item").getAsString()));
                same(drift, TIER_FIELDS[i] + " item slots", tier.get("inventorySlotCount").getAsInt(), callOn(item, "getNumberOfSlots"));
                same(drift, TIER_FIELDS[i] + " upgrade slots", tier.get("upgradeSlotCount").getAsInt(), callOn(item, "getNumberOfUpgradeSlots"));
            }

            Object enabledItems = field(field(coreConfig, null, "COMMON"), "enabledItems");
            Set<String> classified = new HashSet<>();
            for (String id : strings(policy.getAsJsonArray("enabled"))) {
                classified.add(id);
                if (!(Boolean) callOn(enabledItems, "isItemEnabled", ResourceLocation.parse(id))) drift.add(id + " is switched off but the policy enables it");
            }
            for (String id : strings(policy.getAsJsonArray("disabled"))) {
                classified.add(id);
                if ((Boolean) callOn(enabledItems, "isItemEnabled", ResourceLocation.parse(id))) drift.add(id + " is switched on but the policy disables it");
            }
            for (ResourceLocation key : ForgeRegistries.ITEMS.getKeys()) {
                if (key.getNamespace().equals(provider) && !classified.contains(key.toString())) drift.add(key + " is not classified by the policy (a new item of an updated mod needs a decision)");
            }
        } catch (Exception e) {
            drift.add("the policy could not be checked: " + e);
        }
        return drift;
    }

    private static void same(List<String> drift, String what, Object expected, Object actual) {
        if (!expected.equals(actual)) drift.add(what + ": policy " + expected + ", running " + actual);
    }

    /** Logged once when the server is up: one line when the running configuration is the policy, one warning per difference otherwise. */
    @SubscribeEvent
    public void started(ServerStartedEvent event) {
        if (net.minecraftforge.fml.ModList.get().isLoaded(SurvivalSet.LEGACY_BACKPACK_MOD) && net.minecraftforge.fml.ModList.get().isLoaded(SurvivalSet.BACKPACK_MOD)) {
            LOG.error("[UC-BACKPACKS] Traveler's Backpack and Sophisticated Backpacks are both installed: the pack supports one backpack provider (D-092). Remove one; docs/technical/backpack-migration.md says how to keep an old world's items");
        }
        if (!SurvivalSet.backpacksLoaded()) return;
        List<String> drift = audit(load());
        if (drift.isEmpty()) {
            LOG.info("[UC-BACKPACKS] the running Sophisticated Backpacks configuration matches the Underground City policy (D-092)");
        } else {
            LOG.warn("[UC-BACKPACKS] the running Sophisticated Backpacks configuration differs from the Underground City policy in {} way(s); an older world keeps the configuration it was created with (docs/technical/backpack-economy.md)", drift.size());
            drift.forEach(line -> LOG.warn("[UC-BACKPACKS]   {}", line));
        }
    }
}
