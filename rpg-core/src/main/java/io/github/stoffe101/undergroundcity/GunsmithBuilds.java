package io.github.stoffe101.undergroundcity;

import com.mojang.logging.LogUtils;
import net.minecraft.ChatFormatting;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeManager;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraftforge.event.entity.EntityJoinLevelEvent;
import net.minecraftforge.event.entity.player.ItemTooltipEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.registries.ForgeRegistries;
import org.slf4j.Logger;

import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * The Gunsmith builds real TaCZ firearms (D-168). Core ships Gun Smith Table recipes ({@code data/underground_city/recipes/tacz/}, loaded only with TaCZ) that build the civilian
 * pistols, revolvers and shotguns from a Firearm Parts Kit and a few ingots, and a short list of pistols and restricted guns from Unmarked Parts Kits. Both kits come only from a
 * Gunsmith's Bench, so the Gunsmith's edge is production: anybody may own and carry a gun, but the guns that have a kit recipe are built from a kit, and the table refuses TaCZ's
 * own iron-only recipe for them (creative mode aside). Guns without a kit recipe keep TaCZ's recipes and the blueprint gate.
 *
 * <p>A gun built from a licensed kit leaves the table registered: a serial number from the city's register ({@link Register}, kept with the world) and its maker's name. A gun built
 * from an unmarked kit has no number; the contraband rules treat it as restricted whatever its model, and Dray pays for it. Everything else about the gun is TaCZ's own.
 * The mark is put on the gun as TaCZ hands it over: the table drops its result at the player's feet within the same call, so the craft in progress is noted at the start of
 * {@code doCraft} and the gun item that joins the world inside that call is marked (server thread only).
 */
@Mod.EventBusSubscriber(modid = UndergroundCity.MOD_ID)
public final class GunsmithBuilds {
    private static final Logger LOGGER = LogUtils.getLogger();
    public static final String SERIAL = "UcSerial", MAKER = "UcMaker", PROVENANCE = "UcProvenance";
    public static final String REGISTERED = "registered", UNMARKED = "unmarked";
    static final String KIT_LABEL = "UcKitLabel";
    static final int XP_REGISTERED = 80, XP_UNMARKED = 110;
    private static final ResourceLocation LICENSED_KIT = ResourceLocation.fromNamespaceAndPath(UndergroundCity.MOD_ID, "firearm_parts_kit");
    private static final ResourceLocation UNMARKED_KIT = ResourceLocation.fromNamespaceAndPath(UndergroundCity.MOD_ID, "unmarked_parts_kit");
    private static final ResourceLocation GUN = ResourceLocation.fromNamespaceAndPath("tacz", "modern_kinetic_gun");

    private record Pending(ServerPlayer player, ResourceLocation recipe, String provenance) {}
    private static Pending pending;   // server thread: the kit craft whose doCraft is running now

    private static RecipeManager knownManager;
    private static final Map<ResourceLocation, String> ROUTES = new HashMap<>();   // a kit recipe and the provenance of what it builds
    private static final Set<String> KIT_GUNS = new HashSet<>();                    // the guns that have a kit recipe
    private static boolean broken;

    private GunsmithBuilds() {}

    /** At the start of the table's {@code doCraft}, after the blueprint gate: false refuses TaCZ's own recipe for a gun that is built from a kit here. */
    public static boolean allowsCraft(ResourceLocation recipeId, Player player) {
        if (!(player instanceof ServerPlayer serverPlayer) || recipeId == null || broken) return true;
        try {
            routes(serverPlayer.server.getRecipeManager());
            if (ROUTES.containsKey(recipeId) || !recipeId.getNamespace().equals("tacz") || serverPlayer.isCreative()) return true;
            ItemStack result = result(serverPlayer.server.getRecipeManager(), recipeId);
            String gun = gunId(result);
            if (gun == null || !KIT_GUNS.contains(gun)) return true;
            String name = result.getHoverName().getString();
            serverPlayer.sendSystemMessage(Component.literal("The " + name + " is built from a Firearm Parts Kit here: a Gunsmith's Bench makes them."));
            Notice.send(serverPlayer, Notice.Kind.WARN, "Built from a kit", "The " + name + " is built from a Firearm Parts Kit, not from raw iron.");
            LOGGER.info("[UC] refused TaCZ's own recipe {} for {}: the gun is built from a parts kit", recipeId, serverPlayer.getGameProfile().getName());
            return false;
        } catch (ReflectiveOperationException | RuntimeException error) {
            broken = true;
            LOGGER.error("[UC] the Gunsmith's kit recipes could not be read from TaCZ (a version it was not written for?): the table keeps TaCZ's own rules", error);
            return true;
        }
    }

    /** The craft is going ahead (the gates allowed it): note a kit craft so that the gun it hands over is marked. */
    public static void beginCraft(ResourceLocation recipeId, Player player) {
        pending = null;
        if (!(player instanceof ServerPlayer serverPlayer) || recipeId == null || broken) return;
        try {
            routes(serverPlayer.server.getRecipeManager());
        } catch (ReflectiveOperationException | RuntimeException error) {
            broken = true;
            return;
        }
        String provenance = ROUTES.get(recipeId);
        if (provenance != null) pending = new Pending(serverPlayer, recipeId, provenance);
    }

    /** {@code doCraft} has returned: whatever it handed over has been marked, or nothing was built. */
    public static void endCraft() { pending = null; }

    @SubscribeEvent
    public static void joined(EntityJoinLevelEvent event) {
        Pending craft = pending;
        if (craft == null || event.getLevel().isClientSide() || !(event.getEntity() instanceof ItemEntity drop)) return;
        ItemStack stack = drop.getItem();
        if (!GUN.equals(ForgeRegistries.ITEMS.getKey(stack.getItem())) || stack.getTag() == null || stack.getTag().contains(PROVENANCE)) return;
        pending = null;
        ItemStack marked = stack.copy();
        CompoundTag tag = marked.getOrCreateTag();
        ServerPlayer player = craft.player();
        unlabel(tag);
        tag.putString(PROVENANCE, craft.provenance());
        String serial = "";
        if (REGISTERED.equals(craft.provenance())) {
            serial = Register.get(player.server).issue(prefix(player), player, tag.getString("GunId"));
            tag.putString(SERIAL, serial);
            tag.putString(MAKER, player.getGameProfile().getName());
        }
        drop.setItem(marked);
        CitizenState.Citizen citizen = CitizenState.get(player.server).find(player.getUUID()).orElse(null);
        int xp = REGISTERED.equals(craft.provenance()) ? XP_REGISTERED : XP_UNMARKED;
        if (citizen != null && "gunsmith".equals(citizen.career)) CitizenState.get(player.server).addCareerXp(player.getUUID(), xp);
        Notice.send(player, Notice.Kind.LOOT, REGISTERED.equals(craft.provenance()) ? "Registered build" : "Unmarked build",
            REGISTERED.equals(craft.provenance()) ? marked.getHoverName().getString() + ", serial " + serial + ", on the city's register." : marked.getHoverName().getString() + " with no serial number. Restricted at a checkpoint.");
        LOGGER.info("[UC] {} built {} from a {} kit{}", player.getGameProfile().getName(), tag.getString("GunId"), craft.provenance(), serial.isEmpty() ? "" : ", serial " + serial);
    }

    /** The kit recipe's name ("Registered Glock 17") tells the table's list the routes apart; the gun the table hands over keeps TaCZ's own name. */
    static void unlabel(CompoundTag tag) {
        if (!tag.contains(KIT_LABEL)) return;
        tag.remove(KIT_LABEL);
        if (tag.contains("display", net.minecraft.nbt.Tag.TAG_COMPOUND)) {
            CompoundTag display = tag.getCompound("display");
            display.remove("Name");
            if (display.isEmpty()) tag.remove("display");
        }
    }

    /** A blueprint used: the next tick, the gun it taught is recorded under TaCZ Blueprints' table spelling too (see {@link BlueprintGate#learnBothSpellings}). */
    @SubscribeEvent
    public static void blueprintUsed(net.minecraftforge.event.entity.player.PlayerInteractEvent.RightClickItem event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        ResourceLocation item = ForgeRegistries.ITEMS.getKey(event.getItemStack().getItem());
        if (item == null || !item.getNamespace().equals("taczblueprints")) return;
        MinecraftServer server = player.server;
        server.tell(new net.minecraft.server.TickTask(server.getTickCount() + 1, () -> BlueprintGate.learnBothSpellings(player)));
    }

    /** Guns learned before this reconciliation (an older save) are recorded under both spellings at login. */
    @SubscribeEvent
    public static void loggedIn(net.minecraftforge.event.entity.player.PlayerEvent.PlayerLoggedInEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) BlueprintGate.learnBothSpellings(player);
    }

    /** Opening TaCZ's table while carrying a blueprint not yet read: the table will show only learned guns, so say how to learn this one (gunsmith run 2). */
    @SubscribeEvent
    public static void openTable(net.minecraftforge.event.entity.player.PlayerInteractEvent.RightClickBlock event) {
        if (!(event.getEntity() instanceof ServerPlayer player) || event.getHand() != net.minecraft.world.InteractionHand.MAIN_HAND) return;
        if (!"tacz:gun_smith_table".equals(String.valueOf(ForgeRegistries.BLOCKS.getKey(event.getLevel().getBlockState(event.getPos()).getBlock())))) return;
        String blueprint = BlueprintGate.unread(player);
        if (blueprint != null) Notice.send(player, Notice.Kind.WARN, "Blueprint not read", "Right-click the " + blueprint + " in your pack to learn it: the table shows only the guns you have learned.");
    }

    /** What a gun says about where it came from, under its name. */
    @SubscribeEvent
    public static void tooltip(ItemTooltipEvent event) {
        CompoundTag tag = event.getItemStack().getTag();
        if (tag == null || !tag.contains(PROVENANCE)) return;
        if (REGISTERED.equals(tag.getString(PROVENANCE)))
            event.getToolTip().add(Component.literal("Serial " + tag.getString(SERIAL) + ", built by " + tag.getString(MAKER)).withStyle(ChatFormatting.GRAY));
        else if (UNMARKED.equals(tag.getString(PROVENANCE)))
            event.getToolTip().add(Component.literal("No serial number").withStyle(ChatFormatting.RED));
    }

    /** The provenance a gun carries ("registered", "unmarked", or "" for a gun nobody built from a kit). */
    public static String provenance(ItemStack stack) {
        CompoundTag tag = stack.getTag();
        return tag == null ? "" : tag.getString(PROVENANCE);
    }

    /** The guns built from a kit at this server's tables, for the tests and the docs check. */
    static Set<String> kitGuns(MinecraftServer server) throws ReflectiveOperationException {
        routes(server.getRecipeManager());
        return Set.copyOf(KIT_GUNS);
    }

    /** The kit recipes and what they build, read once per recipe manager (a reload makes a new one). */
    private static void routes(RecipeManager manager) throws ReflectiveOperationException {
        if (manager == knownManager) return;
        ROUTES.clear();
        KIT_GUNS.clear();
        ItemStack licensed = new ItemStack(ForgeRegistries.ITEMS.getValue(LICENSED_KIT)), unmarked = new ItemStack(ForgeRegistries.ITEMS.getValue(UNMARKED_KIT));
        for (Recipe<?> recipe : manager.getRecipes()) {
            if (!recipe.getId().getNamespace().equals(UndergroundCity.MOD_ID) || !String.valueOf(recipe.getType()).equals("tacz:gun_smith_table_crafting")) continue;
            String provenance = null;
            Method inputs = recipe.getClass().getMethod("getInputs");
            for (Object input : (java.util.List<?>) inputs.invoke(recipe)) {
                var ingredient = (net.minecraft.world.item.crafting.Ingredient) input.getClass().getMethod("getIngredient").invoke(input);
                if (ingredient.test(unmarked)) provenance = UNMARKED;
                else if (ingredient.test(licensed) && provenance == null) provenance = REGISTERED;
            }
            if (provenance == null) continue;
            ROUTES.put(recipe.getId(), provenance);
            String gun = gunId(result(manager, recipe.getId()));
            if (gun != null) KIT_GUNS.add(gun);
        }
        knownManager = manager;
        LOGGER.info("[UC] {} Gun Smith Table recipes build from parts kits ({} guns)", ROUTES.size(), KIT_GUNS.size());
    }

    private static ItemStack result(RecipeManager manager, ResourceLocation id) throws ReflectiveOperationException {
        var recipe = manager.byKey(id).orElse(null);
        if (recipe == null) return ItemStack.EMPTY;
        Object output = recipe.getClass().getMethod("getOutput").invoke(recipe);
        return output instanceof ItemStack stack ? stack : ItemStack.EMPTY;
    }

    private static String gunId(ItemStack stack) {
        CompoundTag tag = stack.getTag();
        return tag != null && tag.contains("GunId") ? tag.getString("GunId") : null;
    }

    private static String prefix(ServerPlayer player) {
        String city = CitizenState.get(player.server).find(player.getUUID()).map(c -> c.city).orElse("");
        return switch (city) {
            case "ironhaven" -> "IH";
            case "valewood" -> "VW";
            case "blackwater" -> "BW";
            default -> "UC";
        };
    }

    /** The city's register of serial numbers, kept with the world ({@code data/underground_city_firearms.dat}): each number once, with its maker, gun and day. */
    static final class Register extends SavedData {
        private static final String NAME = "underground_city_firearms";
        private final Map<String, Integer> next = new HashMap<>();
        private final net.minecraft.nbt.ListTag entries = new net.minecraft.nbt.ListTag();

        static Register get(MinecraftServer server) {
            return server.overworld().getDataStorage().computeIfAbsent(Register::load, Register::new, NAME);
        }

        String issue(String prefix, ServerPlayer maker, String gun) {
            int number = next.merge(prefix, 1, Integer::sum);
            String serial = String.format(java.util.Locale.ROOT, "%s-%05d", prefix, number);
            CompoundTag entry = new CompoundTag();
            entry.putString("serial", serial);
            entry.putUUID("maker", maker.getUUID());
            entry.putString("maker_name", maker.getGameProfile().getName());
            entry.putString("gun", gun);
            entry.putLong("day", maker.serverLevel().getDayTime() / 24000L);
            entries.add(entry);
            setDirty();
            return serial;
        }

        int issued() { return entries.size(); }

        static Register load(CompoundTag tag) {
            Register register = new Register();
            CompoundTag counters = tag.getCompound("next");
            for (String key : counters.getAllKeys()) register.next.put(key, counters.getInt(key));
            register.entries.addAll(tag.getList("entries", net.minecraft.nbt.Tag.TAG_COMPOUND));
            return register;
        }

        @Override
        public CompoundTag save(CompoundTag tag) {
            CompoundTag counters = new CompoundTag();
            next.forEach(counters::putInt);
            tag.put("next", counters);
            tag.put("entries", entries.copy());
            tag.putInt("schema_version", 1);
            return tag;
        }
    }
}
